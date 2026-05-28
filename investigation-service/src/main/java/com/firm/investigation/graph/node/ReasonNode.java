package com.firm.investigation.graph.node;

import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.reason.ReasonResult;
import com.firm.investigation.reason.ReasonService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Combined "triage + service-reasoning" LLM call.
 *
 * Replaces the old TriageNode + (intended) separate ServiceReasoningNode with
 * a single LLM call that takes the log line + Atlas memories and emits:
 *   - parsed fields (errorCategory, errorPattern, traceId)
 *   - reasoned services list (validated against service catalog)
 *   - structured query plans for Tempo and Loki
 *
 * AI failure mode: throws — propagates to controller as 503 (no fallback dashboard).
 * Atlas failure is upstream and surfaced as empty AtlasMemoryResult.
 */
@Component
public class ReasonNode implements Function<InvestigationState, InvestigationState> {

    private static final Logger log = LoggerFactory.getLogger(ReasonNode.class);

    private final ReasonService reasonService;
    private final ObservationRegistry observationRegistry;

    public ReasonNode(ReasonService reasonService, ObservationRegistry observationRegistry) {
        this.reasonService = reasonService;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        return Observation.createNotStarted("investigation.node.reason", observationRegistry)
                .lowCardinalityKeyValue("appcode", state.request().appcode())
                .lowCardinalityKeyValue("service", state.request().service())
                .observe(() -> {
                    ReasonResult result = reasonService.reason(state.request(), state.atlasMemory());
                    log.info("Reason: category={} confidence={} services={}",
                            result.errorCategory(), result.confidence(), result.likelyServices());
                    return state.withReason(result);
                });
    }
}
