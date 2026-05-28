# AI-Powered Dynamic Investigation Dashboard — CLAUDE.md

## Project Overview

AI-powered investigation platform — an L2 engineer clicks an error log line in Grafana and gets a dashboard targeted at the likely problem, informed by past Atlas incidents and prior L2 feedback. Five services + Neo4j + real LGTM stack:

| Service | Port | Role |
|---|---|---|
| **`investigation-service`** | 8080 | LangGraph AI pipeline; HTTP `/api/v1/investigate` + MCP `/mcp/sse` + Feedback HTML form `/feedback` |
| **`panel-service`** | 8081 | Panel template catalogue (13 templates); REST `/api/panels` + MCP `/mcp/sse` |
| **`test-data-service`** | (none) | Pushes synthetic traces to Tempo, logs to Loki, and synthetic Atlas incidents to a shared volume for the indexer |
| **`incident-graph-indexer`** | (none) | Python batch — reads Atlas incidents, LLM-extracts entities+relationships, writes graph into Neo4j |
| **`lgtm`** | 3000/3100/3200/4318 | All-in-one Grafana + Loki + Tempo + Prometheus stack |
| **`neo4j`** | 7474/7687 | Knowledge graph: Atlas incident graph + DashboardMemory nodes (past L2 feedback) |

The investigation flow combines:
- **Atlas RAG (problem 2)** — historical incidents extracted into a Neo4j graph with entities (Services, ErrorPatterns, RootCauses, SystemComponents). Vector search seeds + graph traversal expands. Tells us: *"This kind of error has affected these services historically, with these root causes."*
- **Dashboard feedback RAG (problem 1)** — past L2 feedback captured via the in-dashboard `/feedback` form. Tells us: *"For similar errors, these panels were actually useful."*
- **Live observability** — Tempo for traces, Loki for live cross-service log search. Tells us: *"This error is currently happening in these services."*

All three sources feed two LLM calls: `ReasonNode` (services + query plans) and `PanelSelectionNode` (panels + arguments). Targeted at the existing ~140-microservice collateral management and margin call platform under appcode AT4278.

---

## Technology Stack

### Core
- **Java 17 + Spring Boot 3.x**
- **Spring WebFlux** — non-blocking throughout; all Tempo/Loki/Grafana calls are async via WebClient
- **Maven**

### AI / LLM
- **Spring AI 1.x** — primary LLM abstraction
  - `ChatClient` — single interface regardless of provider; provider switched via Spring profile
  - `PromptTemplate` — named-parameter prompt building for triage and panel selection prompts
  - Structured output via `.entity(TriageResult.class)` / `.entity(PanelSelectionResult.class)` — maps LLM JSON responses directly to Java records, no manual parsing
  - Built-in Micrometer instrumentation on every LLM call
- **Ollama** (`spring-ai-starter-model-ollama`) — local development; no cloud dependency; default model `gemma4`
- **Azure OpenAI** (`spring-ai-starter-model-azure-openai`) — production and cloud-based testing
- **MCP Server** (`spring-ai-starter-mcp-server-webflux`) — exposes the service as an MCP server over SSE (`/mcp/sse`), allowing Claude Desktop and any MCP-compatible client to call the investigation tools directly

### Graph Orchestration
- **LangGraph4j** — graph-based workflow orchestration
  - Each processing step is an explicit **node** with typed input/output state
  - Conditional edges route to Tempo node, Loki cross-service node, or both in parallel based on triage output
  - Parallel branch execution — TempoResolutionNode and LokiCrossServiceNode run concurrently when both are needed
  - All failure paths route to FallbackNode via error edges — no try/catch scattered across the pipeline
  - `InvestigationState` is the shared graph state passed between nodes

### Integration
- **Spring WebClient** — Tempo, Loki, Grafana HTTP API calls
- **Jackson** — panel template loading and Grafana dashboard JSON assembly

### Resilience
- **Resilience4j** — circuit breaker on all external calls (LLM via Spring AI, Tempo, Loki, Grafana)

### Observability
- **Micrometer + OpenTelemetry** — this service instruments its own operations; each LangGraph node emits a span

### Testing
- **JUnit 5 + Mockito** — unit tests for each node and service in isolation
- **WireMock** — stubs for LLM, Tempo, Loki, Grafana APIs in integration tests
- **JaCoCo** — coverage gate at 85%

---

## Development Profiles

`investigation-service` supports two LLM profiles. There is no longer an embedded `mock-data` profile — the `test-data-service` container handles all external stubs.

### LLM Profiles

| Profile | Provider | When to use |
|---|---|---|
| `ollama` | Ollama at `localhost:11434` | Local dev — no cloud cost, no credentials needed |
| `azure-openai` | Azure OpenAI | Production + cloud-based testing |

### Combinations

```
docker-compose (all 3 services, Ollama on host):
  SPRING_PROFILES_ACTIVE=ollama,real-data
  TEMPO_BASE_URL / LOKI_BASE_URL / GRAFANA_BASE_URL → test-data-service
  PANEL_SERVICE_URL → panel-service

Production:
  SPRING_PROFILES_ACTIVE=azure-openai,real-data
  (all URLs point to real backends)
```

### LLM Provider Config (`LlmConfig.java`)

```java
@Configuration
public class LlmConfig {

    @Bean
    @Profile("ollama")
    public ChatClient ollamaChatClient(OllamaChatModel model) {
        return ChatClient.builder(model)
            .defaultOptions(OllamaOptions.create().withTemperature(0.0f))
            .build();
    }

    @Bean
    @Profile("azure-openai")
    public ChatClient azureChatClient(AzureOpenAiChatModel model) {
        return ChatClient.builder(model).build();
    }
}
```

One `ChatClient` bean active at a time. All nodes (`TriageNode`, `PanelSelectionNode`) inject `ChatClient` — they never reference the provider directly.

### test-data-service (WireMock standalone)

`test-data-service/` is not a Spring Boot app — it is a directory of WireMock mappings run via the `wiremock/wiremock:3.6.0` Docker image. No code, no Dockerfile.

```
test-data-service/
├── mappings/          — WireMock stub mapping JSON files
│   ├── tempo-*.json
│   ├── loki-*.json
│   ├── grafana-publish.json
│   └── llm-chat-completions.json
└── __files/           — response body files
    ├── traces/
    ├── loki/
    ├── grafana/
    └── llm/
```

In docker-compose, `investigation-service` sets all backend URLs to `http://test-data-service:8080`.

---

## Core Design Principles

1. **LLM does two things only** — two focused LLM calls: (1) parse the log line to extract error category, traceId, and cross-service blast-radius potential, (2) select panels given real component data. No free-form reasoning leaks into dashboard assembly.
2. **Entry point is the raw log line** — the Grafana data link passes the full log line text. The LLM extracts all structured information from it. No pre-parsing required in Grafana config.
3. **Component resolution is always data-driven** — Tempo provides call-chain components, Loki provides blast-radius components. Both run in parallel when applicable. The LLM never guesses component names.
4. **Graph-orchestrated, not pipeline-chained** — LangGraph4j owns the control flow. Nodes are independent, conditional routing is explicit, parallel branches are native. Fallback is a graph edge, not a try/catch wrapper.
5. **Panel library is the single source of truth** — panels are pre-built JSON templates. LLM selects panel IDs. Assembly injects variables. No panel generation.
6. **Fail safe** — any node failure routes to FallbackNode via error edge. L2 always gets a usable dashboard URL, never a 5xx.
7. **All datasources are fixed** — IBLoki, IBTempo, IBMimir. No dynamic datasource resolution needed.
8. **All services share `appcode` variable** — always passed through and injected into every panel.

