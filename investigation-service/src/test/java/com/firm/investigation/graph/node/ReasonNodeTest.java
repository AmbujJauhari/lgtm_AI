package com.firm.investigation.graph.node;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.reason.ReasonResult;
import com.firm.investigation.reason.ReasonService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReasonNodeTest {

    @Mock private ReasonService reasonService;

    private ReasonNode node;

    @BeforeEach
    void setUp() {
        node = new ReasonNode(reasonService, ObservationRegistry.NOOP);
    }

    private InvestigationState initialState() {
        return InvestigationState.initial(new InvestigationRequest(
                "error", "collateral-service", "AT4278", "2025-01-15T10:30:00Z"));
    }

    @Test
    void apply_setsReasonOnState() {
        ReasonResult expected = new ReasonResult(
                "NPE", "NPE", null, List.of("collateral-service"), 0.9,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test");
        when(reasonService.reason(any(), any())).thenReturn(expected);

        InvestigationState result = node.apply(initialState());

        assertThat(result.reason()).isSameAs(expected);
    }

    @Test
    void apply_serviceThrows_propagates() {
        when(reasonService.reason(any(), any())).thenThrow(new RuntimeException("LLM error"));

        assertThatThrownBy(() -> node.apply(initialState()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM error");
    }
}
