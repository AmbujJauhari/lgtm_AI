package com.firm.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PanelLibrary {

    private static final Logger log = LoggerFactory.getLogger(PanelLibrary.class);

    private final ObjectMapper objectMapper;
    private List<PanelTemplate> panels;

    public PanelLibrary(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:panels/*.json");
            List<PanelTemplate> loaded = new ArrayList<>();
            for (Resource resource : resources) {
                try {
                    JsonNode node = objectMapper.readTree(resource.getInputStream());
                    loaded.add(fromJson(node));
                } catch (Exception e) {
                    log.error("Failed to load panel from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
            this.panels = List.copyOf(loaded);
            log.info("Loaded {} panel templates", panels.size());
        } catch (Exception e) {
            log.error("Failed to scan classpath:panels/: {}", e.getMessage());
            this.panels = List.of();
        }
    }

    public List<PanelTemplate> allPanels() {
        return panels;
    }

    public String descriptions() {
        return panels.stream()
                .map(p -> p.panelId() + ": " + p.description()
                        + " — suits: " + String.join(", ", p.categories()))
                .collect(Collectors.joining("\n"));
    }

    private PanelTemplate fromJson(JsonNode node) {
        String panelId = node.path("panelId").asText();
        String description = node.path("description").asText();
        String datasource = node.path("datasource").asText();
        List<String> requiredVariables = new ArrayList<>();
        node.path("requiredVariables").forEach(v -> requiredVariables.add(v.asText()));
        List<String> categories = new ArrayList<>();
        node.path("categories").forEach(c -> categories.add(c.asText()));
        JsonNode panelJson = node.path("panelJson");
        return new PanelTemplate(panelId, description, datasource, requiredVariables, categories, panelJson);
    }
}
