package com.firm.investigation.graph.node;

import com.firm.investigation.component.TempoClient;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.reason.ReasonResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * Tempo lookup driven by {@link ReasonResult.TempoPlan}.
 *
 * Two modes (mutually exclusive within a single invocation):
 *   - {@code traceId} set    → get-by-id (precise)
 *   - {@code searchByService} set → fuzzy search for recent error traces (used when
 *     traceId is missing or unreliable, e.g. RMI propagation gaps)
 *
 * If plan says skip, falls back to {@code [originatingService]} as a sensible default.
 * Data failure → degrades to the same fallback rather than throwing.
 */
@Component
public class TempoResolutionNode implements Function<InvestigationState, InvestigationState> {

    private static final Logger log = LoggerFactory.getLogger(TempoResolutionNode.class);

    private final TempoClient tempoClient;
    private final ObservationRegistry observationRegistry;

    public TempoResolutionNode(TempoClient tempoClient, ObservationRegistry observationRegistry) {
        this.tempoClient = tempoClient;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        String originating = state.request().service();
        ReasonResult reason = state.reason();
        if (reason == null || reason.tempoPlan() == null || !reason.tempoPlan().query()) {
            log.info("Tempo skipped (plan.query=false), defaulting to originating service");
            return state.withTraceComponents(List.of(originating));
        }
        ReasonResult.TempoPlan plan = reason.tempoPlan();

        return Observation.createNotStarted("investigation.node.tempo_resolution", observationRegistry)
                .lowCardinalityKeyValue("service", originating)
                .lowCardinalityKeyValue("mode", plan.traceId() != null ? "id" : "search")
                .observe(() -> {
                    try {
                        List<String> services;
                        if (plan.traceId() != null && !plan.traceId().isBlank()) {
                            services = tempoClient.fetchServiceNames(plan.traceId());
                        } else if (plan.searchByService() != null && !plan.searchByService().isBlank()) {
                            int window = plan.searchByTimeMinutes() != null ? plan.searchByTimeMinutes() : 15;
                            services = tempoClient.searchServiceNames(
                                    plan.searchByService(), state.request().timestamp(), window);
                        } else {
                            services = List.of();
                        }
                        if (services.isEmpty()) services = List.of(originating);
                        return state.withTraceComponents(services);
                    } catch (Exception e) {
                        log.warn("TempoResolutionNode failed (non-fatal): {}; falling back to originating service",
                                e.getMessage());
                        return state.withTraceComponents(List.of(originating));
                    }
                });
    }
}
