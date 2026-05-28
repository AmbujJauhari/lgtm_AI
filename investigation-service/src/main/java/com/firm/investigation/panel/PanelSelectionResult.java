package com.firm.investigation.panel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PanelSelectionResult(
    List<SelectedPanel> selectedPanels,
    String dashboardTitle
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SelectedPanel(String panelId, Map<String, String> variables) {}
}
