package com.firm.investigation.graph.node;

import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.panel.PanelSelectionResult;
import com.firm.investigation.panel.PanelSelectionService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class PanelSelectionNode implements Function<InvestigationState, InvestigationState> {

    private final PanelSelectionService panelSelectionService;
    private final ObservationRegistry observationRegistry;

    public PanelSelectionNode(PanelSelectionService panelSelectionService, ObservationRegistry observationRegistry) {
        this.panelSelectionService = panelSelectionService;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        return Observation.createNotStarted("investigation.node.panel_selection", observationRegistry)
                .lowCardinalityKeyValue("service", state.request().service())
                .lowCardinalityKeyValue("error_category",
                        state.reason() != null ? state.reason().errorCategory() : "UNKNOWN")
                .observe(() -> {
                    // AI-call failures propagate (fail fast — no fallback dashboard).
                    PanelSelectionResult result = panelSelectionService.selectPanels(state);
                    return state.withPanels(result);
                });
    }
}
