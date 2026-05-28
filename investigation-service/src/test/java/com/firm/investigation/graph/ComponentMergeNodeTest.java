package com.firm.investigation.graph;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.node.ComponentMergeNode;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentMergeNodeTest {

    private ComponentMergeNode mergeNode;

    @BeforeEach
    void setUp() {
        mergeNode = new ComponentMergeNode(ObservationRegistry.NOOP);
    }

    private InvestigationState stateWith(List<String> tempo, List<String> loki) {
        InvestigationRequest request = new InvestigationRequest(
                "error log", "originating-service", "ORIG", "2024-01-15T10:30:00Z");
        return InvestigationState.initial(request)
                .withTraceComponents(tempo)
                .withLokiResult(loki, List.of());
    }

    @Test
    void merge_combinesTempoAndLokiWithoutDuplicates() {
        InvestigationState state = stateWith(
                List.of("service-a", "service-b"),
                List.of("service-b", "service-c"));
        InvestigationState result = mergeNode.apply(state);
        assertThat(result.mergedComponents())
                .containsExactlyInAnyOrder("service-a", "service-b", "service-c", "originating-service");
        assertThat(result.mergedComponents()).doesNotHaveDuplicates();
    }

    @Test
    void merge_alwaysIncludesOriginatingService() {
        InvestigationState state = stateWith(List.of("service-a"), List.of("service-b"));
        InvestigationState result = mergeNode.apply(state);
        assertThat(result.mergedComponents()).contains("originating-service");
    }

    @Test
    void merge_tempoComponentsHaveInsertionPriority() {
        InvestigationState state = stateWith(
                List.of("tempo-only-service"),
                List.of("loki-only-service"));
        InvestigationState result = mergeNode.apply(state);
        int tempoIndex = result.mergedComponents().indexOf("tempo-only-service");
        int lokiIndex = result.mergedComponents().indexOf("loki-only-service");
        assertThat(tempoIndex).isLessThan(lokiIndex);
    }

    @Test
    void merge_emptyLoki_usesTempoAndOriginating() {
        InvestigationState state = stateWith(List.of("service-a", "service-b"), List.of());
        InvestigationState result = mergeNode.apply(state);
        assertThat(result.mergedComponents()).containsExactlyInAnyOrder("service-a", "service-b", "originating-service");
    }

    @Test
    void merge_emptyTempo_usesLokiAndOriginating() {
        InvestigationState state = stateWith(List.of(), List.of("loki-service"));
        InvestigationState result = mergeNode.apply(state);
        assertThat(result.mergedComponents()).containsExactlyInAnyOrder("loki-service", "originating-service");
    }

    @Test
    void merge_originatingServiceAlreadyInTempo_noDuplicate() {
        InvestigationState state = stateWith(
                List.of("originating-service", "other-service"), List.of());
        InvestigationState result = mergeNode.apply(state);
        long count = result.mergedComponents().stream()
                .filter("originating-service"::equals).count();
        assertThat(count).isEqualTo(1);
    }
}
