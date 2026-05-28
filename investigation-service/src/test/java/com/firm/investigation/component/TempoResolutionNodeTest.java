package com.firm.investigation.component;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.graph.node.TempoResolutionNode;
import com.firm.investigation.reason.ReasonResult;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TempoResolutionNodeTest {

    @Mock private TempoClient tempoClient;

    private TempoResolutionNode node;
    private InvestigationRequest request;

    @BeforeEach
    void setUp() {
        node = new TempoResolutionNode(tempoClient, ObservationRegistry.NOOP);
        request = new InvestigationRequest(
                "error log", "collateral-service", "COLL", "2024-01-15T10:30:00Z");
    }

    private InvestigationState stateWithReason(ReasonResult reason) {
        return InvestigationState.initial(request).withReason(reason);
    }

    private ReasonResult reason(ReasonResult.TempoPlan tempoPlan) {
        return new ReasonResult("NPE", "NPE", null, List.of("collateral-service"), 0.9,
                tempoPlan, ReasonResult.LokiPlan.skip(), "test");
    }

    @Test
    void apply_planSkipsTempo_returnsOriginatingServiceOnly() {
        InvestigationState result = node.apply(stateWithReason(reason(ReasonResult.TempoPlan.skip())));
        assertThat(result.traceComponents()).containsExactly("collateral-service");
        verifyNoInteractions(tempoClient);
    }

    @Test
    void apply_planWithTraceId_callsGetById() {
        ReasonResult.TempoPlan plan = new ReasonResult.TempoPlan(true, "abc123", null, null);
        when(tempoClient.fetchServiceNames("abc123"))
                .thenReturn(List.of("margin-service", "collateral-service", "position-service"));
        InvestigationState result = node.apply(stateWithReason(reason(plan)));
        assertThat(result.traceComponents())
                .containsExactlyInAnyOrder("margin-service", "collateral-service", "position-service");
    }

    @Test
    void apply_planWithSearchByService_callsSearch() {
        ReasonResult.TempoPlan plan = new ReasonResult.TempoPlan(true, null, "collateral-service", 15);
        when(tempoClient.searchServiceNames("collateral-service", request.timestamp(), 15))
                .thenReturn(List.of("collateral-service", "ledger-service"));
        InvestigationState result = node.apply(stateWithReason(reason(plan)));
        assertThat(result.traceComponents())
                .containsExactlyInAnyOrder("collateral-service", "ledger-service");
    }

    @Test
    void apply_tempoReturnsEmpty_fallsBackToOriginatingService() {
        ReasonResult.TempoPlan plan = new ReasonResult.TempoPlan(true, "abc123", null, null);
        when(tempoClient.fetchServiceNames("abc123")).thenReturn(List.of());
        InvestigationState result = node.apply(stateWithReason(reason(plan)));
        assertThat(result.traceComponents()).containsExactly("collateral-service");
    }

    @Test
    void apply_tempoThrows_fallsBackToOriginatingService() {
        ReasonResult.TempoPlan plan = new ReasonResult.TempoPlan(true, "abc123", null, null);
        when(tempoClient.fetchServiceNames("abc123"))
                .thenThrow(new RuntimeException("Tempo connection refused"));
        InvestigationState result = node.apply(stateWithReason(reason(plan)));
        assertThat(result.traceComponents()).containsExactly("collateral-service");
    }

    @Test
    void apply_nullReason_returnsOriginatingService() {
        InvestigationState state = InvestigationState.initial(request); // reason is null
        InvestigationState result = node.apply(state);
        assertThat(result.traceComponents()).containsExactly("collateral-service");
        verifyNoInteractions(tempoClient);
    }
}
