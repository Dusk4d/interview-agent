from __future__ import annotations

import json
import os
import threading
from contextlib import suppress
from pathlib import Path
from typing import TypeVar

from pydantic import BaseModel

from .models import Answer, Evaluation, Question, Report, Resume, Session

T = TypeVar("T", bound=BaseModel)


class Repository:
    """Thread-safe JSON repository using atomic replace for single-process durability."""

    _types: dict[str, type[BaseModel]] = {
        "resumes": Resume,
        "sessions": Session,
        "questions": Question,
        "answers": Answer,
        "evaluations": Evaluation,
        "reports": Report,
    }

    def __init__(self, data_dir: Path | None):
        self.data_dir = data_dir
        self.lock = threading.RLock()
        self.data: dict[str, dict[str, BaseModel]] = {key: {} for key in self._types}
        if data_dir:
            data_dir.mkdir(parents=True, exist_ok=True)
            self._load()

    def _load(self) -> None:
        for bucket, model_type in self._types.items():
            path = self.data_dir / f"{bucket}.json"  # type: ignore[operator]
            if not path.exists():
                continue
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                values = raw.values() if isinstance(raw, dict) else raw
                self.data[bucket] = {item["id"]: model_type.model_validate(item) for item in values}
            except (OSError, ValueError, TypeError, KeyError):
                broken = path.with_suffix(path.suffix + ".corrupt")
                with suppress(OSError):
                    os.replace(path, broken)

    def _flush(self, bucket: str) -> None:
        if not self.data_dir:
            return
        path = self.data_dir / f"{bucket}.json"
        tmp = path.with_suffix(".json.tmp")
        body = {key: value.model_dump(mode="json") for key, value in self.data[bucket].items()}
        tmp.write_text(json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, path)

    def save(self, bucket: str, value: T) -> T:
        with self.lock:
            self.data[bucket][value.id] = value
            self._flush(bucket)
        return value

    def get(self, bucket: str, object_id: str) -> BaseModel | None:
        with self.lock:
            return self.data[bucket].get(object_id)

    def list(self, bucket: str) -> list[BaseModel]:
        with self.lock:
            return sorted(self.data[bucket].values(), key=lambda x: getattr(x, "createdAt", ""), reverse=True)

    def delete(self, bucket: str, object_id: str) -> bool:
        with self.lock:
            found = self.data[bucket].pop(object_id, None) is not None
            if found:
                self._flush(bucket)
            return found

    def delete_where(self, bucket: str, predicate) -> int:
        with self.lock:
            ids = [key for key, value in self.data[bucket].items() if predicate(value)]
            for key in ids:
                del self.data[bucket][key]
            if ids:
                self._flush(bucket)
            return len(ids)
