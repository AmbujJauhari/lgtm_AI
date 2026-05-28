"""Loads synthetic / real Atlas incident records from the source JSON file."""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class AtlasIncident:
    ticket_id: str
    appcode: str
    title: str
    description: str
    closure_notes: str
    created_at: str
    resolved_at: str
    priority: str

    @property
    def combined_text(self) -> str:
        """Used for embedding and as LLM input. Title + description + closure."""
        return f"{self.title}\n\n{self.description}\n\nResolution: {self.closure_notes}"


def load_incidents(path: str) -> list[AtlasIncident]:
    with open(path, "r", encoding="utf-8") as f:
        raw: list[dict[str, Any]] = json.load(f)
    incidents = [
        AtlasIncident(
            ticket_id=r["ticketId"],
            appcode=r["appcode"],
            title=r["title"],
            description=r["description"],
            closure_notes=r["closureNotes"],
            created_at=r["createdAt"],
            resolved_at=r["resolvedAt"],
            priority=r["priority"],
        )
        for r in raw
    ]
    log.info("Loaded %d Atlas incidents from %s", len(incidents), path)
    return incidents
