package com.firm.investigation.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.component.PanelServiceClient;
import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.panel.PanelSelectionResult;
import com.firm.investigation.panel.PanelTemplate;
import com.firm.investigation.reason.ReasonResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardAssemblyServiceTest {

    @Mock private PanelServiceClient panelServiceClient;

    private DashboardAssemblyService assemblyService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        VariableInjector variableInjector = new VariableInjector(objectMapper);
        InvestigationProperties properties = new InvestigationProperties(
                new InvestigationProperties.TempoProperties("http://localhost", 5),
                new InvestigationProperties.LokiProperties("http://localhost", 5),
                new InvestigationProperties.GrafanaProperties("http://localhost", "http://localhost", "token", "folder"),
                new InvestigationProperties.FallbackProperties(true),
                new InvestigationProperties.PanelServiceProperties("http://localhost:8081"),
                new InvestigationProperties.Neo4jProperties("bolt://localhost:7687", "neo4j", "test"),
                new InvestigationProperties.FeedbackProperties("http://localhost:8080"),
                java.util.Map.of());
        assemblyService = new DashboardAssemblyService(panelServiceClient, variableInjector, objectMapper, properties);

        JsonNode logStreamPanelJson = objectMapper.readTree("""
                {"type":"logs","title":"Logs — $service"}
                """);
        JsonNode heapPanelJson = objectMapper.readTree("""
                {"type":"timeseries","title":"Heap — $components"}
                """);

        when(panelServiceClient.getAllPanels()).thenReturn(List.of(
                new PanelTemplate("log_stream", "Log stream", "IBLoki",
                        List.of("service", "appcode"), List.of("ALL"), logStreamPanelJson),
                new PanelTemplate("heap_by_component", "Heap by component", "IBMimir",
                        List.of("components", "appcode"), List.of("NPE", "OOM"), heapPanelJson)
        ));
    }

    private InvestigationState buildState(List<PanelSelectionResult.SelectedPanel> panels, String traceId) {
        InvestigationRequest request = new InvestigationRequest(
                "NullPointerException at Calculator.java:247",
                "collateral-service", "COLL", "2024-01-15T10:30:00Z");
        ReasonResult reason = new ReasonResult(
                "NPE", "NPE", traceId, List.of("collateral-service"), 0.9,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test");
        PanelSelectionResult selection = new PanelSelectionResult(panels, "NPE Investigation — collateral-service");
        return InvestigationState.initial(request)
                .withReason(reason)
                .withMergedComponents(List.of("collateral-service"))
                .withPanels(selection);
    }

    @Test
    void assemble_producesRequiredTopLevelFields() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "collateral-service", "appcode", "COLL",
                                "timeFrom", "2024-01-15T10:25:00Z", "timeTo", "2024-01-15T10:35:00Z"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));

        assertThat(dashboard.has("uid")).isTrue();
        assertThat(dashboard.has("title")).isTrue();
        assertThat(dashboard.has("time")).isTrue();
        assertThat(dashboard.has("panels")).isTrue();
        assertThat(dashboard.path("tags").toString()).contains("ai-investigation");
        assertThat(dashboard.path("tags").toString()).contains("auto-generated");
    }

    @Test
    void assemble_feedbackPanelIsFirst() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "collateral-service", "appcode", "COLL",
                                "timeFrom", "t1", "timeTo", "t2"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));
        JsonNode feedbackPanel = dashboard.path("panels").get(0);
        assertThat(feedbackPanel.path("type").asText()).isEqualTo("text");
        assertThat(feedbackPanel.path("options").path("content").asText())
                .contains("feedback").contains("/feedback?uid=");
        assertThat(feedbackPanel.path("gridPos").path("y").asInt()).isEqualTo(0);
    }

    @Test
    void assemble_dashboardTagsContainAppcodeServiceCategory() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "s", "appcode", "A", "timeFrom", "t1", "timeTo", "t2"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));
        String tagsJson = dashboard.path("tags").toString();
        assertThat(tagsJson).contains("appcode-COLL");
        assertThat(tagsJson).contains("service-collateral-service");
        assertThat(tagsJson).contains("category-NPE");
    }

    @Test
    void assemble_dashboardDescriptionContainsLogLine() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "s", "appcode", "A", "timeFrom", "t1", "timeTo", "t2"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));
        assertThat(dashboard.path("description").asText()).contains("NullPointerException");
    }

    @Test
    void assemble_logStreamIsFirstAfterFeedbackAndFullWidth() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("heap_by_component",
                        Map.of("components", "collateral-service", "appcode", "COLL",
                                "timeFrom", "2024-01-15T10:25:00Z", "timeTo", "2024-01-15T10:35:00Z")),
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "collateral-service", "appcode", "COLL",
                                "timeFrom", "2024-01-15T10:25:00Z", "timeTo", "2024-01-15T10:35:00Z"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));
        JsonNode logStreamPanel = dashboard.path("panels").get(1);  // index 0 is feedback
        assertThat(logStreamPanel.path("title").asText()).contains("collateral-service");
        assertThat(logStreamPanel.path("gridPos").path("w").asInt()).isEqualTo(24);
        assertThat(logStreamPanel.path("gridPos").path("x").asInt()).isEqualTo(0);
        assertThat(logStreamPanel.path("gridPos").path("y").asInt()).isEqualTo(3);  // below feedback
    }

    @Test
    void assemble_secondContentPanelIsHalfWidth() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "collateral-service", "appcode", "COLL",
                                "timeFrom", "t1", "timeTo", "t2")),
                new PanelSelectionResult.SelectedPanel("heap_by_component",
                        Map.of("components", "collateral-service", "appcode", "COLL",
                                "timeFrom", "t1", "timeTo", "t2"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));
        JsonNode secondContentPanel = dashboard.path("panels").get(2);  // 0=feedback, 1=log_stream
        assertThat(secondContentPanel.path("gridPos").path("w").asInt()).isEqualTo(12);
        assertThat(secondContentPanel.path("gridPos").path("y").asInt()).isEqualTo(11);
    }

    @Test
    void assemble_variablesInjectedIntoPanel() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "my-service", "appcode", "TEST",
                                "timeFrom", "2024-01-15T10:25:00Z", "timeTo", "2024-01-15T10:35:00Z"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));
        JsonNode contentPanel = dashboard.path("panels").get(1);  // index 0 is feedback
        assertThat(contentPanel.path("title").asText()).isEqualTo("Logs — my-service");
    }

    @Test
    void assemble_uidContainsInvPrefix() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "s", "appcode", "A", "timeFrom", "t1", "timeTo", "t2"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, "abc123"));
        String uid = dashboard.path("uid").asText();
        assertThat(uid).startsWith("inv-");
        assertThat(uid.length()).isLessThanOrEqualTo(40);
    }

    @Test
    void assemble_unknownPanelIdSkipped() {
        List<PanelSelectionResult.SelectedPanel> panels = List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "s", "appcode", "A", "timeFrom", "t1", "timeTo", "t2")),
                new PanelSelectionResult.SelectedPanel("nonexistent_panel",
                        Map.of("service", "s"))
        );
        JsonNode dashboard = assemblyService.assemble(buildState(panels, null));
        // 1 feedback panel + 1 log_stream (unknown panel skipped) = 2 total
        assertThat(dashboard.path("panels").size()).isEqualTo(2);
    }
}
