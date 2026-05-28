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

class AtlasMemoryClientHappyPathTest {

    private Driver driver;
    private EmbeddingModel embeddingModel;
    private AtlasMemoryClient client;
    private Session session;

    @BeforeEach
    void setUp() {
        driver = mock(Driver.class);
        embeddingModel = mock(EmbeddingModel.class);
        session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        client = new AtlasMemoryClient(driver, embeddingModel);
    }

    private Record recordWith(String ticketId, String title, String closure, String priority,
                              List<String> services, String rootCause, List<String> components, double score) {
        Record r = mock(Record.class);
        when(r.get("ticketId")).thenReturn(Values.value(ticketId));
        when(r.get("title")).thenReturn(Values.value(title));
        when(r.get("closureNotes")).thenReturn(Values.value(closure));
        when(r.get("priority")).thenReturn(Values.value(priority));
        when(r.get("services")).thenReturn(Values.value(services));
        when(r.get("rootCauseCategory")).thenReturn(rootCause == null ? Values.NULL : Values.value(rootCause));
        when(r.get("components")).thenReturn(Values.value(components));
        when(r.get("score")).thenReturn(Values.value(score));
        return r;
    }

    @Test
    void retrieve_returnsParsedResultFromCypher() {
        Record r1 = recordWith("INC0001", "InstrumentNotFoundException", "Re-ran refresh",
                "P1", List.of("booking-service", "ledger-service"), "data-refresh-failure",
                List.of("reference-data-refresh"), 0.92);
        Record r2 = recordWith("INC0002", "InstrumentNotFoundException 6mo", "Manual cleanup",
                "P2", List.of("position-service", "ledger-service"), "data-corruption",
                List.of(), 0.85);

        Result result = mock(Result.class);
        when(result.list()).thenReturn(List.of(r1, r2));
        when(session.run(anyString(), any(Value.class))).thenReturn(result);

        AtlasMemoryResult retrieved = client.retrieve("InstrumentNotFoundException", "AT4278");

        assertThat(retrieved.isEmpty()).isFalse();
        assertThat(retrieved.relatedIncidents()).hasSize(2);
        assertThat(retrieved.relatedIncidents().get(0).ticketId()).isEqualTo("INC0001");
        assertThat(retrieved.historicalServices())
                .containsExactlyInAnyOrder("booking-service", "ledger-service", "position-service");
        assertThat(retrieved.historicalRootCauses())
                .containsExactlyInAnyOrder("data-refresh-failure", "data-corruption");
        assertThat(retrieved.mentionedComponents()).contains("reference-data-refresh");
    }

    @Test
    void retrieve_emptyCypherResult_returnsEmpty() {
        Result result = mock(Result.class);
        when(result.list()).thenReturn(List.of());
        when(session.run(anyString(), any(Value.class))).thenReturn(result);

        AtlasMemoryResult retrieved = client.retrieve("anything", "AT4278");

        assertThat(retrieved.isEmpty()).isTrue();
    }
}
