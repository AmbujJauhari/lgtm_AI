package com.firm.investigation.reason;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.memory.AtlasMemoryResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReasonPromptBuilder {

    public String systemPrompt() {
        return """
                You are an observability reasoning assistant for a financial services platform with 140 microservices.
                You analyze error log lines and reason about:
                  1. What kind of error it is (classification)
                  2. Which services are likely affected (using past incidents + general knowledge)
                  3. Whether live data queries (Tempo trace, Loki cross-service logs) would be useful

                Output ONLY a single JSON object. No markdown fences. No commentary.
                """;
    }

    public String userPrompt(
            InvestigationRequest request,
            AtlasMemoryResult atlasMemory,
            List<String> serviceCatalog) {

        StringBuilder sb = new StringBuilder();
        sb.append("Appcode: ").append(request.appcode()).append("\n");
        sb.append("Originating service: ").append(request.service()).append("\n");
        sb.append("Timestamp: ").append(request.timestamp()).append("\n\n");

        sb.append("Canonical service catalog for this appcode (use ONLY these names):\n");
        for (String svc : serviceCatalog) sb.append("  - ").append(svc).append("\n");
        sb.append("\n");

        if (!atlasMemory.isEmpty()) {
            sb.append("Past similar incidents (most similar first):\n");
            for (AtlasMemoryResult.RelatedIncident inc : atlasMemory.relatedIncidents()) {
                sb.append("  [").append(inc.ticketId()).append("] ")
                  .append(inc.title()).append("\n");
                sb.append("    Affected services: ").append(String.join(", ", inc.affectedServices())).append("\n");
                if (inc.rootCauseCategory() != null) {
                    sb.append("    Root cause: ").append(inc.rootCauseCategory()).append("\n");
                }
                sb.append("    Resolution: ").append(truncate(inc.closureNotes(), 200)).append("\n");
            }
            sb.append("\n");
            sb.append("Historical affected services (union across past incidents): ")
              .append(String.join(", ", atlasMemory.historicalServices())).append("\n\n");
        } else {
            sb.append("No similar past incidents found in Atlas — reason from general knowledge.\n\n");
        }

        sb.append("Current log line:\n").append(request.logLine()).append("\n\n");

        sb.append("""
                Output exactly this JSON structure:
                {
                  "errorCategory": one of [NPE, OOM, TIMEOUT, DB_ERROR, MQ_ERROR, CONNECTION_REFUSED, THREAD_DEADLOCK, BUSINESS_EXCEPTION, UNKNOWN],
                  "errorPattern": "exception class or distinctive substring" or null,
                  "traceId": "extracted trace ID hex string" or null,
                  "likelyServices": ["service-name", ...],
                  "confidence": 0.0 to 1.0,
                  "tempoPlan": {
                    "query": true | false,
                    "traceId": "..." or null,
                    "searchByService": "service-name" or null,
                    "searchByTimeMinutes": integer or null
                  },
                  "lokiPlan": {
                    "query": true | false,
                    "pattern": "distinctive substring to grep" or null,
                    "services": ["service-name", ...],
                    "timeWindowMinutes": integer
                  },
                  "reasoning": "one sentence explaining your service list and query decisions"
                }

                Rules:
                - likelyServices MUST be drawn ONLY from the service catalog above. Do not invent services.
                - errorPattern should be null if too generic (e.g. just "Exception" or "Error") — needs a class name or distinctive token.
                - confidence reflects certainty about likelyServices. High when Atlas has clear precedent, low when novel.
                - Set tempoPlan.query=true if traceId is present (get-by-id) OR if you'd benefit from searching recent error traces in the originating service.
                - traceId values can be unreliable due to RMI calls — if traceId looks orphaned (no Atlas history references it), consider search mode instead.
                - Set lokiPlan.query=true when errorPattern is distinctive enough to grep across services AND past incidents OR error type suggests cross-service blast radius.
                - For business exceptions (InstrumentNotFoundException, AccountNotFoundException, etc.), lean heavily on Atlas history for service list.
                - lokiPlan.services should be the services you want to grep across (subset or all of likelyServices).
                """);
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