---

## Request Flow

```
Grafana Data Link click (on any error log line)
  → GET /api/v1/investigate
      ?logLine=2024-01-15+10:30:00.123+ERROR+[collateral-service,abc123def456,1]+NullPointerException...
      &service=collateral-service
      &appcode=COLL
      &timestamp=2024-01-15T10:30:00Z

  → InvestigationGraph (LangGraph4j)
```

### Graph

```
START
  │
  ▼
[AtlasMemoryNode]   ──── Neo4j vector search on Atlas incident graph
  │                       (always fires; failures → empty result)
  ▼
[ReasonNode]        ──── LLM Call 1 (with log line + Atlas memories + service catalog)
  │                       Emits: errorCategory, errorPattern, traceId, likelyServices,
  │                              confidence, tempoPlan, lokiPlan, reasoning
  │                       Fail fast on AI error → 503 with structured error body.
  ▼
  ├──→ [TempoResolutionNode]    driven by reason.tempoPlan
  │       - get-by-id when traceId present
  │       - search recent error traces when traceId missing (RMI gap)
  │
  ├──→ [LokiCrossServiceNode]   driven by reason.lokiPlan + confidence
  │       - fires if plan.query=true OR confidence < 0.6 (conservative gate)
  │       - LLM provides structured intent (pattern, services, time window);
  │         code builds the actual LogQL — LLM never writes raw queries
  │
  └──→ [DashboardMemoryNode]    Neo4j vector search on past L2 feedback
          (always fires; results used at panel selection, not service reasoning)
  │
  ▼ (all three converge — graceful degradation, none ever throw)
[ComponentMergeNode]   union of reason.likelyServices + trace + loki components
  │
  ▼
[PanelSelectionNode]   LLM Call 2 — panels + arguments
  │                    Prompt blocks: panels catalog · Atlas memories
  │                                  · dashboard memories · cross-service logs
  │                    Fail fast on AI error → 503.
  ▼
[DashboardAssemblyNode]  validate (no panels → throw)
  ▼
[PublishNode]            POST to Grafana → dashboardUrl
  ▼
 END  HTTP 302 → dashboardUrl
```

No fallback node. AI / assembly / publish failures propagate as 503 with structured error.
Data fetch failures (Atlas, Tempo, Loki, DashboardMemory) degrade silently to empty.

### InvestigationState

```java
public record InvestigationState(
    InvestigationRequest    request,
    AtlasMemoryResult       atlasMemory,        // set by AtlasMemoryNode
    DashboardMemoryResult   dashboardMemory,    // set by DashboardMemoryNode
    ReasonResult            reason,             // set by ReasonNode (LLM Call 1)
    List<String>            traceComponents,    // set by TempoResolutionNode
    List<String>            lokiComponents,     // set by LokiCrossServiceNode
    List<CrossServiceLogEntry> crossServiceLogs,// set by LokiCrossServiceNode
    List<String>            mergedComponents,   // set by ComponentMergeNode
    PanelSelectionResult    panels,             // set by PanelSelectionNode
    String                  dashboardUrl,       // set by PublishNode
    String                  availablePanels     // set by controller/MCP entry
) {}
```

---

## Package Structure

### investigation-service

```
com.firm.investigation
├── api
│   ├── InvestigationController.java        // GET /api/v1/investigate — fail-fast 503 on AI failure
│   └── dto/InvestigationRequest.java
├── catalog
│   ├── ServiceCatalogProvider.java         // canonical service list per appcode
│   └── YamlServiceCatalogProvider.java     // YAML-backed impl; swap for DB-backed later
├── reason
│   ├── ReasonResult.java                   // structured output of LLM Call 1
│   ├── ReasonPromptBuilder.java            // prompt for combined triage + reasoning
│   └── ReasonService.java                  // makes the LLM call, validates services vs catalog
├── memory
│   ├── AtlasMemoryClient.java              // Cypher vector + graph traversal on Atlas graph
│   ├── AtlasMemoryResult.java
│   ├── DashboardMemoryClient.java          // vector search on past L2 feedback
│   └── DashboardMemoryResult.java
├── feedback
│   ├── FeedbackController.java             // GET/POST /feedback — HTML form for L2 learnings
│   ├── GrafanaDashboardReader.java         // fetches final dashboard state from Grafana
│   ├── DashboardMemoryWriter.java          // sanitise + embed + write DashboardMemory node
│   └── Sanitizer.java                      // strips PII (ISINs, account #s, amounts, emails)
├── component
│   ├── TempoClient.java                    // get-by-id + search-by-service
│   ├── LokiCrossServiceClient.java         // structured-intent → safe LogQL
│   └── PanelServiceClient.java
├── panel
│   ├── PanelSelectionService.java
│   ├── PanelTemplate.java
│   ├── PanelSelectionResult.java
│   └── CrossServiceLogEntry.java
├── assembly
│   ├── DashboardAssemblyService.java       // prepends feedback panel + adds metadata tags
│   └── VariableInjector.java
├── grafana
│   └── GrafanaPublishService.java
├── llm
│   └── PanelSelectionPromptBuilder.java    // includes Atlas + dashboard memory + cross-service logs
├── graph
│   ├── InvestigationState.java             // 11-field record threaded through nodes
│   └── node
│       ├── AtlasMemoryNode.java
│       ├── ReasonNode.java                 // LLM Call 1 (sequential, after AtlasMemoryNode)
│       ├── TempoResolutionNode.java        // driven by reason.tempoPlan
│       ├── LokiCrossServiceNode.java       // driven by reason.lokiPlan + confidence gate
│       ├── DashboardMemoryNode.java
│       ├── ComponentMergeNode.java         // union likelyServices + trace + loki
│       ├── PanelSelectionNode.java         // LLM Call 2
│       ├── DashboardAssemblyNode.java      // throws if no panels
│       └── PublishNode.java
├── mcp
│   └── InvestigationMcpService.java        // MCP tools: listPanels() + investigate()
└── config
    ├── LlmConfig.java                      // @Profile("ollama") and @Profile("azure-openai")
    ├── Neo4jConfig.java                    // Bolt Driver bean
    ├── LangGraphConfig.java                // graph wiring
    ├── McpConfig.java                      // @Lazy circuit-breaker on InvestigationMcpService
    ├── ResilienceConfig.java
    └── InvestigationProperties.java        // tempo, loki, grafana, panelService, neo4j, feedback, serviceCatalog
```

### panel-service

```
com.firm.panel
├── PanelServiceApplication.java
├── PanelTemplate.java
├── PanelLibrary.java                       // @PostConstruct loads classpath:panels/*.json
└── PanelController.java                    // GET /api/panels (+ /descriptions, /{id})

resources/panels/  — 13 panel JSON templates
```

### test-data-service

```
com.firm.testdata
├── TestDataServiceApplication.java
├── TestDataRunner.java                     // ApplicationRunner — orchestrates push
├── TraceGenerator.java                     // OTel SDK → OTLP HTTP to Tempo (5 scenarios)
├── LokiLogGenerator.java                   // pushes logs via Loki push API
├── AtlasIncidentsExporter.java             // writes synthetic atlas/incidents.json
│                                              to shared volume for indexer
└── TestDataProperties.java

resources/atlas/incidents.json              // 25 synthetic Atlas tickets (NPE, OOM, MQ,
                                              DB, business exceptions, all AT4278)
```

