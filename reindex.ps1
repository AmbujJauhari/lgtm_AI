# Re-pump the Atlas knowledge graph from scratch.
#
# Use after: prompt changes, embedding model swap, schema changes,
# or when local Atlas data has been refreshed.
#
# This sets REINDEX=true so the indexer wipes Neo4j before loading.
$ErrorActionPreference = "Stop"
docker compose run --rm -e REINDEX=true incident-graph-indexer
