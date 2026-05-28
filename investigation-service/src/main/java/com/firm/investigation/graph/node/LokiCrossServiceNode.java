package com.firm.investigation.graph.node;

import com.firm.investigation.component.LokiCrossServiceClient;
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
 * Live cross-service log search driven by {@link ReasonResult.LokiPlan}.
 *
 * Conservative confidence gate (per design discussion): fires when
 *   - reason.lokiPlan.query == true, OR
 *   - reason.confidence < 0.6   (low confidence → belt-and-braces fetch)
 *
 * Data failure → returns empty result, graph continues with whatever was gathered.
 */
@Component
public class LokiCrossServiceNode implements Function<InvestigationState, InvestigationState> {

    private static final Logger log = LoggerFactory.getLogger(LokiCrossServiceNode.class);
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    private final LokiCrossServiceClient lokiClient;
    private final ObservationRegistry observationRegistry;

    public LokiCrossServiceNode(LokiCrossServiceClient lokiClient, ObservationRegistry observationRegistry) {
        this.lokiClient = lokiClient;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        ReasonResult reason = state.reason();
        if (reason == null) {
            return state.withLokiResult(List.of(), List.of());
        }
        ReasonResult.LokiPlan plan = reason.lokiPlan();
        boolean shouldFire = plan != null && (plan.query() || reason.confidence() < CONFIDENCE_THRESHOLD);
        if (!shouldFire || plan.pattern() == null || plan.pattern().isBlank()) {
            log.info("Loki cross-service skipped (plan.query={}, confidence={}, hasPattern={})",
                    plan == null ? null : plan.query(),
                    reason.confidence(),
                    plan != null && plan.pattern() != null);
            return state.withLokiResult(List.of(), List.of());
        }

        return Observation.createNotStarted("investigation.node.loki_cross_service", observationRegistry)
                .lowCardinalityKeyValue("appcode", state.request().appcode())
                .observe(() -> {
                    try {
                        LokiCrossServiceClient.CrossServiceResult result =
                                lokiClient.execute(state.request().appcode(), plan, state.request().timestamp());
                        log.info("Loki cross-service: {} services, {} representative logs",
                                result.services().size(), result.representativeLogs().size());
                        return state.withLokiResult(result.services(), result.representativeLogs());
                    } catch (Exception e) {
                        log.warn("LokiCrossServiceNode failed (non-fatal): {}", e.getMessage());
                        return state.withLokiResult(List.of(), List.of());
                    }
                });
    }
}