### incident-graph-indexer (Python)

```
incident-graph-indexer/
├── pyproject.toml                          // neo4j + requests
├── Dockerfile                              // python:3.11-slim
└── src/indexer/
    ├── main.py                             // wait for Neo4j, load atlas JSON, extract, write
    ├── config.py                           // env-driven LLM provider (ollama / azure)
    ├── atlas_loader.py                     // load incidents.json
    ├── llm_client.py                       // entity+relationship extraction (custom prompts);
    │                                          embeds via Ollama nomic / Azure embeddings
    └── neo4j_writer.py                     // writes Incident + Service + ErrorPattern +
                                               RootCause + SystemComponent nodes + derived
                                               (CO_OCCURS_WITH, TYPICALLY_AFFECTS) edges
```

### Neo4j schema

```
Atlas subgraph (populated by incident-graph-indexer):
  (Incident { ticketId, title, description, closureNotes, createdAt, resolvedAt,
              priority, appcode, embedding[768] })
  (Service { name })
  (ErrorPattern { signature })
  (RootCause { category, description })
  (SystemComponent { name, type })
  (Appcode { code })
  (Incident)-[:BELONGS_TO]->(Appcode)
  (Incident)-[:AFFECTED]->(Service)
  (Incident)-[:HAS_ERROR_PATTERN]->(ErrorPattern)
  (Incident)-[:HAS_ROOT_CAUSE]->(RootCause)
  (Incident)-[:MENTIONS]->(SystemComponent)
  (Service)-[:CO_OCCURS_WITH {weight}]->(Service)            ← derived
  (ErrorPattern)-[:TYPICALLY_AFFECTS {count}]->(Service)     ← derived

DashboardMemory subgraph (populated by FeedbackController submissions):
  (DashboardMemory { id, dashboardUid, logLine, errorCategory, service, appcode,
                      finalPanelDescriptors[], feedbackText, createdAt, embedding[768] })
  Vector index: dashboard_memory_embedding (cosine, 768 dim)
  Vector index: incident_embedding         (cosine, 768 dim)
```

### Knowledge flows

```
Atlas (production) ──nightly batch──→ incident-graph-indexer ──→ Neo4j Atlas subgraph
                                                                          │
                                                                          ▼
                       investigation-service ───AtlasMemoryNode──────────┤
                                                                          │
                                                                          ▼
                                                                  ReasonNode prompt
                                                                  (informs service inference)

L2 finishes investigating in Grafana → clicks /feedback link → submits learnings
                                                                          │
                                                                          ▼
                                                         FeedbackController
                                                                          │
                                                                          ▼
                                                         DashboardMemoryWriter
                                                                          │
                                                                          ▼
                                                         Neo4j DashboardMemory subgraph
                                                                          │
                                                                          ▼
                                            DashboardMemoryNode (in next investigation)
                                                                          │
                                                                          ▼
                                                         PanelSelection prompt
                                                         (informs panel choices)
```

---

## Operational notes

### Reindexing the Atlas knowledge graph

When prompts, embedding model, or extraction rules change, re-pump from scratch:

```bash
./reindex.sh         # Linux/Mac
.\reindex.ps1        # Windows
```

This sets `REINDEX=true` on the `incident-graph-indexer` container so it wipes Neo4j before loading.

### Embedding model coupling

Vectors are **not portable** between embedding models. Local dev (`nomic-embed-text`, 768 dim) and cloud (`text-embedding-3-small`) produce incomparable vectors. Both the Python indexer AND the Java retrievers (`AtlasMemoryClient`, `DashboardMemoryClient`, `DashboardMemoryWriter`) must use the same model per environment. Driven by the active Spring profile (`ollama` / `azure-openai`) and the `EMBEDDING_PROVIDER` env var for the indexer — keep them in lockstep.

Changing the model = full re-pump (`./reindex.sh`).

### Feedback capture flow

Each generated dashboard includes:
1. A markdown panel at row 0 with a deeplink to `${feedback.baseUrl}/feedback?uid=<uid>`
2. Tags: `appcode-X`, `service-Y`, `category-Z`, `traceid-W` — for the feedback endpoint to recover metadata
3. Description field: the original (unsanitised at capture time) log line

When the L2 submits feedback:
1. `FeedbackController.submit` fetches the current dashboard JSON from Grafana
2. Extracts tags + description + final panel list
3. Sanitises log line + feedback text (PII stripping)
4. Embeds the sanitised log line
5. Writes a `DashboardMemory` node into Neo4j

Append-only — resubmissions create new nodes, never overwrite. Useful for capturing iteration on long investigations.

### Fail-fast semantics

| Failure type | Behaviour |
|---|---|
| Data fetch (Atlas, Tempo, Loki, DashboardMemory) | Graceful degradation — node catches its own exception, returns empty result, pipeline continues |
| AI calls (Reason, PanelSelection) | Propagates → controller maps to 503 with structured error body including `stage` classification |
| Mechanical (Assembly, Publish) | Propagates → 503 |

No fallback dashboard. An incomplete investigation surfaces as an explicit error so the L2 knows to switch to manual mode in Grafana, rather than wasting time on a half-built result.

---

## Historical / legacy implementation notes

The sections below describe an earlier iteration of the design (pre-Atlas-RAG, pre-feedback-loop, with `TriageNode` and `FallbackNode`). They are kept as background context — the current authoritative description of the pipeline is the **Graph** + **Package Structure** sections above. Where details disagree, the sections above are correct.

## Stage 1 — TriageNode

### Spring AI wiring

`TriageService` uses Spring AI `ChatClient` with structured output. No manual JSON parsing.
`TriageNode` delegates to `TriageService` and writes the result into `InvestigationState`.

```java
@Service
public class TriageService {
    private final ChatClient chatClient;
    private final TriagePromptBuilder promptBuilder;

    public TriageResult triage(InvestigationRequest request) {
        return chatClient.prompt()
            .system(promptBuilder.systemPrompt())
            .user(promptBuilder.userPrompt(request))
            .call()
            .entity(TriageResult.class);  // Spring AI maps JSON → record automatically
    }
}
```

### Prompt contract (build in `TriagePromptBuilder`)

