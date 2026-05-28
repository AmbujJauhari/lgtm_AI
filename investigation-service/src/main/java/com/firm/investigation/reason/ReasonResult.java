package com.firm.investigation.reason;

import java.util.List;

/**
 * Output of LLM Call 1 (ReasonNode). Combines what TriageNode used to extract
 * (errorCategory, errorPattern, traceId) with reasoned outputs about likely
 * affected services and structured query plans for downstream data fetchers.
 *
 * @param errorCategory   one of: NPE, OOM, TIMEOUT, DB_ERROR, MQ_ERROR,
 *                        CONNECTION_REFUSED, THREAD_DEADLOCK, BUSINESS_EXCEPTION, UNKNOWN
 * @param errorPattern    distinctive substring for cross-service grep; null if too generic
 * @param traceId         extracted from log line MDC/JSON, or null (may be broken due to RMI)
 * @param likelyServices  reasoned list of services likely affected (validated against catalog)
 * @param confidence      LLM's confidence in {@link #likelyServices}, 0.0–1.0
 * @param tempoPlan       structured query plan for Tempo
 * @param lokiPlan        structured query plan for Loki
 * @param reasoning       one-sentence rationale for downstream context
 */
public record ReasonResult(
    String errorCategory,
    String errorPattern,
    String traceId,
    List<String> likelyServices,
    double confidence,
    TempoPlan tempoPlan,
    LokiPlan lokiPlan,
    String reasoning
) {
    /**
     * Tempo query plan. Two modes:
     *  - traceId set → get-by-id lookup (precise)
     *  - searchByService + searchByTimeMinutes set → search recent error traces (fuzzy)
     */
    public record TempoPlan(
        boolean query,
        String traceId,
        String searchByService,
        Integer searchByTimeMinutes
    ) {
        public static TempoPlan skip() {
            return new TempoPlan(false, null, null, null);
        }
    }

    /**
     * Loki cross-service query plan.
     * Service builds the actual LogQL from these structured fields safely.
     */
    public record LokiPlan(
        boolean query,
        String pattern,
        List<String> services,
        int timeWindowMinutes
    ) {
        public static LokiPlan skip() {
            return new LokiPlan(false, null, List.of(), 0);
        }
    }
}
