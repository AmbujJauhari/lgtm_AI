package com.firm.investigation.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.panel.CrossServiceLogEntry;
import com.firm.investigation.reason.ReasonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-service Loki log search.
 *
 * Builds LogQL queries from structured {@link ReasonResult.LokiPlan} fields supplied
 * by the upstream reasoning step. The LLM never writes raw LogQL — it provides
 * intent (pattern, services, time window) and this client builds the actual query.
 */
@Component
public class LokiCrossServiceClient {

    private static final Logger log = LoggerFactory.getLogger(LokiCrossServiceClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public LokiCrossServiceClient(WebClient.Builder webClientBuilder, InvestigationProperties properties,
                                  ObjectMapper objectMapper) {
        this.timeoutSeconds = properties.loki().timeoutSeconds();
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(properties.loki().baseUrl())
                .build();
    }

    public CrossServiceResult execute(String appcode, ReasonResult.LokiPlan plan, String timestamp) {
        List<CrossServiceLogEntry> logs = queryLoki(appcode, plan, timestamp);
        Map<String, String> byService = groupByService(logs);
        List<String> services = new ArrayList<>(byService.keySet());
        List<CrossServiceLogEntry> representative = byService.entrySet().stream()
                .map(e -> new CrossServiceLogEntry(e.getKey(), e.getValue()))
                .toList();
        return new CrossServiceResult(services, representative);
    }

    private List<CrossServiceLogEntry> queryLoki(String appcode, ReasonResult.LokiPlan plan, String timestamp) {
        try {
            int lookBack = Math.max(plan.timeWindowMinutes(), 1);
            Instant end = parseTimestamp(timestamp);
            Instant start = end.minus(Duration.ofMinutes(lookBack));
            String query = buildLogQL(appcode, plan);

            // Manually URL-encode to prevent UriComponentsBuilder from treating
            // curly braces as URI template variables.
            String uri = "/loki/api/v1/query_range?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&start=" + URLEncoder.encode(start.toString(), StandardCharsets.UTF_8)
                    + "&end=" + URLEncoder.encode(end.toString(), StandardCharsets.UTF_8)
                    + "&limit=100";

            String body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            return parseLokiResponse(objectMapper.readTree(body));
        } catch (Exception e) {
            log.warn("Loki cross-service query failed for appcode={} pattern={}: {}",
                    appcode, plan.pattern(), e.getMessage());
            throw new RuntimeException("Loki query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a safe LogQL query from the structured plan. Escapes user-controlled
     * substrings to prevent injection into label selectors or grep patterns.
     */
    String buildLogQL(String appcode, ReasonResult.LokiPlan plan) {
        StringBuilder selectors = new StringBuilder("{appcode=\"").append(escapeLabel(appcode)).append("\"");
        if (plan.services() != null && !plan.services().isEmpty()) {
            String alternation = plan.services().stream()
                    .map(LokiCrossServiceClient::escapeLabel)
                    .reduce((a, b) -> a + "|" + b).orElse("");
            selectors.append(", service=~\"").append(alternation).append("\"");
        }
        selectors.append("}");

        String pattern = plan.pattern();
        if (pattern == null || pattern.isBlank()) {
            // Shouldn't happen — Reason should null out lokiPlan.query if pattern unusable.
            // Defensive: skip the grep filter.
            return selectors + " | level = \"ERROR\"";
        }
        return selectors + " |= \"" + escapeGrepPattern(pattern) + "\" | level = \"ERROR\"";
    }

    private static String escapeLabel(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private static String escapeGrepPattern(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<CrossServiceLogEntry> parseLokiResponse(JsonNode root) {
        List<CrossServiceLogEntry> entries = new ArrayList<>();
        JsonNode results = root.path("data").path("result");
        if (results.isArray()) {
            for (JsonNode stream : results) {
                String service = stream.path("stream").path("service").asText(null);
                if (service == null) {
                    service = stream.path("stream").path("app").asText("unknown");
                }
                JsonNode values = stream.path("values");
                if (values.isArray() && !values.isEmpty()) {
                    String logLine = values.get(0).get(1).asText("");
                    entries.add(new CrossServiceLogEntry(service, logLine));
                }
            }
        }
        return entries;
    }

    private Map<String, String> groupByService(List<CrossServiceLogEntry> entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (CrossServiceLogEntry entry : entries) {
            map.putIfAbsent(entry.service(), entry.logLine());
        }
        return map;
    }

    private Instant parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse timestamp '{}', using now", timestamp);
            return Instant.now();
        }
    }

    public record CrossServiceResult(List<String> services, List<CrossServiceLogEntry> representativeLogs) {}
}
