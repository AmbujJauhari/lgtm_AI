"""LLM and embedding client — abstracts over Ollama (local) and Azure OpenAI (cloud).

GraphRAG-style entity + relationship extraction lives here. Kept as a single
adapter so the rest of the pipeline doesn't care which provider is active —
the same prompts and same JSON output shape work for both.

Note: this is a thin direct-HTTP implementation rather than using Microsoft's
graphrag library. The graphrag package's API has been changing rapidly across
versions; for ~25 incidents we don't yet need its community detection. The
extraction prompts and output schema below mirror what GraphRAG produces, so
swapping to the framework later requires only changing this file.
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from typing import Any

import requests

from .config import Config

log = logging.getLogger(__name__)


EXTRACTION_PROMPT = """You are extracting structured information from an IT incident ticket
in a financial services platform with 140 microservices under appcode AT4278.

Extract ONLY entities explicitly mentioned in the incident text. Do not invent.

Entities to extract:
1. Services — canonical service names (e.g. "booking-service", "ledger-service",
   "collateral-service", "position-service", "margin-call-service",
   "trade-enrichment-service", "risk-aggregator-service", "margin-event-consumer",
   "trade-settlement-service", "pricing-service", "reference-data-service").
   Normalize variants ("Booking Service" → "booking-service").
2. Error pattern — the most distinctive exception class or error string
   (e.g. "NullPointerException", "SybSQLException JZ006", "MQRC_Q_FULL",
   "InstrumentNotFoundException"). One per incident.
3. Root cause — categorize into one of:
   data-refresh-failure, deadlock, memory-leak, connection-leak, timeout,
   network-partition, queue-full, configuration-error, schema-mismatch,
   resource-exhaustion, certificate-expiry, race-condition, query-plan-regression,
   stale-cache, partial-migration, sync-lag, other.
4. System components — databases, jobs, MQ queues, datastores mentioned by name
   (e.g. {"name": "instrument-master", "type": "datastore"},
        {"name": "reference-data-refresh", "type": "job"},
        {"name": "MARGIN.CALL.REQUEST.QUEUE", "type": "queue"}).
   Types: database, datastore, job, queue, cache, feed, other.

Output ONLY valid JSON, no markdown, no commentary:
{
  "services": ["..."],
  "errorPattern": "..." or null,
  "rootCause": {"category": "...", "description": "one short sentence"},
  "systemComponents": [{"name": "...", "type": "..."}]
}

Incident:
[Title] <<TITLE>>
[Description] <<DESCRIPTION>>
[Closure Notes] <<CLOSURE_NOTES>>
"""


@dataclass(frozen=True)
class Extraction:
    services: list[str]
    error_pattern: str | None
    root_cause_category: str | None
    root_cause_description: str | None
    system_components: list[dict[str, str]]


class LLMClient:
    def __init__(self, config: Config):
        self.config = config

    # ── Extraction ─────────────────────────────────────────────────────────
    def extract(self, title: str, description: str, closure_notes: str) -> Extraction:
        # Use simple replacement rather than str.format() — the prompt body
        # contains literal JSON braces ({"name": "..."}) that would otherwise
        # be interpreted as format placeholders.
        prompt = (EXTRACTION_PROMPT
                  .replace("<<TITLE>>", title or "")
                  .replace("<<DESCRIPTION>>", description or "")
                  .replace("<<CLOSURE_NOTES>>", closure_notes or ""))
        raw = self._chat(prompt)
        parsed = self._parse_extraction(raw)
        return Extraction(
            services=parsed.get("services", []) or [],
            error_pattern=parsed.get("errorPattern"),
            root_cause_category=(parsed.get("rootCause") or {}).get("category"),
            root_cause_description=(parsed.get("rootCause") or {}).get("description"),
            system_components=parsed.get("systemComponents", []) or [],
        )

    def _parse_extraction(self, raw: str) -> dict[str, Any]:
        # Models sometimes wrap JSON in ```json ... ``` fences — strip them.
        cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw.strip(), flags=re.MULTILINE)
        try:
            return json.loads(cleaned)
        except json.JSONDecodeError as e:
            # Try to find the first JSON object in the response.
            match = re.search(r"\{.*\}", cleaned, flags=re.DOTALL)
            if match:
                try:
                    return json.loads(match.group(0))
                except json.JSONDecodeError:
                    pass
            log.warning("Failed to parse extraction JSON, returning empty: %s", e)
            log.debug("Raw response: %s", raw)
            return {}

    # ── Embedding ──────────────────────────────────────────────────────────
    def embed(self, text: str) -> list[float]:
        if self.config.llm_provider == "ollama":
            return self._ollama_embed(text)
        return self._azure_embed(text)

    # ── Provider implementations ───────────────────────────────────────────
    def _chat(self, prompt: str) -> str:
        if self.config.llm_provider == "ollama":
            return self._ollama_chat(prompt)
        return self._azure_chat(prompt)

    def _ollama_chat(self, prompt: str) -> str:
        url = f"{self.config.ollama_base_url}/api/chat"
        payload = {
            "model": self.config.ollama_chat_model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "options": {"temperature": 0.0},
            "format": "json",
        }
        r = requests.post(url, json=payload, timeout=120)
        r.raise_for_status()
        return r.json()["message"]["content"]

    def _ollama_embed(self, text: str) -> list[float]:
        url = f"{self.config.ollama_base_url}/api/embeddings"
        payload = {"model": self.config.ollama_embedding_model, "prompt": text}
        r = requests.post(url, json=payload, timeout=60)
        r.raise_for_status()
        return r.json()["embedding"]

    def _azure_chat(self, prompt: str) -> str:
        url = (
            f"{self.config.azure_endpoint}/openai/deployments/"
            f"{self.config.azure_chat_deployment}/chat/completions?api-version=2024-02-15-preview"
        )
        payload = {
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.0,
            "response_format": {"type": "json_object"},
        }
        r = requests.post(
            url,
            json=payload,
            headers={"api-key": self.config.azure_api_key},
            timeout=120,
        )
        r.raise_for_status()
        return r.json()["choices"][0]["message"]["content"]

    def _azure_embed(self, text: str) -> list[float]:
        url = (
            f"{self.config.azure_endpoint}/openai/deployments/"
            f"{self.config.azure_embedding_deployment}/embeddings?api-version=2024-02-15-preview"
        )
        payload = {"input": text}
        r = requests.post(
            url,
            json=payload,
            headers={"api-key": self.config.azure_api_key},
            timeout=60,
        )
        r.raise_for_status()
        return r.json()["data"][0]["embedding"]
