from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


def now_iso() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class ApiModel(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)


class FactType(StrEnum):
    EDUCATION = "EDUCATION"
    INTERNSHIP = "INTERNSHIP"
    PROJECT = "PROJECT"
    SKILL = "SKILL"
    AWARD = "AWARD"
    SUMMARY = "SUMMARY"
    OTHER = "OTHER"
    KNOWLEDGE = "KNOWLEDGE"


class InterviewMode(StrEnum):
    PROJECT = "PROJECT"
    KNOWLEDGE = "KNOWLEDGE"
    FULL = "FULL"


class SessionStatus(StrEnum):
    CREATED = "CREATED"
    RESUME_READY = "RESUME_READY"
    INTERVIEW_STARTED = "INTERVIEW_STARTED"
    WAITING_ANSWER = "WAITING_ANSWER"
    EVALUATING = "EVALUATING"
    FOLLOW_UP = "FOLLOW_UP"
    NEXT_QUESTION = "NEXT_QUESTION"
    FINISHED = "FINISHED"
    REPORT_READY = "REPORT_READY"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class ResumeFact(ApiModel):
    id: str
    resumeId: str
    type: FactType
    label: str
    content: str
    sourceOrder: int
    confidence: float = 1.0
    metadata: list[str] = Field(default_factory=list)


class Resume(ApiModel):
    id: str
    fileName: str
    fileType: str
    fileSize: int
    rawText: str
    maskedText: str
    status: str
    errorMessage: str | None = None
    warnings: list[str] = Field(default_factory=list)
    facts: list[ResumeFact] = Field(default_factory=list)
    createdAt: str = Field(default_factory=now_iso)
    updatedAt: str = Field(default_factory=now_iso)


class Session(ApiModel):
    id: str
    resumeId: str | None = None
    mode: InterviewMode
    stage: str = "SINGLE_QUESTION"
    status: SessionStatus = SessionStatus.CREATED
    currentQuestionId: str | None = None
    lastAnswerId: str | None = None
    activeProject: str | None = None
    questionCount: int = 0
    answerCount: int = 0
    followUpCount: int = 0
    followUpUsedOnCurrent: int = 0
    questionSkips: int = 0
    maxQuestions: int = 8
    coveredTopics: list[str] = Field(default_factory=list)
    recentFeedback: list[str] = Field(default_factory=list)
    askedQuestionDigests: list[str] = Field(default_factory=list)
    endReason: str | None = None
    createdAt: str = Field(default_factory=now_iso)
    startedAt: str | None = None
    endedAt: str | None = None
    updatedAt: str = Field(default_factory=now_iso)


class Question(ApiModel):
    id: str
    sessionId: str
    sequence: int
    type: str
    difficulty: str = "MEDIUM"
    content: str
    intent: str
    focus: list[str] = Field(default_factory=list)
    sourceIds: list[str] = Field(default_factory=list)
    sourceSnippets: list[str] = Field(default_factory=list)
    followUpPlan: str = "继续追问技术机制与验证方式"
    parentQuestionId: str | None = None
    followUp: bool = False
    stage: str = "SINGLE_QUESTION"
    degraded: bool = False
    degradationReason: str | None = None
    createdAt: str = Field(default_factory=now_iso)


class Answer(ApiModel):
    id: str
    sessionId: str
    questionId: str
    content: str
    createdAt: str = Field(default_factory=now_iso)


class Evaluation(ApiModel):
    id: str
    answerId: str
    questionId: str
    sessionId: str
    totalScore: float
    dimensions: list[dict[str, Any]]
    strengths: list[str]
    missingPoints: list[str]
    corrections: list[str]
    suggestedAdditions: list[str]
    referenceAnswerStructure: str
    referenceAnswer: str | None = None
    evidenceWarnings: list[str] = Field(default_factory=list)
    followUpRecommended: bool = False
    followUpFocus: str = ""
    summary: str = ""
    degraded: bool = True
    rawModelOutput: str | None = None
    createdAt: str = Field(default_factory=now_iso)


class Report(ApiModel):
    id: str
    sessionId: str
    mode: InterviewMode
    startedAt: str
    endedAt: str
    durationSeconds: int
    questionCount: int
    answerCount: int
    overallScore: float
    abilityRadar: list[dict[str, Any]]
    weakestQuestions: list[str]
    knowledgeGaps: list[dict[str, Any]]
    projectRisks: list[str]
    actionItems: list[str]
    summary: str
    markdown: str
    createdAt: str = Field(default_factory=now_iso)
