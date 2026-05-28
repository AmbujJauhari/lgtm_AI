package com.firm.investigation.graph.node;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.assembly.DashboardAssemblyService;
import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.grafana.GrafanaPublishService;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.panel.PanelSelectionResult;
import com.firm.investigation.panel.PanelSelectionService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphNodeTest {

    @Mock private DashboardAssemblyService assemblyService;
    @Mock private GrafanaPublishService grafanaPublishService;
    @Mock private InvestigationProperties properties;
    @Mock private PanelSelectionService panelSelectionService;

    private PublishNode publishNode;
    private DashboardAssemblyNode dashboardAssemblyNode;
    private PanelSelectionNode panelSelectionNode;

    @BeforeEach
    void setUp() {
        publishNode = new PublishNode(assemblyService, grafanaPublishService, properties, ObservationRegistry.NOOP);
        dashboardAssemblyNode = new DashboardAssemblyNode(ObservationRegistry.NOOP);
        panelSelectionNode = new PanelSelectionNode(panelSelectionService, ObservationRegistry.NOOP);
    }

    private InvestigationState stateWithPanels() {
        InvestigationRequest request = new InvestigationRequest(
                "error", "svc", "APP", "2024-01-15T10:30:00Z");
        PanelSelectionResult panels = new PanelSelectionResult(
                List.of(new PanelSelectionResult.SelectedPanel("log_stream", Map.of())),
                "Test Dashboard");
        return InvestigationState.initial(request).withPanels(panels);
    }

    // PublishNode — happy path
    @Test
    void publishNode_success_setsDashboardUrl() {
        when(assemblyService.assemble(any())).thenReturn(JsonNodeFactory.instance.objectNode());
        when(grafanaPublishService.publish(any(), any())).thenReturn("/d/uid/title");

        InvestigationState result = publishNode.apply(stateWithPanels());

        assertThat(result.dashboardUrl()).isEqualTo("/d/uid/title");
    }

    // PublishNode — assembly failure propagates (no fallback)
    @Test
    void publishNode_assemblyThrows_propagates() {
        when(assemblyService.assemble(any())).thenThrow(new RuntimeException("assemble failed"));

        assertThatThrownBy(() -> publishNode.apply(stateWithPanels()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("assemble failed");
    }

    // DashboardAssemblyNode — happy path
    @Test
    void dashboardAssemblyNode_withPanels_returnsState() {
        InvestigationState result = dashboardAssemblyNode.apply(stateWithPanels());

        assertThat(result.panels()).isNotNull();
    }

    // DashboardAssemblyNode — no panels means fail fast
    @Test
    void dashboardAssemblyNode_noPanels_throws() {
        InvestigationRequest request = new InvestigationRequest(
                "error", "svc", "APP", "2024-01-15T10:30:00Z");
        InvestigationState noPanels = InvestigationState.initial(request);

        assertThatThrownBy(() -> dashboardAssemblyNode.apply(noPanels))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no panels");
    }

    // PanelSelectionNode — happy path
    @Test
    void panelSelectionNode_success_setsPanels() {
        PanelSelectionResult panels = new PanelSelectionResult(
                List.of(new PanelSelectionResult.SelectedPanel("log_stream", Map.of())),
                "Test Dashboard");
        when(panelSelectionService.selectPanels(any())).thenReturn(panels);

        InvestigationRequest request = new InvestigationRequest(
                "error", "svc", "APP", "2024-01-15T10:30:00Z");
        InvestigationState result = panelSelectionNode.apply(InvestigationState.initial(request));

        assertThat(result.panels()).isNotNull();
        assertThat(result.panels().dashboardTitle()).isEqualTo("Test Dashboard");
    }

    // PanelSelectionNode — service throws propagates (fail fast)
    @Test
    void panelSelectionNode_serviceThrows_propagates() {
        when(panelSelectionService.selectPanels(any())).thenThrow(new RuntimeException("LLM error"));

        InvestigationRequest request = new InvestigationRequest(
                "error", "svc", "APP", "2024-01-15T10:30:00Z");

        assertThatThrownBy(() -> panelSelectionNode.apply(InvestigationState.initial(request)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM error");
    }
}
