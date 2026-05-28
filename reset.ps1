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
$ErrorActionPreference = "Stop"

Write-Host "▸ Stopping all containers and removing named volumes..."
docker compose down -v --remove-orphans

Write-Host "▸ Starting fresh stack..."
docker compose up -d

Write-Host ""
Write-Host "✓ Reset complete. Tail logs with:  docker compose logs -f"
Write-Host "  Once incident-graph-indexer logs 'Index complete', everything is ready."
