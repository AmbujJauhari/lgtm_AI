"""Configuration loaded from environment variables."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    neo4j_uri: str
    neo4j_user: str
    neo4j_password: str
    atlas_data_path: str
    llm_provider: str            # "ollama" | "azure"
    ollama_base_url: str
    ollama_chat_model: str
    ollama_embedding_model: str
    embedding_dimensions: int
    azure_endpoint: str
    azure_api_key: str
    azure_chat_deployment: str
    azure_embedding_deployment: str

    @classmethod
    def from_env(cls) -> "Config":
        provider = os.environ.get("LLM_PROVIDER", "ollama").lower()
        return cls(
            neo4j_uri=os.environ.get("NEO4J_URI", "bolt://localhost:7687"),
            neo4j_user=os.environ.get("NEO4J_USER", "neo4j"),
            neo4j_password=os.environ.get("NEO4J_PASSWORD", "testpassword"),
            atlas_data_path=os.environ.get("ATLAS_DATA_PATH", "/data/atlas/incidents.json"),
            llm_provider=provider,
            ollama_base_url=os.environ.get("OLLAMA_BASE_URL", "http://host.docker.internal:11434"),
            ollama_chat_model=os.environ.get("OLLAMA_CHAT_MODEL", "gemma4"),
            ollama_embedding_model=os.environ.get("OLLAMA_EMBEDDING_MODEL", "nomic-embed-text"),
            embedding_dimensions=int(os.environ.get("EMBEDDING_DIMENSIONS", "768")),
            azure_endpoint=os.environ.get("AZURE_OPENAI_ENDPOINT", ""),
            azure_api_key=os.environ.get("AZURE_OPENAI_API_KEY", ""),
            azure_chat_deployment=os.environ.get("AZURE_OPENAI_DEPLOYMENT", "gpt-4o"),
            azure_embedding_deployment=os.environ.get("AZURE_OPENAI_EMBEDDING_DEPLOYMENT", "text-embedding-3-small"),
        )
