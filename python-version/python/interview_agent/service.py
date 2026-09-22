from __future__ import annotations

import hashlib
import re
import threading
import uuid
from datetime import UTC, datetime
from functools import wraps
from pathlib import Path
from typing import Any

from .config import Settings
from .errors import file_too_large, invalid_state, not_found, parse_error, validation
from .llm import LlmClient, bool_value, string_list
from .models import (
    Answer,
    Evaluation,
    FactType,
    InterviewMode,
    Question,
    Report,
    Resume,
    ResumeFact,
    Session,
    SessionStatus,
    now_iso,
)
from .parsing import clean_text, extract_document, extract_facts
from .privacy import mask
from .rag import Hit, Retriever
from .storage import Repository

TYPE_LABELS = {
    "EDUCATION": "教育经历", "INTERNSHIP": "实习/工作经历", "PROJECT": "项目经历",
    "SKILL": "技能", "AWARD": "奖项", "SUMMARY": "个人概况", "KNOWLEDGE": "知识点", "OTHER": "其它",
}
MODE_LABELS = {"PROJECT": "项目面", "KNOWLEDGE": "八股面", "FULL": "完整模拟面试"}
STATE_DESCRIPTIONS = {
    "CREATED": "面试已创建，尚未绑定简历", "RESUME_READY": "简历已就绪，可以开始面试",
    "INTERVIEW_STARTED": "面试已开始，等待出题", "WAITING_ANSWER": "已下发问题，等待你的回答",
    "EVALUATING": "正在评估回答", "FOLLOW_UP": "已生成追问，等待你的回答",
    "NEXT_QUESTION": "本题已完成，可以进入下一题", "FINISHED": "面试已结束，可以生成报告",
    "REPORT_READY": "报告已生成", "FAILED": "会话失败，请新建面试", "CANCELLED": "会话已取消",
}
DIMENSIONS = [
    ("technicalCorrectness", "技术正确性"), ("completeness", "内容完整性"),
    ("experienceMatch", "经历匹配度"), ("expressionStructure", "表达结构"),
]
PROJECT_QUESTION_TYPES = {
    "PROJECT", "PROJECT_TECH", "PROJECT_TRADEOFF", "PROJECT_BOUNDARY", "PROJECT_RESULT",
}
ABILITY_RULES = [
    ("project_expression", "项目表达", PROJECT_QUESTION_TYPES, ()),
    ("java_basic", "Java 基础", set(),
     ("java", "jvm", "jmm", "集合", "hashmap", "并发", "线程", "线程池", "gc", "类加载", "string")),
    ("database", "数据库", set(),
     ("mysql", "索引", "事务", "mvcc", "间隙锁", "sql", "postgresql", "数据库")),
    ("middleware", "中间件", set(),
     ("redis", "kafka", "rabbitmq", "rocketmq", "mq", "zookeeper", "缓存")),
    ("network_os", "网络与操作系统", set(),
     ("tcp", "http", "网络", "io", "epoll", "进程", "操作系统", "虚拟内存")),
    ("ai_application", "AI 应用", set(),
     ("rag", "embedding", "向量", "prompt", "agent", "大模型", "llm", "检索")),
    ("system_design", "系统设计", set(),
     ("架构", "微服务", "幂等", "分布式", "限流", "熔断", "设计模式")),
]


def _synchronized(method):
    @wraps(method)
    def wrapped(self, *args, **kwargs):
        with self._workflow_lock:
            return method(self, *args, **kwargs)
    return wrapped


def _uuid() -> str:
    return str(uuid.uuid4())


