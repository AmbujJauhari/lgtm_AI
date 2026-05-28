package com.firm.investigation.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class VariableInjector {

    private final ObjectMapper objectMapper;

    public VariableInjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode inject(JsonNode panelJson, Map<String, String> variables) {
        try {
            String json = objectMapper.writeValueAsString(panelJson);
            // Sort longest key first so $components is replaced before $component
            List<Map.Entry<String, String>> entries = new ArrayList<>(variables.entrySet());
            entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
            for (Map.Entry<String, String> entry : entries) {
                String value = entry.getValue() != null ? entry.getValue() : "";
                json = json.replace("$" + entry.getKey(), value);
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Variable injection failed: " + e.getMessage(), e);
        }
    }
}
