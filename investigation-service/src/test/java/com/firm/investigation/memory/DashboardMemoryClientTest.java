package com.firm.investigation.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardMemoryClientTest {

    private Driver driver;
    private EmbeddingModel embeddingModel;
    private DashboardMemoryClient client;
    private Session session;

    @BeforeEach
    void setUp() {
        driver = mock(Driver.class);
        embeddingModel = mock(EmbeddingModel.class);
        session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        client = new DashboardMemoryClient(driver, embeddingModel);
    }

    @Test
    void retrieve_embeddingFailure_returnsEmpty() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embed error"));
        DashboardMemoryResult result = client.retrieve("log", "NPE");
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void retrieve_emptyCypherResult_returnsEmpty() {
        Result r = mock(Result.class);
        when(r.list()).thenReturn(List.of());
        when(session.run(anyString(), any(Value.class))).thenReturn(r);

        DashboardMemoryResult result = client.retrieve("log", "NPE");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void retrieve_parsesRecords() {
        Record record = mock(Record.class);
        when(record.get("dashboardUid")).thenReturn(Values.value("inv-1"));
        when(record.get("logLine")).thenReturn(Values.value("old log"));
        when(record.get("errorCategory")).thenReturn(Values.value("NPE"));
        when(record.get("service")).thenReturn(Values.value("svc"));
        when(record.get("panels")).thenReturn(Values.value(List.of("log_stream", "heap")));
        when(record.get("feedback")).thenReturn(Values.value("useful"));
        when(record.get("score")).thenReturn(Values.value(0.92));

        Result r = mock(Result.class);
        when(r.list()).thenReturn(List.of(record));
        when(session.run(anyString(), any(Value.class))).thenReturn(r);

        DashboardMemoryResult result = client.retrieve("similar log", "NPE");

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).dashboardUid()).isEqualTo("inv-1");
        assertThat(result.entries().get(0).finalPanelDescriptors()).containsExactly("log_stream", "heap");
        assertThat(result.entries().get(0).feedbackText()).isEqualTo("useful");
    }

    @Test
    void retrieve_sessionThrows_returnsEmpty() {
        when(session.run(anyString(), any(Value.class))).thenThrow(new RuntimeException("cypher fail"));

        DashboardMemoryResult result = client.retrieve("log", "NPE");

        assertThat(result.isEmpty()).isTrue();
    }
}
