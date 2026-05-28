#!/usr/bin/env bash
# Re-pump the Atlas knowledge graph from scratch.
#
# Use after: prompt changes, embedding model swap, schema changes,
# or when local Atlas data has been refreshed.
#
# This sets REINDEX=true so the indexer wipes Neo4j before loading.
set -euo pipefail
docker compose run --rm -e REINDEX=true incident-graph-indexer
