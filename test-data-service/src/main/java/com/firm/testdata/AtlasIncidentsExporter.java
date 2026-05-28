package com.firm.testdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class AtlasIncidentsExporter {

    private static final Logger log = LoggerFactory.getLogger(AtlasIncidentsExporter.class);
    private static final String RESOURCE_PATH = "atlas/incidents.json";

    private final TestDataProperties properties;

    public AtlasIncidentsExporter(TestDataProperties properties) {
        this.properties = properties;
    }

    public void export() throws IOException {
        Path target = Path.of(properties.atlasExportPath());
        Files.createDirectories(target.getParent());

        try (InputStream src = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Exported synthetic Atlas incidents → {}", target.toAbsolutePath());
    }
}
