package com.firm.investigation.panel;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.llm.PanelSelectionPromptBuilder;
import com.firm.investigation.reason.ReasonResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PanelSelectionServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private PanelSelectionPromptBuilder promptBuilder;

    private PanelSelectionService service;

    private static final String AVAILABLE_PANELS = "log_stream: Log stream\nheap_by_component: Heap usage";

    @BeforeEach
    void setUp() {
        when(promptBuilder.systemPrompt()).thenReturn("system prompt");
        when(promptBuilder.userPrompt(any(), anyString())).thenReturn("user prompt");
        service = new PanelSelectionService(chatClient, promptBuilder);
    }

    private void mockChatChain(PanelSelectionResult returnValue) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(PanelSelectionResult.class)).thenReturn(returnValue);
    }

    private InvestigationState buildState(String errorCategory, boolean crossServicePotential, String traceId) {
        InvestigationRequest request = new InvestigationRequest(
                "error log", "collateral-service", "COLL", "2024-01-15T10:30:00Z");
        ReasonResult reason = new ReasonResult(
                errorCategory, "pattern", traceId,
                List.of("collateral-service"), 0.85,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test");
        return InvestigationState.initial(request)
                .withReason(reason)
                .withMergedComponents(List.of("collateral-service"))
                .withAvailablePanels(AVAILABLE_PANELS);
    }

    @Test
    void selectPanels_npe_includesLogStreamAndHeap() {
        PanelSelectionResult expected = new PanelSelectionResult(List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "collateral-service", "appcode", "COLL",
                                "timeFrom", "2024-01-15T10:25:00Z", "timeTo", "2024-01-15T10:35:00Z")),
                new PanelSelectionResult.SelectedPanel("heap_by_component",
                        Map.of("components", "collateral-service", "appcode", "COLL",
                                "timeFrom", "2024-01-15T10:25:00Z", "timeTo", "2024-01-15T10:35:00Z"))
        ), "NPE Investigation — collateral-service — 2024-01-15 10:30");
        mockChatChain(expected);

        PanelSelectionResult result = service.selectPanels(buildState("NPE", false, null));

        List<String> panelIds = result.selectedPanels().stream()
                .map(PanelSelectionResult.SelectedPanel::panelId).toList();
        assertThat(panelIds).contains("log_stream");
        assertThat(panelIds).doesNotContain("trace_waterfall");
        assertThat(result.dashboardTitle()).contains("NPE");
    }

    @Test
    void selectPanels_timeout_includesTraceWaterfall() {
        PanelSelectionResult expected = new PanelSelectionResult(List.of(
                new PanelSelectionResult.SelectedPanel("log_stream",
                        Map.of("service", "collateral-service", "appcode", "COLL",
                                "timeFrom", "t1", "timeTo", "t2")),
                new PanelSelectionResult.SelectedPanel("trace_waterfall",
                        Map.of("traceId", "abc123"))
        ), "TIMEOUT Investigation — collateral-service");
        mockChatChain(expected);

        PanelSelectionResult result = service.selectPanels(buildState("TIMEOUT", true, "abc123"));

        List<String> panelIds = result.selectedPanels().stream()
                .map(PanelSelectionResult.SelectedPanel::panelId).toList();
        assertThat(panelIds).contains("log_stream");
        assertThat(panelIds).contains("trace_waterfall");
    }

    @Test
    void selectPanels_returnsCorrectDashboardTitle() {
        PanelSelectionResult expected = new PanelSelectionResult(
                List.of(new PanelSelectionResult.SelectedPanel("log_stream", Map.of())),
                "MQ_ERROR Investigation — margin-event-consumer — 2024-01-15 08:45");
        mockChatChain(expected);

        PanelSelectionResult result = service.selectPanels(buildState("MQ_ERROR", true, null));

        assertThat(result.dashboardTitle()).isNotBlank();
    }
}
