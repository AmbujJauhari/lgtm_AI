package com.firm.investigation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "investigation")
public record InvestigationProperties(
    TempoProperties tempo,
    LokiProperties loki,
    GrafanaProperties grafana,
    FallbackProperties fallback,
    PanelServiceProperties panelService,
    Neo4jProperties neo4j,
    FeedbackProperties feedback,
    DatasourceProperties datasources,
    Map<String, List<String>> serviceCatalog
) {
    public record TempoProperties(String baseUrl, int timeoutSeconds) {}
    public record LokiProperties(String baseUrl, int timeoutSeconds) {}
    /**
     * @param baseUrl       internal Grafana URL used for API calls (e.g. {@code http://lgtm:3000})
     * @param publicBaseUrl browser-facing Grafana URL returned in dashboard links
     *                      (e.g. {@code http://localhost:3000}). Defaults to {@code baseUrl}
     *                      when not set.
     */
    public record GrafanaProperties(String baseUrl, String publicBaseUrl, String token,
                                    String investigationFolderUid) {
        public String effectivePublicBaseUrl() {
            return (publicBaseUrl == null || publicBaseUrl.isBlank()) ? baseUrl : publicBaseUrl;
        }
    }
    public record FallbackProperties(boolean enabled) {}
    public record PanelServiceProperties(String baseUrl) {}
    public record Neo4jProperties(String uri, String username, String password) {}
    public record FeedbackProperties(String baseUrl) {}

    /**
     * Default Grafana datasource UIDs injected into every generated dashboard
     * as templating variables ({@code ${ds_loki}}, {@code ${ds_tempo}},
     * {@code ${ds_prometheus}}). The L2 can switch datasources from the
     * dashboard UI without us republishing.
     */
    public record DatasourceProperties(String loki, String tempo, String prometheus) {}
}
