package com.firm.investigation.component;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.firm.investigation.config.InvestigationProperties;
import com.firm.investigation.panel.PanelTemplate;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PanelServiceClientTest {

    private WireMockServer wireMock;
    private PanelServiceClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        InvestigationProperties.PanelServiceProperties panelServiceProps =
                mock(InvestigationProperties.PanelServiceProperties.class);
        when(panelServiceProps.baseUrl()).thenReturn("http://localhost:" + wireMock.port());

        InvestigationProperties properties = mock(InvestigationProperties.class);
        when(properties.panelService()).thenReturn(panelServiceProps);

        client = new PanelServiceClient(properties);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void getDescriptions_returnsPlainText() {
        wireMock.stubFor(get(urlEqualTo("/api/panels/descriptions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("log_stream: Log stream — suits: ALL\nheap_by_component: Heap usage — suits: NPE")));

        String result = client.getDescriptions();

        assertThat(result).contains("log_stream");
        assertThat(result).contains("heap_by_component");
    }

    @Test
    void getAllPanels_returnsParsedList() {
        wireMock.stubFor(get(urlEqualTo("/api/panels"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "panelId": "log_stream",
                                    "description": "Log stream",
                                    "datasource": "IBLoki",
                                    "requiredVariables": ["service", "appcode"],
                                    "categories": ["ALL"],
                                    "panelJson": {"type": "logs"}
                                  }
                                ]
                                """)));

        List<PanelTemplate> panels = client.getAllPanels();

        assertThat(panels).hasSize(1);
        assertThat(panels.get(0).panelId()).isEqualTo("log_stream");
        assertThat(panels.get(0).datasource()).isEqualTo("IBLoki");
    }
}
