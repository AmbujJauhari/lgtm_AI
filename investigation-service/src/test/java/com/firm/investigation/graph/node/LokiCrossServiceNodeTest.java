package com.firm.investigation.graph.node;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.component.LokiCrossServiceClient;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.panel.CrossServiceLogEntry;
import com.firm.investigation.reason.ReasonResult;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LokiCrossServiceNodeTest {

    @Mock private LokiCrossServiceClient client;

    private LokiCrossServiceNode node;
    private InvestigationRequest request;

    @BeforeEach
    void setUp() {
        node = new LokiCrossServiceNode(client, ObservationRegistry.NOOP);
        request = new InvestigationRequest(
                "error", "svc", "AT4278", "2025-01-15T10:30:00Z");
    }

    private ReasonResult reasonWith(ReasonResult.LokiPlan plan, double confidence) {
        return new ReasonResult("NPE", "NPE", null, List.of("svc"), confidence,
                ReasonResult.TempoPlan.skip(), plan, "test");
    }

    @Test
    void apply_nullReason_skipsAndReturnsEmpty() {
        InvestigationState state = InvestigationState.initial(request);
        InvestigationState result = node.apply(state);
        assertThat(result.lokiComponents()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void apply_planSkipAndHighConfidence_skipsCall() {
        ReasonResult reason = reasonWith(ReasonResult.LokiPlan.skip(), 0.9);
        InvestigationState result = node.apply(InvestigationState.initial(request).withReason(reason));
        assertThat(result.lokiComponents()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void apply_planSkipButLowConfidence_triggersCallAnyway() {
        ReasonResult.LokiPlan plan = new ReasonResult.LokiPlan(false, "NPE", List.of(), 10);
        ReasonResult reason = reasonWith(plan, 0.3);  // < 0.6 threshold
        when(client.execute(anyString(), any(), anyString())).thenReturn(
                new LokiCrossServiceClient.CrossServiceResult(
                        List.of("svc-a"), List.of(new CrossServiceLogEntry("svc-a", "log"))));

        InvestigationState result = node.apply(InvestigationState.initial(request).withReason(reason));

        assertThat(result.lokiComponents()).containsExactly("svc-a");
    }

    @Test
    void apply_planQueryTrueWithPattern_executes() {
        ReasonResult.LokiPlan plan = new ReasonResult.LokiPlan(true, "InstrumentNotFound", List.of(), 15);
        ReasonResult reason = reasonWith(plan, 0.9);
        when(client.execute(anyString(), any(), anyString())).thenReturn(
                new LokiCrossServiceClient.CrossServiceResult(
                        List.of("booking", "ledger"),
                        List.of(new CrossServiceLogEntry("booking", "log b"),
                                new CrossServiceLogEntry("ledger", "log l"))));

        InvestigationState result = node.apply(InvestigationState.initial(request).withReason(reason));

        assertThat(result.lokiComponents()).containsExactly("booking", "ledger");
        assertThat(result.crossServiceLogs()).hasSize(2);
    }

    @Test
    void apply_planQueryTrueButNullPattern_skips() {
        ReasonResult.LokiPlan plan = new ReasonResult.LokiPlan(true, null, List.of(), 15);
        ReasonResult reason = reasonWith(plan, 0.9);

        InvestigationState result = node.apply(InvestigationState.initial(request).withReason(reason));

        assertThat(result.lokiComponents()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void apply_clientThrows_degradesGracefully() {
        ReasonResult.LokiPlan plan = new ReasonResult.LokiPlan(true, "pattern", List.of(), 15);
        ReasonResult reason = reasonWith(plan, 0.9);
        when(client.execute(anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("loki down"));

        InvestigationState result = node.apply(InvestigationState.initial(request).withReason(reason));

        assertThat(result.lokiComponents()).isEmpty();
        assertThat(result.crossServiceLogs()).isEmpty();
    }
}
