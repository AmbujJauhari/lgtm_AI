"""Atlas incident → Neo4j knowledge graph indexer.

End-to-end:
  1. Wait for Neo4j to be reachable
  2. Load Atlas incidents from JSON
  3. For each incident: LLM-extract entities + compute embedding
  4. Write to Neo4j (Incident + Service + ErrorPattern + RootCause + Components)
  5. Compute derived edges (CO_OCCURS_WITH, TYPICALLY_AFFECTS)
  6. Print summary; exit

Re-runnable: set REINDEX=true to clear the graph before loading.
"""
from __future__ import annotations

import logging
import os
import sys
import time

from neo4j import GraphDatabase
from neo4j.exceptions import ServiceUnavailable

from .atlas_loader import load_incidents
from .config import Config
from .llm_client import LLMClient
from .neo4j_writer import IncidentRecord, Neo4jWriter

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("indexer")


def wait_for_neo4j(uri: str, user: str, password: str, max_attempts: int = 30) -> None:
    for attempt in range(1, max_attempts + 1):
        try:
            with GraphDatabase.driver(uri, auth=(user, password)) as driver:
                driver.verify_connectivity()
                log.info("Connected to Neo4j at %s", uri)
                return
        except ServiceUnavailable as e:
            log.info("Neo4j not ready (attempt %d/%d): %s", attempt, max_attempts, e)
            time.sleep(2)
    raise RuntimeError(f"Neo4j unreachable at {uri} after {max_attempts} attempts")


def main() -> int:
    config = Config.from_env()
    reindex = os.environ.get("REINDEX", "false").lower() == "true"

    log.info("incident-graph-indexer starting")
    log.info("  LLM provider:       %s", config.llm_provider)
    log.info("  Atlas data path:    %s", config.atlas_data_path)
    log.info("  Neo4j URI:          %s", config.neo4j_uri)
    log.info("  Reindex (clear):    %s", reindex)

    wait_for_neo4j(config.neo4j_uri, config.neo4j_user, config.neo4j_password)

    if not os.path.exists(config.atlas_data_path):
        log.warning("No Atlas data file at %s — nothing to index", config.atlas_data_path)
        return 0

    incidents = load_incidents(config.atlas_data_path)
    if not incidents:
        log.warning("Atlas data file is empty — nothing to index")
        return 0

    llm = LLMClient(config)
    writer = Neo4jWriter(
        config.neo4j_uri, config.neo4j_user, config.neo4j_password,
        config.embedding_dimensions,
    )
    try:
        if reindex:
            writer.clear_graph()
        writer.setup_schema()

        for idx, inc in enumerate(incidents, start=1):
            log.info("[%d/%d] Indexing %s — %s", idx, len(incidents), inc.ticket_id, inc.title)
            try:
                extraction = llm.extract(inc.title, inc.description, inc.closure_notes)
                embedding = llm.embed(inc.combined_text)
                writer.write_incident(IncidentRecord(inc, extraction, embedding))
            except Exception as e:
                log.exception("Failed to index %s, skipping: %s", inc.ticket_id, e)

        writer.compute_derived_edges()
        counts = writer.summary()
        log.info("Index complete. Node counts: %s", counts)
    finally:
        writer.close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