```
System:
You are an observability triage assistant for a Java-based investment banking platform
with 140 microservices. Your job is to parse a raw log line, classify the error category,
extract the traceId if present, and determine whether this error has the potential to
impact multiple services (shared resource failure). Log lines may be in various formats
(plain text, JSON, logfmt, or Spring Boot default with MDC fields).
Respond ONLY with valid JSON. No explanation. No markdown.

User:
Parse this log line and classify the error for investigation.

Service: {service}
Timestamp: {timestamp}
Raw Log Line:
{logLine}

Respond with exactly this JSON structure:
{
  "errorCategory": one of [NPE, OOM, TIMEOUT, DB_ERROR, MQ_ERROR, CONNECTION_REFUSED, THREAD_DEADLOCK, UNKNOWN],
  "lookBackMinutes": integer between 2 and 60,
  "traceId": "extracted trace ID string" or null,
  "crossServicePotential": true or false,
  "errorPattern": "most distinctive error string to grep across services" or null,
  "reason": "one sentence explanation"
}

Rules:
- errorCategory: classify based on the exception type and message in the log line
- lookBackMinutes: tight window for NPE (2-5), longer for OOM/memory (30-60), medium for timeouts (10-15)
- traceId: extract from MDC pattern [service,traceId,spanId], JSON field "traceId", or any hex string
  labelled as trace/correlation ID. Set null if not detectable.
- crossServicePotential: true if the error implies a shared resource is failing and other services
  are likely also affected right now — examples: database down, MQ broker unavailable, shared cache
  failure, DNS failure, certificate expiry. false if the error is self-contained — examples: NPE,
  OOM, thread deadlock, business logic exception.
- errorPattern: if crossServicePotential is true, extract the most distinctive substring from the
  exception class or message that would appear in other services' logs for the same root cause
  (e.g. "SybSQLException", "MQRC_Q_FULL", "Connection refused"). Set null if crossServicePotential
  is false.
```

### TriageResult model

```java
public record TriageResult(
    String errorCategory,          // NPE | OOM | TIMEOUT | DB_ERROR | MQ_ERROR | etc
    int lookBackMinutes,
    String traceId,                // extracted from log line — null if not present
    boolean crossServicePotential, // LLM judges: is a shared resource likely failing?
    String errorPattern,           // grep pattern for Loki cross-service search — null if not applicable
    String reason
) {}
```

---

## Stage 2 — Component Resolution (Parallel Nodes)

TempoResolutionNode and LokiCrossServiceNode run in **parallel** when both are triggered.
ComponentMergeNode waits for both to complete before proceeding.

---

### TempoResolutionNode

Triggered when: `triage.traceId() != null`

**TempoClient:**
- Endpoint: `GET {tempoBaseUrl}/api/traces/{traceId}`
- Response: standard Jaeger/Tempo JSON trace format
- Extract: all unique `process.serviceName` values from spans

```java
// On success: state.traceComponents = [all services in call chain]
// On failure: state.traceComponents = [] — error edge routes to ComponentMergeNode
//             ComponentMergeNode will use lokiComponents or fall back to [originatingService]
```

---

### LokiCrossServiceNode

Triggered when: `triage.crossServicePotential() == true`

**LokiCrossServiceClient:**
- Endpoint: `GET {lokiBaseUrl}/loki/api/v1/query_range`
- Query: `{appcode="{appcode}"} |= "{errorPattern}" | level = "ERROR"`
- Window: `timestamp - lookBackMinutes` → `timestamp + 5min`
  (forward window is essential — cascading failures arrive after the initial error)
- Limit: 100 lines total across all services
- Extract: unique service names from Loki stream labels → `lokiComponents`
- Collect: up to 3 representative log lines per affected service → `crossServiceLogs`
  (these are passed verbatim to the PanelSelectionNode LLM for context)

```java
// On success: state.lokiComponents = [all services showing the same error pattern]
//             state.crossServiceLogs = [CrossServiceLogEntry(service, logLine), ...]
// On failure: state.lokiComponents = [] — error edge routes to ComponentMergeNode
```

---

### ComponentMergeNode

Always runs after both parallel nodes complete (or error).

```java
Set<String> merged = new LinkedHashSet<>();
merged.addAll(state.traceComponents());
merged.addAll(state.lokiComponents());
if (merged.isEmpty()) {
    merged.add(state.request().service()); // final fallback
}
// state.mergedComponents = List.copyOf(merged)
// Ordering: traceComponents first (call chain), then additional blast-radius services from Loki
```

---

## Stage 3 — PanelSelectionNode

### Panel library (define in `resources/panels/`)

Create the following panel template JSON files. Each must use Grafana variable syntax (`$variable`) for injectable values.

**Required panels to implement:**

| Panel ID | Datasource | Key Variables | Relevant Categories |
|---|---|---|---|
| `trace_waterfall` | IBTempo | `$traceId` | TIMEOUT, MQ_ERROR, CONNECTION_REFUSED |
| `log_stream` | IBLoki | `$service`, `$appcode`, `$timeFrom`, `$timeTo` | ALL |
| `heap_by_component` | IBMimir | `$components`, `$appcode`, `$timeFrom`, `$timeTo` | NPE, OOM, THREAD_DEADLOCK |
| `latency_p99` | IBMimir | `$components`, `$appcode`, `$timeFrom`, `$timeTo` | TIMEOUT, CONNECTION_REFUSED |
| `latency_p95` | IBMimir | `$components`, `$appcode`, `$timeFrom`, `$timeTo` | TIMEOUT |
| `error_rate` | IBMimir | `$components`, `$appcode`, `$timeFrom`, `$timeTo` | ALL |
| `thread_pool_active` | IBMimir | `$components`, `$appcode`, `$timeFrom`, `$timeTo` | THREAD_DEADLOCK, NPE |
| `db_connection_pool` | IBMimir | `$service`, `$appcode`, `$timeFrom`, `$timeTo` | DB_ERROR |
| `db_query_latency` | IBMimir | `$service`, `$appcode`, `$timeFrom`, `$timeTo` | DB_ERROR |
| `mq_consumer_lag` | IBMimir | `$service`, `$appcode`, `$timeFrom`, `$timeTo` | MQ_ERROR |
| `mq_dead_letter_count` | IBMimir | `$service`, `$appcode`, `$timeFrom`, `$timeTo` | MQ_ERROR |
| `mq_queue_depth` | IBMimir | `$service`, `$appcode`, `$timeFrom`, `$timeTo` | MQ_ERROR |
| `gc_pause_duration` | IBMimir | `$components`, `$appcode`, `$timeFrom`, `$timeTo` | OOM, NPE |

**PanelTemplate model:**

```java
public record PanelTemplate(
    String panelId,
    String description,
    String datasource,           // IBLoki | IBTempo | IBMimir
    List<String> requiredVariables,
    List<String> categories,     // which error categories this panel suits
    JsonNode panelJson           // raw Grafana panel definition
) {}
```

**Panel library loading:** Load all JSON files from `classpath:panels/` on startup. Expose a method `List<String> availablePanelIds()` for the prompt builder.

### Spring AI wiring

`PanelSelectionService` follows the same pattern as `TriageService`.
`PanelSelectionNode` passes the full merged state — including cross-service log entries.

```java
@Service
public class PanelSelectionService {
    private final ChatClient chatClient;
    private final PanelSelectionPromptBuilder promptBuilder;
    private final PanelLibrary panelLibrary;

    public PanelSelectionResult selectPanels(InvestigationState state) {
        return chatClient.prompt()
            .system(promptBuilder.systemPrompt())
            .user(promptBuilder.userPrompt(state, panelLibrary.availablePanelIds()))
            .call()
            .entity(PanelSelectionResult.class);
    }
}
```

### Prompt contract (build in `PanelSelectionPromptBuilder`)

