from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

from .models import Resume

TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z0-9+#._-]*|[\u4e00-\u9fff]")


@dataclass
class Chunk:
    id: str
    kind: str
    title: str
    content: str
    resume_id: str | None
    topic: str | None
    metadata: dict[str, Any]


@dataclass
class Hit:
    chunk: Chunk
    score: float


class Retriever:
    def __init__(self, knowledge_path: Path, dimension: int = 256, *, embedding_mode: str = "local",
                 base_url: str = "", api_key: str = "", embedding_model: str = "nomic-embed-text",
                 timeout: float = 15.0, min_score: float = 0.08):
        self.dimension = dimension
        self.requested_embedding_mode = embedding_mode.lower()
        self.embedding_provider = "remote" if self.requested_embedding_mode == "remote" else "local-hash"
        self.embedding_degradation_reason: str | None = None
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.embedding_model = embedding_model
        self.timeout = timeout
        self.min_score = max(0.0, min(1.0, min_score))
        self.chunks: dict[str, Chunk] = {}
        self.vectors: dict[str, list[float]] = {}
        raw = json.loads(knowledge_path.read_text(encoding="utf-8"))
        self.knowledge_items: list[dict[str, Any]] = raw.get("items", [])
        for item in self.knowledge_items:
            self.chunks[item["id"]] = Chunk(
                id=item["id"], kind="KNOWLEDGE", title=item["title"], content=item["content"],
                resume_id=None, topic=item.get("topic"), metadata=item,
            )
        self._reindex_all()

    def index_resume(self, resume: Resume) -> None:
        self.delete_resume(resume.id)
        new_ids: list[str] = []
        for fact in resume.facts:
            self.chunks[fact.id] = Chunk(
                id=fact.id, kind="RESUME", title=fact.label, content=fact.content,
                resume_id=resume.id, topic=fact.type.value,
                metadata={"type": fact.type.value, "metadata": fact.metadata, "sourceOrder": fact.sourceOrder},
            )
            new_ids.append(fact.id)
        if new_ids:
            vectors = self._embed_many([self.chunks[key].title + " " + self.chunks[key].content for key in new_ids])
            self.vectors.update(zip(new_ids, vectors, strict=False))

    def delete_resume(self, resume_id: str) -> None:
        for key in [key for key, value in self.chunks.items() if value.resume_id == resume_id]:
            del self.chunks[key]
            self.vectors.pop(key, None)

    def _tokens(self, text: str) -> list[str]:
        raw = [token.lower() for token in TOKEN_RE.findall(text or "")]
        # Chinese unigrams plus adjacent bigrams improve exact terminology matching without a tokenizer dependency.
        chinese = [token for token in raw if len(token) == 1 and "\u4e00" <= token <= "\u9fff"]
        return raw + ["".join(chinese[i:i + 2]) for i in range(max(0, len(chinese) - 1))]

    def _local_embed(self, text: str) -> list[float]:
        vector = [0.0] * self.dimension
        for token in self._tokens(text):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimension
            vector[index] += 1.0 if digest[4] & 1 else -1.0
        norm = math.sqrt(sum(value * value for value in vector)) or 1.0
        return [value / norm for value in vector]

    def _remote_embed_many(self, texts: list[str]) -> list[list[float]]:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        response = httpx.post(
            f"{self.base_url}/embeddings",
            json={"model": self.embedding_model, "input": texts},
            headers=headers, timeout=self.timeout, trust_env=False,
        )
        response.raise_for_status()
        data = sorted(response.json()["data"], key=lambda item: int(item.get("index", 0)))
        vectors = [[float(value) for value in item["embedding"]] for item in data]
        if len(vectors) != len(texts) or not vectors or not vectors[0]:
            raise ValueError("远程 embedding 返回数量或维度不正确")
        dimension = len(vectors[0])
        if any(len(vector) != dimension for vector in vectors):
            raise ValueError("远程 embedding 维度不一致")
        self.dimension = dimension
        return [self._normalize(vector) for vector in vectors]

    def _embed_many(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        if self.embedding_provider == "remote":
            try:
                return self._remote_embed_many(texts)
            except Exception as exc:
                # A query-time remote failure would otherwise compare a local query
                # vector against cached remote vectors.  Switch provider and rebuild
                # the entire index atomically before returning the local query vector.
                self.embedding_provider = "local-hash-fallback"
                self.embedding_degradation_reason = f"{type(exc).__name__}: {exc}"
                self.dimension = max(8, self.dimension if self.dimension <= 4096 else 256)
                self.vectors = {key: self._local_embed(chunk.title + " " + chunk.content)
                                for key, chunk in self.chunks.items()}
        return [self._local_embed(text) for text in texts]

    @staticmethod
    def _normalize(vector: list[float]) -> list[float]:
        norm = math.sqrt(sum(value * value for value in vector)) or 1.0
        return [value / norm for value in vector]

    def _reindex_all(self) -> None:
        ids = list(self.chunks)
        vectors = self._embed_many([self.chunks[key].title + " " + self.chunks[key].content for key in ids])
        self.vectors = dict(zip(ids, vectors, strict=False))

    def embed(self, text: str) -> list[float]:
        return self._embed_many([text])[0]

    def search(self, query: str, mode: str, resume_id: str | None = None, top_k: int = 5) -> list[Hit]:
        query_tokens = set(self._tokens(query))
        query_vector = self.embed(query)
        hits: list[Hit] = []
        for chunk in self.chunks.values():
            if mode == "PROJECT" and (chunk.kind != "RESUME" or chunk.resume_id != resume_id):
                continue
            if mode == "KNOWLEDGE" and chunk.kind != "KNOWLEDGE":
                continue
            if mode == "FULL" and chunk.kind == "RESUME" and chunk.resume_id != resume_id:
                continue
            content_tokens = set(self._tokens(chunk.title + " " + chunk.content))
            keyword = len(query_tokens & content_tokens) / max(1, len(query_tokens))
            vector = self.vectors.get(chunk.id)
            if vector is None:
                vector = self.embed(chunk.title + " " + chunk.content)
                self.vectors[chunk.id] = vector
            cosine = sum(a * b for a, b in zip(query_vector, vector, strict=False))
            score = 0.58 * max(0.0, cosine) + 0.42 * keyword
            # An empty/general query is used by the question planner: preserve stable source order.
            if not query.strip():
                score = 0.1 + (0.02 if chunk.kind == "RESUME" else 0.0)
            if not query.strip() or score >= self.min_score:
                hits.append(Hit(chunk, round(score, 6)))
        return sorted(hits, key=lambda hit: (-hit.score, hit.chunk.id))[:max(1, min(top_k, 20))]

    def topics(self) -> list[str]:
        return sorted({str(item.get("topic", "")) for item in self.knowledge_items if item.get("topic")})

    def by_topic(self, topic: str | None) -> list[dict[str, Any]]:
        items = self.knowledge_items
        if topic:
            items = [item for item in items if str(item.get("topic", "")).lower() == topic.lower()]
        return items
