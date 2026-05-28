package com.firm.investigation.memory;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Retrieves past L2 feedback from the dashboard_memory store for similar errors.
 *
 * Vector similarity search on DashboardMemory.embedding, scoped by errorCategory
 * (when available) to keep retrieval precise. Gracefully degrades to empty result
 * on Neo4j errors — feedback is a "nice to have" signal, never blocks investigation.
 */
@Component
public class DashboardMemoryClient {

    private static final Logger log = LoggerFactory.getLogger(DashboardMemoryClient.class);
    private static final int TOP_K = 3;

    private final Driver driver;
    private final EmbeddingModel embeddingModel;

    public DashboardMemoryClient(Driver driver, EmbeddingModel embeddingModel) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
    }

    public DashboardMemoryResult retrieve(String logLine, String errorCategory) {
        try {
            float[] embedding = embeddingModel.embed(logLine);
            return query(embedding, errorCategory);
        } catch (Exception e) {
            log.warn("DashboardMemory retrieval failed (degrading to empty): {}", e.getMessage());
            return DashboardMemoryResult.empty();
        }
    }

    private DashboardMemoryResult query(float[] embedding, String errorCategory) {
        List<Float> embeddingList = new ArrayList<>(embedding.length);
        for (float v : embedding) embeddingList.add(v);

        String cypher = """
            CALL db.index.vector.queryNodes('dashboard_memory_embedding', $k, $embedding)
            YIELD node AS m, score
            WHERE $errorCategory IS NULL OR m.errorCategory = $errorCategory
            RETURN m.dashboardUid          AS dashboardUid,
                   m.logLine               AS logLine,
                   m.errorCategory         AS errorCategory,
                   m.service               AS service,
                   m.finalPanelDescriptors AS panels,
                   m.feedbackText          AS feedback,
                   score
            ORDER BY score DESC
            """;

        try (Session session = driver.session()) {
            var results = session.run(cypher, Values.parameters(
                    "k", TOP_K,
                    "embedding", embeddingList,
                    "errorCategory", errorCategory
            )).list();

            if (results.isEmpty()) return DashboardMemoryResult.empty();

            List<DashboardMemoryResult.PastFeedback> entries = results.stream()
                    .map(this::toPastFeedback)
                    .toList();

            log.info("DashboardMemory: {} past feedback entries (errorCategory={})",
                    entries.size(), errorCategory);
            return new DashboardMemoryResult(entries);
        }
    }

    private DashboardMemoryResult.PastFeedback toPastFeedback(Record r) {
        Value panelsVal = r.get("panels");
        List<String> panels = panelsVal == null || panelsVal.isNull()
                ? List.of() : panelsVal.asList(Value::asString);
        return new DashboardMemoryResult.PastFeedback(
                stringOrEmpty(r, "dashboardUid"),
                stringOrEmpty(r, "logLine"),
                stringOrNull(r, "errorCategory"),
                stringOrEmpty(r, "service"),
                panels,
                stringOrEmpty(r, "feedback"),
                r.get("score").asDouble(0.0)
        );
    }

    private static String stringOrEmpty(Record r, String field) {
        Value v = r.get(field);
        return v == null || v.isNull() ? "" : v.asString();
    }

    private static String stringOrNull(Record r, String field) {
        Value v = r.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
