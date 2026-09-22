from __future__ import annotations

import logging
import os
from typing import Any

from fastapi import Body, FastAPI, File, Query, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .config import Settings
from .errors import ApiError, invalid_request_body, not_found, validation
from .llm import LlmClient
from .rag import Retriever
from .service import InterviewEngine
from .storage import Repository


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.load()
    repo = Repository(None if settings.storage_mode == "memory" else settings.data_dir)
    retriever = Retriever(
        settings.knowledge_path,
        settings.embedding_dimension,
        embedding_mode=settings.embedding_mode,
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
        embedding_model=settings.embedding_model,
        timeout=min(settings.llm_timeout, 15.0),
        min_score=settings.retrieval_min_score,
    )
    llm = LlmClient(settings)
    engine = InterviewEngine(settings, repo, retriever, llm)
    app = FastAPI(title="AI 面试陪练系统 Python 版", version=__version__)
    app.state.settings, app.state.repo, app.state.retriever, app.state.llm, app.state.engine = settings, repo, retriever, llm, engine

    @app.exception_handler(ApiError)
    async def api_error(request: Request, exc: ApiError):
        return JSONResponse(status_code=exc.status, content={
            "code": exc.code, "message": exc.message, "path": request.url.path,
            "timestamp": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
            "details": exc.details,
        })

    @app.exception_handler(RequestValidationError)
    async def request_error(request: Request, exc: RequestValidationError):
        details = [f"{'.'.join(str(x) for x in error['loc'])}: {error['msg']}" for error in exc.errors()]
        error = ApiError(400, "INVALID_REQUEST_BODY", "请求体或参数格式不正确。", details)
        return await api_error(request, error)

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request: Request, exc: StarletteHTTPException):
        if exc.status_code == 404:
            error = ApiError(404, "NOT_FOUND", f"接口或静态资源不存在：{request.url.path}")
        elif exc.status_code == 405:
            error = ApiError(405, "METHOD_NOT_ALLOWED", f"该路径不支持 {request.method} 方法。")
        else:
            error = ApiError(exc.status_code, "HTTP_ERROR", str(exc.detail))
        return await api_error(request, error)

    @app.exception_handler(Exception)
    async def internal_error(request: Request, exc: Exception):
        logging.getLogger("interview_agent").exception("Unhandled request failure: %s", request.url.path, exc_info=exc)
        error = ApiError(500, "INTERNAL_ERROR", "服务内部发生未预期错误，请查看服务日志。")
        return await api_error(request, error)

    @app.get("/api/health")
    def health():
        available, status, hint, models = llm.health()
        return {
            "status": "UP", "version": __version__,
            "llmProvider": "mock" if llm.is_mock else "openai-compatible", "llmModel": settings.llm_model,
            "llmAvailable": available, "llmBaseUrl": settings.llm_base_url, "llmStatus": status,
            "llmHint": hint, "llmAvailableModels": models, "thinkingDisabled": settings.disable_thinking,
            "embeddingProvider": retriever.embedding_provider, "embeddingDimension": retriever.dimension,
            "embeddingStatus": retriever.embedding_degradation_reason or "embedding 可用",
            "retrievalTopK": settings.retrieval_top_k, "retrievalMinScore": settings.retrieval_min_score,
            "knownChunks": len(retriever.chunks), "knowledgeItems": len(retriever.knowledge_items),
            "resumeCount": len(repo.list("resumes")), "sessionCount": len(repo.list("sessions")),
            "storageMode": settings.storage_mode, "topics": retriever.topics(),
        }

    @app.get("/api/diagnostics/config")
    def config_diagnostics():
        names = ["INTERVIEW_LLM_PROVIDER", "INTERVIEW_LLM_BASE_URL", "INTERVIEW_LLM_CHAT_MODEL",
            "INTERVIEW_LLM_MAX_TOKENS", "INTERVIEW_LLM_MAX_RETRIES", "INTERVIEW_LLM_EVAL_SAMPLES",
            "INTERVIEW_KNOWLEDGE_PATH", "INTERVIEW_DATA_DIR", "INTERVIEW_STORAGE_MODE",
            "INTERVIEW_EMBEDDING_MODE", "INTERVIEW_EMBEDDING_DIMENSION",
            "INTERVIEW_RETRIEVAL_TOP_K", "INTERVIEW_RETRIEVAL_MIN_SCORE",
            "INTERVIEW_RETRIEVAL_MAX_CONTEXT_CHARS"]
        return {
            "appHome": str(settings.root), "workingDir": os.getcwd(), "env": {name: os.getenv(name) for name in names},
            "bound": {"baseUrl": settings.llm_base_url, "chatModel": settings.llm_model,
                      "maxTokens": settings.llm_max_tokens, "maxRetries": settings.llm_max_retries,
                      "evalSamples": settings.llm_eval_samples, "embeddingMode": settings.embedding_mode,
                      "retrievalTopK": settings.retrieval_top_k,
                      "retrievalMinScore": settings.retrieval_min_score,
                      "retrievalMaxContextChars": settings.retrieval_max_context_chars,
                      "knowledgePath": str(settings.knowledge_path),
                      "storageMode": settings.storage_mode, "dataDir": str(settings.data_dir)},
        }

    @app.post("/api/resumes/import")
    async def import_resume(file: UploadFile = File(...)):
        body = await file.read(settings.max_file_bytes + 1)
        resume, confidence = engine.import_file(file.filename or "未命名文件", body)
        return _import_view(resume, confidence)

    @app.post("/api/resumes/text")
    def import_text(body: dict[str, Any] = Body(...)):
        if not isinstance(body.get("text"), str) or (
            body.get("fileName") is not None and not isinstance(body.get("fileName"), str)
        ):
            raise invalid_request_body("text 必须是字符串，fileName 必须是字符串或 null。")
        resume, confidence = engine.import_text(body["text"], body.get("fileName"))
        return _import_view(resume, confidence)

    @app.get("/api/resumes")
    def list_resumes():
        return [engine.resume_view(value) for value in repo.list("resumes")]

    @app.get("/api/resumes/{resume_id}")
    def get_resume(resume_id: str):
        return engine.resume_view(engine.require_resume(resume_id))

    @app.put("/api/resumes/{resume_id}/facts")
    def update_facts(resume_id: str, body: dict[str, Any] = Body(...)):
        facts = body.get("facts")
        if not isinstance(facts, list) or any(not isinstance(item, dict) for item in facts):
            raise invalid_request_body("facts 必须是事实对象数组。")
        for item in facts:
            if any(item.get(field) is not None and not isinstance(item.get(field), str)
                   for field in ("id", "type", "label", "content")):
                raise invalid_request_body("事实的 id、type、label、content 必须是字符串或 null。")
            metadata = item.get("metadata")
            if metadata is not None and (
                not isinstance(metadata, list) or any(not isinstance(value, str) for value in metadata)
            ):
                raise invalid_request_body("事实的 metadata 必须是字符串数组或 null。")
        return engine.resume_view(engine.update_facts(resume_id, facts))

    @app.delete("/api/resumes/{resume_id}")
    def delete_resume(resume_id: str):
        engine.delete_resume(resume_id)
        return {"ok": True, "message": "简历已删除，相关检索片段已清理。"}

    @app.post("/api/interviews")
    def create_interview(body: dict[str, Any] = Body(...)):
        resume_id, mode, maximum = body.get("resumeId"), body.get("mode"), body.get("maxQuestions")
        if resume_id is not None and not isinstance(resume_id, str):
            raise invalid_request_body("resumeId 必须是字符串或 null。")
        if not isinstance(mode, str):
            raise invalid_request_body("mode 必须是字符串。")
        if maximum is not None and (not isinstance(maximum, int) or isinstance(maximum, bool)):
            raise invalid_request_body("maxQuestions 必须是整数或 null。")
        session = engine.create_session(resume_id, mode, maximum)
        return engine.session_view(session)

    @app.get("/api/interviews")
    def list_interviews():
        return [engine.session_view(value) for value in repo.list("sessions")]

    @app.get("/api/interviews/{session_id}")
    def get_interview(session_id: str):
        return engine.session_view(engine.require_session(session_id))

    @app.post("/api/interviews/{session_id}/next-question")
    def next_question(session_id: str):
        return engine.question_view(engine.next_question(session_id))

    @app.post("/api/interviews/{session_id}/answers")
    def submit_answer(session_id: str, body: dict[str, Any] = Body(...)):
        if body.get("questionId") is not None and not isinstance(body.get("questionId"), str):
            raise invalid_request_body("questionId 必须是字符串或 null。")
        if not isinstance(body.get("content"), str):
            raise invalid_request_body("content 必须是字符串。")
        answer, evaluation, session, action, hint = engine.submit_answer(
            session_id, body.get("questionId"), body["content"],
        )
        return {"answerId": answer.id, "questionId": answer.questionId,
                "evaluation": engine.evaluation_view(evaluation), "session": engine.session_view(session),
                "nextAction": action, "nextActionHint": hint}

    @app.get("/api/interviews/{session_id}/evaluation")
    def evaluation(session_id: str):
        session = engine.require_session(session_id)
        result = engine.latest_evaluation(session_id)
        if not result:
            raise not_found("评分反馈（请先提交回答）", session_id)
        return {"evaluation": engine.evaluation_view(result), "session": engine.session_view(session),
                "nextAction": session.status.value, "nextActionHint": "可以继续追问、获取下一题或结束面试。"}

    @app.post("/api/interviews/{session_id}/follow-up")
    def follow_up(session_id: str):
        return engine.question_view(engine.follow_up(session_id))

    @app.post("/api/interviews/{session_id}/retry")
    def retry(session_id: str):
        question, session, score = engine.retry(session_id)
        return {"question": engine.question_view(question), "session": engine.session_view(session), "discardedScore": score}

    @app.post("/api/interviews/{session_id}/replace-question")
    def replace_question(session_id: str):
        return engine.question_view(engine.replace_question(session_id))

    @app.post("/api/interviews/{session_id}/finish")
    def finish(session_id: str, body: dict[str, Any] | None = Body(None)):
        if body and body.get("reason") is not None and not isinstance(body.get("reason"), str):
            raise invalid_request_body("reason 必须是字符串或 null。")
        return engine.session_view(engine.finish(session_id, (body or {}).get("reason")))

    @app.post("/api/interviews/{session_id}/cancel")
    def cancel(session_id: str):
        return engine.session_view(engine.cancel(session_id))

    @app.post("/api/interviews/{session_id}/report")
    def generate_report(session_id: str):
        report = engine.generate_report(session_id)
        return {"report": engine.report_view(report), "markdown": report.markdown}

    @app.get("/api/interviews/{session_id}/report")
    def get_report(session_id: str):
        report = engine.require_report(session_id)
        return {"report": engine.report_view(report), "markdown": report.markdown}

    @app.get("/api/interviews/{session_id}/report.md")
    def report_markdown(session_id: str):
        report = engine.require_report(session_id)
        headers = {"Content-Disposition": f'attachment; filename="interview-report-{session_id}.md"'}
        return Response(report.markdown.encode("utf-8"), media_type="text/markdown; charset=utf-8", headers=headers)

    @app.get("/api/knowledge/topics")
    def knowledge_topics():
        return retriever.topics()

    @app.get("/api/knowledge/items")
    def knowledge_items(topic: str | None = None):
        return [{"id": x["id"], "topic": x.get("topic", ""), "subtopic": x.get("subtopic", ""),
                 "difficulty": x.get("difficulty", "MEDIUM"), "title": x.get("title", ""),
                 "keyPoints": x.get("keyPoints", [])} for x in retriever.by_topic(topic)]

    @app.get("/api/retrieval/search")
    def retrieval_search(q: str = Query(...), resumeId: str | None = None, mode: str = "PROJECT", topK: int | None = None):
        if not q.strip():
            raise validation("查询内容不能为空。")
        mode = mode.upper()
        if mode not in {"PROJECT", "KNOWLEDGE", "FULL"}:
            raise validation(f"未知的面试模式：{mode}")
        hits = retriever.search(q, mode, resumeId, topK or settings.retrieval_top_k)
        citations = [f"{'知识点' if h.chunk.kind == 'KNOWLEDGE' else '简历片段'}：{h.chunk.title}" for h in hits]
        context = "\n".join(
            f"[{i}] {c}: {h.chunk.content}"
            for i, (c, h) in enumerate(zip(citations, hits, strict=False), 1)
        )
        return {"query": q, "empty": not hits, "citations": citations,
                "chunks": [{"id": h.chunk.id, "kind": h.chunk.kind, "label": h.chunk.title,
                            "score": h.score, "fusedScore": h.score, "text": _abbreviate(h.chunk.content)} for h in hits],
                "contextPreview": _abbreviate(context, settings.retrieval_max_context_chars)}

    app.mount("/", StaticFiles(directory=settings.static_dir, html=True), name="static")
    return app


def _import_view(resume, confidence: float) -> dict[str, Any]:
    preview = " ".join(resume.maskedText.split())
    return {"resumeId": resume.id, "fileName": resume.fileName, "fileType": resume.fileType,
            "status": resume.status, "factCount": len(resume.facts), "confidence": round(confidence, 2),
            "warnings": resume.warnings, "preview": preview[:400] + ("…" if len(preview) > 400 else "")}


def _abbreviate(value: str, maximum: int = 500) -> str:
    value = value or ""
    return value if len(value) <= maximum else value[:maximum] + "…"
