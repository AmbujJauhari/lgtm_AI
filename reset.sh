#!/usr/bin/env bash
# Full ephemeral reset — guarantees a "fresh as new" state on next start.
#
# Tears down all containers AND all named volumes used by this stack
# (atlas-data, maven-cache), then brings everything back up.
#
# Use when you want a guaranteed clean slate (demo, after schema changes,
# after Atlas-data changes, etc.).
#
# After this completes, expect ~30s for the incident-graph-indexer to
# repopulate the Neo4j graph from the synthetic Atlas tickets via Ollama.
set -euo pipefail

echo "▸ Stopping all containers and removing named volumes..."
docker compose down -v --remove-orphans

echo "▸ Starting fresh stack..."
docker compose up -d

echo
echo "✓ Reset complete. Tail logs with:  docker compose logs -f"
echo "  Once incident-graph-indexer logs 'Index complete', everything is ready."
