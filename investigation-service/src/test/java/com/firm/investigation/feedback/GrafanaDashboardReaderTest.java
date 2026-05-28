package com.firm.investigation.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.config.InvestigationProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrafanaDashboardReaderTest {

    private static WireMockServer wireMock;
    private GrafanaDashboardReader reader;

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
        InvestigationProperties props = new InvestigationProperties(
                new InvestigationProperties.TempoProperties("http://localhost", 5),
                new InvestigationProperties.LokiProperties("http://localhost", 5),
                new InvestigationProperties.GrafanaProperties(
                        "http://localhost:" + wireMock.port(),
                        "http://localhost:" + wireMock.port(),
                        "test-token", "folder"),
                new InvestigationProperties.FallbackProperties(true),
                new InvestigationProperties.PanelServiceProperties("http://localhost"),
                new InvestigationProperties.Neo4jProperties("bolt://localhost", "n", "p"),
                new InvestigationProperties.FeedbackProperties("http://localhost"),
                Map.of());
        reader = new GrafanaDashboardReader(WebClient.builder(), props, new ObjectMapper());
    }

    @Test
    void fetch_parsesTagsDescriptionAndPanels() {
        wireMock.stubFor(get(urlPathEqualTo("/api/dashboards/uid/inv-1"))
                .willReturn(okJson("""
                        {
                          "dashboard": {
                            "title": "NPE Investigation",
                            "description": "raw log line text",
                            "tags": ["ai-investigation", "appcode-AT4278", "service-svc", "category-NPE"],
                            "panels": [
                              {"type": "text", "title": ""},
                              {"type": "logs", "title": "Logs"},
                              {"type": "timeseries", "title": "Heap"}
                            ]
                          }
                        }
                        """)));

        GrafanaDashboardReader.DashboardSnapshot snapshot = reader.fetch("inv-1");

        assertThat(snapshot.title()).isEqualTo("NPE Investigation");
        assertThat(snapshot.description()).isEqualTo("raw log line text");
        assertThat(snapshot.appcode()).isEqualTo("AT4278");
        assertThat(snapshot.service()).isEqualTo("svc");
        assertThat(snapshot.errorCategory()).isEqualTo("NPE");
        // text panel skipped, only logs + timeseries captured
        assertThat(snapshot.finalPanelDescriptors()).hasSize(2);
        assertThat(snapshot.finalPanelDescriptors()).contains("logs:Logs", "timeseries:Heap");
    }

    @Test
    void fetch_missingMetadata_returnsNullsForUnsetTags() {
        wireMock.stubFor(get(urlPathEqualTo("/api/dashboards/uid/inv-2"))
                .willReturn(okJson("""
                        {"dashboard": {"title": "", "tags": [], "panels": []}}
                        """)));

        GrafanaDashboardReader.DashboardSnapshot snapshot = reader.fetch("inv-2");

        assertThat(snapshot.appcode()).isNull();
        assertThat(snapshot.service()).isNull();
        assertThat(snapshot.finalPanelDescriptors()).isEmpty();
    }

    @Test
    void fetch_grafana404_throws() {
        wireMock.stubFor(get(urlPathEqualTo("/api/dashboards/uid/missing"))
                .willReturn(notFound()));

        assertThatThrownBy(() -> reader.fetch("missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not fetch dashboard");
    }
}
