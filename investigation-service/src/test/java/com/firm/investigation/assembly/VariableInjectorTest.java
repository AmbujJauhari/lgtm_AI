package com.firm.investigation.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableInjectorTest {

    private VariableInjector injector;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        injector = new VariableInjector(objectMapper);
    }

    @Test
    void inject_replacesSimpleVariable() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {"title": "Logs — $service", "expr": "{service=\\"$service\\"}"}
                """);
        JsonNode result = injector.inject(template, Map.of("service", "collateral-service"));
        assertThat(result.path("title").asText()).isEqualTo("Logs — collateral-service");
        assertThat(result.path("expr").asText()).isEqualTo("{service=\"collateral-service\"}");
    }

    @Test
    void inject_replacesMultipleVariables() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {"expr": "{appcode=\\"$appcode\\", service=\\"$service\\"}"}
                """);
        JsonNode result = injector.inject(template, Map.of("service", "margin-service", "appcode", "MRGN"));
        assertThat(result.path("expr").asText()).isEqualTo("{appcode=\"MRGN\", service=\"margin-service\"}");
    }

    @Test
    void inject_leavesUnknownVariableUnchanged() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {"title": "$unknownVar"}
                """);
        JsonNode result = injector.inject(template, Map.of("service", "my-service"));
        assertThat(result.path("title").asText()).isEqualTo("$unknownVar");
    }

    @Test
    void inject_nullValueBecomesEmptyString() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {"title": "$traceId"}
                """);
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("traceId", null);
        JsonNode result = injector.inject(template, vars);
        assertThat(result.path("title").asText()).isEqualTo("");
    }

    @Test
    void inject_longerKeyReplacedBeforeShorterPrefix() throws Exception {
        // $components must be replaced before $component to avoid partial replacement
        JsonNode template = objectMapper.readTree("""
                {"expr": "metric{service=\\"$components\\"}"}
                """);
        JsonNode result = injector.inject(template, Map.of(
                "components", "svc-a,svc-b",
                "component", "SHOULD-NOT-APPEAR"));
        assertThat(result.path("expr").asText()).isEqualTo("metric{service=\"svc-a,svc-b\"}");
    }

    @Test
    void inject_worksInNestedJson() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {"targets": [{"expr": "{appcode=\\"$appcode\\"}"}]}
                """);
        JsonNode result = injector.inject(template, Map.of("appcode", "COLL"));
        assertThat(result.path("targets").get(0).path("expr").asText())
                .isEqualTo("{appcode=\"COLL\"}");
    }

    @Test
    void inject_replacesAllOccurrencesOfSameVariable() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {"title": "$service panel", "description": "Logs for $service"}
                """);
        JsonNode result = injector.inject(template, Map.of("service", "risk-service"));
        assertThat(result.path("title").asText()).isEqualTo("risk-service panel");
        assertThat(result.path("description").asText()).isEqualTo("Logs for risk-service");
    }
}
