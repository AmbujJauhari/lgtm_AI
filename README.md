# AI-Powered Investigation Platform

An AI investigation system that turns a single error log click in Grafana into a
targeted dashboard, informed by past incident history and prior on-call feedback.

```
                 ┌────────────────┐
   click log →   │   Grafana      │
                 │  (Loki Table)  │ ── 🔍 ──→ ┌──────────────────────┐
                 └────────────────┘            │ investigation-service │
                                               │  LangGraph pipeline:  │
                                               │  AtlasMemory → Reason │
                                               │  → Tempo + Loki +     │
                                               │    DashboardMemory    │
                                               │  → PanelSelection     │
                                               │  → Assemble + Publish │
                                               └──────────┬───────────┘
                                                          │
                              ┌───────────────────────────┼────────────────────┐
                              ▼                           ▼                    ▼
                       ┌──────────────┐         ┌──────────────┐      ┌──────────────┐
                       │ panel-service│         │  Neo4j graph │      │   LGTM stack │
                       │  MCP + REST  │         │  Atlas memory│      │ Loki + Tempo │
                       └──────────────┘         │  + feedback  │      │   + Grafana  │
                                                └──────────────┘      └──────────────┘
                                                       ▲                     ▲
                                                       │                     │
                                          ┌────────────┴───┐         ┌───────┴────────┐
                                          │ incident-graph │         │ test-data-     │
                                          │    indexer     │         │   service      │
                                          │  (Python)      │         │ (Java)         │
                                          └────────────────┘         └────────────────┘
```

## Services

| Service | Port | Role |
|---|---|---|
| `investigation-service` | 8080 | LangGraph AI pipeline, HTTP + MCP server, feedback HTML form |
| `panel-service` | 8081 | Panel template catalogue (REST + MCP) |
| `test-data-service` | – | Pushes synthetic traces, logs, and Atlas tickets on startup |
| `incident-graph-indexer` | – | Python batch: LLM-extracts entities from Atlas tickets, writes graph to Neo4j |
| `neo4j` | 7474 / 7687 | Knowledge graph (Atlas memory + dashboard feedback memory) |
| `lgtm` (`grafana/otel-lgtm`) | 3000 / 3100 / 3200 / 4318 / 9090 | All-in-one observability stack |

## Quick start

Prerequisites: Docker, Ollama running on host (`gemma4` and `nomic-embed-text` pulled).

```bash
docker compose up
```

First boot takes a few minutes — the indexer runs gemma4 against each of the 25 synthetic
Atlas tickets to extract entities. Subsequent restarts are fast.

Then:

1. Open **http://localhost:3000** → "AI Investigation" folder → **AI Log Investigation** dashboard
2. Click the 🔍 button on any log row
3. A loading page appears, then redirects to a generated investigation dashboard
4. Submit feedback via the markdown link at the top of the dashboard — your learnings
   improve future investigations of similar errors

## Reindexing the Atlas graph

When prompts, embedding model, or extraction rules change, repump from scratch:

```bash
./reindex.sh        # Linux / macOS
.\reindex.ps1       # Windows
```

## Architecture details

See [`CLAUDE.md`](./CLAUDE.md) for the full architecture, schema, prompt design,
graph wiring, and operational notes.

## Tests

```bash
cd investigation-service && mvn verify
```

142 tests, JaCoCo line coverage gate at 85%.
