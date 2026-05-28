package com.firm.investigation.graph.node;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.memory.AtlasMemoryClient;
import com.firm.investigation.memory.AtlasMemoryResult;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AtlasMemoryNodeTest {

    @Mock private AtlasMemoryClient atlasMemoryClient;

    private AtlasMemoryNode node;

    @BeforeEach
    void setUp() {
        node = new AtlasMemoryNode(atlasMemoryClient, ObservationRegistry.NOOP);
    }

    private InvestigationState initialState() {
        return InvestigationState.initial(new InvestigationRequest(
                "InstrumentNotFoundException", "booking-service", "AT4278", "2025-01-15T10:30:00Z"));
    }

    @Test
    void apply_writesRetrievedMemoryToState() {
        AtlasMemoryResult expected = new AtlasMemoryResult(
                List.of(new AtlasMemoryResult.RelatedIncident(
                        "INC0001", "InstrumentNotFoundException booking + ledger",
                        "Re-ran refresh", "P1",
                        List.of("booking-service", "ledger-service"),
                        "data-refresh-failure", 0.92)),
                List.of("booking-service", "ledger-service"),
                List.of("data-refresh-failure"),
                List.of());
        when(atlasMemoryClient.retrieve(anyString(), anyString())).thenReturn(expected);

        InvestigationState result = node.apply(initialState());

        assertThat(result.atlasMemory()).isSameAs(expected);
    }

    @Test
    void apply_emptyResult_stillUpdatesState() {
        when(atlasMemoryClient.retrieve(anyString(), anyString())).thenReturn(AtlasMemoryResult.empty());

        InvestigationState result = node.apply(initialState());

        assertThat(result.atlasMemory().isEmpty()).isTrue();
    }
}
