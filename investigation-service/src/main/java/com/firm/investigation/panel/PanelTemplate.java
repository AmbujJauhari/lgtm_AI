package com.firm.investigation.panel;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record PanelTemplate(
    String panelId,
    String description,
    String datasource,
    List<String> requiredVariables,
    List<String> categories,
    JsonNode panelJson
) {}
