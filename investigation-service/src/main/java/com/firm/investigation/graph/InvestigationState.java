package com.firm.investigation.graph;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.memory.AtlasMemoryResult;
import com.firm.investigation.memory.DashboardMemoryResult;
import com.firm.investigation.panel.CrossServiceLogEntry;
import com.firm.investigation.panel.PanelSelectionResult;
import com.firm.investigation.reason.ReasonResult;

import java.util.List;

public record InvestigationState(
    InvestigationRequest request,
    AtlasMemoryResult atlasMemory,
    DashboardMemoryResult dashboardMemory,
    ReasonResult reason,
    List<String> traceComponents,
    List<String> lokiComponents,
    List<CrossServiceLogEntry> crossServiceLogs,
    List<String> mergedComponents,
    PanelSelectionResult panels,
    String dashboardUrl,
    String availablePanels
) {
    public static InvestigationState initial(InvestigationRequest request) {
        return new InvestigationState(request,
            AtlasMemoryResult.empty(), DashboardMemoryResult.empty(), null,
            List.of(), List.of(), List.of(), List.of(), null, null, null);
    }

    public InvestigationState withAtlasMemory(AtlasMemoryResult atlas) {
        return new InvestigationState(request, atlas, dashboardMemory, reason, traceComponents, lokiComponents,
            crossServiceLogs, mergedComponents, panels, dashboardUrl, availablePanels);
    }

    public InvestigationState withDashboardMemory(DashboardMemoryResult dm) {
        return new InvestigationState(request, atlasMemory, dm, reason, traceComponents, lokiComponents,
            crossServiceLogs, mergedComponents, panels, dashboardUrl, availablePanels);
    }

    public InvestigationState withReason(ReasonResult r) {
        return new InvestigationState(request, atlasMemory, dashboardMemory, r, traceComponents, lokiComponents,
            crossServiceLogs, mergedComponents, panels, dashboardUrl, availablePanels);
    }

    public InvestigationState withTraceComponents(List<String> tc) {
        return new InvestigationState(request, atlasMemory, dashboardMemory, reason, tc, lokiComponents,
            crossServiceLogs, mergedComponents, panels, dashboardUrl, availablePanels);
    }

    public InvestigationState withLokiResult(List<String> lc, List<CrossServiceLogEntry> logs) {
        return new InvestigationState(request, atlasMemory, dashboardMemory, reason, traceComponents, lc,
            logs, mergedComponents, panels, dashboardUrl, availablePanels);
    }

    public InvestigationState withMergedComponents(List<String> mc) {
        return new InvestigationState(request, atlasMemory, dashboardMemory, reason, traceComponents, lokiComponents,
            crossServiceLogs, mc, panels, dashboardUrl, availablePanels);
    }

    public InvestigationState withPanels(PanelSelectionResult p) {
        return new InvestigationState(request, atlasMemory, dashboardMemory, reason, traceComponents, lokiComponents,
            crossServiceLogs, mergedComponents, p, dashboardUrl, availablePanels);
    }

    public InvestigationState withDashboardUrl(String url) {
        return new InvestigationState(request, atlasMemory, dashboardMemory, reason, traceComponents, lokiComponents,
            crossServiceLogs, mergedComponents, panels, url, availablePanels);
    }

    public InvestigationState withAvailablePanels(String ap) {
        return new InvestigationState(request, atlasMemory, dashboardMemory, reason, traceComponents, lokiComponents,
            crossServiceLogs, mergedComponents, panels, dashboardUrl, ap);
    }
}
