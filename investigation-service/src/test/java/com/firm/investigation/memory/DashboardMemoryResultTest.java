package com.firm.investigation.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardMemoryResultTest {

    @Test
    void empty_isEmpty() {
        DashboardMemoryResult r = DashboardMemoryResult.empty();
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.entries()).isEmpty();
    }

    @Test
    void nonEmpty_isNotEmpty() {
        DashboardMemoryResult.PastFeedback fb = new DashboardMemoryResult.PastFeedback(
                "uid", "log", "NPE", "svc", List.of("log_stream"), "notes", 0.9);
        DashboardMemoryResult r = new DashboardMemoryResult(List.of(fb));
        assertThat(r.isEmpty()).isFalse();
        assertThat(r.entries()).hasSize(1);
    }
}
