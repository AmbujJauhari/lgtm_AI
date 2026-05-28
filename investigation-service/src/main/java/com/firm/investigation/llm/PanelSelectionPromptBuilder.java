package com.firm.investigation.llm;

import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.memory.AtlasMemoryResult;
import com.firm.investigation.memory.DashboardMemoryResult;
import com.firm.investigation.panel.CrossServiceLogEntry;
import com.firm.investigation.reason.ReasonResult;
import org.springframework.stereotype.Component;

@Component
public class PanelSelectionPromptBuilder {

    public String systemPrompt() {
        return """
                You are selecting Grafana dashboard panels for incident investigation in a \
                Java-based investment banking platform with 140 microservices.
                Respond ONLY with valid JSON. No explanation. No markdown. No code fences.""";
    }

    public String userPrompt(InvestigationState state, String availablePanels) {
        ReasonResult reason = state.reason();
        var request = state.request();
        String components = String.join(", ", state.mergedComponents());
        String traceId = reason != null ? reason.traceId() : null;
        boolean hasTrace = traceId != null && !traceId.isBlank();
        String errorCategory = reason != null ? reason.errorCategory() : "UNKNOWN";

        var sb = new StringBuilder();
        sb.append("Select the most relevant panels for this incident from the available panel library.\n\n");
        sb.append("Error Category: ").append(errorCategory).append("\n");
        sb.append("Originating Service: ").append(request.service()).append("\n");
        sb.append("Appcode: ").append(request.appcode()).append("\n");
        sb.append("Timestamp: ").append(request.timestamp()).append("\n");
        sb.append("Trace ID available: ").append(hasTrace ? "YES — " + traceId : "NO").append("\n");
        sb.append("Affected Components (use exactly): ").append(components).append("\n");
        sb.append("Original Log Line: ").append(request.logLine()).append("\n");

        if (reason != null && reason.reasoning() != null && !reason.reasoning().isBlank()) {
            sb.append("\nReasoning context: ").append(reason.reasoning()).append("\n");
        }

        appendAtlasContext(sb, state.atlasMemory());
        appendDashboardMemoryContext(sb, state.dashboardMemory());
        appendCrossServiceLogs(sb, state);

        sb.append("\nAvailable panels:\n").append(availablePanels);

        sb.append("""

                Rules:
                - Always include log_stream
                - If Trace ID is available (YES), always include trace_waterfall
                - Maximum 6 panels total — prioritise specificity over quantity
                - For "components" variable use the exact "Affected Components" list above (comma-separated)
                - timeFrom = timestamp minus 15 minutes; timeTo = timestamp plus 5 minutes (ISO-8601 format)
                - For panels scoped to the originating service only (log_stream, db_*, mq_*) use "service" variable
                - For panels spanning multiple components (heap_by_component, latency_*, error_rate, thread_pool_active,
                  gc_pause_duration) use "components" variable with the full comma-separated list

                Respond with exactly this JSON:
                {
                  "selectedPanels": [
                    {
                      "panelId": "<panelId from available panels>",
                      "variables": {
                        "service": "<originating service>",
                        "appcode": "<appcode>",
                        "components": "<comma-separated components list>",
                        "traceId": "<traceId or empty string>",
                        "timeFrom": "<ISO-8601 start time>",
                        "timeTo": "<ISO-8601 end time>"
                      }
                    }
                  ],
                  "dashboardTitle": "<errorCategory> Investigation — <service> — <date time>"
                }
                """);

        return sb.toString();
    }

    private void appendAtlasContext(StringBuilder sb, AtlasMemoryResult atlas) {
        if (atlas == null || atlas.isEmpty()) return;
        sb.append("\nPast similar incidents (Atlas):\n");
        for (AtlasMemoryResult.RelatedIncident inc : atlas.relatedIncidents()) {
            sb.append("  [").append(inc.ticketId()).append("] ").append(inc.title()).append("\n");
            sb.append("    Services: ").append(String.join(", ", inc.affectedServices())).append("\n");
            if (inc.rootCauseCategory() != null) {
                sb.append("    Root cause: ").append(inc.rootCauseCategory()).append("\n");
            }
        }
    }

    private void appendDashboardMemoryContext(StringBuilder sb, DashboardMemoryResult memory) {
        if (memory == null || memory.isEmpty()) return;
        sb.append("\nPast L2 feedback on similar investigations (real signal about which panels were useful):\n");
        for (DashboardMemoryResult.PastFeedback fb : memory.entries()) {
            sb.append("  service=").append(fb.service())
              .append(" category=").append(fb.errorCategory()).append("\n");
            if (fb.finalPanelDescriptors() != null && !fb.finalPanelDescriptors().isEmpty()) {
                sb.append("    Panels L2 kept: ").append(String.join(", ", fb.finalPanelDescriptors())).append("\n");
            }
            if (fb.feedbackText() != null && !fb.feedbackText().isBlank()) {
                sb.append("    L2 notes: \"").append(fb.feedbackText()).append("\"\n");
            }
        }
    }

    private void appendCrossServiceLogs(StringBuilder sb, InvestigationState state) {
        if (state.crossServiceLogs() == null || state.crossServiceLogs().isEmpty()) return;
        sb.append("\nCross-Service Log Evidence (same error pattern found in these services right now):\n");
        for (CrossServiceLogEntry entry : state.crossServiceLogs()) {
            sb.append("  [").append(entry.service()).append("] ").append(entry.logLine()).append("\n");
        }
    }
}
