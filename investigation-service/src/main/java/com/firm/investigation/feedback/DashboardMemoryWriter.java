package com.firm.investigation.feedback;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes a DashboardMemory record to Neo4j when an L2 submits feedback.
 *
 * Schema:
 *   (DashboardMemory {
 *     id, dashboardUid, logLine, errorCategory, service, appcode,
 *     finalPanelDescriptors (list), feedbackText, createdAt, embedding
 *   })
 *
 * Append-only: each submission creates a fresh node. Resubmissions don't replace
 * previous ones — useful for capturing iteration in long investigations.
 *
 * Vector index on DashboardMemory.embedding enables semantic retrieval at panel
 * selection time.
 */
@Component
public class DashboardMemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(DashboardMemoryWriter.class);
    private static final int EMBEDDING_DIM = 768;  // matches nomic-embed-text default

    private final Driver driver;
    private final EmbeddingModel embeddingModel;
    private final Sanitizer sanitizer;

    public DashboardMemoryWriter(Driver driver, EmbeddingModel embeddingModel, Sanitizer sanitizer) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
        this.sanitizer = sanitizer;
        ensureSchema();
    }

    public void write(DashboardMemoryRecord record) {
        String sanitisedLogLine = sanitizer.sanitize(record.logLine());
        String sanitisedFeedback = sanitizer.sanitize(record.feedbackText());

        float[] embedding = embeddingModel.embed(sanitisedLogLine);
        List<Float> embeddingList = new ArrayList<>(embedding.length);
        for (float v : embedding) embeddingList.add(v);

        String cypher = """
            CREATE (m:DashboardMemory {
                id: $id,
                dashboardUid: $dashboardUid,
                logLine: $logLine,
                errorCategory: $errorCategory,
                service: $service,
                appcode: $appcode,
                finalPanelDescriptors: $panels,
                feedbackText: $feedback,
                createdAt: $createdAt,
                embedding: $embedding
            })
            """;

        try (Session session = driver.session()) {
            session.run(cypher, Values.parameters(
                    "id", UUID.randomUUID().toString(),
                    "dashboardUid", record.dashboardUid(),
                    "logLine", sanitisedLogLine,
                    "errorCategory", record.errorCategory(),
                    "service", record.service(),
                    "appcode", record.appcode(),
                    "panels", record.finalPanelDescriptors(),
                    "feedback", sanitisedFeedback,
                    "createdAt", Instant.now().toString(),
                    "embedding", embeddingList
            ));
        }
        log.info("Wrote DashboardMemory for uid={} service={} appcode={}",
                record.dashboardUid(), record.service(), record.appcode());
    }

    private void ensureSchema() {
        String vectorIndex = (
                "CREATE VECTOR INDEX dashboard_memory_embedding IF NOT EXISTS " +
                "FOR (m:DashboardMemory) ON (m.embedding) " +
                "OPTIONS { indexConfig: { " +
                "  `vector.dimensions`: " + EMBEDDING_DIM + ", " +
                "  `vector.similarity_function`: 'cosine' " +
                "} }");
        try (Session session = driver.session()) {
            session.run(vectorIndex);
            log.debug("DashboardMemory vector index ensured (dim={})", EMBEDDING_DIM);
        } catch (Exception e) {
            // Neo4j unreachable on startup is fine — writes will fail naturally then.
            log.warn("Could not ensure DashboardMemory vector index (Neo4j may be down): {}", e.getMessage());
        }
    }

    public record DashboardMemoryRecord(
            String dashboardUid,
            String logLine,
            String errorCategory,
            String service,
            String appcode,
            List<String> finalPanelDescriptors,
            String feedbackText
    ) {}
}
