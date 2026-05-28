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
import java.util.Map;

/**
 * Queries the Atlas knowledge graph in Neo4j for incidents similar to a given log line.
 *
 * Retrieval flow:
 *   1. Embed the log line
 *   2. Vector-similarity match against Incident.embedding (filtered by appcode)
 *   3. Graph-traverse from matched incidents to their affected services,
 *      root causes, and mentioned components
 *   4. Return structured result for downstream reasoning + panel selection
 *
 * Gracefully degrades on Neo4j errors — returns AtlasMemoryResult.empty()
 * rather than propagating exceptions. Atlas is a "hint" source, not authoritative.
 */
@Component
public class AtlasMemoryClient {

    private static final Logger log = LoggerFactory.getLogger(AtlasMemoryClient.class);
    private static final int TOP_K = 5;

    private final Driver driver;
    private final EmbeddingModel embeddingModel;

    public AtlasMemoryClient(Driver driver, EmbeddingModel embeddingModel) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
    }

    public AtlasMemoryResult retrieve(String logLine, String appcode) {
        try {
            float[] embedding = embeddingModel.embed(logLine);
            return queryNeo4j(embedding, appcode);
        } catch (Exception e) {
            log.warn("Atlas memory retrieval failed (degrading to empty result): {}", e.getMessage());
            return AtlasMemoryResult.empty();
        }
    }

    private AtlasMemoryResult queryNeo4j(float[] embedding, String appcode) {
        List<Float> embeddingList = new ArrayList<>(embedding.length);
        for (float v : embedding) embeddingList.add(v);

        String cypher = """
            CALL db.index.vector.queryNodes('incident_embedding', $k, $embedding)
            YIELD node AS incident, score
            WHERE incident.appcode = $appcode
            OPTIONAL MATCH (incident)-[:AFFECTED]->(svc:Service)
            OPTIONAL MATCH (incident)-[:HAS_ROOT_CAUSE]->(rc:RootCause)
            OPTIONAL MATCH (incident)-[:MENTIONS]->(comp:SystemComponent)
            WITH incident, score, rc,
                 collect(DISTINCT svc.name) AS services,
                 collect(DISTINCT comp.name) AS components
            RETURN incident.ticketId    AS ticketId,
                   incident.title       AS title,
                   incident.closureNotes AS closureNotes,
                   incident.priority    AS priority,
                   services,
                   rc.category          AS rootCauseCategory,
                   components,
                   score
            ORDER BY score DESC
            """;

        try (Session session = driver.session()) {
            var results = session.run(cypher, Values.parameters(
                    "k", TOP_K,
                    "embedding", embeddingList,
                    "appcode", appcode
            )).list();

            if (results.isEmpty()) {
                log.debug("Atlas memory: no related incidents for appcode={}", appcode);
                return AtlasMemoryResult.empty();
            }

            List<AtlasMemoryResult.RelatedIncident> related = results.stream()
                    .map(this::toRelatedIncident)
                    .toList();

            List<String> services = distinctOrdered(results, "services");
            List<String> components = distinctOrdered(results, "components");
            List<String> rootCauses = results.stream()
                    .map(r -> r.get("rootCauseCategory"))
                    .filter(v -> v != null && !v.isNull())
                    .map(Value::asString)
                    .distinct()
                    .toList();

            log.info("Atlas memory: {} related incidents, {} historical services, {} root causes for appcode={}",
                    related.size(), services.size(), rootCauses.size(), appcode);
            return new AtlasMemoryResult(related, services, rootCauses, components);
        }
    }

    private AtlasMemoryResult.RelatedIncident toRelatedIncident(Record r) {
        List<String> services = r.get("services").asList(Value::asString);
        return new AtlasMemoryResult.RelatedIncident(
                stringOrEmpty(r, "ticketId"),
                stringOrEmpty(r, "title"),
                stringOrEmpty(r, "closureNotes"),
                stringOrEmpty(r, "priority"),
                services,
                stringOrNull(r, "rootCauseCategory"),
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

    private static List<String> distinctOrdered(List<Record> records, String listField) {
        return records.stream()
                .flatMap(r -> r.get(listField).asList(Value::asString).stream())
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }
}
