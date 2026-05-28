package com.firm.investigation.llm;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.panel.CrossServiceLogEntry;
import com.firm.investigation.reason.ReasonResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PanelSelectionPromptBuilderTest {

    private PanelSelectionPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PanelSelectionPromptBuilder();
    }

    private InvestigationState buildState(String errorCategory, String traceId, List<String> components) {
        InvestigationRequest request = new InvestigationRequest(
                "error log", "collateral-service", "COLL", "2024-01-15T10:30:00Z");
        ReasonResult reason = new ReasonResult(
                errorCategory, "pattern", traceId, components, 0.85,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test reasoning");
        return InvestigationState.initial(request)
                .withReason(reason)
                .withMergedComponents(components);
    }

    @Test
    void systemPrompt_isNotBlank() {
        assertThat(builder.systemPrompt()).isNotBlank();
    }

    @Test
    void systemPrompt_instructsJsonOnlyResponse() {
        String prompt = builder.systemPrompt();
        assertThat(prompt.toLowerCase()).containsAnyOf("json", "JSON");
    }

    @Test
    void userPrompt_containsErrorCategoryAndService() {
        InvestigationState state = buildState("TIMEOUT", "abc123", List.of("collateral-service"));
        String prompt = builder.userPrompt(state, "log_stream: Logs");
        assertThat(prompt).contains("TIMEOUT");
        assertThat(prompt).contains("collateral-service");
    }

    @Test
    void userPrompt_indicatesTraceIdAvailability_whenPresent() {
        InvestigationState state = buildState("TIMEOUT", "abc123def", List.of("svc-a"));
        String prompt = builder.userPrompt(state, "available panels");
        assertThat(prompt).containsIgnoringCase("YES");
        assertThat(prompt).contains("abc123def");
    }

    @Test
    void userPrompt_indicatesNoTraceId_whenAbsent() {
        InvestigationState state = buildState("NPE", null, List.of("collateral-service"));
        String prompt = builder.userPrompt(state, "available panels");
        assertThat(prompt).containsIgnoringCase("NO");
    }

    @Test
    void userPrompt_includesCrossServiceLogEvidence_whenPresent() {
        InvestigationRequest request = new InvestigationRequest(
                "error log", "svc-a", "COLL", "2024-01-15T10:30:00Z");
        ReasonResult reason = new ReasonResult(
                "DB_ERROR", "SybSQL", null, List.of("svc-a", "svc-b"), 0.7,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test");
        InvestigationState state = InvestigationState.initial(request)
                .withReason(reason)
                .withMergedComponents(List.of("svc-a", "svc-b"))
                .withLokiResult(
                        List.of("svc-b"),
                        List.of(new CrossServiceLogEntry("svc-b", "ERROR SybSQLException at svc-b")));

        String prompt = builder.userPrompt(state, "available panels");
        assertThat(prompt).contains("svc-b");
        assertThat(prompt).contains("SybSQLException");
    }

    @Test
    void userPrompt_includesAvailablePanels() {
        InvestigationState state = buildState("NPE", null, List.of("collateral-service"));
        String availablePanels = "log_stream: Logs — suits: ALL\nheap_by_component: Heap — suits: NPE, OOM";
        String prompt = builder.userPrompt(state, availablePanels);
        assertThat(prompt).contains("log_stream");
        assertThat(prompt).contains("heap_by_component");
    }

    @Test
    void userPrompt_specifiesJsonResponseFormat() {
        InvestigationState state = buildState("OOM", null, List.of("risk-service"));
        String prompt = builder.userPrompt(state, "panels");
        assertThat(prompt).contains("selectedPanels");
        assertThat(prompt).contains("dashboardTitle");
    }
}
