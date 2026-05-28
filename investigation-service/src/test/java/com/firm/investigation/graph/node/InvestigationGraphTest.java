package com.firm.investigation.graph.node;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.config.LangGraphConfig;
import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.panel.PanelSelectionResult;
import com.firm.investigation.reason.ReasonResult;
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
class InvestigationGraphTest {

    @Mock private AtlasMemoryNode atlasMemoryNode;
    @Mock private ReasonNode reasonNode;
    @Mock private TempoResolutionNode tempoResolutionNode;
    @Mock private LokiCrossServiceNode lokiCrossServiceNode;
    @Mock private DashboardMemoryNode dashboardMemoryNode;
    @Mock private ComponentMergeNode componentMergeNode;
    @Mock private PanelSelectionNode panelSelectionNode;
    @Mock private DashboardAssemblyNode dashboardAssemblyNode;
    @Mock private PublishNode publishNode;

    private LangGraphConfig.InvestigationGraph graph;

    @BeforeEach
    void setUp() {
        graph = new LangGraphConfig.InvestigationGraph(
                atlasMemoryNode, reasonNode, tempoResolutionNode, lokiCrossServiceNode,
                dashboardMemoryNode, componentMergeNode, panelSelectionNode,
                dashboardAssemblyNode, publishNode);
    }

    private InvestigationState initialState() {
        return InvestigationState.initial(new InvestigationRequest(
                "error", "svc", "APP", "2024-01-15T10:30:00Z"));
    }

    private InvestigationState reasoned() {
        ReasonResult reason = new ReasonResult("NPE", "NPE", null, List.of("svc"), 0.9,
                ReasonResult.TempoPlan.skip(), ReasonResult.LokiPlan.skip(), "test");
        return initialState().withReason(reason);
    }

    private InvestigationState withPanels(InvestigationState state) {
        return state.withMergedComponents(List.of("svc"))
                .withPanels(new PanelSelectionResult(
                        List.of(new PanelSelectionResult.SelectedPanel("log_stream", Map.of())),
                        "NPE Dashboard"));
    }

    @Test
    void invoke_happyPath_returnsDashboardUrl() {
        InvestigationState afterAtlas = initialState();
        InvestigationState afterReason = reasoned();
        InvestigationState afterTempo = afterReason.withTraceComponents(List.of("svc"));
        InvestigationState afterLoki = afterReason.withLokiResult(List.of(), List.of());
        InvestigationState afterMerge = afterReason.withMergedComponents(List.of("svc"));
        InvestigationState afterPanels = withPanels(afterMerge);
        InvestigationState afterAssembly = afterPanels;
        InvestigationState afterPublish = afterPanels.withDashboardUrl("/d/uid/title");

        when(atlasMemoryNode.apply(any())).thenReturn(afterAtlas);
        when(reasonNode.apply(any())).thenReturn(afterReason);
        when(tempoResolutionNode.apply(any())).thenReturn(afterTempo);
        when(lokiCrossServiceNode.apply(any())).thenReturn(afterLoki);
        when(dashboardMemoryNode.apply(any())).thenReturn(afterReason);
        when(componentMergeNode.apply(any())).thenReturn(afterMerge);
        when(panelSelectionNode.apply(any())).thenReturn(afterPanels);
        when(dashboardAssemblyNode.apply(any())).thenReturn(afterAssembly);
        when(publishNode.apply(any())).thenReturn(afterPublish);

        InvestigationState result = graph.invoke(initialState());

        assertThat(result.dashboardUrl()).isEqualTo("/d/uid/title");
    }

    @Test
    void invoke_reasonThrows_propagates() {
        when(atlasMemoryNode.apply(any())).thenReturn(initialState());
        when(reasonNode.apply(any())).thenThrow(new RuntimeException("LLM unavailable"));

        assertThatThrownBy(() -> graph.invoke(initialState()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM unavailable");
    }

    @Test
    void invoke_panelSelectionThrows_propagates() {
        InvestigationState afterReason = reasoned();
        InvestigationState afterMerge = afterReason.withMergedComponents(List.of("svc"));

        when(atlasMemoryNode.apply(any())).thenReturn(initialState());
        when(reasonNode.apply(any())).thenReturn(afterReason);
        when(tempoResolutionNode.apply(any())).thenReturn(afterReason.withTraceComponents(List.of("svc")));
        when(lokiCrossServiceNode.apply(any())).thenReturn(afterReason.withLokiResult(List.of(), List.of()));
        when(dashboardMemoryNode.apply(any())).thenReturn(afterReason);
        when(componentMergeNode.apply(any())).thenReturn(afterMerge);
        when(panelSelectionNode.apply(any())).thenThrow(new RuntimeException("LLM panel error"));

        assertThatThrownBy(() -> graph.invoke(initialState()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM panel error");
    }
}