```
System:
You are selecting Grafana dashboard panels for incident investigation.
Respond ONLY with valid JSON. No explanation. No markdown.

User:
Select the most relevant panels for this incident from the available panel library.

Error Category: {errorCategory}
Originating Service: {service}
Affected Components: {mergedComponents as comma-separated list}
Original Log Line: {logLine}
{if crossServiceLogs non-empty:
"Cross-Service Impact — other services showing the same error pattern:
{foreach entry in crossServiceLogs}
  [{entry.service}]: {entry.logLine}
{end}"}

Available panels:
{panelId}: {description} — suits: {categories}
... (one per line)

Rules:
- Always include log_stream
- Include trace_waterfall if components contains more than one service, or if errorCategory
  is TIMEOUT, MQ_ERROR, or CONNECTION_REFUSED
- If cross-service impact is shown, scope $components panels to all affected services, not just
  the originating service
- Maximum 6 panels total — prioritise specificity
- $components must use the provided mergedComponents list exactly

Respond with exactly this JSON:
{
  "selectedPanels": [
    {
      "panelId": "log_stream",
      "variables": {
        "service": "collateral-service",
        "appcode": "COLL",
        "timeFrom": "2024-01-15T10:25:00Z",
        "timeTo": "2024-01-15T10:35:00Z"
      }
    }
  ],
  "dashboardTitle": "NPE Investigation — collateral-service — 2024-01-15 10:30"
}
```

---

## Stage 4 — DashboardAssemblyNode

### DashboardAssemblyService

- Load each selected `PanelTemplate` from `PanelLibrary`
- For each panel, call `VariableInjector.inject(panelJson, variables)`
- `VariableInjector` does a recursive JSON string replacement: `$variableName` → `value`
- Assign auto-incrementing panel IDs and grid positions (2-column layout, each panel 12 units wide, 8 units tall)
- Compose final Grafana dashboard JSON with:
  - `title` from LLM output
  - `time.from` and `time.to` from resolved window
  - `panels[]` array
  - `tags: ["ai-investigation", "auto-generated"]`
  - `uid`: generated as `inv-{traceId}-{timestamp-epoch}` if traceId available, else `inv-{service}-{timestamp-epoch}`, truncated to 40 chars

### Grid layout logic

```
Panel 0: x=0,  y=0,  w=24, h=8  (log_stream — always full width, always first)
Panel 1: x=0,  y=8,  w=12, h=8
Panel 2: x=12, y=8,  w=12, h=8
Panel 3: x=0,  y=16, w=12, h=8
Panel 4: x=12, y=16, w=12, h=8
Panel 5: x=0,  y=24, w=24, h=8  (if 6th panel, full width)
```

---

## Stage 5 — PublishNode

### GrafanaPublishService

```
POST {grafanaBaseUrl}/api/dashboards/db
Authorization: Bearer {grafanaToken}
Content-Type: application/json

{
  "dashboard": { ...assembled dashboard JSON... },
  "overwrite": true,
  "folderUid": "{investigationFolderUid}",
  "message": "AI-generated investigation dashboard"
}
```

- On success: extract `url` from response, return to controller
- On failure: throw `GrafanaPublishException`, trigger fallback

---

## LangGraph Graph Wiring (`InvestigationGraph`)

```java
@Configuration
public class LangGraphConfig {

    @Bean
    public CompiledGraph<InvestigationState> investigationGraph(
            TriageNode triageNode,
            TempoResolutionNode tempoNode,
            LokiCrossServiceNode lokiNode,
            ComponentMergeNode mergeNode,
            PanelSelectionNode panelNode,
            DashboardAssemblyNode assemblyNode,
            PublishNode publishNode,
            FallbackNode fallbackNode) {

        return StateGraph.builder(InvestigationState.class)
            .addNode("triage",             triageNode)
            .addNode("tempo_resolution",   tempoNode)
            .addNode("loki_cross_service", lokiNode)
            .addNode("component_merge",    mergeNode)
            .addNode("panel_selection",    panelNode)
            .addNode("assembly",           assemblyNode)
            .addNode("publish",            publishNode)
            .addNode("fallback",           fallbackNode)

            .addEdge(START, "triage")

            // Conditional fan-out from triage — both branches may fire in parallel
            .addConditionalEdges("triage", state -> {
                List<String> next = new ArrayList<>();
                if (state.triage().traceId() != null)             next.add("tempo_resolution");
                if (state.triage().crossServicePotential())       next.add("loki_cross_service");
                if (next.isEmpty())                               next.add("component_merge");
                return next;
            })

            // Both parallel branches converge at component_merge
            .addEdge("tempo_resolution",   "component_merge")
            .addEdge("loki_cross_service", "component_merge")

            .addEdge("component_merge",    "panel_selection")
            .addEdge("panel_selection",    "assembly")
            .addEdge("assembly",           "publish")
            .addEdge("publish",            END)

            // Any node failure routes to fallback
            .addErrorEdge("triage",             "fallback")
            .addErrorEdge("panel_selection",    "fallback")
            .addErrorEdge("assembly",           "fallback")
            .addErrorEdge("publish",            "fallback")
            .addEdge("fallback", END)

            .compile();
    }
}
```

---

## Fallback Behaviour

`FallbackDashboardService` handles any stage failure:
- Creates a minimal dashboard with `log_stream` panel only
- Scoped to originating service + timestamp
- Title: `"[FALLBACK] Investigation — {service} — {timestamp}"`
- Publishes same way via GrafanaPublishService
- Logs the failure stage and reason with ERROR level

This ensures L2 always gets redirected somewhere useful, never a 500.

---

## Configuration

### `application.yml` — shared defaults (all profiles)

```yaml
investigation:
  tempo:
    base-url: ${TEMPO_BASE_URL:http://localhost:8089}
    timeout-seconds: 5
  loki:
    base-url: ${LOKI_BASE_URL:http://localhost:8089}
    timeout-seconds: 5
  grafana:
    base-url: ${GRAFANA_BASE_URL:http://localhost:8089}
    token: ${GRAFANA_TOKEN:mock-token}
    investigation-folder-uid: ${GRAFANA_FOLDER_UID:mock-folder}
  fallback:
    enabled: true
```

### `application-ollama.yml` — Ollama provider

```yaml
spring:
  ai:
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        options:
          model: ${OLLAMA_MODEL:llama3.1}
          temperature: 0.0
```

### `application-azure-openai.yml` — Azure OpenAI provider

```yaml
spring:
  ai:
    azure:
      openai:
        endpoint: ${AZURE_OPENAI_ENDPOINT}          # https://{resource}.openai.azure.com/
        api-key: ${AZURE_OPENAI_API_KEY}
        chat:
          options:
            deployment-name: ${AZURE_OPENAI_DEPLOYMENT:gpt-4o}
            temperature: 0.0
```

### `application-mock-data.yml` — WireMock data sources

```yaml
investigation:
  tempo:
    base-url: http://localhost:8089
  loki:
    base-url: http://localhost:8089
  grafana:
    base-url: http://localhost:8089
    token: mock-token
    investigation-folder-uid: mock-folder
```

### `application-real-data.yml` — live observability stack

```yaml
investigation:
  tempo:
    base-url: ${TEMPO_BASE_URL}
  loki:
    base-url: ${LOKI_BASE_URL}
  grafana:
    base-url: ${GRAFANA_BASE_URL}
    token: ${GRAFANA_TOKEN}
    investigation-folder-uid: ${GRAFANA_FOLDER_UID}
```

---

## Grafana Data Link Configuration

On any existing Grafana **Logs panel** (Loki datasource), add a Data Link. No pre-parsing
of the log line is needed — the full raw line is passed directly to the service.

