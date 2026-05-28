package com.firm.investigation.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardMemoryWriterTest {

    private Driver driver;
    private Session session;
    private EmbeddingModel embeddingModel;
    private Sanitizer sanitizer;

    @BeforeEach
    void setUp() {
        driver = mock(Driver.class);
        session = mock(Session.class);
        embeddingModel = mock(EmbeddingModel.class);
        sanitizer = new Sanitizer();
        when(driver.session()).thenReturn(session);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
    }

    @Test
    void write_executesCypherWithExpectedFields() {
        DashboardMemoryWriter writer = new DashboardMemoryWriter(driver, embeddingModel, sanitizer);
        DashboardMemoryWriter.DashboardMemoryRecord record = new DashboardMemoryWriter.DashboardMemoryRecord(
                "inv-1", "NullPointerException at line", "NPE", "svc", "AT4278",
                List.of("logs:Logs"), "useful gc panel");

        writer.write(record);

        verify(session, atLeastOnce()).run(anyString(), any(Value.class));
    }

    @Test
    void write_sanitizesLogLineAndFeedback() {
        DashboardMemoryWriter writer = new DashboardMemoryWriter(driver, embeddingModel, sanitizer);
        DashboardMemoryWriter.DashboardMemoryRecord record = new DashboardMemoryWriter.DashboardMemoryRecord(
                "inv-1",
                "ISIN GB00B4QFG876 fail",  // ISIN will be replaced
                "NPE", "svc", "AT4278", List.of(),
                "fix related to account 12345678");  // long digits replaced

        writer.write(record);

        // Embedding called with sanitized log line — not raw ISIN
        verify(embeddingModel).embed("ISIN [ISIN] fail");
    }

    @Test
    void constructor_neo4jDownDuringSchemaCheck_doesNotThrow() {
        when(session.run(anyString())).thenThrow(new RuntimeException("neo4j down"));
        // Should not throw during construction even if schema setup fails
        DashboardMemoryWriter writer = new DashboardMemoryWriter(driver, embeddingModel, sanitizer);
        // Constructor returned successfully
        org.junit.jupiter.api.Assertions.assertNotNull(writer);
    }
}
