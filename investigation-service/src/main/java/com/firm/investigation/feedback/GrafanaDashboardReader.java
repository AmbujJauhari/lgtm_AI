package com.firm.investigation.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.config.InvestigationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches dashboard JSON from Grafana so feedback submission can:
 *  - Recover the original metadata (log line, appcode, service, errorCategory) from tags + description
 *  - Read the FINAL panel set (what the L2 kept / edited / added)
 */
@Component
public class GrafanaDashboardReader {

    private static final Logger log = LoggerFactory.getLogger(GrafanaDashboardReader.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String grafanaToken;

    public GrafanaDashboardReader(WebClient.Builder builder, InvestigationProperties properties,
                                  ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.grafanaToken = properties.grafana().token();
        this.webClient = builder.baseUrl(properties.grafana().baseUrl()).build();
    }

    public DashboardSnapshot fetch(String uid) {
        try {
            String body = webClient.get()
                    .uri("/api/dashboards/uid/{uid}", uid)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + grafanaToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            JsonNode root = objectMapper.readTree(body);
            JsonNode dash = root.path("dashboard");
            return parseSnapshot(dash);
        } catch (Exception e) {
            log.warn("Failed to fetch dashboard uid={}: {}", uid, e.getMessage());
            throw new RuntimeException("Could not fetch dashboard " + uid + ": " + e.getMessage(), e);
        }
    }

    private DashboardSnapshot parseSnapshot(JsonNode dash) {
        String title = dash.path("title").asText("");
        String description = dash.path("description").asText("");

        String appcode = null, service = null, errorCategory = null, traceId = null;
        for (JsonNode tag : dash.path("tags")) {
            String t = tag.asText("");
            if (t.startsWith("appcode-")) appcode = t.substring("appcode-".length());
            else if (t.startsWith("service-")) service = t.substring("service-".length());
            else if (t.startsWith("category-")) errorCategory = t.substring("category-".length());
            else if (t.startsWith("traceid-")) traceId = t.substring("traceid-".length());
        }

        List<String> panelIds = new ArrayList<>();
        for (JsonNode panel : dash.path("panels")) {
            String panelType = panel.path("type").asText("");
            // Skip text panels (feedback row); we only capture catalogued panel ids.
            // Catalogued panels carry a meaningful "datasource.uid" — text panels don't.
            String panelId = panel.path("title").asText("");  // fallback if no other identifier
            // Tempo/Loki/Mimir panels — title contains a recognizable element. We can't reliably
            // map a Grafana panel back to a catalog ID after editing; for now, capture the panel type
            // and the title slug as a coarse signal.
            if (!"text".equals(panelType) && !panel.path("title").asText("").isEmpty()) {
                panelIds.add(panelType + ":" + panelId);
            }
        }

        return new DashboardSnapshot(title, description, appcode, service, errorCategory, traceId, panelIds);
    }

    public record DashboardSnapshot(
        String title,
        String description,
        String appcode,
        String service,
        String errorCategory,
        String traceId,
        List<String> finalPanelDescriptors
    ) {}
}
