package com.firm.investigation.config;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jConfigTest {

    @Test
    void neo4jDriver_returnsNonNullDriverWithConfiguredUri() {
        Neo4jConfig config = new Neo4jConfig();
        InvestigationProperties properties = new InvestigationProperties(
                new InvestigationProperties.TempoProperties("http://localhost", 5),
                new InvestigationProperties.LokiProperties("http://localhost", 5),
                new InvestigationProperties.GrafanaProperties("http://localhost", "http://localhost", "token", "folder"),
                new InvestigationProperties.FallbackProperties(true),
                new InvestigationProperties.PanelServiceProperties("http://localhost:8081"),
                new InvestigationProperties.Neo4jProperties("bolt://localhost:7687", "neo4j", "test"),
                new InvestigationProperties.FeedbackProperties("http://localhost:8080"),
                Map.of());

        Driver driver = config.neo4jDriver(properties);
        try {
            assertThat(driver).isNotNull();
        } finally {
            driver.close();
        }
    }
}
