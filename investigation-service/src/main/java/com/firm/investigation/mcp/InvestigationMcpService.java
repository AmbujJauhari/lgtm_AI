package com.firm.investigation.mcp;

import com.firm.investigation.api.dto.InvestigationRequest;
import com.firm.investigation.component.PanelServiceClient;
import com.firm.investigation.config.LangGraphConfig;
import com.firm.investigation.graph.InvestigationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class InvestigationMcpService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationMcpService.class);

    private final PanelServiceClient panelServiceClient;
    private final LangGraphConfig langGraphConfig;

    public InvestigationMcpService(PanelServiceClient panelServiceClient, LangGraphConfig langGraphConfig) {
        this.panelServiceClient = panelServiceClient;
        this.langGraphConfig = langGraphConfig;
    }

    @Tool(description = """
            Returns all available Grafana dashboard panel templates from the panel-service catalogue.
            Each entry lists the panel ID, a short description, and the error categories it suits.
            Use this to understand what panels can be included in an investigation dashboard.
            """)
    public String listPanels() {
        return panelServiceClient.getDescriptions();
    }

    @Tool(description = """
            Investigates an error log line and deploys a Grafana dashboard.
            Internally: fetches the panel catalogue (listPanels), runs LLM triage to classify the
            error and extract traceId, resolves affected components via Tempo and Loki in parallel,
            selects the most relevant panels, assembles the dashboard JSON, and publishes it to Grafana.
            Returns the dashboard URL on success, or a failure message if all fallbacks are exhausted.
            """)
    public String investigate(
            @ToolParam(description = "Full raw log line exactly as it appears in the application logs") String logLine,
            @ToolParam(description = "Name of the service that produced the log line (e.g. collateral-service)") String service,
            @ToolParam(description = "Application code for the service (e.g. COLL, MRGN, RISK)") String appcode,
            @ToolParam(description = "ISO-8601 timestamp of the error (e.g. 2024-01-15T10:30:00Z)") String timestamp) {

        String availablePanels = listPanels();
        log.info("MCP investigate called for service={} appcode={}", service, appcode);

        InvestigationRequest request = new InvestigationRequest(logLine, service, appcode, timestamp);
        InvestigationState initial = InvestigationState.initial(request)
                .withAvailablePanels(availablePanels);

        InvestigationState result = langGraphConfig.investigationGraph().invoke(initial);

        if (result.dashboardUrl() != null && !result.dashboardUrl().isBlank()) {
            return result.dashboardUrl();
        }
        return "Investigation did not produce a dashboard URL.";
    }
}
