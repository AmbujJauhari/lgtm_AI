package com.firm.investigation.memory;

import java.util.List;

/**
 * Retrieval output for past L2 dashboard feedback similar to the current investigation.
 * Empty when the dashboard_memory store has no relevant past records — common at
 * cold start, gracefully handled by panel selection prompt.
 */
public record DashboardMemoryResult(
    List<PastFeedback> entries
) {
    public static DashboardMemoryResult empty() {
        return new DashboardMemoryResult(List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public record PastFeedback(
        String dashboardUid,
        String logLine,
        String errorCategory,
        String service,
        List<String> finalPanelDescriptors,
        String feedbackText,
        double similarity
    ) {}
}