class InterviewEngine:
    def __init__(self, settings: Settings, repository: Repository, retriever: Retriever, llm: LlmClient):
        self.settings = settings
        self.repo = repository
        self.retriever = retriever
        self.llm = llm
        # A single process/file store is the declared deployment boundary.  Keep
        # each state transition atomic so duplicate HTTP submissions cannot both
        # observe WAITING_ANSWER and increment statistics twice.
        self._workflow_lock = threading.RLock()
        for resume in self.repo.list("resumes"):
            self.retriever.index_resume(resume)  # type: ignore[arg-type]

    # ---------------------------------------------------------------- resumes

    @_synchronized
    def import_file(self, file_name: str, content: bytes) -> tuple[Resume, float]:
        if not content:
            raise parse_error("EMPTY_RESUME_TEXT", "上传的文件内容为空，请重新选择文件。")
        if len(content) > self.settings.max_file_bytes:
            raise file_too_large()
        text, file_type, warnings = extract_document(file_name, content)
        return self._build_resume(Path(file_name or "未命名文件").name, file_type, len(content), text, warnings)

    @_synchronized
    def import_text(self, text: str, file_name: str | None) -> tuple[Resume, float]:
        if not text or not text.strip():
            raise validation("粘贴的简历内容不能为空。")
        return self._build_resume(file_name or "手动粘贴的简历.txt", "txt", len(text.encode("utf-8")), text, [])

    def _build_resume(self, file_name: str, file_type: str, size: int, text: str, warnings: list[str]) -> tuple[Resume, float]:
        cleaned, clean_warnings = clean_text(text[:100_000])
        resume_id = _uuid()
        facts, confidence, fact_warnings = extract_facts(cleaned, resume_id)
        warnings = warnings + clean_warnings + fact_warnings
        masked = mask(cleaned)
        resume = Resume(
            id=resume_id, fileName=file_name, fileType=file_type, fileSize=size,
            rawText=masked, maskedText=masked, status="PARSED" if confidence >= 0.5 else "NEEDS_REVIEW",
            warnings=warnings, facts=facts,
        )
        self.repo.save("resumes", resume)
        self.retriever.index_resume(resume)
        return resume, confidence

    def require_resume(self, resume_id: str) -> Resume:
        value = self.repo.get("resumes", resume_id)
        if not value:
            raise not_found("简历", resume_id)
        return value  # type: ignore[return-value]

    @_synchronized
    def update_facts(self, resume_id: str, inputs: list[dict[str, Any]]) -> Resume:
        resume = self.require_resume(resume_id)
        if not inputs:
            raise validation("修正内容不能为空。")
        facts: list[ResumeFact] = []
        for order, item in enumerate(inputs, 1):
            content = mask(str(item.get("content") or "").strip())
            if not content:
                raise validation(f"第 {order} 条事实内容为空，请补充内容或删除该条。")
            try:
                kind = FactType(str(item.get("type") or "").upper())
            except ValueError as exc:
                raise validation(f"未知的事实类型：{item.get('type')}") from exc
            facts.append(ResumeFact(
                id=str(item.get("id") or f"resume-{kind.value.lower()}-{order:03d}"), resumeId=resume_id,
                type=kind, label=mask(str(item.get("label") or TYPE_LABELS[kind.value])), content=content,
                sourceOrder=order, confidence=1.0,
                metadata=[mask(str(value)) for value in (item.get("metadata") or [])],
            ))
        resume.facts = facts
        resume.updatedAt = now_iso()
        self.repo.save("resumes", resume)
        self.retriever.index_resume(resume)
        return resume

    @_synchronized
    def delete_resume(self, resume_id: str) -> None:
        self.require_resume(resume_id)
        self.repo.delete("resumes", resume_id)
        self.retriever.delete_resume(resume_id)

    # ---------------------------------------------------------------- sessions

    def require_session(self, session_id: str) -> Session:
        value = self.repo.get("sessions", session_id)
        if not value:
            raise not_found("面试会话", session_id)
        return value  # type: ignore[return-value]

    @_synchronized
    def create_session(self, resume_id: str | None, mode_raw: str, max_questions: int | None) -> Session:
        try:
            mode = InterviewMode((mode_raw or "").upper())
        except ValueError as exc:
            raise validation("面试模式不能为空或无效，可选 PROJECT / KNOWLEDGE / FULL。") from exc
        if mode in {InterviewMode.PROJECT, InterviewMode.FULL}:
            if not resume_id:
                raise validation("项目面和完整模拟面试必须选择一份简历。")
            resume = self.require_resume(resume_id)
            if resume.status != "PARSED" or not resume.facts:
                raise validation("这份简历还没有可用的解析结果，请先修正解析内容。")
        try:
            requested = self.settings.max_questions if max_questions is None else int(max_questions)
        except (TypeError, ValueError) as exc:
            raise validation("题目数量必须是整数。") from exc
        maximum = max(1, min(requested, 30))
        session = Session(
            id=_uuid(), resumeId=resume_id, mode=mode,
            status=SessionStatus.RESUME_READY if resume_id else SessionStatus.INTERVIEW_STARTED,
            stage="SELF_INTRO" if mode == InterviewMode.FULL else "SINGLE_QUESTION",
            maxQuestions=maximum,
        )
        return self.repo.save("sessions", session)

    def session_view(self, session: Session) -> dict[str, Any]:
        return {
            "id": session.id, "resumeId": session.resumeId, "mode": session.mode.value,
            "modeLabel": MODE_LABELS[session.mode.value], "stage": session.stage,
            "status": session.status.value, "stateDescription": STATE_DESCRIPTIONS[session.status.value],
            "currentQuestionId": session.currentQuestionId, "questionCount": session.questionCount,
            "answerCount": session.answerCount, "followUpCount": session.followUpCount,
            "maxQuestions": session.maxQuestions, "coveredTopics": session.coveredTopics,
            "recentFeedback": session.recentFeedback, "endReason": session.endReason,
            "createdAt": session.createdAt, "startedAt": session.startedAt, "endedAt": session.endedAt,
        }

    def _question_candidates(self, session: Session, query: str = "") -> list[Hit]:
        mode = session.mode.value
        if session.mode == InterviewMode.FULL:
            mode = "KNOWLEDGE" if session.stage == "FUNDAMENTALS" else "PROJECT"
        hits = self.retriever.search(query, mode, session.resumeId, top_k=20)
        if mode == "PROJECT":
            if session.stage == "SELF_INTRO":
                summaries = [hit for hit in hits if hit.chunk.metadata.get("type") == "SUMMARY"]
                if summaries:
                    return summaries
            projects = [hit for hit in hits if hit.chunk.metadata.get("type") == "PROJECT"]
            if projects:
                return projects
        return hits

    @staticmethod
    def _covered_topic(hit: Hit) -> str | None:
        chunk = hit.chunk
        if chunk.kind == "KNOWLEDGE":
            topic = str(chunk.metadata.get("topic") or chunk.topic or "").strip()
            subtopic = str(chunk.metadata.get("subtopic") or chunk.title).strip()
            return f"{topic}/{subtopic}" if topic else subtopic
        if chunk.metadata.get("type") == "PROJECT":
            return chunk.title
        return None

    @_synchronized
    def next_question(self, session_id: str, replacing: bool = False) -> Question:
        session = self.require_session(session_id)
        if session.status in {SessionStatus.FINISHED, SessionStatus.REPORT_READY, SessionStatus.CANCELLED, SessionStatus.FAILED}:
            raise invalid_state("本场面试已结束，无法继续出题。")
        if session.status == SessionStatus.WAITING_ANSWER and not replacing:
            raise invalid_state("当前问题尚未回答；请先回答、换题或结束面试。")
        if session.questionCount >= session.maxQuestions and not replacing:
            finished = self.finish(session_id, "达到题目数量上限")
            raise invalid_state(
                f"已达到本场题目上限（{finished.questionCount} 题），面试已结束。请查看复盘报告或新建一场面试。"
            )
        sequence = session.questionCount + (0 if replacing else 1)
        if session.mode == InterviewMode.FULL:
            if sequence == 1:
                session.stage = "SELF_INTRO"
            elif sequence == 2:
                session.stage = "PROJECT"
            elif sequence == 3:
                session.stage = "PROJECT_DEEP_DIVE"
            elif sequence == session.maxQuestions:
                session.stage = "CANDIDATE_QUESTIONS"
            else:
                session.stage = "FUNDAMENTALS"
        hits = self._question_candidates(session)
        if not hits:
            raise validation("没有可用于出题的简历事实或知识点。")
        attempt_index = len(session.askedQuestionDigests)
        uncovered = [hit for hit in hits if self._covered_topic(hit) not in session.coveredTopics]
        source_pool = uncovered or hits
        source = source_pool[attempt_index % len(source_pool)] if not uncovered else source_pool[0]
        question = self._generate_question(session, source, sequence)
        self.repo.save("questions", question)
        session.currentQuestionId = question.id
        if not replacing:
            session.questionCount += 1
        session.followUpUsedOnCurrent = 0
        session.status = SessionStatus.WAITING_ANSWER
        session.startedAt = session.startedAt or now_iso()
        session.updatedAt = now_iso()
        topic = self._covered_topic(source)
        if topic and topic not in session.coveredTopics:
            session.coveredTopics.append(topic)
        session.activeProject = (
            source.chunk.title if source.chunk.metadata.get("type") == "PROJECT" else None
        )
        session.askedQuestionDigests.append(self._digest(question.content))
        self.repo.save("sessions", session)
        return question

    def _generate_question(self, session: Session, hit: Hit, sequence: int) -> Question:
        chunk = hit.chunk
        templates = [
            ("PROJECT_BOUNDARY", "在你的「{title}」中，你具体负责了哪一部分？请说明边界、关键决策和验证结果。"),
            ("PROJECT_TECH", "「{title}」里最难的技术点是什么？你如何定位问题并验证解决效果？"),
            ("PROJECT_RESULT", "在「{title}」中，有哪些证据能证明你的工作有效？请区分个人贡献与团队结果。"),
            ("PROJECT_TRADEOFF", "如果「{title}」的流量或数据量增长十倍，哪个设计会先出问题？你会如何改进？"),
        ]
        variant = len(session.askedQuestionDigests)
        if session.stage == "SELF_INTRO":
            content = "请用 1 分钟做自我介绍，重点说明与你目标岗位相关的经历、个人贡献和技术优势。"
            qtype, focus = "BEHAVIOR", ["岗位相关经历", "个人贡献", "技术优势"]
        elif session.stage == "CANDIDATE_QUESTIONS":
            content = "现在进入反问环节。你会向面试官提出哪两个有信息增益的问题？请说明你为什么这样问。"
            qtype, focus = "BEHAVIOR", ["业务目标", "团队协作", "岗位成功标准"]
        elif chunk.kind == "KNOWLEDGE":
            key_points = chunk.metadata.get("keyPoints", [])
            content = f"请解释{chunk.title}，并结合实际场景说明关键机制、适用边界和常见误区。"
            qtype, focus = "PRINCIPLE", list(key_points)[:4]
        else:
            qtype, template = templates[variant % len(templates)]
            content, focus = template.format(title=chunk.title), list(chunk.metadata.get("metadata", []))[:4]
        fallback_content, fallback_type, fallback_focus = content, qtype, list(focus)
        degraded = self.llm.is_mock
        reason = "离线 Mock 模式，使用可复现模板出题" if degraded else None
        if not self.llm.is_mock:
            try:
                raw = self.llm.complete_json(
                    "你是严格的技术面试官。只根据给定材料出一道题，以 JSON 返回 content,type,intent,focus,followUpPlan。",
                    f"模式：{session.mode.value}\n本场第 {variant + 1} 次出题。\n材料标题：{chunk.title}\n材料：{chunk.content}", self.settings.llm_temperature,
                )
                content = str(raw["content"])
                qtype = normalize_question_type(str(raw.get("type", qtype)), chunk.kind)
                raw_focus = raw.get("focus", focus)
                if isinstance(raw_focus, list):
                    focus = [str(x).strip() for x in raw_focus if str(x).strip()]
                elif raw_focus is not None and str(raw_focus).strip():
                    focus = [x.strip() for x in re.split(r"[；;、,，\n]", str(raw_focus)) if x.strip()]
                duplicate = self._digest(content) in session.askedQuestionDigests
                ungrounded_metrics = unsupported_answer_metrics(content, chunk.content)
                if duplicate or ungrounded_metrics:
                    content, qtype, focus = fallback_content, fallback_type, fallback_focus
                    degraded = True
                    reason = "模型问题重复，已改用轮换模板" if duplicate else "模型问题引入无来源量化指标，已改用接地模板"
                else:
                    degraded, reason = False, None
            except Exception as exc:
                degraded, reason = True, f"模型调用失败，已使用模板出题：{type(exc).__name__}"
        return Question(
            id=_uuid(), sessionId=session.id, sequence=sequence, type=qtype, content=content,
            intent="验证候选人是否真正理解并能解释材料中的关键机制", focus=focus,
            sourceIds=[chunk.id], sourceSnippets=[f"{'知识点' if chunk.kind == 'KNOWLEDGE' else '简历片段'}：{chunk.title}"],
            stage=session.stage, degraded=degraded, degradationReason=reason,
        )

    def _digest(self, text: str) -> str:
        return hashlib.sha256(text.strip().lower().encode("utf-8")).hexdigest()[:16]

    def require_question(self, question_id: str) -> Question:
        value = self.repo.get("questions", question_id)
        if not value:
            raise not_found("面试问题", question_id)
        return value  # type: ignore[return-value]

    # ---------------------------------------------------------------- evaluation and workflow

    @_synchronized
    def submit_answer(self, session_id: str, question_id: str | None, content: str) -> tuple[Answer, Evaluation, Session, str, str]:
        session = self.require_session(session_id)
        if session.status not in {SessionStatus.WAITING_ANSWER, SessionStatus.FOLLOW_UP}:
            raise invalid_state("当前状态不允许提交回答：" + STATE_DESCRIPTIONS[session.status.value])
        if not question_id or question_id != session.currentQuestionId:
            raise validation("questionId 与当前问题不一致，请刷新后重试。")
        text = (content or "").strip()
        if len(text) > self.settings.max_answer_chars:
            raise validation(f"回答不能超过 {self.settings.max_answer_chars} 个字符。")
        if not text:
            raise validation("回答内容不能为空。如果暂时不会，可以写「这题我没准备过」，系统会给出思路提示。")
        if len(text) < self.settings.min_answer_chars:
            raise validation(f"回答过短（少于 {self.settings.min_answer_chars} 字），请把思路写清楚一些再提交。")
        question = self.require_question(question_id)
        if any(item.sessionId == session_id and item.questionId == question_id for item in self.repo.list("answers")):
            raise invalid_state("这道题已经提交过回答，请获取下一题、请求追问或选择重新回答。")
        answer = Answer(id=_uuid(), sessionId=session_id, questionId=question_id, content=mask(text))
        self.repo.save("answers", answer)
        evaluation = self._evaluate(answer, question)
        self.repo.save("evaluations", evaluation)
        session.lastAnswerId = answer.id
        session.answerCount += 1
        session.recentFeedback = (session.recentFeedback + [evaluation.summary])[-5:]
        # The last answer advertises FINISH but remains retryable.  The next attempt
        # to fetch a question transitions the session to FINISHED, matching the Java
        # contract and preserving “retry the last answer” semantics.
        if session.questionCount >= session.maxQuestions:
            session.status, action = SessionStatus.NEXT_QUESTION, "FINISH"
            hint = "已完成本场全部题目，可以结束面试并生成复盘报告。"
        elif evaluation.followUpRecommended and session.followUpUsedOnCurrent < self.settings.follow_up_limit:
            session.status, action = SessionStatus.FOLLOW_UP, "FOLLOW_UP"
            hint = "可以追问当前问题，也可以直接进入下一题。"
        else:
            session.status, action = SessionStatus.NEXT_QUESTION, "NEXT_QUESTION"
            hint = "本题已完成，可以获取下一题。"
        session.updatedAt = now_iso()
        self.repo.save("sessions", session)
        return answer, evaluation, session, action, hint

    def _evaluate(self, answer: Answer, question: Question) -> Evaluation:
        text = answer.content.strip()
        source_text = "\n".join(self.retriever.chunks[source_id].content
                                 for source_id in question.sourceIds if source_id in self.retriever.chunks)
        deterministic_warnings = unsupported_answer_metrics(text, source_text)
        if not text:
            scores = [0.0] * 4
        else:
            length_score = min(4.0, 0.7 + len(text) / 120)
            structure = 0.5 + sum(0.55 for word in ("首先", "其次", "最后", "背景", "负责", "结果", "验证", "因为") if word in text)
            mechanism = 0.8 + sum(0.35 for word in ("原理", "机制", "并发", "事务", "缓存", "异常", "监控", "测试") if word in text)
            match = 0.8 + min(2.7, len(set(re.findall(r"[A-Za-z][A-Za-z0-9+#._-]+|[\u4e00-\u9fff]{2,}", text)) & set(re.findall(r"[A-Za-z][A-Za-z0-9+#._-]+|[\u4e00-\u9fff]{2,}", question.content + " ".join(question.focus)))) * 0.45)
            scores = [min(3.5, (length_score + mechanism) / 2), min(3.5, length_score), min(3.5, match), min(3.5, structure)]
        degraded, raw_output = True, None
        remote: dict[str, Any] | None = None
        remote_dims: list[dict[str, Any]] | None = None
        if not self.llm.is_mock and text:
            try:
                samples: list[tuple[float, dict[str, Any], list[dict[str, Any]]]] = []
                last_error: Exception | None = None
                for _ in range(self.settings.llm_eval_samples):
                    try:
                        payload = self.llm.complete_json(
                            "你是技术面试评分器。严格输出 JSON：scores 对象含 technicalCorrectness、completeness、experienceMatch、expressionStructure（0到5），以及 strengths、missingPoints、corrections、suggestedAdditions、summary、followUpRecommended、followUpFocus、referenceAnswerStructure、referenceAnswer、evidenceWarnings。不得编造量化结果。",
                            f"问题：{question.content}\n依据：{'；'.join(question.sourceSnippets)}\n回答：{text}", self.settings.llm_eval_temperature,
                        )
                        dimensions = normalize_dimensions(payload)
                        if not dimensions:
                            raise ValueError("模型未返回任何可识别的评分维度")
                        sample_score = sum(float(item["score"]) for item in dimensions) / len(dimensions)
                        samples.append((sample_score, payload, dimensions))
                    except Exception as exc:
                        last_error = exc
                if not samples:
                    raise last_error or ValueError("模型评分没有有效样本")
                samples.sort(key=lambda item: item[0])
                _, remote, remote_dims = samples[len(samples) // 2]
                scores = [float(item["score"]) for item in remote_dims]
                degraded = False
            except Exception as exc:
                raw_output = f"{type(exc).__name__}: {exc}"[:500]
        total = round(sum(scores) / 4, 2)
        if remote_dims:
            dims = remote_dims
            total = round(sum(float(item["score"]) for item in dims) / len(dims), 2)
        else:
            dims = [{"key": key, "label": label, "score": round(scores[i], 2), "weight": 0.25,
                     "reason": self._dimension_reason(i, scores[i], bool(text))}
                    for i, (key, label) in enumerate(DIMENSIONS)]
        if remote:
            strengths = string_list(remote, "strengths")
            missing = string_list(remote, "missingPoints")
            corrections = string_list(remote, "corrections")
            additions = string_list(remote, "suggestedAdditions")
            present = {item["key"] for item in (remote_dims or [])}
            absent = [label for key, label in DIMENSIONS if key not in present]
            if absent:
                corrections.append("模型未返回以下维度的评分：" + "、".join(absent))
            summary = str(remote.get("summary", f"本题得分 {total}/5。"))
            if self.settings.llm_eval_samples > 1:
                summary += f"（配置为 {self.settings.llm_eval_samples} 次评分，结果取有效样本中位数）"
            follow = bool_value(remote, "followUpRecommended", total < 4)
            focus = str(remote.get("followUpFocus", "补充关键机制与验证证据"))
            structure = str(remote.get("referenceAnswerStructure", "背景 → 职责 → 机制 → 验证 → 结果与反思"))
            reference = str(remote.get("referenceAnswer") or "") or None
            warnings = list(dict.fromkeys(string_list(remote, "evidenceWarnings") + deterministic_warnings))
            if reference:
                reference, dropped = ground_reference_answer(reference, answer.content + "\n" + source_text)
                if dropped:
                    corrections.append(dropped)
        else:
            strengths = ["回答包含一定的有效信息。"] if text else []
            missing = ["回答为空，无法判断掌握程度。"] if not text else ["可进一步补充关键机制、取舍依据和验证方法。"]
            corrections = []
            additions = ["按背景、个人职责、技术机制、验证结果和复盘的顺序组织回答。"]
            summary = "没有收到有效回答。" if not text else f"启发式评估得分 {total}/5；需由真实模型或面试官复核。"
            follow, focus = bool(text and total < 3.2), "补充最关键的技术机制与验证证据"
            structure, reference, warnings = ("背景 → 个人职责 → 技术机制 → 验证 → 结果 → 反思",
                                               None, deterministic_warnings)
        return Evaluation(
            id=_uuid(), answerId=answer.id, questionId=question.id, sessionId=answer.sessionId,
            totalScore=total, dimensions=dims, strengths=strengths, missingPoints=missing,
            corrections=corrections, suggestedAdditions=additions, referenceAnswerStructure=structure,
            referenceAnswer=reference, evidenceWarnings=warnings, followUpRecommended=follow,
            followUpFocus=focus, summary=summary, degraded=degraded, rawModelOutput=raw_output,
        )

    def _dimension_reason(self, index: int, score: float, has_text: bool) -> str:
        if not has_text:
            return "回答为空。"
        labels = ["根据技术术语与机制描述的覆盖情况估算。", "根据回答长度与关键点覆盖估算。",
                  "根据回答与题目/来源材料的词项重合估算。", "根据背景、职责、机制、结果等结构信号估算。"]
        return labels[index] + f" 启发式得分 {score:.2f}，上限 3.5。"

    def latest_evaluation(self, session_id: str) -> Evaluation | None:
        values = [x for x in self.repo.list("evaluations") if x.sessionId == session_id]
        return values[0] if values else None  # type: ignore[return-value]

    @_synchronized
    def follow_up(self, session_id: str) -> Question:
        session = self.require_session(session_id)
        if session.status != SessionStatus.FOLLOW_UP:
            raise invalid_state("当前没有可追问的已评分回答。")
        if session.followUpUsedOnCurrent >= self.settings.follow_up_limit:
            raise invalid_state("本题追问次数已达到上限。")
        parent = self.require_question(session.currentQuestionId or "")
        evaluation = self.latest_evaluation(session_id)
        focus = evaluation.followUpFocus if evaluation else "关键机制"
        question = Question(
            id=_uuid(), sessionId=session_id, sequence=session.questionCount + 1, type="FOLLOW_UP",
            content=f"你刚才提到了相关方案。请进一步说明「{focus}」，并给出验证方式或失败边界。",
            intent="针对上一回答的薄弱点继续追问", focus=[focus], sourceIds=parent.sourceIds,
            sourceSnippets=parent.sourceSnippets, parentQuestionId=parent.id, followUp=True,
            stage=session.stage, degraded=evaluation.degraded if evaluation else True,
            degradationReason="基于评分结果生成追问" if evaluation and evaluation.degraded else None,
        )
        self.repo.save("questions", question)
        session.currentQuestionId = question.id
        session.questionCount += 1
        session.followUpCount += 1
        session.followUpUsedOnCurrent += 1
        session.status = SessionStatus.WAITING_ANSWER
        self.repo.save("sessions", session)
        return question

    @_synchronized
    def retry(self, session_id: str) -> tuple[Question, Session, float | None]:
        session = self.require_session(session_id)
        if session.status not in {SessionStatus.NEXT_QUESTION, SessionStatus.FOLLOW_UP}:
            raise invalid_state("只有刚答完且尚未进入下一题时才能重新回答。")
        question = self.require_question(session.currentQuestionId or "")
        answer_id = session.lastAnswerId
        discarded: float | None = None
        if answer_id:
            evaluations = [x for x in self.repo.list("evaluations") if x.answerId == answer_id]
            if evaluations:
                discarded = evaluations[0].totalScore  # type: ignore[attr-defined]
                self.repo.delete("evaluations", evaluations[0].id)
            self.repo.delete("answers", answer_id)
            session.answerCount = max(0, session.answerCount - 1)
        session.lastAnswerId = None
        if session.recentFeedback:
            session.recentFeedback = session.recentFeedback[:-1]
        session.status = SessionStatus.WAITING_ANSWER
        self.repo.save("sessions", session)
        return question, session, discarded

    @_synchronized
    def replace_question(self, session_id: str) -> Question:
        session = self.require_session(session_id)
        if session.status != SessionStatus.WAITING_ANSWER:
            raise invalid_state("只能更换已下发且尚未回答的问题。")
        if session.questionSkips >= self.settings.max_question_skips:
            raise invalid_state("换题次数已达到上限。")
        old_id = session.currentQuestionId
        session.questionSkips += 1
        if old_id:
            self.repo.delete("questions", old_id)
        self.repo.save("sessions", session)
        return self.next_question(session_id, replacing=True)

    @_synchronized
    def finish(self, session_id: str, reason: str | None) -> Session:
        session = self.require_session(session_id)
        if session.status in {SessionStatus.FINISHED, SessionStatus.REPORT_READY}:
            return session
        if session.status in {SessionStatus.CANCELLED, SessionStatus.FAILED}:
            raise invalid_state("本场面试已结束，无法重复结束。")
        session.status = SessionStatus.FINISHED
        session.endReason = reason or "用户主动结束"
        session.endedAt = now_iso()
        self.repo.save("sessions", session)
        return session

    @_synchronized
    def cancel(self, session_id: str) -> Session:
        session = self.require_session(session_id)
        if session.status in {SessionStatus.FINISHED, SessionStatus.REPORT_READY, SessionStatus.CANCELLED}:
            raise invalid_state("本场面试已结束，无法取消。")
        session.status, session.endReason, session.endedAt = SessionStatus.CANCELLED, "用户取消", now_iso()
        self.repo.save("sessions", session)
        return session

    # ---------------------------------------------------------------- reports

    def require_report(self, session_id: str) -> Report:
        reports = [x for x in self.repo.list("reports") if x.sessionId == session_id]
        if not reports:
            raise not_found("复盘报告（请先生成）", session_id)
        return reports[0]  # type: ignore[return-value]

    @_synchronized
    def generate_report(self, session_id: str) -> Report:
        session = self.require_session(session_id)
        if session.status not in {SessionStatus.FINISHED, SessionStatus.REPORT_READY}:
            session = self.finish(session_id, "生成报告前自动结束")
        evaluations: list[Evaluation] = [x for x in self.repo.list("evaluations") if x.sessionId == session_id]  # type: ignore[assignment]
        scores = [e.totalScore for e in evaluations]
        overall = round(sum(scores) / len(scores), 2) if scores else 0.0
        scored = [(self.require_question(e.questionId), e) for e in evaluations]
        radar = []
        for key, label, types, keywords in ABILITY_RULES:
            values = []
            for question, evaluation in scored:
                haystack = (question.content + " " + " ".join(question.focus)).lower()
                if (types and question.type in types) or (not types and any(word in haystack for word in keywords)):
                    values.append(evaluation.totalScore)
            radar.append({"key": key, "label": label,
                          "score": round(sum(values) / len(values), 2) if values else 0.0,
                          "sample": len(values)})
        weakest = [
            f"[{evaluation.totalScore:.1f} 分] {question.content[:80]}"
            for question, evaluation in sorted(scored, key=lambda item: item[1].totalScore)
            if question.type != "FOLLOW_UP"
        ][:5]
        gaps = []
        gap_topics: set[str] = set()
        actions = []
        risks = []
        for question, evaluation in scored:
            if evaluation.totalScore < 3.0 or evaluation.corrections or evaluation.evidenceWarnings:
                topic = (question.focus[0] if question.focus else question.content[:40]).strip()
                if topic and topic not in gap_topics:
                    gap_topics.add(topic)
                    reason = ("回答明显不足（低于 2 分）" if evaluation.totalScore < 2.0
                              else "回答不完整（低于 3 分）" if evaluation.totalScore < 3.0
                              else "存在技术表述需要纠正" if not evaluation.evidenceWarnings
                              else "存在没有证据的强主张")
                    evidence = list(dict.fromkeys(evaluation.corrections[:2] + evaluation.evidenceWarnings[:2]))
                    gaps.append({"topic": topic, "reason": reason, "evidence": evidence})
            if evaluation.totalScore < 3.0 and question.type != "FOLLOW_UP":
                actions.append(f"重答题目「{question.content[:40]}」，按参考结构组织后再提交一次")
            if question.type in PROJECT_QUESTION_TYPES and evaluation.totalScore < 4.0:
                risks.extend(([evaluation.followUpFocus] if evaluation.followUpFocus.strip() else [])
                             + evaluation.missingPoints)
        actions = list(dict.fromkeys(actions))[:5]
        if not actions and scored:
            actions = ["挑一道最没把握的题，按「背景 → 职责 → 机制 → 难点 → 结果」重写一遍"]
        if not actions:
            actions = ["先完成至少一道题的作答，才能得到有针对性的复盘建议"]
        risks = list(dict.fromkeys(item[:60] for item in risks if item.strip()))[:4]
        summary = f"本场完成 {session.answerCount} 次回答，综合得分 {overall:.2f}/5。"
        ended = session.endedAt or now_iso()
        duration = max(0, int((_dt(ended) - _dt(session.startedAt or session.createdAt)).total_seconds()))
        markdown = self._render_report(session, overall, radar, evaluations, weakest, gaps, actions, summary)
        old = [x for x in self.repo.list("reports") if x.sessionId == session_id]
        report = Report(
            id=old[0].id if old else _uuid(), sessionId=session_id, mode=session.mode,
            startedAt=session.startedAt or session.createdAt, endedAt=ended, durationSeconds=duration,
            questionCount=session.questionCount, answerCount=session.answerCount, overallScore=overall,
            abilityRadar=radar, weakestQuestions=weakest, knowledgeGaps=gaps,
            projectRisks=risks,
            actionItems=actions, summary=summary, markdown=markdown,
        )
        self.repo.save("reports", report)
        session.status = SessionStatus.REPORT_READY
        self.repo.save("sessions", session)
        return report

    def _render_report(self, session: Session, overall: float, radar: list[dict[str, Any]], evaluations: list[Evaluation],
                       weakest: list[str], gaps: list[dict[str, Any]], actions: list[str], summary: str) -> str:
        lines = ["# AI 面试复盘报告", "", f"- 模式：{MODE_LABELS[session.mode.value]}",
                 f"- 综合得分：{overall:.2f} / 5", f"- 回答数：{session.answerCount}", "", "## 能力维度", ""]
        lines += [f"- {item['label']}：{item['score']:.2f} / 5（样本 {item['sample']}）" for item in radar]
        lines += ["", "## 薄弱题目", ""] + ([f"- {x}" for x in weakest] or ["- 暂无已评分题目"])
        lines += ["", "## 行动建议", ""] + [f"- {x}" for x in actions]
        lines += ["", "## 总结", "", summary, "", "## 逐题明细", ""]
        for index, evaluation in enumerate(reversed(evaluations), 1):
            question = self.require_question(evaluation.questionId)
            answer = self.repo.get("answers", evaluation.answerId)
            lines += [f"### {index}. {question.content}", "", f"- 得分：{evaluation.totalScore:.2f} / 5",
                      f"- 我的回答：{getattr(answer, 'content', '[回答已作废]')}",
                      f"- 参考回答：{evaluation.referenceAnswer or '降级评估未生成参考回答'}", ""]
        return "\n".join(lines)

    # ---------------------------------------------------------------- API views

    def resume_view(self, resume: Resume) -> dict[str, Any]:
        facts = [{**f.model_dump(mode="json"), "typeLabel": TYPE_LABELS[f.type.value]} for f in resume.facts]
        projects = [{"id": f.id, "name": f.label, "content": f.content,
                     "techStack": [x for x in f.metadata if not x.startswith("时间：")],
                     "period": next((x for x in f.metadata if x.startswith("时间：")), None)}
                    for f in resume.facts if f.type == FactType.PROJECT]
        overview = next((f.metadata for f in resume.facts if f.type == FactType.SKILL and "总览" in f.label), None)
        tech = overview or list(dict.fromkeys(x for f in resume.facts for x in f.metadata if not x.startswith("时间：")))[:30]
        return {
            "id": resume.id, "fileName": resume.fileName, "fileType": resume.fileType,
            "fileSize": resume.fileSize, "status": resume.status, "errorMessage": resume.errorMessage,
            "warnings": resume.warnings, "facts": facts, "projects": projects, "techStack": tech,
            "text": resume.maskedText, "createdAt": resume.createdAt,
        }

    def question_view(self, question: Question) -> dict[str, Any]:
        data = question.model_dump(mode="json", exclude={"sourceSnippets", "createdAt"})
        data["citations"] = question.sourceSnippets
        return data

    def evaluation_view(self, evaluation: Evaluation) -> dict[str, Any]:
        return evaluation.model_dump(mode="json", exclude={"sessionId", "rawModelOutput"})

    def report_view(self, report: Report) -> dict[str, Any]:
        data = report.model_dump(mode="json", exclude={"markdown", "createdAt"})
        data["modeLabel"] = MODE_LABELS[report.mode.value]
        return data


def _dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


_DIMENSION_ALIASES = {
    "technicalcorrectness": "technicalCorrectness", "technical_correctness": "technicalCorrectness",
    "技术正确性": "technicalCorrectness", "正确性": "technicalCorrectness",
    "completeness": "completeness", "内容完整性": "completeness", "完整性": "completeness",
    "experiencematch": "experienceMatch", "experience_match": "experienceMatch",
    "经历匹配度": "experienceMatch", "经历匹配": "experienceMatch",
    "expressionstructure": "expressionStructure", "expression_structure": "expressionStructure",
    "structure": "expressionStructure", "表达结构": "expressionStructure", "结构": "expressionStructure",
}


def normalize_dimensions(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Normalize common real-model score shapes and scales to the API contract."""
    raw = payload.get("dimensionScores")
    if raw is None:
        raw = payload.get("scores")
    if raw is None:
        # Small models often flatten the requested scores object into top-level
        # fields even when response_format=json_object is respected.
        top_level = {key: value for key, value in payload.items()
                     if (_DIMENSION_ALIASES.get(re.sub(r"[\s-]", "", str(key)).lower())
                         or _DIMENSION_ALIASES.get(str(key).strip()))}
        raw = top_level or None
    entries: list[tuple[str, Any]] = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, dict):
                name = item.get("dimension") or item.get("key") or item.get("name") or ""
                entries.append((str(name), item))
    elif isinstance(raw, dict):
        entries = [(str(key), value) for key, value in raw.items()]
    labels = dict(DIMENSIONS)
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for name, value in entries:
        compact = re.sub(r"[\s-]", "", name).lower()
        key = _DIMENSION_ALIASES.get(compact) or _DIMENSION_ALIASES.get(name.strip())
        if not key or key in seen:
            continue
        item = value if isinstance(value, dict) else {"score": value}
        nested = item.get("scores") if isinstance(item.get("scores"), dict) else {}
        raw_score = item.get("score", item.get("value", nested.get("value")))
        try:
            match = re.search(r"-?\d+(?:\.\d+)?", str(raw_score))
            if not match:
                continue
            score = float(match.group())
        except (TypeError, ValueError):
            continue
        if score > 10:
            score /= 20.0
        elif score > 5:
            score /= 2.0
        score = round(max(0.0, min(5.0, score)), 2)
        reason = next((str(item.get(field)).strip() for field in ("reason", "comment", "explanation", "rationale")
                       if item.get(field) is not None and str(item.get(field)).strip()), "")
        if not reason and nested:
            reason = str(nested.get("reason") or "").strip()
        reason = re.sub(r"^(?:[:：]|[-—]{1,2})\s*", "", reason) or "模型未给出打分依据"
        result.append({"key": key, "label": labels[key], "score": score, "weight": 0.25, "reason": reason})
        seen.add(key)
    order = {key: index for index, (key, _) in enumerate(DIMENSIONS)}
    return sorted(result, key=lambda item: order[item["key"]])


_METRIC_PATTERN = re.compile(
    r"\d+(?:\.\d+)?\s*(?:%|％|倍|万|亿|ms|毫秒|秒|分钟|小时|QPS|TPS|qps|tps|人日|天)"
)


def ground_reference_answer(reference: str, allowed_source: str) -> tuple[str | None, str | None]:
    """Drop a model-written sample answer if it introduces unsupported metrics."""
    text = (reference or "").strip()
    if not text:
        return None, None
    for match in _METRIC_PATTERN.finditer(text):
        digits = re.sub(r"[^0-9.]", "", match.group())
        if digits and digits not in (allowed_source or ""):
            return None, ("模型生成的参考回答引入了原回答与简历中都没有的量化指标，"
                          "为避免编造已丢弃该段参考回答；请只使用真实数据组织回答。")
    return text, None


def unsupported_answer_metrics(answer: str, source: str) -> list[str]:
    warnings: list[str] = []
    for match in _METRIC_PATTERN.finditer(answer or ""):
        metric = match.group()
        digits = re.sub(r"[^0-9.]", "", metric)
        if digits and digits not in (source or ""):
            warnings.append(f"回答中的量化指标“{metric}”未在简历事实中找到，请确认其真实且可核验。")
    return list(dict.fromkeys(warnings))


_QUESTION_TYPES = {
    "PROJECT", "PROJECT_TECH", "PROJECT_TRADEOFF", "PROJECT_BOUNDARY", "PROJECT_RESULT",
    "CONCEPT", "PRINCIPLE", "APPLICATION", "BOUNDARY", "COMPARISON", "BEHAVIOR", "FOLLOW_UP",
}


def normalize_question_type(raw: str, source_kind: str) -> str:
    value = (raw or "").strip().upper().replace("-", "_").replace(" ", "_")
    aliases = {
        "TECHNICAL": "PROJECT_TECH", "TECH": "PROJECT_TECH", "DESIGN": "PROJECT_TRADEOFF",
        "TRADEOFF": "PROJECT_TRADEOFF", "RESULT": "PROJECT_RESULT", "KNOWLEDGE": "CONCEPT",
        "FUNDAMENTAL": "PRINCIPLE", "FUNDAMENTALS": "PRINCIPLE", "BEHAVIORAL": "BEHAVIOR",
    }
    value = aliases.get(value, value)
    if value in _QUESTION_TYPES:
        return value
    return "PRINCIPLE" if source_kind == "KNOWLEDGE" else "PROJECT_TECH"
