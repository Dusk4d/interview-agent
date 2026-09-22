from pathlib import Path

from interview_agent.models import (
    Answer,
    Evaluation,
    InterviewMode,
    Question,
    Report,
    Resume,
    ResumeFact,
    Session,
    SessionStatus,
)
from interview_agent.storage import Repository


def test_json_repository_survives_reopen(tmp_path: Path):
    repo = Repository(tmp_path)
    resume = Resume(id="r1", fileName="a.txt", fileType="txt", fileSize=3,
                    rawText="abc", maskedText="abc", status="PARSED")
    repo.save("resumes", resume)
    reopened = Repository(tmp_path)
    restored = reopened.get("resumes", "r1")
    assert restored is not None
    assert restored.fileName == "a.txt"


def test_session_aggregate_survives_reopen_with_nested_fields(tmp_path: Path):
    timestamp = "2024-06-01T10:00:00Z"
    repo = Repository(tmp_path)
    repo.save("sessions", Session(
        id="s1", resumeId="r1", mode=InterviewMode.PROJECT,
        status=SessionStatus.REPORT_READY, currentQuestionId="q1", lastAnswerId="a1",
        activeProject="FinanceAgent 智能财务问答系统", questionCount=1, answerCount=1,
        coveredTopics=["FinanceAgent 智能财务问答系统"], recentFeedback=["回答可用"],
        askedQuestionDigests=["请说明你在项目中的职责"], endReason="用户主动结束",
        createdAt=timestamp, startedAt=timestamp, endedAt=timestamp, updatedAt=timestamp,
    ))
    repo.save("questions", Question(
        id="q1", sessionId="s1", sequence=1, type="PROJECT_TECH",
        content="你在该项目中负责哪一部分？", intent="考察职责边界",
        focus=["个人职责"], sourceIds=["resume-project-001"],
        sourceSnippets=["简历片段：FinanceAgent 智能财务问答系统"], createdAt=timestamp,
    ))
    repo.save("answers", Answer(
        id="a1", sessionId="s1", questionId="q1",
        content="我负责文档解析与检索链路", createdAt=timestamp,
    ))
    repo.save("evaluations", Evaluation(
        id="e1", answerId="a1", questionId="q1", sessionId="s1", totalScore=3.5,
        dimensions=[{"key": "technicalCorrectness", "score": 4.0, "reason": "提到了机制"}],
        strengths=["给出了具体实现"], missingPoints=["缺少量化结果"], corrections=[],
        suggestedAdditions=["补充边界条件"], referenceAnswerStructure="背景 → 职责 → 机制 → 结果",
        referenceAnswer="我负责文档解析与检索链路。", followUpRecommended=True,
        followUpFocus="追问边界", summary="总体可用", degraded=False, createdAt=timestamp,
    ))
    repo.save("reports", Report(
        id="report1", sessionId="s1", mode=InterviewMode.PROJECT,
        startedAt=timestamp, endedAt=timestamp, durationSeconds=300,
        questionCount=1, answerCount=1, overallScore=3.5,
        abilityRadar=[{"key": "projectExpression", "label": "项目表达", "score": 3.5, "sample": 1}],
        weakestQuestions=["[3.5 分] 你在该项目中负责哪一部分？"],
        knowledgeGaps=[{"topic": "个人职责", "reason": "回答不完整", "missingPoints": ["缺少量化结果"]}],
        projectRisks=["可能追问边界条件"], actionItems=["重答该题"],
        summary="总体可用", markdown="# 面试复盘报告", createdAt=timestamp,
    ))

    reopened = Repository(tmp_path)
    session = reopened.get("sessions", "s1")
    question = reopened.get("questions", "q1")
    answer = reopened.get("answers", "a1")
    evaluation = reopened.get("evaluations", "e1")
    report = reopened.get("reports", "report1")

    assert isinstance(session, Session) and session.status is SessionStatus.REPORT_READY
    assert session.coveredTopics == ["FinanceAgent 智能财务问答系统"]
    assert isinstance(question, Question) and question.sourceIds == ["resume-project-001"]
    assert isinstance(answer, Answer) and answer.content == "我负责文档解析与检索链路"
    assert isinstance(evaluation, Evaluation) and evaluation.dimensions[0]["score"] == 4.0
    assert isinstance(report, Report) and report.knowledgeGaps[0]["topic"] == "个人职责"
    assert report.markdown == "# 面试复盘报告"
    assert not list(tmp_path.glob("*.corrupt"))


def test_update_and_delete_are_persisted(tmp_path: Path):
    repo = Repository(tmp_path)
    base = Resume(
        id="r1", fileName="a.txt", fileType="txt", fileSize=3,
        rawText="abc", maskedText="abc", status="PARSED",
        facts=[ResumeFact(
            id="f1", resumeId="r1", type="SKILL", label="技能",
            content="Python", sourceOrder=1, confidence=0.9,
        )],
    )
    repo.save("resumes", base)
    repo.save("resumes", Resume(**{
        **base.model_dump(),
        "status": "NEEDS_REVIEW",
        "errorMessage": "置信度较低",
    }))
    repo.save("resumes", Resume(
        id="r2", fileName="b.txt", fileType="txt", fileSize=3,
        rawText="xyz", maskedText="xyz", status="PARSED",
    ))
    assert repo.delete("resumes", "r2") is True

    reopened = Repository(tmp_path)
    restored = reopened.get("resumes", "r1")
    assert isinstance(restored, Resume)
    assert restored.status == "NEEDS_REVIEW"
    assert restored.errorMessage == "置信度较低"
    assert restored.facts[0].type == "SKILL"
    assert reopened.get("resumes", "r2") is None


def test_corrupt_json_is_quarantined_without_blocking_startup(tmp_path: Path):
    (tmp_path / "resumes.json").write_text("{broken", encoding="utf-8")
    repo = Repository(tmp_path)
    assert repo.list("resumes") == []
    assert (tmp_path / "resumes.json.corrupt").exists()


def test_atomic_replace_leaves_no_temp_file(tmp_path: Path):
    repo = Repository(tmp_path)
    for index in range(10):
        repo.save("resumes", Resume(id=f"r{index}", fileName="a.txt", fileType="txt", fileSize=3,
                                    rawText="abc", maskedText="abc", status="PARSED"))
    assert not list(tmp_path.glob("*.tmp"))
    assert len(Repository(tmp_path).list("resumes")) == 10
