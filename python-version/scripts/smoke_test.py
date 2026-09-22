from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8090").rstrip("/")
checks = 0


def request(method: str, path: str, payload=None, expected: int = 200):
    global checks
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        response = urllib.request.urlopen(req, timeout=60)
        status, body, headers = response.status, response.read(), response.headers
    except urllib.error.HTTPError as exc:
        status, body, headers = exc.code, exc.read(), exc.headers
    assert status == expected, f"{method} {path}: expected {expected}, got {status}: {body[:500]!r}"
    checks += 1
    content_type = headers.get("Content-Type", "")
    return json.loads(body) if "json" in content_type else body.decode("utf-8")


def check(condition: bool, message: str):
    global checks
    assert condition, message
    checks += 1


health = request("GET", "/api/health")
check(health["status"] == "UP", "health is not UP")
check(health["knowledgeItems"] >= 20, "knowledge base not loaded")
page = request("GET", "/index.html")
check("AI" in page, "static UI missing")

resume = request("POST", "/api/resumes/text", {
    "fileName": "smoke.txt",
    "text": "张三\n手机 13812345678 邮箱 smoke@example.com\n专业技能\nPython FastAPI Redis Kafka\n项目经历\n订单中台 2024.01-2024.08\n负责 Redis 幂等与 Kafka 消息链路，通过压测验证。",
})
check(resume["factCount"] >= 2, "resume facts missing")
detail = request("GET", f"/api/resumes/{resume['resumeId']}")
check("13812345678" not in detail["text"] and "smoke@example.com" not in detail["text"], "PII leak")

search = request("GET", f"/api/retrieval/search?q=Redis&resumeId={resume['resumeId']}&mode=PROJECT&topK=3")
check(not search["empty"] and search["chunks"], "retrieval has no evidence")

session = request("POST", "/api/interviews", {"resumeId": resume["resumeId"], "mode": "PROJECT", "maxQuestions": 1})
question = request("POST", f"/api/interviews/{session['id']}/next-question")
check(bool(question["sourceIds"] and question["citations"]), "question is not grounded")
error = request("POST", f"/api/interviews/{session['id']}/next-question", expected=409)
check(error["code"] == "INVALID_SESSION_STATE", "wrong state error contract")

answer = request("POST", f"/api/interviews/{session['id']}/answers", {
    "questionId": question["id"],
    "content": "首先我负责 Redis 幂等设计，其次用数据库唯一约束兜底，并通过并发压测与重复请求回放验证。",
})
check(len(answer["evaluation"]["dimensions"]) == 4, "four-dimension evaluation missing")
check(answer["nextAction"] == "FINISH", "question budget did not advertise completion")
check(answer["session"]["status"] == "NEXT_QUESTION", "last answer is not retryable")

report = request("POST", f"/api/interviews/{session['id']}/report")
check(report["report"]["answerCount"] == 1, "report statistics incorrect")
check("逐题明细" in report["markdown"], "report details missing")
markdown = request("GET", f"/api/interviews/{session['id']}/report.md")
check("AI 面试复盘报告" in markdown, "markdown download invalid")

missing = request("GET", "/api/resumes/not-found", expected=404)
check(missing["code"] == "NOT_FOUND", "not-found error contract invalid")
missing_route = request("GET", "/api/does-not-exist", expected=404)
check(missing_route["code"] == "NOT_FOUND", "route not-found error contract invalid")
wrong_method = request("PATCH", "/api/health", expected=405)
check(wrong_method["code"] == "METHOD_NOT_ALLOWED", "method error contract invalid")
wrong_field_type = request("POST", "/api/interviews", {"mode": "PROJECT", "maxQuestions": "abc"}, expected=400)
check(wrong_field_type["code"] == "INVALID_REQUEST_BODY", "field type error contract invalid")

print(f"ALL PYTHON SMOKE CHECKS PASSED ({checks}/{checks})")
