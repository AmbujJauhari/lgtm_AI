package com.firm.investigation.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtlasMemoryClientTest {

    private Driver driver;
    private EmbeddingModel embeddingModel;
    private AtlasMemoryClient client;

    @BeforeEach
    void setUp() {
        driver = mock(Driver.class);
        embeddingModel = mock(EmbeddingModel.class);
        client = new AtlasMemoryClient(driver, embeddingModel);
    }

    @Test
    void retrieve_embeddingFailure_returnsEmpty() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embedding failed"));

        AtlasMemoryResult result = client.retrieve("any log line", "AT4278");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void retrieve_driverFailure_returnsEmpty() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(driver.session()).thenThrow(new RuntimeException("neo4j unreachable"));

        AtlasMemoryResult result = client.retrieve("any log line", "AT4278");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void retrieve_sessionThrowsOnQuery_returnsEmpty() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        Session session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), org.mockito.ArgumentMatchers.<org.neo4j.driver.Value>any()))
                .thenThrow(new RuntimeException("cypher error"));

        AtlasMemoryResult result = client.retrieve("any log line", "AT4278");

        assertThat(result.isEmpty()).isTrue();
    }
}
