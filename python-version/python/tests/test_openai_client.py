from __future__ import annotations

import json
import threading
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from interview_agent.config import Settings
from interview_agent.llm import LlmClient


class StubHandler(BaseHTTPRequestHandler):
    requests: list[dict] = []
    chat_responses: list[tuple[int, dict]] = []
    models: list[str] = ["stub-model"]

    def log_message(self, *_args):
        pass

    def do_GET(self):
        if self.path.endswith("/models"):
            body = {"data": [{"id": model} for model in self.models]}
            self._send(200, body)
        else:
            self._send(404, {"error": "missing"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.requests.append(json.loads(self.rfile.read(length)))
        status, body = self.chat_responses.pop(0)
        self._send(status, body)

    def _send(self, status: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def server():
    StubHandler.requests = []
    StubHandler.chat_responses = []
    StubHandler.models = ["stub-model"]
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), StubHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return httpd


def client(httpd, model="stub-model", disable_thinking=False, max_retries=0):
    settings = replace(Settings.load(), llm_provider="openai-compatible",
                       llm_base_url=f"http://127.0.0.1:{httpd.server_port}/v1",
                       llm_model=model, disable_thinking=disable_thinking, llm_max_retries=max_retries)
    return LlmClient(settings)


def success(content='{"summary":"ok"}'):
    return {"model": "stub-model", "choices": [{"message": {"content": content}, "finish_reason": "stop"}]}


def test_negotiates_json_object_to_json_schema():
    httpd = server()
    try:
        StubHandler.chat_responses = [
            (400, {"error": {"message": "response_format json_object unsupported"}}),
            (200, success()),
        ]
        result = client(httpd).complete_json("system", "user", 0.0)
        assert result["summary"] == "ok"
        assert StubHandler.requests[0]["response_format"]["type"] == "json_object"
        assert StubHandler.requests[1]["response_format"]["type"] == "json_schema"
    finally:
        httpd.shutdown()


def test_falls_back_to_text_and_remembers_unsupported_formats():
    httpd = server()
    try:
        StubHandler.chat_responses = [
            (400, {"error": "response_format json_object"}),
            (400, {"error": "response_format json_schema"}),
            (200, success()),
            (200, success('{"summary":"again"}')),
        ]
        llm = client(httpd)
        assert llm.complete_json("", "user", 0.0)["summary"] == "ok"
        assert "response_format" not in StubHandler.requests[2]
        assert llm.complete_json("", "user2", 0.0)["summary"] == "again"
        assert "response_format" not in StubHandler.requests[3]
        assert StubHandler.requests[3]["messages"] == [{"role": "user", "content": "user2"}]
    finally:
        httpd.shutdown()


def test_reasoning_model_disables_thinking_without_duplicate_marker():
    httpd = server()
    try:
        StubHandler.chat_responses = [(200, success()), (200, success())]
        llm = client(httpd, model="qwen3:1.7b", disable_thinking=True)
        llm.complete_json("system", "prompt", 0.0)
        llm.complete_json("system", "prompt /no_think", 0.0)
        first, second = StubHandler.requests
        assert first["think"] is False and first["messages"][-1]["content"].endswith(" /no_think")
        assert second["messages"][-1]["content"].count("/no_think") == 1
    finally:
        httpd.shutdown()


def test_health_requires_configured_model_when_list_is_available():
    httpd = server()
    try:
        available, _, _, models = client(httpd).health()
        assert available is True and models == ["stub-model"]
        available, status, hint, models = client(httpd, model="missing").health()
        assert available is False and "不在模型列表" in status and hint
    finally:
        httpd.shutdown()


def test_unreachable_health_is_safe():
    settings = replace(Settings.load(), llm_provider="openai-compatible", llm_base_url="http://127.0.0.1:1/v1")
    available, status, hint, models = LlmClient(settings).health(discover_alternates=False)
    assert available is False and "连不上" in status and hint and models == []


def test_server_error_retries_only_when_configured():
    httpd = server()
    try:
        StubHandler.chat_responses = [(500, {"error": "temporary"}), (200, success())]
        result = client(httpd, max_retries=1).complete_json("", "retry", 0.0)
        assert result["summary"] == "ok"
        assert len(StubHandler.requests) == 2
    finally:
        httpd.shutdown()
