"""Writes the extracted Atlas knowledge graph into Neo4j.

Schema:
  Nodes:
    (Incident       { ticketId, title, description, closureNotes,
                      createdAt, resolvedAt, priority, appcode, embedding })
    (Service        { name })
    (ErrorPattern   { signature })
    (RootCause      { category, description })
    (SystemComponent { name, type })
    (Appcode        { code })

  Edges (per-incident):
    (Incident)-[:BELONGS_TO]->(Appcode)
    (Incident)-[:AFFECTED]->(Service)
    (Incident)-[:HAS_ERROR_PATTERN]->(ErrorPattern)
    (Incident)-[:HAS_ROOT_CAUSE]->(RootCause)
    (Incident)-[:MENTIONS]->(SystemComponent)

  Derived edges (computed post-load):
    (Service)-[:CO_OCCURS_WITH { weight }]->(Service)
    (ErrorPattern)-[:TYPICALLY_AFFECTS { count }]->(Service)

Vector index on Incident.embedding for semantic search at query time.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

from neo4j import Driver, GraphDatabase

from .atlas_loader import AtlasIncident
from .llm_client import Extraction

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class IncidentRecord:
    incident: AtlasIncident
    extraction: Extraction
    embedding: list[float]


class Neo4jWriter:
    def __init__(self, uri: str, user: str, password: str, embedding_dimensions: int):
        self._driver: Driver = GraphDatabase.driver(uri, auth=(user, password))
        self._dim = embedding_dimensions

    def close(self) -> None:
        self._driver.close()

    # ── Bootstrap ──────────────────────────────────────────────────────────
    def setup_schema(self) -> None:
        constraints = [
            "CREATE CONSTRAINT incident_id IF NOT EXISTS FOR (i:Incident) REQUIRE i.ticketId IS UNIQUE",
            "CREATE CONSTRAINT service_name IF NOT EXISTS FOR (s:Service) REQUIRE s.name IS UNIQUE",
            "CREATE CONSTRAINT error_pattern_sig IF NOT EXISTS FOR (e:ErrorPattern) REQUIRE e.signature IS UNIQUE",
            "CREATE CONSTRAINT root_cause_cat IF NOT EXISTS FOR (r:RootCause) REQUIRE r.category IS UNIQUE",
            "CREATE CONSTRAINT system_component_name IF NOT EXISTS FOR (c:SystemComponent) REQUIRE c.name IS UNIQUE",
            "CREATE CONSTRAINT appcode_code IF NOT EXISTS FOR (a:Appcode) REQUIRE a.code IS UNIQUE",
        ]
        vector_index = (
            "CREATE VECTOR INDEX incident_embedding IF NOT EXISTS "
            "FOR (i:Incident) ON (i.embedding) "
            "OPTIONS { indexConfig: { "
            f"  `vector.dimensions`: {self._dim}, "
            "  `vector.similarity_function`: 'cosine' "
            "} }"
        )
        with self._driver.session() as s:
            for c in constraints:
                s.run(c)
            s.run(vector_index)
        log.info("Schema constraints and vector index ready (dim=%d)", self._dim)

    def clear_graph(self) -> None:
        """Wipe the graph — used by reindex command."""
        with self._driver.session() as s:
            s.run("MATCH (n) DETACH DELETE n")
        log.info("Graph cleared")

    # ── Per-incident ingest ────────────────────────────────────────────────
    def write_incident(self, rec: IncidentRecord) -> None:
        params = {
            "ticketId": rec.incident.ticket_id,
            "title": rec.incident.title,
            "description": rec.incident.description,
            "closureNotes": rec.incident.closure_notes,
            "createdAt": rec.incident.created_at,
            "resolvedAt": rec.incident.resolved_at,
            "priority": rec.incident.priority,
            "appcode": rec.incident.appcode,
            "embedding": rec.embedding,
            "services": rec.extraction.services,
            "errorPattern": rec.extraction.error_pattern,
            "rootCauseCategory": rec.extraction.root_cause_category,
            "rootCauseDescription": rec.extraction.root_cause_description,
            "systemComponents": rec.extraction.system_components,
        }
        query = """
        MERGE (i:Incident { ticketId: $ticketId })
          SET i.title = $title,
              i.description = $description,
              i.closureNotes = $closureNotes,
              i.createdAt = $createdAt,
              i.resolvedAt = $resolvedAt,
              i.priority = $priority,
              i.appcode = $appcode,
              i.embedding = $embedding

        MERGE (a:Appcode { code: $appcode })
        MERGE (i)-[:BELONGS_TO]->(a)

        FOREACH (svcName IN $services |
          MERGE (s:Service { name: svcName })
          MERGE (i)-[:AFFECTED]->(s)
        )

        FOREACH (_ IN CASE WHEN $errorPattern IS NULL THEN [] ELSE [1] END |
          MERGE (e:ErrorPattern { signature: $errorPattern })
          MERGE (i)-[:HAS_ERROR_PATTERN]->(e)
        )

        FOREACH (_ IN CASE WHEN $rootCauseCategory IS NULL THEN [] ELSE [1] END |
          MERGE (r:RootCause { category: $rootCauseCategory })
            SET r.description = $rootCauseDescription
          MERGE (i)-[:HAS_ROOT_CAUSE]->(r)
        )

        FOREACH (comp IN $systemComponents |
          MERGE (c:SystemComponent { name: comp.name })
            SET c.type = comp.type
          MERGE (i)-[:MENTIONS]->(c)
        )
        """
        with self._driver.session() as s:
            s.run(query, params)
        log.info("Wrote incident %s: %d services, error=%s, cause=%s",
                 rec.incident.ticket_id, len(rec.extraction.services),
                 rec.extraction.error_pattern, rec.extraction.root_cause_category)

    # ── Derived edges (run once at end) ────────────────────────────────────
    def compute_derived_edges(self) -> None:
        """Compute CO_OCCURS_WITH and TYPICALLY_AFFECTS edges from per-incident data."""
        with self._driver.session() as s:
            # Services that appear together in same incident
            s.run("""
                MATCH (i:Incident)-[:AFFECTED]->(s1:Service)
                MATCH (i)-[:AFFECTED]->(s2:Service)
                WHERE s1.name < s2.name
                WITH s1, s2, count(i) AS weight
                MERGE (s1)-[r:CO_OCCURS_WITH]->(s2)
                  SET r.weight = weight
            """)
            # Error patterns and which services they typically affect
            s.run("""
                MATCH (e:ErrorPattern)<-[:HAS_ERROR_PATTERN]-(i:Incident)-[:AFFECTED]->(s:Service)
                WITH e, s, count(i) AS occurrences
                MERGE (e)-[r:TYPICALLY_AFFECTS]->(s)
                  SET r.count = occurrences
            """)
        log.info("Derived edges (CO_OCCURS_WITH, TYPICALLY_AFFECTS) computed")

    # ── Summary ────────────────────────────────────────────────────────────
    def summary(self) -> dict[str, int]:
        with self._driver.session() as s:
            result = s.run("""
                MATCH (n) WITH labels(n) AS labels, count(*) AS c
                UNWIND labels AS label
                RETURN label, sum(c) AS count
                ORDER BY label
            """)
            counts = {r["label"]: r["count"] for r in result}
        return counts
