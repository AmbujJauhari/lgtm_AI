package com.firm.investigation.graph.node;

import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.memory.DashboardMemoryClient;
import com.firm.investigation.memory.DashboardMemoryResult;
import com.firm.investigation.reason.ReasonResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Retrieves past L2 feedback for similar errors from the dashboard_memory store.
 *
 * Runs in parallel with Tempo / Loki after ReasonNode — its result feeds
 * PanelSelectionNode (LLM Call 2), not ReasonNode (LLM Call 1).
 *
 * Failure is non-fatal: returns DashboardMemoryResult.empty() so panel selection
 * proceeds without past feedback context (cold-start state is identical).
 */
@Component
public class DashboardMemoryNode implements Function<InvestigationState, InvestigationState> {

    private static final Logger log = LoggerFactory.getLogger(DashboardMemoryNode.class);

    private final DashboardMemoryClient client;
    private final ObservationRegistry observationRegistry;

    public DashboardMemoryNode(DashboardMemoryClient client, ObservationRegistry observationRegistry) {
        this.client = client;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        return Observation.createNotStarted("investigation.node.dashboard_memory", observationRegistry)
                .lowCardinalityKeyValue("appcode", state.request().appcode())
                .observe(() -> {
                    ReasonResult reason = state.reason();
                    String errorCategory = reason == null ? null : reason.errorCategory();
                    DashboardMemoryResult result = client.retrieve(
                            state.request().logLine(), errorCategory);
                    log.info("DashboardMemory: {} past feedback entries",
                            result.entries().size());
                    return state.withDashboardMemory(result);
                });
    }
}
