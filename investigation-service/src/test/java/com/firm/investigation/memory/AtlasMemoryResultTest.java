package com.firm.investigation.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AtlasMemoryResultTest {

    @Test
    void empty_isEmpty() {
        AtlasMemoryResult e = AtlasMemoryResult.empty();
        assertThat(e.isEmpty()).isTrue();
        assertThat(e.relatedIncidents()).isEmpty();
        assertThat(e.historicalServices()).isEmpty();
        assertThat(e.historicalRootCauses()).isEmpty();
        assertThat(e.mentionedComponents()).isEmpty();
    }

    @Test
    void nonEmpty_isNotEmpty() {
        AtlasMemoryResult.RelatedIncident inc = new AtlasMemoryResult.RelatedIncident(
                "INC1", "title", "notes", "P2", List.of("svc"), "cause", 0.9);
        AtlasMemoryResult r = new AtlasMemoryResult(List.of(inc), List.of("svc"), List.of("cause"), List.of());
        assertThat(r.isEmpty()).isFalse();
        assertThat(r.relatedIncidents()).hasSize(1);
    }
}
