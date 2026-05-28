package com.firm.investigation.graph.node;

import com.firm.investigation.graph.InvestigationState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Validates that PanelSelection produced something usable before publish.
 * Empty / null panel selection → throws (fail fast, no fallback dashboard).
 */
@Component
public class DashboardAssemblyNode implements Function<InvestigationState, InvestigationState> {

    private final ObservationRegistry observationRegistry;

    public DashboardAssemblyNode(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        return Observation.createNotStarted("investigation.node.dashboard_assembly", observationRegistry)
                .lowCardinalityKeyValue("service", state.request().service())
                .observe(() -> {
                    if (state.panels() == null || state.panels().selectedPanels().isEmpty()) {
                        throw new IllegalStateException("Panel selection produced no panels — cannot assemble dashboard");
                    }
                    return state;
                });
    }
}
