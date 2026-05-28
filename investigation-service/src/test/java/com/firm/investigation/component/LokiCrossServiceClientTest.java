package com.firm.investigation.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.panel.CrossServiceLogEntry;
import com.firm.investigation.reason.ReasonResult;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LokiCrossServiceClientTest {

    private static WireMockServer wireMock;
    private LokiCrossServiceClient lokiClient;

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
        InvestigationProperties.LokiProperties lokiProps =
                new InvestigationProperties.LokiProperties("http://localhost:" + wireMock.port(), 5);
        InvestigationProperties props = new InvestigationProperties(
                new InvestigationProperties.TempoProperties("http://localhost:" + wireMock.port(), 5),
                lokiProps,
                new InvestigationProperties.GrafanaProperties("http://localhost", "http://localhost", "token", "folder"),
                new InvestigationProperties.FallbackProperties(true),
                new InvestigationProperties.PanelServiceProperties("http://localhost:8081"),
                new InvestigationProperties.Neo4jProperties("bolt://localhost:7687", "neo4j", "test"),
                new InvestigationProperties.FeedbackProperties("http://localhost:8080"),
                new InvestigationProperties.DatasourceProperties("loki", "tempo", "prometheus"),
                java.util.Map.of());
        lokiClient = new LokiCrossServiceClient(WebClient.builder(), props, new ObjectMapper());
    }

    private static ReasonResult.LokiPlan plan(String pattern, List<String> services) {
        return new ReasonResult.LokiPlan(true, pattern, services, 10);
    }

    private static final String LOKI_RESPONSE_TWO_SERVICES = """
            {
              "data": {
                "result": [
                  {
                    "stream": {"service": "service-a", "appcode": "COLL"},
                    "values": [["1705316400000000000", "ERROR SybSQLException at ServiceA.java:42"]]
                  },
                  {
                    "stream": {"service": "service-b", "appcode": "COLL"},
                    "values": [["1705316401000000000", "ERROR SybSQLException connection refused"]]
                  }
                ]
              }
            }
            """;

    @Test
    void execute_returnsServiceNamesFromLoki() {
        wireMock.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(okJson(LOKI_RESPONSE_TWO_SERVICES)));

        LokiCrossServiceClient.CrossServiceResult result = lokiClient.execute(
                "COLL", plan("SybSQLException", List.of()), "2024-01-15T10:30:00Z");

        assertThat(result.services()).containsExactlyInAnyOrder("service-a", "service-b");
    }

    @Test
    void execute_returnsOneRepresentativeLogPerService() {
        wireMock.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(okJson(LOKI_RESPONSE_TWO_SERVICES)));

        LokiCrossServiceClient.CrossServiceResult result = lokiClient.execute(
                "COLL", plan("SybSQLException", List.of()), "2024-01-15T10:30:00Z");

        assertThat(result.representativeLogs()).hasSize(2);
        assertThat(result.representativeLogs().stream().map(CrossServiceLogEntry::service))
                .containsExactlyInAnyOrder("service-a", "service-b");
    }

    @Test
    void execute_deduplicatesServicesKeepingFirst() {
        String responseWithDuplicate = """
                {
                  "data": {
                    "result": [
                      {
                        "stream": {"service": "service-a", "appcode": "COLL"},
                        "values": [["1", "first log"]]
                      },
                      {
                        "stream": {"service": "service-a", "appcode": "COLL"},
                        "values": [["2", "second log"]]
                      }
                    ]
                  }
                }
                """;
        wireMock.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(okJson(responseWithDuplicate)));

        LokiCrossServiceClient.CrossServiceResult result = lokiClient.execute(
                "COLL", plan("SybSQLException", List.of()), "2024-01-15T10:30:00Z");

        assertThat(result.representativeLogs()).hasSize(1);
        assertThat(result.representativeLogs().get(0).logLine()).isEqualTo("first log");
    }

    @Test
    void execute_lokiServerError_throwsException() {
        wireMock.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(serverError()));

        assertThatThrownBy(() -> lokiClient.execute(
                "COLL", plan("SybSQLException", List.of()), "2024-01-15T10:30:00Z"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Loki query failed");
    }

    @Test
    void execute_emptyResult_returnsEmptyResult() {
        wireMock.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(okJson("""
                        {"data": {"result": []}}
                        """)));

        LokiCrossServiceClient.CrossServiceResult result = lokiClient.execute(
                "COLL", plan("MQRC_Q_FULL", List.of()), "2024-01-15T10:30:00Z");

        assertThat(result.services()).isEmpty();
    }

    @Test
    void buildLogQL_withServices_addsServiceSelector() {
        String query = lokiClient.buildLogQL("AT4278", plan("InstrumentNotFoundException",
                List.of("booking", "ledger")));
        assertThat(query).contains("appcode=\"AT4278\"");
        assertThat(query).contains("service=~\"booking|ledger\"");
        assertThat(query).contains("InstrumentNotFoundException");
    }

    @Test
    void buildLogQL_withoutServices_skipsServiceSelector() {
        String query = lokiClient.buildLogQL("AT4278", plan("OOMError", List.of()));
        assertThat(query).contains("appcode=\"AT4278\"");
        assertThat(query).doesNotContain("service=~");
    }
}
