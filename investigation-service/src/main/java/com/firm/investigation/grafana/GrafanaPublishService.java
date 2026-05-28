package com.firm.investigation.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firm.investigation.config.InvestigationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Service
public class GrafanaPublishService {

    private static final Logger log = LoggerFactory.getLogger(GrafanaPublishService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final InvestigationProperties properties;

    public GrafanaPublishService(WebClient.Builder webClientBuilder, InvestigationProperties properties,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(properties.grafana().baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.grafana().token())
                .build();
    }

    public String publish(JsonNode dashboardJson, InvestigationProperties props) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("dashboard", dashboardJson);
        body.put("overwrite", true);
        body.put("folderUid", props.grafana().investigationFolderUid());
        body.put("message", "AI-generated investigation dashboard");

        try {
            String responseBody = webClient.post()
                    .uri("/api/dashboards/db")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            JsonNode response = objectMapper.readTree(responseBody);
            String url = response.path("url").asText(null);
            if (url == null || url.isBlank()) {
                throw new GrafanaPublishException("Grafana response missing 'url' field: " + responseBody);
            }
            String fullUrl = toAbsoluteUrl(url, props);
            log.info("Dashboard published: url={}", fullUrl);
            return fullUrl;
        } catch (GrafanaPublishException e) {
            throw e;
        } catch (Exception e) {
            throw new GrafanaPublishException("Grafana publish failed: " + e.getMessage(), e);
        }
    }

    /**
     * Grafana returns a path-only URL ({@code /d/uid/title}). Prefix it with the
     * publicly-reachable Grafana base URL so the browser navigates to Grafana
     * directly, not back to investigation-service.
     */
    private static String toAbsoluteUrl(String urlFromGrafana, InvestigationProperties props) {
        if (urlFromGrafana.startsWith("http://") || urlFromGrafana.startsWith("https://")) {
            return urlFromGrafana;
        }
        String base = props.grafana().effectivePublicBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + urlFromGrafana;
    }

    public static class GrafanaPublishException extends RuntimeException {
        public GrafanaPublishException(String message) {
            super(message);
        }
        public GrafanaPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
