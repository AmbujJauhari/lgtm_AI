package com.firm.investigation.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.config.InvestigationProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrafanaPublishServiceTest {

    private static WireMockServer wireMock;
    private GrafanaPublishService publishService;
    private InvestigationProperties properties;
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        objectMapper = new ObjectMapper();
        properties = new InvestigationProperties(
                new InvestigationProperties.TempoProperties("http://localhost", 5),
                new InvestigationProperties.LokiProperties("http://localhost", 5),
                new InvestigationProperties.GrafanaProperties(
                        "http://localhost:" + wireMock.port(),
                        "http://localhost:" + wireMock.port(),
                        "test-token", "test-folder"),
                new InvestigationProperties.FallbackProperties(true),
                new InvestigationProperties.PanelServiceProperties("http://localhost:8081"),
                new InvestigationProperties.Neo4jProperties("bolt://localhost:7687", "neo4j", "test"),
                new InvestigationProperties.FeedbackProperties("http://localhost:8080"),
                java.util.Map.of());
        publishService = new GrafanaPublishService(WebClient.builder(), properties, objectMapper);
    }

    @Test
    void publish_successResponse_returnsUrl() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/dashboards/db"))
                .willReturn(okJson("""
                        {"id": 42, "uid": "inv-abc123", "url": "/d/inv-abc123/title", "status": "success"}
                        """)));

        JsonNode dashboard = objectMapper.readTree("""
                {"title": "Test Dashboard", "panels": []}
                """);

        String url = publishService.publish(dashboard, properties);

        assertThat(url).isEqualTo("http://localhost:" + wireMock.port() + "/d/inv-abc123/title");
    }

    @Test
    void publish_requestIncludesAuthHeader() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/dashboards/db"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .willReturn(okJson("""
                        {"url": "/d/uid/title"}
                        """)));

        JsonNode dashboard = objectMapper.readTree("{\"title\": \"t\"}");
        String url = publishService.publish(dashboard, properties);

        assertThat(url).endsWith("/d/uid/title");
        wireMock.verify(postRequestedFor(urlEqualTo("/api/dashboards/db"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void publish_serverError_throwsGrafanaPublishException() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/dashboards/db"))
                .willReturn(serverError()));

        JsonNode dashboard = objectMapper.readTree("{\"title\": \"t\"}");

        assertThatThrownBy(() -> publishService.publish(dashboard, properties))
                .isInstanceOf(GrafanaPublishService.GrafanaPublishException.class);
    }

    @Test
    void publish_missingUrlInResponse_throwsGrafanaPublishException() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/dashboards/db"))
                .willReturn(okJson("""
                        {"id": 42, "status": "success"}
                        """)));

        JsonNode dashboard = objectMapper.readTree("{\"title\": \"t\"}");

        assertThatThrownBy(() -> publishService.publish(dashboard, properties))
                .isInstanceOf(GrafanaPublishService.GrafanaPublishException.class)
                .hasMessageContaining("missing 'url'");
    }

    @Test
    void publish_requestBodyContainsFolderUid() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/dashboards/db"))
                .willReturn(okJson("{\"url\": \"/d/uid/t\"}")));

        JsonNode dashboard = objectMapper.readTree("{\"title\": \"t\"}");
        publishService.publish(dashboard, properties);

        wireMock.verify(postRequestedFor(urlEqualTo("/api/dashboards/db"))
                .withRequestBody(containing("test-folder")));
    }
}