```
Name: Investigate with AI
URL:  http://investigation-service/api/v1/investigate?logLine=${__data.fields.line}&service=${__data.fields.labels.service}&appcode=${__data.fields.labels.appcode}&timestamp=${__data.fields.Time}
Open in new tab: true
```

Grafana URL-encodes `${__data.fields.line}` automatically. The `logLine` parameter will
contain the full text of the clicked log entry, regardless of format (plain text, JSON,
Spring Boot MDC, logfmt). The service extracts all structure from it server-side via LLM.

**No `traceId` field is required in the data link.** Whether a traceId is needed, and
how to obtain it, is decided by the service after LLM triage — not by Grafana config.

---

## Test Data Requirements

### Unit Test Data (`src/test/resources/testdata/`)

#### `triage/`

**`npe_triage_request.json`** — NPE in collateral service (Spring Boot MDC format, traceId present)
```json
{
  "service": "collateral-service",
  "appcode": "COLL",
  "timestamp": "2024-01-15T10:30:00Z",
  "logLine": "2024-01-15 10:30:00.123 ERROR [collateral-service,abc123def456,7a3f1b] [http-nio-8080-exec-3] c.f.c.CollateralCalculator - java.lang.NullPointerException: null\n\tat com.firm.collateral.CollateralCalculator.calculateInitialMargin(CollateralCalculator.java:247)"
}
```

**`timeout_triage_request.json`** — Timeout involving multiple services (traceId present in MDC)
```json
{
  "service": "margin-call-service",
  "appcode": "MRGN",
  "timestamp": "2024-01-15T11:00:00Z",
  "logLine": "2024-01-15 11:00:00.456 ERROR [margin-call-service,xyz789abc123,4d2e9c] [http-nio-8080-exec-7] c.f.m.MarginCallService - java.net.SocketTimeoutException: Read timed out after 30000ms\n\tat com.firm.margin.client.CollateralServiceClient.getPositions(CollateralServiceClient.java:112) calling collateral-service /api/positions"
}
```

**`db_error_triage_request.json`** — DB error (traceId present)
```json
{
  "service": "trade-enrichment-service",
  "appcode": "TRADE",
  "timestamp": "2024-01-15T09:15:00Z",
  "logLine": "2024-01-15 09:15:00.789 ERROR [trade-enrichment-service,def456ghi789,1a2b3c] [db-pool-3] c.f.t.TradeRepository - com.sybase.jdbc4.jdbc.SybSQLException: JZ006: Caught IOException: Connection reset by peer"
}
```

**`mq_error_triage_request.json`** — MQ error (traceId present)
```json
{
  "service": "margin-event-consumer",
  "appcode": "MRGN",
  "timestamp": "2024-01-15T08:45:00Z",
  "logLine": "2024-01-15 08:45:00.321 ERROR [margin-event-consumer,ghi789jkl012,5e6f7a] [mq-listener-1] c.f.m.MarginEventConsumer - com.ibm.mq.MQException: MQRC_Q_FULL (reason=2053) queue=MARGIN.CALL.REQUEST.QUEUE"
}
```

**`oom_triage_request.json`** — OOM (no traceId — JVM error pre-empts MDC logging)
```json
{
  "service": "risk-aggregator-service",
  "appcode": "RISK",
  "timestamp": "2024-01-15T07:30:00Z",
  "logLine": "2024-01-15 07:30:00.001 ERROR [risk-aggregator-service] [GC-worker-2] c.f.r.PositionAggregator - java.lang.OutOfMemoryError: Java heap space\n\tat com.firm.risk.PositionAggregator.aggregate(PositionAggregator.java:891)"
}
```

#### `triage/expected_llm_responses/`

**`npe_triage_response.json`** — NPE: self-contained, no cross-service potential
```json
{
  "errorCategory": "NPE",
  "lookBackMinutes": 5,
  "traceId": "abc123def456",
  "crossServicePotential": false,
  "errorPattern": null,
  "reason": "NullPointerException in CollateralCalculator internal method"
}
```

**`timeout_triage_response.json`** — Timeout: Tempo trace covers call chain, no blast-radius search needed
```json
{
  "errorCategory": "TIMEOUT",
  "lookBackMinutes": 15,
  "traceId": "xyz789abc123",
  "crossServicePotential": false,
  "errorPattern": null,
  "reason": "SocketTimeoutException calling downstream — Tempo trace captures the full call chain"
}
```

**`db_error_triage_response.json`** — DB error: shared Sybase instance likely affecting multiple services
```json
{
  "errorCategory": "DB_ERROR",
  "lookBackMinutes": 10,
  "traceId": "def456ghi789",
  "crossServicePotential": true,
  "errorPattern": "SybSQLException",
  "reason": "Sybase connection error on shared DB — other services on same DB are likely also failing"
}
```

**`mq_error_triage_response.json`** — MQ error: shared broker, all producers and consumers affected
```json
{
  "errorCategory": "MQ_ERROR",
  "lookBackMinutes": 20,
  "traceId": "ghi789jkl012",
  "crossServicePotential": true,
  "errorPattern": "MQRC_Q_FULL",
  "reason": "IBM MQ queue full on shared broker — upstream producers and downstream consumers both impacted"
}
```

**`oom_triage_response.json`** — OOM: self-contained, no cross-service potential, no traceId
```json
{
  "errorCategory": "OOM",
  "lookBackMinutes": 45,
  "traceId": null,
  "crossServicePotential": false,
  "errorPattern": null,
  "reason": "Java heap space exhausted — self-contained JVM failure, no shared resource involved"
}
```

#### `cross_service_loki/`

**`db_error_loki_response.json`** — Loki response for DB_ERROR cross-service search (`|= "SybSQLException"`), showing 3 affected services:
```json
{
  "status": "success",
  "data": {
    "resultType": "streams",
    "result": [
      {
        "stream": { "service": "trade-enrichment-service", "appcode": "TRADE", "level": "ERROR" },
        "values": [["1705316400000000000", "2024-01-15 09:15:00.789 ERROR ... SybSQLException: JZ006: Connection reset"]]
      },
      {
        "stream": { "service": "collateral-service", "appcode": "COLL", "level": "ERROR" },
        "values": [["1705316405000000000", "2024-01-15 09:15:05.123 ERROR ... SybSQLException: JZ006: Connection reset"]]
      },
      {
        "stream": { "service": "risk-aggregator-service", "appcode": "RISK", "level": "ERROR" },
        "values": [["1705316408000000000", "2024-01-15 09:15:08.456 ERROR ... SybSQLException: JZ006: Connection reset"]]
      }
    ]
  }
}
```

**`mq_error_loki_response.json`** — Loki response for MQ_ERROR cross-service search (`|= "MQRC_Q_FULL"`), showing producer and consumer services both affected.

#### `traces/`

**`npe_trace.json`** — single-span trace for the NPE scenario. Only collateral-service appears. Demonstrates that a single-service error naturally produces components = [1 service].

**`timeout_trace.json`** — realistic Tempo trace response for the timeout scenario, with spans across margin-call-service, collateral-service, trade-enrichment-service, position-service. Each span must have `process.serviceName` set correctly. Include one span with error=true.

**`mq_trace.json`** — trace for MQ scenario spanning margin-event-consumer, margin-call-service, settlement-service.

**`db_error_trace.json`** — single-span trace for DB error scenario. Only trade-enrichment-service appears.

**`oom_no_trace.json`** — not a trace file; documents the no-traceId OOM scenario where Tempo is never called and components falls back to [risk-aggregator-service].

