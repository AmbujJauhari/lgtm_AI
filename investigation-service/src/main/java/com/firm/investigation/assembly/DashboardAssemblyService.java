package com.firm.investigation.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firm.investigation.component.PanelServiceClient;
import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.panel.PanelSelectionResult;
import com.firm.investigation.panel.PanelTemplate;
import com.firm.investigation.reason.ReasonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles the Grafana dashboard JSON from selected panels + injected variables,
 * plus a feedback markdown panel at the top and metadata tags for the feedback endpoint to read back.
 *
 * <h3>Feedback metadata persisted in dashboard</h3>
 * <ul>
 *   <li>Tags: {@code appcode-<code>}, {@code service-<name>}, {@code category-<errorCategory>}
 *       — read back by FeedbackService when the L2 submits feedback</li>
 *   <li>Description: original sanitised log line — read back to construct the DashboardMemory record</li>
 *   <li>Feedback panel (row 0): markdown with a clickable link to the feedback form</li>
 * </ul>
 */
@Service
public class DashboardAssemblyService {

    private static final Logger log = LoggerFactory.getLogger(DashboardAssemblyService.class);
    private static final int FEEDBACK_ROW_HEIGHT = 3;

    // Grid for content panels — each y-offset is FEEDBACK_ROW_HEIGHT below where it would normally sit.
    private static final int[][] GRID = {
        {0, FEEDBACK_ROW_HEIGHT, 24, 8},               // log_stream
        {0, FEEDBACK_ROW_HEIGHT + 8, 12, 8},
        {12, FEEDBACK_ROW_HEIGHT + 8, 12, 8},
        {0, FEEDBACK_ROW_HEIGHT + 16, 12, 8},
        {12, FEEDBACK_ROW_HEIGHT + 16, 12, 8},
        {0, FEEDBACK_ROW_HEIGHT + 24, 24, 8}
    };

    private final PanelServiceClient panelServiceClient;
    private final VariableInjector variableInjector;
    private final ObjectMapper objectMapper;
    private final InvestigationProperties properties;

    public DashboardAssemblyService(PanelServiceClient panelServiceClient, VariableInjector variableInjector,
                                    ObjectMapper objectMapper, InvestigationProperties properties) {
        this.panelServiceClient = panelServiceClient;
        this.variableInjector = variableInjector;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public JsonNode assemble(InvestigationState state) {
        PanelSelectionResult selection = state.panels();
        List<PanelSelectionResult.SelectedPanel> selectedPanels = reorderLogStreamFirst(selection.selectedPanels());

        ObjectNode dashboard = objectMapper.createObjectNode();
        String uid = buildUid(state);
        dashboard.put("uid", uid);
        dashboard.put("title", selection.dashboardTitle());
        dashboard.put("schemaVersion", 36);
        dashboard.put("version", 1);
        dashboard.put("editable", true);   // L2 needs to be able to edit
        dashboard.put("refresh", "30s");
        dashboard.put("description", state.request().logLine());

        addTags(dashboard, state);

        ObjectNode time = dashboard.putObject("time");
        time.put("from", resolveTimeFrom(selectedPanels));
        time.put("to", resolveTimeTo(selectedPanels));

        ArrayNode panelsArray = dashboard.putArray("panels");
        panelsArray.add(buildFeedbackPanel(uid, panelId(1)));

        Map<String, PanelTemplate> templateIndex = buildTemplateIndex();
        int nextId = 2;
        for (int i = 0; i < Math.min(selectedPanels.size(), GRID.length); i++) {
            PanelSelectionResult.SelectedPanel selected = selectedPanels.get(i);
            PanelTemplate template = templateIndex.get(selected.panelId());
            if (template == null) {
                log.warn("Unknown panelId '{}' from LLM — skipping", selected.panelId());
                continue;
            }
            JsonNode injected = variableInjector.inject(template.panelJson(), selected.variables());
            ObjectNode panel = (ObjectNode) injected.deepCopy();
            panel.put("id", nextId++);

            int[] grid = GRID[Math.min(i, GRID.length - 1)];
            ObjectNode gridPos = panel.putObject("gridPos");
            gridPos.put("x", grid[0]);
            gridPos.put("y", grid[1]);
            gridPos.put("w", grid[2]);
            gridPos.put("h", grid[3]);

            panelsArray.add(panel);
        }

        log.info("Assembled dashboard '{}' with {} panels (+ feedback)",
                selection.dashboardTitle(), panelsArray.size() - 1);
        return dashboard;
    }

    private void addTags(ObjectNode dashboard, InvestigationState state) {
        ArrayNode tags = dashboard.putArray("tags");
        tags.add("ai-investigation");
        tags.add("auto-generated");
        tags.add("appcode-" + state.request().appcode());
        tags.add("service-" + state.request().service());
        if (state.reason() != null && state.reason().errorCategory() != null) {
            tags.add("category-" + state.reason().errorCategory());
        }
        if (state.reason() != null && state.reason().traceId() != null && !state.reason().traceId().isBlank()) {
            tags.add("traceid-" + state.reason().traceId());
        }
    }

    private ObjectNode buildFeedbackPanel(String uid, int id) {
        String feedbackUrl = properties.feedback().baseUrl() + "/feedback?uid=" + uid;
        ObjectNode panel = objectMapper.createObjectNode();
        panel.put("id", id);
        panel.put("type", "text");
        panel.put("title", "");
        panel.put("transparent", true);
        ObjectNode options = panel.putObject("options");
        options.put("mode", "markdown");
        options.put("content", String.format(
                "### 📝 Investigation feedback%n%n" +
                "Spent time investigating? **[Submit your learnings →](%s)** — your feedback teaches the system " +
                "which panels are actually useful for similar errors in the future.",
                feedbackUrl));
        ObjectNode gridPos = panel.putObject("gridPos");
        gridPos.put("x", 0);
        gridPos.put("y", 0);
        gridPos.put("w", 24);
        gridPos.put("h", FEEDBACK_ROW_HEIGHT);
        return panel;
    }

    private int panelId(int id) { return id; }

    private List<PanelSelectionResult.SelectedPanel> reorderLogStreamFirst(
            List<PanelSelectionResult.SelectedPanel> panels) {
        Optional<PanelSelectionResult.SelectedPanel> logStream = panels.stream()
                .filter(p -> "log_stream".equals(p.panelId()))
                .findFirst();
        if (logStream.isEmpty()) return panels;
        return java.util.stream.Stream.concat(
                logStream.stream(),
                panels.stream().filter(p -> !"log_stream".equals(p.panelId()))
        ).toList();
    }

    private Map<String, PanelTemplate> buildTemplateIndex() {
        Map<String, PanelTemplate> index = new java.util.HashMap<>();
        panelServiceClient.getAllPanels().forEach(t -> index.put(t.panelId(), t));
        return index;
    }

    private String buildUid(InvestigationState state) {
        ReasonResult reason = state.reason();
        String traceId = reason != null && reason.traceId() != null ? reason.traceId() : "notrace";
        long epoch = Instant.now().getEpochSecond();
        String raw = "inv-" + traceId + "-" + epoch;
        return raw.length() > 40 ? raw.substring(0, 40) : raw;
    }

    private String resolveTimeFrom(List<PanelSelectionResult.SelectedPanel> panels) {
        return panels.stream()
                .map(p -> p.variables().get("timeFrom"))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse("now-1h");
    }

    private String resolveTimeTo(List<PanelSelectionResult.SelectedPanel> panels) {
        return panels.stream()
                .map(p -> p.variables().get("timeTo"))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse("now");
    }
}
