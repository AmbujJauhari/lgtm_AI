package com.firm.investigation.graph.node;

import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.memory.AtlasMemoryClient;
import com.firm.investigation.memory.AtlasMemoryResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Queries the Atlas knowledge graph for incidents similar to the current log line.
 * Always fires — every error potentially has historical context.
 *
 * Failure mode: returns AtlasMemoryResult.empty() on any Neo4j error,
 * never throws. Atlas is a "hint" source — the pipeline continues with
 * whatever live data is available.
 */
@Component
public class AtlasMemoryNode implements Function<InvestigationState, InvestigationState> {

    private static final Logger log = LoggerFactory.getLogger(AtlasMemoryNode.class);

    private final AtlasMemoryClient atlasMemoryClient;
    private final ObservationRegistry observationRegistry;

    public AtlasMemoryNode(AtlasMemoryClient atlasMemoryClient, ObservationRegistry observationRegistry) {
        this.atlasMemoryClient = atlasMemoryClient;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        return Observation.createNotStarted("investigation.node.atlas_memory", observationRegistry)
                .lowCardinalityKeyValue("appcode", state.request().appcode())
                .observe(() -> {
                    AtlasMemoryResult result = atlasMemoryClient.retrieve(
                            state.request().logLine(),
                            state.request().appcode()
                    );
                    log.info("Atlas memory: {} related incidents, {} historical services",
                            result.relatedIncidents().size(),
                            result.historicalServices().size());
                    return state.withAtlasMemory(result);
                });
    }
}
