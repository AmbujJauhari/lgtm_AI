package com.firm.investigation.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.config.InvestigationProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempoClientTest {

    private static WireMockServer wireMock;
    private TempoClient tempoClient;

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
                new InvestigationProperties.TempoProperties("http://localhost:" + wireMock.port(), 5),
                new InvestigationProperties.LokiProperties("http://localhost", 5),
                new InvestigationProperties.GrafanaProperties("http://localhost", "http://localhost", "token", "folder"),
                new InvestigationProperties.FallbackProperties(true),
                new InvestigationProperties.PanelServiceProperties("http://localhost:8081"),
                new InvestigationProperties.Neo4jProperties("bolt://localhost:7687", "neo4j", "test"),
                new InvestigationProperties.FeedbackProperties("http://localhost:8080"),
                new InvestigationProperties.DatasourceProperties("loki", "tempo", "prometheus"),
                Map.of());
        tempoClient = new TempoClient(WebClient.builder(), props, new ObjectMapper());
    }

    private static final String TRACE_RESPONSE_TWO_SERVICES = """
            {
              "data": [{
                "processes": {
                  "p1": {"serviceName": "booking-service"},
                  "p2": {"serviceName": "ledger-service"},
                  "p3": {"serviceName": "booking-service"}
                }
              }]
            }
            """;

    @Test
    void fetchServiceNames_returnsDistinctServiceNames() {
        wireMock.stubFor(get(urlPathEqualTo("/api/traces/abc123"))
                .willReturn(okJson(TRACE_RESPONSE_TWO_SERVICES)));

        List<String> services = tempoClient.fetchServiceNames("abc123");

        assertThat(services).containsExactlyInAnyOrder("booking-service", "ledger-service");
    }

    @Test
    void fetchServiceNames_emptyData_returnsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo("/api/traces/xyz"))
                .willReturn(okJson("""
                        {"data": []}
                        """)));

        List<String> services = tempoClient.fetchServiceNames("xyz");

        assertThat(services).isEmpty();
    }

    @Test
    void fetchServiceNames_serverError_throwsException() {
        wireMock.stubFor(get(urlPathEqualTo("/api/traces/err"))
                .willReturn(serverError()));

        assertThatThrownBy(() -> tempoClient.fetchServiceNames("err"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Tempo fetch failed");
    }

    private static final String SEARCH_RESPONSE = """
            {
              "traces": [
                {
                  "rootServiceName": "booking-service",
                  "spanSets": [{
                    "spans": [{
                      "attributes": [{"key": "service.name", "value": {"stringValue": "ledger-service"}}]
                    }]
                  }]
                }
              ]
            }
            """;

    @Test
    void searchServiceNames_returnsServiceNamesFromTracesAndSpans() {
        wireMock.stubFor(get(urlPathEqualTo("/api/search"))
                .willReturn(okJson(SEARCH_RESPONSE)));

        List<String> services = tempoClient.searchServiceNames(
                "booking-service", "2025-01-15T10:30:00Z", 15);

        assertThat(services).containsExactlyInAnyOrder("booking-service", "ledger-service");
    }

    @Test
    void searchServiceNames_emptyTraces_returnsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo("/api/search"))
                .willReturn(okJson("""
                        {"traces": []}
                        """)));

        List<String> services = tempoClient.searchServiceNames(
                "booking-service", "2025-01-15T10:30:00Z", 15);

        assertThat(services).isEmpty();
    }

    @Test
    void searchServiceNames_serverError_throwsException() {
        wireMock.stubFor(get(urlPathEqualTo("/api/search")).willReturn(serverError()));

        assertThatThrownBy(() -> tempoClient.searchServiceNames(
                "svc", "2025-01-15T10:30:00Z", 15))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Tempo search failed");
    }

    @Test
    void searchServiceNames_invalidTimestamp_usesNow() {
        wireMock.stubFor(get(urlPathEqualTo("/api/search"))
                .willReturn(okJson("""
                        {"traces": []}
                        """)));

        // Should not throw despite invalid timestamp — fall back to Instant.now()
        List<String> services = tempoClient.searchServiceNames("svc", "not-a-date", 15);

        assertThat(services).isEmpty();
    }
}
