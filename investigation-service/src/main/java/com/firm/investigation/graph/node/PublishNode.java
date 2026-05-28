package com.firm.investigation.graph.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.firm.investigation.assembly.DashboardAssemblyService;
import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.grafana.GrafanaPublishService;
import com.firm.investigation.graph.InvestigationState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Assembles the dashboard JSON and publishes to Grafana.
 * Mechanical step — failures propagate (no fallback dashboard).
 */
@Component
public class PublishNode implements Function<InvestigationState, InvestigationState> {

    private final DashboardAssemblyService assemblyService;
    private final GrafanaPublishService grafanaPublishService;
    private final InvestigationProperties properties;
    private final ObservationRegistry observationRegistry;

    public PublishNode(
            DashboardAssemblyService assemblyService,
            GrafanaPublishService grafanaPublishService,
            InvestigationProperties properties,
            ObservationRegistry observationRegistry) {
        this.assemblyService = assemblyService;
        this.grafanaPublishService = grafanaPublishService;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public InvestigationState apply(InvestigationState state) {
        return Observation.createNotStarted("investigation.node.publish", observationRegistry)
                .lowCardinalityKeyValue("service", state.request().service())
                .observe(() -> {
                    JsonNode dashboardJson = assemblyService.assemble(state);
                    String url = grafanaPublishService.publish(dashboardJson, properties);
                    return state.withDashboardUrl(url);
                });
    }
}
