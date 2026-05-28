package com.firm.investigation.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.config.InvestigationProperties;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tempo client. Two query modes:
 *   - {@link #fetchServiceNames(String)}: precise get-by-traceId (Jaeger v1 trace API)
 *   - {@link #searchServiceNames(String, String, int)}: fuzzy search recent error traces
 *     in a given service, used when traceId is missing or unreliable (e.g. RMI hops).
 */
@Component
public class TempoClient {

    private static final Logger log = LoggerFactory.getLogger(TempoClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public TempoClient(WebClient.Builder webClientBuilder, InvestigationProperties properties,
                       ObjectMapper objectMapper) {
        this.timeoutSeconds = properties.tempo().timeoutSeconds();
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(properties.tempo().baseUrl())
                .build();
    }

    public List<String> fetchServiceNames(String traceId) {
        log.info("Fetching trace from Tempo by id: traceId={}", traceId);
        try {
            String body = webClient.get()
                    .uri("/api/traces/{traceId}", traceId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            return extractServiceNamesFromTrace(objectMapper.readTree(body));
        } catch (Exception e) {
            log.warn("Tempo get-by-id failed for traceId={}: {}", traceId, e.getMessage());
            throw new RuntimeException("Tempo fetch failed: " + e.getMessage(), e);
        }
    }

    public List<String> searchServiceNames(String anchorService, String timestamp, int lookBackMinutes) {
        log.info("Searching Tempo for error traces: service={} lookBackMinutes={}",
                anchorService, lookBackMinutes);
        try {
            Instant end = parseTimestamp(timestamp);
            Instant start = end.minus(Duration.ofMinutes(Math.max(lookBackMinutes, 1)));

            String tags = URLEncoder.encode(
                    "service.name=" + anchorService + " error=true",
                    StandardCharsets.UTF_8);
            String uri = "/api/search?tags=" + tags
                    + "&start=" + start.getEpochSecond()
                    + "&end=" + end.getEpochSecond()
                    + "&limit=5";

            String body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            return extractServiceNamesFromSearch(objectMapper.readTree(body));
        } catch (Exception e) {
            log.warn("Tempo search failed for service={}: {}", anchorService, e.getMessage());
            throw new RuntimeException("Tempo search failed: " + e.getMessage(), e);
        }
    }

    private List<String> extractServiceNamesFromTrace(JsonNode root) {
        // Jaeger v1 format: data[0].processes.{key}.serviceName
        Set<String> services = new LinkedHashSet<>();
        JsonNode dataArray = root.path("data");
        if (dataArray.isArray() && !dataArray.isEmpty()) {
            JsonNode processes = dataArray.get(0).path("processes");
            processes.fields().forEachRemaining(entry -> {
                String serviceName = entry.getValue().path("serviceName").asText(null);
                if (serviceName != null && !serviceName.isBlank()) {
                    services.add(serviceName);
                }
            });
        }
        return new ArrayList<>(services);
    }

    private List<String> extractServiceNamesFromSearch(JsonNode root) {
        // Tempo search response: traces[].rootServiceName + spans[]
        Set<String> services = new LinkedHashSet<>();
        JsonNode traces = root.path("traces");
        if (traces.isArray()) {
            for (JsonNode trace : traces) {
                String root1 = trace.path("rootServiceName").asText(null);
                if (root1 != null && !root1.isBlank()) services.add(root1);
                JsonNode spanSets = trace.path("spanSets");
                if (spanSets.isArray()) {
                    for (JsonNode spanSet : spanSets) {
                        JsonNode spans = spanSet.path("spans");
                        if (spans.isArray()) {
                            for (JsonNode span : spans) {
                                JsonNode attrs = span.path("attributes");
                                if (attrs.isArray()) {
                                    for (JsonNode attr : attrs) {
                                        if ("service.name".equals(attr.path("key").asText())) {
                                            String v = attr.path("value").path("stringValue").asText(null);
                                            if (v != null && !v.isBlank()) services.add(v);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(services);
    }

    private Instant parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse timestamp '{}', using now", timestamp);
            return Instant.now();
        }
    }
}
