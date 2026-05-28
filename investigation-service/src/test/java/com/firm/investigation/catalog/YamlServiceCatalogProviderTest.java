package com.firm.investigation.catalog;

import com.firm.investigation.config.InvestigationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlServiceCatalogProviderTest {

    private InvestigationProperties propsWith(Map<String, List<String>> catalog) {
        return new InvestigationProperties(
                new InvestigationProperties.TempoProperties("http://localhost", 5),
                new InvestigationProperties.LokiProperties("http://localhost", 5),
                new InvestigationProperties.GrafanaProperties("http://localhost", "http://localhost", "token", "folder"),
                new InvestigationProperties.FallbackProperties(true),
                new InvestigationProperties.PanelServiceProperties("http://localhost:8081"),
                new InvestigationProperties.Neo4jProperties("bolt://localhost:7687", "neo4j", "test"),
                new InvestigationProperties.FeedbackProperties("http://localhost:8080"),
                new InvestigationProperties.DatasourceProperties("loki", "tempo", "prometheus"),
                catalog);
    }

    @Test
    void servicesFor_returnsConfiguredList() {
        YamlServiceCatalogProvider p = new YamlServiceCatalogProvider(propsWith(Map.of(
                "AT4278", List.of("booking-service", "ledger-service"))));
        assertThat(p.servicesFor("AT4278"))
                .containsExactlyInAnyOrder("booking-service", "ledger-service");
    }

    @Test
    void servicesFor_unknownAppcode_returnsEmpty() {
        YamlServiceCatalogProvider p = new YamlServiceCatalogProvider(propsWith(Map.of()));
        assertThat(p.servicesFor("XXXX")).isEmpty();
    }

    @Test
    void isKnownService_returnsTrueForKnown() {
        YamlServiceCatalogProvider p = new YamlServiceCatalogProvider(propsWith(Map.of(
                "AT4278", List.of("booking-service"))));
        assertThat(p.isKnownService("AT4278", "booking-service")).isTrue();
        assertThat(p.isKnownService("AT4278", "unknown")).isFalse();
    }

    @Test
    void validate_dropsHallucinatedServices() {
        YamlServiceCatalogProvider p = new YamlServiceCatalogProvider(propsWith(Map.of(
                "AT4278", List.of("booking-service", "ledger-service"))));
        List<String> validated = p.validate("AT4278",
                List.of("booking-service", "ledger-services", "fake-service"));
        assertThat(validated).containsExactly("booking-service");
    }

    @Test
    void validate_emptyInput_returnsEmpty() {
        YamlServiceCatalogProvider p = new YamlServiceCatalogProvider(propsWith(Map.of(
                "AT4278", List.of("booking-service"))));
        assertThat(p.validate("AT4278", List.of())).isEmpty();
        assertThat(p.validate("AT4278", null)).isEmpty();
    }

    @Test
    void validate_unknownAppcode_acceptsAllCandidates() {
        YamlServiceCatalogProvider p = new YamlServiceCatalogProvider(propsWith(Map.of()));
        List<String> result = p.validate("UNKNOWN", List.of("a", "b"));
        assertThat(result).containsExactly("a", "b");
    }

    @Test
    void nullCatalog_handlesGracefully() {
        YamlServiceCatalogProvider p = new YamlServiceCatalogProvider(propsWith(null));
        assertThat(p.servicesFor("AT4278")).isEmpty();
    }
}
