package com.firm.investigation.graph.node;

import com.firm.investigation.graph.InvestigationState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Component
public class ComponentMergeNode implements Function<InvestigationState, InvestigationState> {

    private static final Logger log = LoggerFactory.getLogger(ComponentMergeNode.class);

    private final ObservationRegistry observationRegistry;

    public ComponentMergeNode(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        return Observation.createNotStarted("investigation.node.component_merge", observationRegistry)
                .lowCardinalityKeyValue("service", state.request().service())
                .observe(() -> {
                    Set<String> merged = new LinkedHashSet<>();
                    // Reasoned services from LLM (informed by Atlas) come first — strongest prior.
                    if (state.reason() != null && state.reason().likelyServices() != null) {
                        merged.addAll(state.reason().likelyServices());
                    }
                    merged.addAll(state.traceComponents());
                    merged.addAll(state.lokiComponents());
                    merged.add(state.request().service());
                    List<String> mergedList = new ArrayList<>(merged);
                    log.debug("ComponentMergeNode merged {} components: {}", mergedList.size(), mergedList);
                    return state.withMergedComponents(mergedList);
                });
    }
}
