from dataclasses import replace
from pathlib import Path

import pytest
from interview_agent.config import Settings
from interview_agent.llm import bool_value, number_value, parse_json_object, string_list
from interview_agent.models import Answer, Question
from interview_agent.rag import Retriever
from interview_agent.service import (
    InterviewEngine,
    ground_reference_answer,
    normalize_dimensions,
    normalize_question_type,
    unsupported_answer_metrics,
)
from interview_agent.storage import Repository


def test_structured_output_repairs_common_model_defects():
    assert parse_json_object('```json\n{"summary":"ok"}\n```', ["summary"])["summary"] == "ok"
    assert parse_json_object('说明：{"summary":"ok"} 完毕', ["summary"])["summary"] == "ok"
    repaired = parse_json_object("{'summary': '回答不错', score: 4, }", ["summary"])
    assert repaired == {"summary": "回答不错", "score": 4}
    truncated = parse_json_object('{"summary":"回答覆盖机制","missingPoints":["边界","验证"', ["summary", "missingPoints"])
    assert truncated["missingPoints"] == ["边界", "验证"]


def test_structured_output_rejects_invalid_or_missing_fields():
    with pytest.raises(ValueError, match="空内容"):
        parse_json_object("")
    with pytest.raises(ValueError, match="没有 JSON"):
        parse_json_object("回答挺好的")
    with pytest.raises(ValueError, match="缺少必需字段"):
        parse_json_object('{"foo": 1}', ["summary"])


def test_structured_scalar_helpers():
    data = {"a": "4 分", "b": "3.5/5", "c": 9, "yes": "是", "no": "no", "items": "x；y；z", "empty": 0}
    assert number_value(data, "a") == 4
    assert number_value(data, "b") == 3.5
    assert number_value(data, "c") == 5
    assert bool_value(data, "yes") is True and bool_value(data, "no", True) is False
    assert string_list(data, "items") == ["x", "y", "z"]
    assert string_list(data, "empty") == []


def test_dimension_array_ten_point_scale_and_reason_aliases():
    dimensions = normalize_dimensions({"dimensionScores": [
        {"dimension": "technical_correctness", "score": 8, "reason": "机制正确"},
        {"dimension": "completeness", "score": 7, "comment": "缺少结果"},
        {"dimension": "experience_match", "score": 10, "explanation": "与事实一致"},
        {"dimension": "structure", "score": 9, "rationale": "结构清晰"},
    ]})
    assert [item["score"] for item in dimensions] == [4.0, 3.5, 5.0, 4.5]
    assert all(item["reason"] != "模型未给出打分依据" for item in dimensions)


def test_dimension_nested_chinese_and_percent_scales():
    nested = normalize_dimensions({"dimensionScores": [
        {"dimension": "技术正确性", "scores": {"value": 4, "reason": ": 推理正确"}},
        {"dimension": "内容完整性", "score": 3},
    ]})
    assert nested[0]["key"] == "technicalCorrectness"
    assert nested[0]["reason"] == "推理正确"
    assert nested[1]["reason"] == "模型未给出打分依据"
    percent = normalize_dimensions({"dimensionScores": {
        "技术正确性": {"score": 80}, "completeness": {"score": 60},
        "experience_match": {"score": 100}, "表达结构": {"score": 20},
    }})
    assert [item["score"] for item in percent] == [4.0, 3.0, 5.0, 1.0]


def test_unrecognized_dimensions_do_not_silently_become_zero():
    assert normalize_dimensions({"dimensionScores": {"foo": {"score": 4}}}) == []


def test_flattened_top_level_dimensions_from_small_model():
    dimensions = normalize_dimensions({
        "technicalCorrectness": 4, "completeness": 3,
        "experienceMatch": 5, "expressionStructure": 4,
        "summary": "ok",
    })
    assert [item["score"] for item in dimensions] == [4, 3, 5, 4]


def test_question_type_aliases_are_constrained_to_contract():
    assert normalize_question_type("technical", "RESUME") == "PROJECT_TECH"
    assert normalize_question_type("behavioral", "RESUME") == "BEHAVIOR"
    assert normalize_question_type("invented_type", "KNOWLEDGE") == "PRINCIPLE"


def test_reference_answer_metric_grounding():
    grounded = "QPS 从 800 提升到 3200，重复下单率降到 60%。"
    kept, note = ground_reference_answer(grounded, "回答与简历中存在 800、3200 和 60")
    assert kept == grounded and note is None

    fabricated = "性能提升了 5 倍，平均耗时降到 200ms。"
    kept, note = ground_reference_answer(fabricated, "QPS 从 800 提升到 3200，重复率 60%")
    assert kept is None
    assert "编造" in note and "已丢弃" in note


def test_reference_answer_does_not_mistake_ordinals_for_metrics():
    reference = "第一步梳理链路，第二步把两个模块合并，最后用 Redis 去重。"
    kept, note = ground_reference_answer(reference, "我梳理链路并合并模块")
    assert kept == reference and note is None


def test_answer_metrics_without_resume_evidence_are_warned():
    assert unsupported_answer_metrics("接口提升 5 倍，耗时 200ms", "简历只写了 Redis") == [
        "回答中的量化指标“5 倍”未在简历事实中找到，请确认其真实且可核验。",
        "回答中的量化指标“200ms”未在简历事实中找到，请确认其真实且可核验。",
    ]
    assert unsupported_answer_metrics("QPS 从 800 提升到 3200", "事实：800 到 3200") == []


def test_multi_sample_evaluation_selects_median_score():
    class FakeLlm:
        is_mock = False

        def __init__(self):
            self.calls = 0

        def complete_json(self, *_args):
            score = [1, 5, 3][self.calls]
            self.calls += 1
            return {
                "technicalCorrectness": score, "completeness": score,
                "experienceMatch": score, "expressionStructure": score,
                "summary": f"sample {score}", "followUpRecommended": False,
            }

    settings = replace(Settings.load(), storage_mode="memory", llm_provider="openai-compatible", llm_eval_samples=3)
    knowledge = Path(__file__).resolve().parents[2] / "resources" / "knowledge" / "knowledge-base.json"
    fake = FakeLlm()
    engine = InterviewEngine(settings, Repository(None), Retriever(knowledge), fake)
    question = Question(id="q", sessionId="s", sequence=1, type="PROJECT_TECH",
                        content="如何保证幂等？", intent="test")
    answer = Answer(id="a", sessionId="s", questionId="q", content="使用唯一键和数据库约束保证幂等，并通过测试验证。")
    result = engine._evaluate(answer, question)
    assert fake.calls == 3
    assert result.totalScore == 3.0
    assert "中位数" in result.summary
