from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class Settings:
    root: Path
    host: str
    port: int
    storage_mode: str
    data_dir: Path
    llm_provider: str
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_max_tokens: int
    llm_max_retries: int
    llm_temperature: float
    llm_eval_temperature: float
    llm_eval_samples: int
    llm_timeout: float
    disable_thinking: bool
    embedding_mode: str
    embedding_model: str
    embedding_dimension: int
    knowledge_path: Path
    static_dir: Path
    retrieval_top_k: int
    retrieval_min_score: float
    retrieval_max_context_chars: int
    max_questions: int
    follow_up_limit: int
    max_question_skips: int
    min_answer_chars: int
    max_answer_chars: int
    max_file_bytes: int

    @classmethod
    def load(cls, root: Path | None = None) -> Settings:
        root = (root or Path(__file__).resolve().parents[2]).resolve()
        data_raw = os.getenv("INTERVIEW_DATA_DIR", "").strip()
        data_dir = Path(data_raw).resolve() if data_raw else root / "python-data"
        model = os.getenv("INTERVIEW_LLM_CHAT_MODEL", "qwen2.5-7b-instruct")
        disable_raw = os.getenv("INTERVIEW_LLM_DISABLE_THINKING", "").strip().lower()
        disable_thinking = (disable_raw in {"1", "true", "yes", "on"}) if disable_raw else (
            model.lower().startswith("qwen3") or "qwq" in model.lower()
            or model.lower().startswith("deepseek-r1") or "reasoning" in model.lower()
            or model.lower().startswith("magistral") or "thinking" in model.lower()
        )
        knowledge_raw = os.getenv("INTERVIEW_KNOWLEDGE_PATH", "").strip()
        project_resources = root / "resources"
        packaged_resources = Path(__file__).resolve().parent / "resources"
        resource_root = project_resources if project_resources.is_dir() else packaged_resources
        knowledge_path = (
            Path(knowledge_raw).resolve()
            if knowledge_raw
            else resource_root / "knowledge" / "knowledge-base.json"
        )
        return cls(
            root=root,
            host=os.getenv("INTERVIEW_HOST", "127.0.0.1"),
            port=_int("INTERVIEW_PORT", 8090),
            storage_mode=os.getenv("INTERVIEW_STORAGE_MODE", "file").lower(),
            data_dir=data_dir,
            llm_provider=os.getenv("INTERVIEW_LLM_PROVIDER", "mock").lower(),
            llm_base_url=os.getenv("INTERVIEW_LLM_BASE_URL", "http://127.0.0.1:1234/v1").rstrip("/"),
            llm_api_key=os.getenv("INTERVIEW_LLM_API_KEY", "lm-studio"),
            llm_model=model,
            llm_max_tokens=max(64, _int("INTERVIEW_LLM_MAX_TOKENS", 1600)),
            llm_max_retries=max(0, _int("INTERVIEW_LLM_MAX_RETRIES", 0)),
            llm_temperature=_float("INTERVIEW_LLM_TEMPERATURE", 0.3),
            llm_eval_temperature=_float("INTERVIEW_LLM_EVAL_TEMPERATURE", 0.0),
            llm_eval_samples=max(1, _int("INTERVIEW_LLM_EVAL_SAMPLES", 1)),
            llm_timeout=_float("INTERVIEW_LLM_TIMEOUT", 45.0),
            disable_thinking=disable_thinking,
            embedding_mode=os.getenv("INTERVIEW_EMBEDDING_MODE", "local").lower(),
            embedding_model=os.getenv("INTERVIEW_LLM_EMBEDDING_MODEL", "nomic-embed-text"),
            embedding_dimension=_int("INTERVIEW_EMBEDDING_DIMENSION", 256),
            knowledge_path=knowledge_path,
            static_dir=resource_root / "static",
            retrieval_top_k=max(1, min(20, _int("INTERVIEW_RETRIEVAL_TOP_K", 5))),
            retrieval_min_score=max(0.0, min(1.0, _float("INTERVIEW_RETRIEVAL_MIN_SCORE", 0.08))),
            retrieval_max_context_chars=max(200, _int("INTERVIEW_RETRIEVAL_MAX_CONTEXT_CHARS", 4000)),
            max_questions=_int("INTERVIEW_MAX_QUESTIONS", 8),
            follow_up_limit=_int("INTERVIEW_FOLLOW_UP_LIMIT", 1),
            max_question_skips=_int("INTERVIEW_MAX_QUESTION_SKIPS", 3),
            min_answer_chars=_int("INTERVIEW_MIN_ANSWER_CHARS", 8),
            max_answer_chars=_int("INTERVIEW_MAX_ANSWER_CHARS", 4000),
            max_file_bytes=_int("INTERVIEW_MAX_FILE_BYTES", 10 * 1024 * 1024),
        )
