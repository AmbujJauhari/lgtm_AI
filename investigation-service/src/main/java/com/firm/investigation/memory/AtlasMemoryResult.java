package com.firm.investigation.memory;

import java.util.List;

/**
 * Retrieval output from the Atlas incident graph.
 *
 * @param relatedIncidents top-K past incidents with the strongest similarity
 * @param historicalServices union of services affected across the related incidents
 * @param historicalRootCauses distinct root-cause categories observed
 * @param mentionedComponents distinct system components referenced across related incidents
 */
public record AtlasMemoryResult(
    List<RelatedIncident> relatedIncidents,
    List<String> historicalServices,
    List<String> historicalRootCauses,
    List<String> mentionedComponents
) {
    public static AtlasMemoryResult empty() {
        return new AtlasMemoryResult(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return relatedIncidents.isEmpty();
    }

    public record RelatedIncident(
        String ticketId,
        String title,
        String closureNotes,
        String priority,
        List<String> affectedServices,
        String rootCauseCategory,
        double similarity
    ) {}
}
