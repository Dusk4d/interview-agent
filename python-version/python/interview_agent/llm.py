from __future__ import annotations

import json
import re
from typing import Any

import httpx

from .config import Settings


class LlmClient:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._unsupported_formats: set[str] = set()

    @property
    def is_mock(self) -> bool:
        return self.settings.llm_provider == "mock"

    def health(self, discover_alternates: bool = True) -> tuple[bool, str, str | None, list[str]]:
        if self.is_mock:
            return True, "离线 Mock 模式：模板出题 + 启发式评分，不会调用真实模型", None, []
        try:
            models = self._models_at(self.settings.llm_base_url, 3.0)
            available = not models or self.settings.llm_model in models
            if available:
                return True, f"已连接 {self.settings.llm_base_url}，模型 {self.settings.llm_model} 可用", None, models
            return False, f"服务可达，但模型 {self.settings.llm_model} 不在模型列表中", "请设置 INTERVIEW_LLM_CHAT_MODEL 为可用模型。", models
        except Exception:
            if discover_alternates:
                candidates = [
                    ("Ollama", "http://127.0.0.1:11434/v1"),
                    ("LM Studio", "http://127.0.0.1:1234/v1"),
                    ("本地服务", "http://127.0.0.1:8000/v1"),
                    ("本地服务", "http://127.0.0.1:8080/v1"),
                ]
                for name, url in candidates:
                    if url.rstrip("/") == self.settings.llm_base_url.rstrip("/"):
                        continue
                    try:
                        models = self._models_at(url, 0.6)
                        model_hint = f"，可用模型：{'、'.join(models)}" if models else ""
                        status = (f"当前配置的地址 {self.settings.llm_base_url} 连不上（模型 {self.settings.llm_model}），"
                                  f"但检测到 {name} 正在 {url} 提供服务{model_hint}")
                        suggested = models[0] if models else self.settings.llm_model
                        hint = (f"设置 INTERVIEW_LLM_BASE_URL={url} 与 "
                                f"INTERVIEW_LLM_CHAT_MODEL={suggested}，然后重启服务。")
                        return False, status, hint, models
                    except Exception:
                        continue
            return False, f"当前配置的地址 {self.settings.llm_base_url} 连不上（模型 {self.settings.llm_model}）", "请启动本地模型服务，或设置 INTERVIEW_LLM_PROVIDER=mock。", []

    def _models_at(self, base_url: str, timeout: float) -> list[str]:
        headers = ({"Authorization": f"Bearer {self.settings.llm_api_key}"}
                   if self.settings.llm_api_key else {})
        response = httpx.get(f"{base_url.rstrip('/')}/models", headers=headers,
                             timeout=timeout, trust_env=False)
        response.raise_for_status()
        try:
            data = response.json().get("data", [])
            return [str(item.get("id")) for item in data if isinstance(item, dict) and item.get("id")]
        except (ValueError, AttributeError):
            # A reachable service with a non-standard models payload still proves
            # connectivity; the caller treats an empty list as “model list unknown”.
            return []

    def complete_json(self, system: str, user: str, temperature: float) -> dict[str, Any]:
        if self.is_mock:
            raise RuntimeError("mock client has no remote completion")
        user_prompt = user
        if self.settings.disable_thinking and "/no_think" not in user_prompt:
            user_prompt += " /no_think"
        payload: dict[str, Any] = {
            "model": self.settings.llm_model,
            "messages": ([{"role": "system", "content": system}] if system.strip() else [])
                        + [{"role": "user", "content": user_prompt}],
            "temperature": temperature,
            "max_tokens": self.settings.llm_max_tokens,
            "stream": False,
        }
        if self.settings.disable_thinking:
            payload["think"] = False
        headers = ({"Authorization": f"Bearer {self.settings.llm_api_key}"}
                   if self.settings.llm_api_key else {})
        # Local model endpoints must not be routed through a machine-wide proxy.
        with httpx.Client(timeout=self.settings.llm_timeout, http1=True, http2=False, trust_env=False) as client:
            response = None
            last_transport_error: Exception | None = None
            for network_attempt in range(self.settings.llm_max_retries + 1):
                try:
                    formats: list[str | None] = ["json_object", "json_schema", None]
                    for output_format in formats:
                        if output_format and output_format in self._unsupported_formats:
                            continue
                        if output_format == "json_object":
                            payload["response_format"] = {"type": "json_object"}
                        elif output_format == "json_schema":
                            payload["response_format"] = {"type": "json_schema", "json_schema": {
                                "name": "structured_output", "schema": {"type": "object"},
                            }}
                        else:
                            payload.pop("response_format", None)
                        response = client.post(f"{self.settings.llm_base_url}/chat/completions", json=payload, headers=headers)
                        lower = response.text.lower()
                        unsupported = response.status_code == 400 and any(
                            marker in lower for marker in ("response_format", "json_object", "json_schema")
                        )
                        if unsupported and output_format:
                            self._unsupported_formats.add(output_format)
                            continue
                        break
                    assert response is not None
                    if response.status_code >= 500 and network_attempt < self.settings.llm_max_retries:
                        continue
                    response.raise_for_status()
                    break
                except (httpx.TimeoutException, httpx.TransportError) as exc:
                    last_transport_error = exc
                    if network_attempt >= self.settings.llm_max_retries:
                        raise
            if response is None and last_transport_error:
                raise last_transport_error
            assert response is not None
        content = response.json()["choices"][0]["message"]["content"]
        return parse_json_object(content)


