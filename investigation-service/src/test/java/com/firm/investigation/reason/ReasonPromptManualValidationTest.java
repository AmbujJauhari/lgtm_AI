package com.firm.investigation.reason;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.memory.AtlasMemoryResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;

import java.util.List;

/**
 * Manual validation harness — verifies the combined ReasonNode prompt produces
 * reliable structured output with the local gemma4 (Ollama) model.
 *
 * <p><strong>NOT run in CI.</strong> Requires a running Ollama at localhost:11434
 * with the configured model pulled. Enable by removing the @Disabled annotation
 * (or invoke directly from an IDE) when validating prompt changes or model swaps.</p>
 *
 * <p>Pass criteria (manual judgment):
 * <ul>
 *   <li>All 5 sample log lines produce parseable {@link ReasonResult} (no JSON parse errors)</li>
 *   <li>errorCategory is reasonable for each sample (NPE for NPE, OOM for OOM, etc.)</li>
 *   <li>likelyServices contains the originating service and is bounded by the catalog</li>
 *   <li>Confidence values are meaningful (high for clear cases, lower for novel)</li>
 *   <li>tempoPlan/lokiPlan have sensible defaults (won't fire for self-contained NPE; will fire for cross-service patterns)</li>
 * </ul>
 */
@Disabled("Manual prompt-validation harness — requires running Ollama at localhost:11434 with gemma4 pulled")
class ReasonPromptManualValidationTest {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String OLLAMA_MODEL = "gemma4";
    private static final String APPCODE = "AT4278";
    private static final List<String> SERVICE_CATALOG = List.of(
            "booking-service", "ledger-service", "position-service", "collateral-service",
            "margin-call-service", "trade-enrichment-service", "risk-aggregator-service",
            "margin-event-consumer", "trade-settlement-service", "pricing-service",
            "reference-data-service", "settlement-service");

    record Sample(String label, InvestigationRequest request) {}

    private static final List<Sample> SAMPLES = List.of(
            new Sample("NPE in collateral", new InvestigationRequest(
                    "2025-01-15 10:30:00 ERROR [collateral-service,abc123,7a3f] c.f.c.CollateralCalculator - java.lang.NullPointerException at CollateralCalculator.java:247",
                    "collateral-service", APPCODE, "2025-01-15T10:30:00Z")),
            new Sample("Timeout margin → collateral", new InvestigationRequest(
                    "2025-01-15 11:00:00 ERROR [margin-call-service,xyz789,4d2e] c.f.m.MarginCallService - java.net.SocketTimeoutException: Read timed out after 30000ms calling collateral-service",
                    "margin-call-service", APPCODE, "2025-01-15T11:00:00Z")),
            new Sample("InstrumentNotFound business exception", new InvestigationRequest(
                    "2025-01-15 09:15:00 ERROR [booking-service,bbb111,2c3d] c.f.b.BookingService - com.firm.exception.InstrumentNotFoundException: ISIN GB00B4QFG87 not in instrument master",
                    "booking-service", APPCODE, "2025-01-15T09:15:00Z")),
            new Sample("DB Sybase error", new InvestigationRequest(
                    "2025-01-15 09:20:00 ERROR [trade-enrichment-service,def456,1a2b] c.f.t.TradeRepository - com.sybase.jdbc4.jdbc.SybSQLException: JZ006 connection reset",
                    "trade-enrichment-service", APPCODE, "2025-01-15T09:20:00Z")),
            new Sample("OOM no traceId", new InvestigationRequest(
                    "2025-01-15 07:30:00 ERROR [risk-aggregator-service] c.f.r.PositionAggregator - java.lang.OutOfMemoryError: Java heap space",
                    "risk-aggregator-service", APPCODE, "2025-01-15T07:30:00Z"))
    );

    @Test
    void validateGemmaPromptOnFiveSamples() {
        ChatClient chatClient = buildOllamaChatClient();
        ReasonPromptBuilder builder = new ReasonPromptBuilder();

        for (Sample sample : SAMPLES) {
            System.out.println("─".repeat(60));
            System.out.println("Sample: " + sample.label);
            System.out.println("─".repeat(60));
            try {
                ReasonResult result = chatClient.prompt()
                        .system(builder.systemPrompt())
                        .user(builder.userPrompt(sample.request, AtlasMemoryResult.empty(), SERVICE_CATALOG))
                        .call()
                        .entity(ReasonResult.class);

                System.out.println("  errorCategory:   " + result.errorCategory());
                System.out.println("  errorPattern:    " + result.errorPattern());
                System.out.println("  traceId:         " + result.traceId());
                System.out.println("  likelyServices:  " + result.likelyServices());
                System.out.println("  confidence:      " + result.confidence());
                System.out.println("  tempoPlan.query: " + (result.tempoPlan() == null ? "null" : result.tempoPlan().query()));
                System.out.println("  lokiPlan.query:  " + (result.lokiPlan() == null ? "null" : result.lokiPlan().query()));
                System.out.println("  reasoning:       " + result.reasoning());
            } catch (Exception e) {
                System.out.println("  FAILED to parse: " + e.getMessage());
            }
        }
    }

    private ChatClient buildOllamaChatClient() {
        OllamaApi api = OllamaApi.builder().baseUrl(OLLAMA_BASE_URL).build();
        OllamaOptions options = OllamaOptions.builder()
                .model(OLLAMA_MODEL)
                .temperature(0.0d)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
        return ChatClient.builder(model).build();
    }
}
