package com.firm.investigation.api;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.component.PanelServiceClient;
import com.firm.investigation.config.LangGraphConfig;
import com.firm.investigation.graph.InvestigationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestigationControllerTest {

    @Mock private LangGraphConfig langGraphConfig;
    @Mock private PanelServiceClient panelServiceClient;
    @Mock private LangGraphConfig.InvestigationGraph graph;

    private InvestigationController controller;

    @BeforeEach
    void setUp() {
        controller = new InvestigationController(langGraphConfig, panelServiceClient, "AT4278");
    }

    private void stubGraphAndPanels() {
        when(panelServiceClient.getDescriptions()).thenReturn("log_stream: Log stream");
        when(langGraphConfig.investigationGraph()).thenReturn(graph);
    }

    @Test
    void investigate_returnsRedirectWhenUrlPresent() {
        stubGraphAndPanels();
        InvestigationRequest request = new InvestigationRequest(
                "log", "svc", "APP", "2025-01-15T10:30:00Z");
        InvestigationState withUrl = InvestigationState.initial(request).withDashboardUrl("/d/uid/title");
        when(graph.invoke(any())).thenReturn(withUrl);

        ResponseEntity<?> response = controller.investigate("log", "svc", "APP", "2025-01-15T10:30:00Z", null)
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/d/uid/title");
    }

    @Test
    void investigate_returns503WhenNoUrl() {
        stubGraphAndPanels();
        InvestigationRequest request = new InvestigationRequest(
                "log", "svc", "APP", "2025-01-15T10:30:00Z");
        when(graph.invoke(any())).thenReturn(InvestigationState.initial(request));

        ResponseEntity<?> response = controller.investigate("log", "svc", "APP", "2025-01-15T10:30:00Z", null)
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void investigate_returns503OnException() {
        stubGraphAndPanels();
        when(graph.invoke(any())).thenThrow(new RuntimeException("ReasonNode LLM failure"));

        ResponseEntity<?> response = controller.investigate("log", "svc", "APP", "2025-01-15T10:30:00Z", null)
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().toString()).contains("AI investigation failed");
    }

    @Test
    void extractService_recognisesMdcPattern() {
        String log = "2024-01-15 10:30:00.123 ERROR [collateral-service,abc123def,7a3f1b] c.f.CollateralCalculator - NPE";
        assertThat(InvestigationController.extractService(log)).isEqualTo("collateral-service");
    }

    @Test
    void extractService_noMdcPattern_returnsUnknown() {
        String log = "2024-01-15 plain log line with no MDC";
        assertThat(InvestigationController.extractService(log)).isEqualTo("unknown");
    }

    @Test
    void extractService_nullInput_returnsUnknown() {
        assertThat(InvestigationController.extractService(null)).isEqualTo("unknown");
    }

    @Test
    void extractTimestamp_parsesIsoLikePrefix() {
        String log = "2024-01-15 10:30:00.123 ERROR [svc,abc,def] message";
        String ts = InvestigationController.extractTimestamp(log);
        assertThat(ts).isEqualTo("2024-01-15T10:30:00.123Z");
    }

    @Test
    void extractTimestamp_noTimestamp_fallsBackToNow() {
        String log = "no timestamp here";
        String ts = InvestigationController.extractTimestamp(log);
        // Should be parseable as Instant (i.e., be Instant.now().toString())
        assertThat(ts).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    @Test
    void investigate_missingParams_derivesFromLogLine() {
        stubGraphAndPanels();
        com.firm.investigation.api.dto.InvestigationRequest dummy = new com.firm.investigation.api.dto.InvestigationRequest(
                "log", "svc", "APP", "2025-01-15T10:30:00Z");
        InvestigationState withUrl = InvestigationState.initial(dummy).withDashboardUrl("/d/uid/title");
        when(graph.invoke(any())).thenReturn(withUrl);

        String log = "2024-01-15 10:30:00.123 ERROR [margin-call-service,xyz,789] SocketTimeoutException";
        ResponseEntity<?> response = controller.investigate(log, null, null, null, null).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
}
