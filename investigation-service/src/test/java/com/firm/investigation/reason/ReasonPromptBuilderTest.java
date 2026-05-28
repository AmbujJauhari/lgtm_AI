package com.firm.investigation.reason;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.memory.AtlasMemoryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReasonPromptBuilderTest {

    private ReasonPromptBuilder builder;
    private InvestigationRequest request;

    @BeforeEach
    void setUp() {
        builder = new ReasonPromptBuilder();
        request = new InvestigationRequest(
                "ERROR java.lang.NullPointerException at Calculator.java:42",
                "collateral-service", "AT4278", "2025-01-15T10:30:00Z");
    }

    @Test
    void systemPrompt_isJsonOnlyInstructive() {
        String prompt = builder.systemPrompt();
        assertThat(prompt.toLowerCase()).contains("json");
        assertThat(prompt.toLowerCase()).contains("no markdown");
    }

    @Test
    void userPrompt_includesAppcodeServiceCatalogAndLogLine() {
        String prompt = builder.userPrompt(request, AtlasMemoryResult.empty(),
                List.of("collateral-service", "ledger-service"));
        assertThat(prompt).contains("AT4278");
        assertThat(prompt).contains("collateral-service");
        assertThat(prompt).contains("ledger-service");
        assertThat(prompt).contains("NullPointerException");
    }

    @Test
    void userPrompt_noteWhenAtlasEmpty() {
        String prompt = builder.userPrompt(request, AtlasMemoryResult.empty(), List.of("svc"));
        assertThat(prompt).contains("No similar past incidents");
    }

    @Test
    void userPrompt_includesPastIncidents_whenAtlasNonEmpty() {
        AtlasMemoryResult atlas = new AtlasMemoryResult(
                List.of(new AtlasMemoryResult.RelatedIncident(
                        "INC0001", "InstrumentNotFoundException", "Re-ran ref-data refresh",
                        "P2", List.of("booking-service", "ledger-service"),
                        "data-refresh-failure", 0.92)),
                List.of("booking-service", "ledger-service"),
                List.of("data-refresh-failure"),
                List.of("reference-data-refresh"));
        String prompt = builder.userPrompt(request, atlas, List.of("collateral-service"));
        assertThat(prompt).contains("INC0001");
        assertThat(prompt).contains("InstrumentNotFoundException");
        assertThat(prompt).contains("booking-service");
        assertThat(prompt).contains("data-refresh-failure");
    }

    @Test
    void userPrompt_specifiesJsonSchema() {
        String prompt = builder.userPrompt(request, AtlasMemoryResult.empty(), List.of("svc"));
        assertThat(prompt).contains("errorCategory");
        assertThat(prompt).contains("likelyServices");
        assertThat(prompt).contains("confidence");
        assertThat(prompt).contains("tempoPlan");
        assertThat(prompt).contains("lokiPlan");
    }
}
