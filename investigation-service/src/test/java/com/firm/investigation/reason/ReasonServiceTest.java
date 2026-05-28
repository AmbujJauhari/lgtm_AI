package com.firm.investigation.reason;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.catalog.ServiceCatalogProvider;
import com.firm.investigation.memory.AtlasMemoryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReasonServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ReasonPromptBuilder promptBuilder;
    @Mock private ServiceCatalogProvider serviceCatalog;

    private ReasonService service;
    private InvestigationRequest request;

    @BeforeEach
    void setUp() {
        service = new ReasonService(chatClient, promptBuilder, serviceCatalog);
        request = new InvestigationRequest(
                "ERROR java.lang.NullPointerException", "collateral-service", "AT4278",
                "2025-01-15T10:30:00Z");
        when(promptBuilder.systemPrompt()).thenReturn("system");
        when(promptBuilder.userPrompt(any(), any(), any())).thenReturn("user");
    }

    private void mockChatChain(ReasonResult returnValue) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        when(resp.entity(ReasonResult.class)).thenReturn(returnValue);
    }

    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }

    @Test
    void reason_validatedServicesFiltered() {
        ReasonResult raw = new ReasonResult(
                "NPE", "NullPointerException", null,
                List.of("collateral-service", "hallucinated-service"), 0.9,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test");
        mockChatChain(raw);
        when(serviceCatalog.servicesFor("AT4278")).thenReturn(List.of("collateral-service", "ledger-service"));
        when(serviceCatalog.validate(eq("AT4278"), eq(List.of("collateral-service", "hallucinated-service"))))
                .thenReturn(List.of("collateral-service"));

        ReasonResult result = service.reason(request, AtlasMemoryResult.empty());

        assertThat(result.likelyServices()).containsExactly("collateral-service");
        assertThat(result.errorCategory()).isEqualTo("NPE");
    }

    @Test
    void reason_lokiPlanServicesAlsoValidated() {
        ReasonResult.LokiPlan rawLoki = new ReasonResult.LokiPlan(true, "NPE",
                List.of("collateral-service", "fake-service"), 15);
        ReasonResult raw = new ReasonResult(
                "NPE", "NPE", null, List.of("collateral-service"), 0.8,
                ReasonResult.TempoPlan.skip(), rawLoki, "test");
        mockChatChain(raw);
        when(serviceCatalog.servicesFor("AT4278")).thenReturn(List.of("collateral-service"));
        when(serviceCatalog.validate(eq("AT4278"), eq(List.of("collateral-service"))))
                .thenReturn(List.of("collateral-service"));
        when(serviceCatalog.validate(eq("AT4278"), eq(List.of("collateral-service", "fake-service"))))
                .thenReturn(List.of("collateral-service"));

        ReasonResult result = service.reason(request, AtlasMemoryResult.empty());

        assertThat(result.lokiPlan().services()).containsExactly("collateral-service");
    }

    @Test
    void reason_nullTempoAndLokiPlans_defaultsToSkip() {
        ReasonResult raw = new ReasonResult(
                "NPE", "NPE", null, List.of("svc"), 0.9, null, null, "test");
        mockChatChain(raw);
        when(serviceCatalog.servicesFor("AT4278")).thenReturn(List.of("svc"));
        when(serviceCatalog.validate(eq("AT4278"), any())).thenReturn(List.of("svc"));

        ReasonResult result = service.reason(request, AtlasMemoryResult.empty());

        assertThat(result.tempoPlan().query()).isFalse();
        assertThat(result.lokiPlan().query()).isFalse();
    }
}
