package com.firm.investigation.config;

import com.firm.investigation.graph.InvestigationState;
import com.firm.investigation.graph.node.AtlasMemoryNode;
import com.firm.investigation.graph.node.ComponentMergeNode;
import com.firm.investigation.graph.node.DashboardAssemblyNode;
import com.firm.investigation.graph.node.DashboardMemoryNode;
import com.firm.investigation.graph.node.LokiCrossServiceNode;
import com.firm.investigation.graph.node.PanelSelectionNode;
import com.firm.investigation.graph.node.PublishNode;
import com.firm.investigation.graph.node.ReasonNode;
import com.firm.investigation.graph.node.TempoResolutionNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;

/**
 * Investigation pipeline wiring.
 *
 *   START
 *     → atlasMemory    (AtlasMemoryNode — Neo4j vector search on Atlas incidents)
 *     → reason         (ReasonNode — LLM Call 1: parse log + reason about services + emit query plans)
 *     ├→ tempoResolution    (driven by reason.tempoPlan)            ┐
 *     ├→ lokiCrossService   (driven by reason.lokiPlan + confidence) ├→ parallel
 *     └→ dashboardMemory    (Neo4j vector search on past L2 feedback) ┘
 *     → componentMerge      (union likelyServices + trace + loki)
 *     → panelSelection      (LLM Call 2: panels + arguments,
 *                            with Atlas + DashboardMemory context blocks in prompt)
 *     → dashboardAssembly   (validate)
 *     → publish             (POST to Grafana)
 *   END
 *
 * Error semantics:
 *   - Data nodes (Atlas / Tempo / Loki / DashboardMemory) degrade gracefully — empty result, never throw
 *   - AI nodes (Reason / PanelSelection) and mechanical nodes (Assembly / Publish) propagate exceptions
 *   - No fallback dashboard. An incomplete investigation surfaces as a 503 with structured error.
 */
@Configuration
public class LangGraphConfig {

    private final AtlasMemoryNode atlasMemoryNode;
    private final ReasonNode reasonNode;
    private final TempoResolutionNode tempoResolutionNode;
    private final LokiCrossServiceNode lokiCrossServiceNode;
    private final DashboardMemoryNode dashboardMemoryNode;
    private final ComponentMergeNode componentMergeNode;
    private final PanelSelectionNode panelSelectionNode;
    private final DashboardAssemblyNode dashboardAssemblyNode;
    private final PublishNode publishNode;

    public LangGraphConfig(
            AtlasMemoryNode atlasMemoryNode,
            ReasonNode reasonNode,
            TempoResolutionNode tempoResolutionNode,
            LokiCrossServiceNode lokiCrossServiceNode,
            DashboardMemoryNode dashboardMemoryNode,
            ComponentMergeNode componentMergeNode,
            PanelSelectionNode panelSelectionNode,
            DashboardAssemblyNode dashboardAssemblyNode,
            PublishNode publishNode) {
        this.atlasMemoryNode = atlasMemoryNode;
        this.reasonNode = reasonNode;
        this.tempoResolutionNode = tempoResolutionNode;
        this.lokiCrossServiceNode = lokiCrossServiceNode;
        this.dashboardMemoryNode = dashboardMemoryNode;
        this.componentMergeNode = componentMergeNode;
        this.panelSelectionNode = panelSelectionNode;
        this.dashboardAssemblyNode = dashboardAssemblyNode;
        this.publishNode = publishNode;
    }

    @Bean
    public InvestigationGraph investigationGraph() {
        return new InvestigationGraph(
                atlasMemoryNode, reasonNode, tempoResolutionNode, lokiCrossServiceNode,
                dashboardMemoryNode, componentMergeNode, panelSelectionNode,
                dashboardAssemblyNode, publishNode);
    }

    public static class InvestigationGraph {

        private final AtlasMemoryNode atlasMemoryNode;
        private final ReasonNode reasonNode;
        private final TempoResolutionNode tempoResolutionNode;
        private final LokiCrossServiceNode lokiCrossServiceNode;
        private final DashboardMemoryNode dashboardMemoryNode;
        private final ComponentMergeNode componentMergeNode;
        private final PanelSelectionNode panelSelectionNode;
        private final DashboardAssemblyNode dashboardAssemblyNode;
        private final PublishNode publishNode;

        public InvestigationGraph(
                AtlasMemoryNode atlasMemoryNode,
                ReasonNode reasonNode,
                TempoResolutionNode tempoResolutionNode,
                LokiCrossServiceNode lokiCrossServiceNode,
                DashboardMemoryNode dashboardMemoryNode,
                ComponentMergeNode componentMergeNode,
                PanelSelectionNode panelSelectionNode,
                DashboardAssemblyNode dashboardAssemblyNode,
                PublishNode publishNode) {
            this.atlasMemoryNode = atlasMemoryNode;
            this.reasonNode = reasonNode;
            this.tempoResolutionNode = tempoResolutionNode;
            this.lokiCrossServiceNode = lokiCrossServiceNode;
            this.dashboardMemoryNode = dashboardMemoryNode;
            this.componentMergeNode = componentMergeNode;
            this.panelSelectionNode = panelSelectionNode;
            this.dashboardAssemblyNode = dashboardAssemblyNode;
            this.publishNode = publishNode;
        }

        public InvestigationState invoke(InvestigationState initial) {
            // 1. Atlas memory (sequential — input to reasoning)
            InvestigationState afterAtlas = atlasMemoryNode.apply(initial);

            // 2. LLM Call 1 — reason about services and emit query plans (fail fast on AI error)
            InvestigationState afterReason = reasonNode.apply(afterAtlas);

            // 3. Tempo + Loki + DashboardMemory in parallel (graceful degradation each)
            final InvestigationState forParallel = afterReason;
            CompletableFuture<InvestigationState> tempoFuture =
                    CompletableFuture.supplyAsync(() -> tempoResolutionNode.apply(forParallel));
            CompletableFuture<InvestigationState> lokiFuture =
                    CompletableFuture.supplyAsync(() -> lokiCrossServiceNode.apply(forParallel));
            CompletableFuture<InvestigationState> dashMemFuture =
                    CompletableFuture.supplyAsync(() -> dashboardMemoryNode.apply(forParallel));

            InvestigationState afterTempo = tempoFuture.join();
            InvestigationState afterLoki = lokiFuture.join();
            InvestigationState afterDashMem = dashMemFuture.join();

            // 4. Merge — union reason.likelyServices + trace components + loki components; carry dashboard memory
            InvestigationState merged = afterReason
                    .withTraceComponents(afterTempo.traceComponents())
                    .withLokiResult(afterLoki.lokiComponents(), afterLoki.crossServiceLogs())
                    .withDashboardMemory(afterDashMem.dashboardMemory());
            InvestigationState afterMerge = componentMergeNode.apply(merged);

            // 5. LLM Call 2 — panel selection (fail fast on AI error)
            InvestigationState afterPanels = panelSelectionNode.apply(afterMerge);

            // 6. Assemble + publish (fail fast — mechanical)
            InvestigationState afterAssembly = dashboardAssemblyNode.apply(afterPanels);
            return publishNode.apply(afterAssembly);
        }
    }
}
