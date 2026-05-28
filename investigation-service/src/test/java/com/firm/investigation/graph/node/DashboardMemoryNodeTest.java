package com.firm.investigation.graph.node;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.memory.DashboardMemoryClient;
import com.firm.investigation.memory.DashboardMemoryResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardMemoryNodeTest {

    @Mock private DashboardMemoryClient client;

    private DashboardMemoryNode node;

    @BeforeEach
    void setUp() {
        node = new DashboardMemoryNode(client, ObservationRegistry.NOOP);
    }

    private InvestigationState initialState() {
        return InvestigationState.initial(new InvestigationRequest(
                "log line", "svc", "AT4278", "2025-01-15T10:30:00Z"));
    }

    @Test
    void apply_setsRetrievedDashboardMemoryOnState() {
        DashboardMemoryResult expected = new DashboardMemoryResult(List.of(
                new DashboardMemoryResult.PastFeedback("inv-1", "old log", "NPE", "svc",
                        List.of("log_stream"), "useful", 0.9)));
        when(client.retrieve(any(), any())).thenReturn(expected);

        InvestigationState result = node.apply(initialState());

        assertThat(result.dashboardMemory()).isSameAs(expected);
    }

    @Test
    void apply_passesErrorCategoryFromReason() {
        ReasonResult reason = new ReasonResult("NPE", "NPE", null, List.of("svc"), 0.9,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test");
        when(client.retrieve(any(), eq("NPE"))).thenReturn(DashboardMemoryResult.empty());

        InvestigationState result = node.apply(initialState().withReason(reason));

        assertThat(result.dashboardMemory().isEmpty()).isTrue();
    }

    @Test
    void apply_noReason_passesNullErrorCategory() {
        when(client.retrieve(any(), eq(null))).thenReturn(DashboardMemoryResult.empty());

        InvestigationState result = node.apply(initialState());

        assertThat(result.dashboardMemory().isEmpty()).isTrue();
    }
}