#### `panel_selection/expected/`

**`npe_panel_selection.json`** — expected LLM panel selection for NPE
```json
{
  "selectedPanels": [
    { "panelId": "log_stream", "variables": { "service": "collateral-service", "appcode": "COLL", "timeFrom": "2024-01-15T10:25:00Z", "timeTo": "2024-01-15T10:35:00Z" } },
    { "panelId": "heap_by_component", "variables": { "components": "collateral-service", "appcode": "COLL", "timeFrom": "2024-01-15T10:25:00Z", "timeTo": "2024-01-15T10:35:00Z" } },
    { "panelId": "thread_pool_active", "variables": { "components": "collateral-service", "appcode": "COLL", "timeFrom": "2024-01-15T10:25:00Z", "timeTo": "2024-01-15T10:35:00Z" } }
  ],
  "dashboardTitle": "NPE Investigation — collateral-service — 2024-01-15 10:30"
}
```

**`timeout_panel_selection.json`** — expected for timeout scenario (must include trace_waterfall, latency_p99, error_rate with all 3 resolved components)

**`mq_error_panel_selection.json`** — must include mq_consumer_lag, mq_dead_letter_count, trace_waterfall

#### `grafana/`

**`publish_success_response.json`** — mock Grafana API response
```json
{
  "id": 42,
  "uid": "inv-abc123-1705316200",
  "url": "/d/inv-abc123-1705316200/npe-investigation-collateral-service",
  "status": "success",
  "version": 1
}
```

---

### WireMock Stubs (`src/test/resources/wiremock/`)

Create WireMock stub mappings for:

1. **LLM API (triage)** — stub `POST /v1/chat/completions` for each error scenario returning the expected triage JSON (all 5 fields including `crossServicePotential` and `errorPattern`)
2. **LLM API (panel selection)** — stub `POST /v1/chat/completions` for each scenario returning the expected panel selection JSON
3. **Tempo API** — stub `GET /api/traces/abc123def456` returning `npe_trace.json`
4. **Tempo API** — stub `GET /api/traces/xyz789abc123` returning `timeout_trace.json`
5. **Tempo API** — stub `GET /api/traces/ghi789jkl012` returning `mq_trace.json`
6. **Tempo API** — stub `GET /api/traces/def456ghi789` returning `db_error_trace.json`
7. **Loki API (cross-service DB)** — stub `GET /loki/api/v1/query_range` with query containing `SybSQLException` returning `db_error_loki_response.json`
8. **Loki API (cross-service MQ)** — stub `GET /loki/api/v1/query_range` with query containing `MQRC_Q_FULL` returning `mq_error_loki_response.json`
9. **Grafana API** — stub `POST /api/dashboards/db` returning `publish_success_response.json`
10. **Grafana API failure stub** — stub `POST /api/dashboards/db` with status 500 to test fallback path

---

### Integration Test Profile Strategy

Integration tests always run with `@ActiveProfiles("azure-openai", "mock-data")` by default:
- `azure-openai` — ensures the Azure OpenAI `ChatClient` bean is active (LLM calls are stubbed via WireMock anyway)
- `mock-data` — activates embedded WireMock for all external services

A separate `OllamaIntegrationTest` class uses `@ActiveProfiles("ollama", "mock-data")` and can be run locally to verify Ollama-specific behaviour (model name in prompt, response format differences).

The `@SpringBootTest` WireMock port is fixed at 8089 to match `application-mock-data.yml`.

### Integration Tests (`InvestigationIntegrationTest`)

Test the full end-to-end flow using `@SpringBootTest` + WireMock for all scenarios:

1. **NPE** — assert: TriageNode sets crossServicePotential=false, only TempoResolutionNode fires, 1-service trace → mergedComponents=[collateral-service], heap + thread panels, no cross-service logs in prompt
2. **Timeout** — assert: crossServicePotential=false, TempoResolutionNode fires, 4-service trace → mergedComponents=[4 services], trace_waterfall + latency panels
3. **DB error (cross-service)** — assert: crossServicePotential=true, BOTH TempoResolutionNode AND LokiCrossServiceNode fire in parallel; Tempo gives 1-service trace, Loki gives 3 services; mergedComponents=[3 services from Loki]; db panels + error_rate scoped to all 3; cross-service log lines present in panel selection prompt
4. **MQ error (cross-service)** — assert: crossServicePotential=true, both nodes fire; MQ panels + trace_waterfall; mergedComponents includes producer and consumer services from Loki
5. **OOM** — assert: traceId=null AND crossServicePotential=false → neither parallel node fires → mergedComponents=[risk-aggregator-service]; gc_pause_duration included
6. **Loki cross-service failure** — assert: LokiCrossServiceNode fails, falls back gracefully; mergedComponents uses Tempo result only; dashboard still produced
7. **Tempo failure** — assert: TempoResolutionNode fails, mergedComponents uses Loki result only (if crossServicePotential=true) or [originating service]; dashboard still produced
8. **LLM triage failure** — assert: FallbackNode activated, fallback dashboard with log_stream only, no 500
9. **Grafana publish failure** — assert: FallbackNode activated, fallback attempted; if fallback also fails return 503 with meaningful error body

### Unit Tests

- `TriageServiceTest` — test JSON parsing for all 5 categories including new `crossServicePotential` and `errorPattern` fields; test traceId extraction from MDC/JSON/plain text; test null traceId; test malformed LLM response handling
- `TempoResolutionNodeTest` — test span parsing, service name extraction, deduplication; test single-span and multi-span traces; test Tempo 404 → empty list; test Tempo failure → error edge taken
- `LokiCrossServiceClientTest` — test query built correctly with errorPattern; test service name extraction from stream labels; test CrossServiceLogEntry collection (max 3 per service); test empty response → empty lists; test Loki failure → error edge taken
- `ComponentMergeNodeTest` — test union of traceComponents + lokiComponents with deduplication; test traceComponents-only (crossServicePotential=false); test lokiComponents-only (Tempo failed); test both empty → [originating service]
- `PanelSelectionServiceTest` — test cross-service logs included in prompt when present; test trace_waterfall rule; test max 6 panels enforced; test $components uses mergedComponents exactly
- `DashboardAssemblyServiceTest` — test variable injection, grid layout, UID generation with and without traceId
- `VariableInjectorTest` — test all variable types injected correctly, test missing variable handling
- `FallbackDashboardServiceTest` — test fallback dashboard always has log_stream, always produces valid Grafana JSON

---

## Future Enhancements — LangGraph Migration Path

The current design is a linear pipeline (Stages 1–5) orchestrated by a WebFlux reactive chain. Each stage is already structured as an independent stateless Spring component with a well-defined input/output contract — making them ready to become LangGraph nodes when the following enhancements are needed.

The migration strategy is: **keep all stage classes unchanged, replace the WebFlux chain with a LangGraph graph**.

---

### Enhancement 1 — Root Cause Analysis Agent (ReAct Loop)

**Problem:** Currently we show panels. The L2 still has to find the root cause manually.

**What LangGraph enables:**
```
[Triage] → [Fetch Trace] → [Reason: identify slowest span]
  → [Tool: fetch Mimir metrics for that service]
  → [Reason: "latency spiked at 10:28, 2 min before error"]
  → [Tool: fetch Loki logs for that service at 10:28]
  → [Reason: "pricing feed reconnected at 10:27 — this is the cause"]
  → [Output: root cause + evidence + dashboard]
         ↑______________ loop until confidence threshold met ________________|
```