def parse_json_object(content: str, required: list[str] | None = None) -> dict[str, Any]:
    text = (content or "").strip()
    if not text:
        raise ValueError("模型返回空内容")
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.I)
    start, end = text.find("{"), text.rfind("}")
    if start < 0:
        raise ValueError("模型输出中没有 JSON 对象")
    text = text[start:] if end < start else text[start:end + 1]
    text = text.replace("“", '"').replace("”", '"').replace("‘", "'").replace("’", "'")
    attempts = [text]
    repaired = re.sub(r"'([^'\\]*(?:\\.[^'\\]*)*)'", lambda m: json.dumps(m.group(1), ensure_ascii=False), text)
    repaired = re.sub(r"([{,]\s*)([A-Za-z_][\w-]*)(\s*:)", r'\1"\2"\3', repaired)
    repaired = re.sub(r",\s*([}\]])", r"\1", repaired)
    attempts.append(repaired)
    # Local models often stop after a valid string/array item.  Close only the
    # unmatched containers; json.loads still rejects semantically broken text.
    completed = repaired.rstrip().rstrip(",")
    in_string, escaped = False, False
    stack: list[str] = []
    for char in completed:
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char in "[{":
            stack.append(char)
        elif char in "]}" and stack:
            stack.pop()
    if in_string:
        completed += '"'
    completed = completed.rstrip().rstrip(",")
    while stack:
        completed += "]" if stack.pop() == "[" else "}"
    attempts.append(completed)
    last_error: Exception | None = None
    for candidate in attempts:
        try:
            parsed = json.loads(candidate)
            if not isinstance(parsed, dict):
                raise ValueError("结构化输出不是 JSON 对象")
            missing = [key for key in (required or []) if key not in parsed]
            if missing:
                raise ValueError("缺少必需字段：" + "、".join(missing))
            return parsed
        except (json.JSONDecodeError, ValueError) as exc:
            last_error = exc
    raise ValueError(f"无法解析模型 JSON：{last_error}")


def number_value(data: dict[str, Any], key: str, default: float = 0.0,
                 minimum: float = 0.0, maximum: float = 5.0) -> float:
    raw = data.get(key)
    if isinstance(raw, bool):
        return default
    try:
        value = float(raw)
    except (TypeError, ValueError):
        match = re.search(r"-?\d+(?:\.\d+)?", str(raw or ""))
        if not match:
            return default
        value = float(match.group())
    return max(minimum, min(maximum, value))


def bool_value(data: dict[str, Any], key: str, default: bool = False) -> bool:
    raw = data.get(key)
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (list, dict)):
        return bool(raw)
    normalized = str(raw or "").strip().lower()
    if normalized in {"true", "yes", "y", "1", "是", "建议", "需要"}:
        return True
    if normalized in {"false", "no", "n", "0", "否", "不建议", "不需要"}:
        return False
    return default


def string_list(data: dict[str, Any], key: str) -> list[str]:
    raw = data.get(key)
    if raw is None or raw is False or raw == 0 or raw == "":
        return []
    if isinstance(raw, list):
        return [str(item).strip() for item in raw if str(item).strip()]
    if raw is None:
        return []
    return [item.strip() for item in re.split(r"[；;\n]", str(raw)) if item.strip()]
