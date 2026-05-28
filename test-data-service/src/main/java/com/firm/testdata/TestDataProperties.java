package com.firm.testdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "test-data")
public record TestDataProperties(
    String otlpEndpoint,
    String lokiUrl,
    String atlasExportPath,
    String appcode
) {}