**Why WebFlux can't do this:** The number of reasoning/tool iterations is unknown upfront. LangGraph's cycle support (conditional edges that loop back) is exactly designed for ReAct agents. A WebFlux chain is always a fixed sequence.

---

### Enhancement 2 — Cascading Failure Tracing

**Problem:** In a 140-service system, one failure often cascades. The initial error log is just the symptom.

**What LangGraph enables:**
```
[Find initial error service] → [Find all errors in same time window]
  → [Build causal chain: who called whom, in what order did failures appear?]
  → [At each hop: decide whether to go deeper or stop]
  → [Output: full cascade map + originating failure point]
```

**Why WebFlux can't do this:** Iterative graph traversal with a data-driven stopping condition requires cycles and conditional edges. The depth of traversal depends on what the data shows, not a fixed pipeline.

---

### Enhancement 3 — Multi-Agent Parallel Investigation

**Problem:** For a major incident across 5 services, investigating them sequentially is too slow.

**What LangGraph enables:**
```
                 ┌── [Agent: collateral-service]  ──┐
[Coordinator] ──├── [Agent: position-service]    ──┤── [Synthesis Agent] → Dashboard + RCA
                └── [Agent: market-data-service] ──┘
```
Each sub-agent independently investigates its service (logs + metrics + spans). The coordinator adapts its synthesis based on what each branch found — it doesn't know upfront which agent will surface the most critical finding.

**Why WebFlux can't do this:** `Flux.zip()` can parallelise but has no concept of a coordinator that changes behaviour based on branch outputs. LangGraph's parallel branches with a join/supervisor node handles this natively.

---

### Enhancement 4 — Historical Pattern Matching + Runbook Suggestion

**Problem:** Senior engineers have seen these errors before. The system should know that too.

**What LangGraph enables:**
```
[Triage] → [RAG: search incident history for similar errors]
  → high confidence match (>0.85): surface matching runbook steps directly
  → medium confidence (0.5–0.85): show runbook as suggestion alongside panels
  → low confidence (<0.5): full panel investigation + record as new pattern
```

**Why WebFlux can't do this:** Conditional routing based on a confidence score — branching the workflow into structurally different paths — requires LangGraph's conditional edges. In a WebFlux chain this becomes deeply nested if/else that grows unmanageable as routing logic evolves.

---

### Enhancement 5 — Human-in-the-Loop Dashboard Refinement

**Problem:** L2 opens the dashboard, finds two panels irrelevant, wants to re-focus the investigation.

**What LangGraph enables:**
```
[Initial Dashboard] → L2 dismisses panels, adds context → [Reflect] → [Refined Dashboard]
         ↑_____________________________|
```
LangGraph's interrupt/resume support pauses the graph at a checkpoint, waits for human input (which panels were useful, what additional context was provided), then resumes with updated state.

**Why WebFlux can't do this:** WebFlux chains are fire-and-forget. Pause/resume across an HTTP boundary requires significant custom state management. LangGraph's persistence layer (checkpointing) handles this out of the box.

---

### Design Decisions That Enable This Migration

| Current decision | Why it matters for LangGraph later |
|---|---|
| Each stage is an independent `@Service` with no direct dependency on adjacent stages | Becomes a LangGraph node with zero refactoring |
| `TriageResult`, `PanelSelectionResult` are plain Java records | Become fields in the LangGraph state object |
| `InvestigationRequest` is immutable and serialisable | Can be checkpointed and resumed across restarts |
| Spring AI `ChatClient` is injected, not coupled to a specific model | LangGraph agents can use the same client |
| Resilience4j wraps each external call independently | Each node retries independently in a LangGraph graph |

---

## MCP Server

The service exposes itself as an MCP server over SSE at `/mcp/sse`, enabling Claude Desktop and any MCP-compatible client to call its tools directly — no Grafana click required.

### Entry points

| Entry point | Who calls it | What happens |
|---|---|---|
| `GET /api/v1/investigate` | Grafana Data Link | HTTP redirect to dashboard URL |
| MCP tool: `investigate` | Claude Desktop / MCP clients | Returns dashboard URL as text |

Both paths invoke the same `InvestigationGraph`.

### Tools

**Tool 1 — `listPanels()`**

Returns the full panel catalogue: panel ID, description, and applicable error categories for each of the 13 panels. Primary consumer is `investigate()` itself; also callable independently to inspect what panels are available.

**Tool 2 — `investigate(logLine, service, appcode, timestamp)`**

Internally calls `listPanels()` to fetch the panel catalogue and sets it on `InvestigationState.availablePanels` before invoking the graph. `PanelSelectionService` reads `state.availablePanels()` (set by Tool 2 via Tool 1) in preference to fetching from `PanelLibrary` directly — making Tool 1 the canonical panel discovery path for all MCP-originated investigations.

### Panel list flow

```
MCP path:   investigate() → listPanels() → state.withAvailablePanels(...) → PanelSelectionService reads state.availablePanels()
HTTP path:  InvestigationController → panelLibrary.availablePanelIds() → state.withAvailablePanels(...) → PanelSelectionService reads state.availablePanels()
```

`PanelSelectionService` has no dependency on `PanelLibrary`. `state.availablePanels()` is always populated before the graph runs — both entry points are responsible for setting it.

### Circular dependency note

`ToolCallbackProvider` beans are wired into Spring AI's `ToolCallbackResolver` → `ToolCallingManager` → `AzureOpenAiChatModel` → `ChatClient` → `TriageService` → `LangGraphConfig` → `InvestigationMcpService` → `ToolCallbackProvider` (cycle). Broken with `@Lazy` on the `InvestigationMcpService` parameter in `McpConfig.investigationTools()`.

### Claude Desktop configuration

```json
{
  "mcpServers": {
    "investigation": {
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

---

## What NOT to Build

- Do not dynamically generate PromQL/LogQL/TraceQL queries
- Do not generate panel JSON from scratch
- Do not make the LLM resolve component names — always from Tempo spans
- Do not store investigation dashboards permanently — they are ephemeral, Grafana manages them
- Do not build a UI — the entry point is always a Grafana Data Link, the exit is always a Grafana dashboard URL

---

## Definition of Done

- [ ] All 5 error scenarios produce a dashboard redirect with correct panel selection
- [ ] LangGraph graph routes correctly: NPE/TIMEOUT → Tempo only; DB_ERROR/MQ_ERROR → Tempo + Loki in parallel; OOM → neither
- [ ] DB_ERROR dashboard scopes panels to all Loki-identified blast-radius services, not just originating service
- [ ] MQ_ERROR dashboard includes producer and consumer services from Loki cross-service search
- [ ] Cross-service log entries appear in panel selection LLM prompt when Loki finds affected services
- [ ] Tempo or Loki node failure routes via error edge — graph continues with partial data, no 5xx
- [ ] FallbackNode always produces a usable log_stream dashboard — L2 never sees a 5xx
- [ ] All WireMock integration tests pass
- [ ] All unit tests pass with >85% line coverage (JaCoCo)
- [ ] Panel templates validated as valid Grafana panel JSON for IBLoki, IBTempo, IBMimir datasources
- [ ] Each LangGraph node emits an OTel span for its own operation