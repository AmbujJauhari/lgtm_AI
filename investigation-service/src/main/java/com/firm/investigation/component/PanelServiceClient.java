package com.firm.investigation.component;

import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.panel.PanelTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Component
public class PanelServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PanelServiceClient.class);

    private final WebClient webClient;

    public PanelServiceClient(InvestigationProperties properties) {
        this.webClient = WebClient.builder()
                .baseUrl(properties.panelService().baseUrl())
                .build();
    }

    public String getDescriptions() {
        log.debug("Fetching panel descriptions from panel-service");
        return webClient.get()
                .uri("/api/panels/descriptions")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    public List<PanelTemplate> getAllPanels() {
        log.debug("Fetching all panel templates from panel-service");
        return webClient.get()
                .uri("/api/panels")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<PanelTemplate>>() {})
                .block();
    }
}
