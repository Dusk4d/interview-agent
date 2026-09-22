from pathlib import Path

from interview_agent.config import Settings


def test_environment_configuration_is_bound(monkeypatch, tmp_path: Path):
    values = {
        "INTERVIEW_HOST": "0.0.0.0", "INTERVIEW_PORT": "19090",
        "INTERVIEW_STORAGE_MODE": "memory", "INTERVIEW_DATA_DIR": str(tmp_path / "data"),
        "INTERVIEW_LLM_PROVIDER": "openai-compatible", "INTERVIEW_LLM_BASE_URL": "http://localhost:11434/v1/",
        "INTERVIEW_LLM_CHAT_MODEL": "qwen3:test", "INTERVIEW_LLM_MAX_TOKENS": "2048",
        "INTERVIEW_LLM_MAX_RETRIES": "2", "INTERVIEW_LLM_EVAL_SAMPLES": "3",
        "INTERVIEW_EMBEDDING_MODE": "remote", "INTERVIEW_LLM_EMBEDDING_MODEL": "embed-test",
        "INTERVIEW_EMBEDDING_DIMENSION": "384", "INTERVIEW_KNOWLEDGE_PATH": str(tmp_path / "kb.json"),
        "INTERVIEW_RETRIEVAL_TOP_K": "7", "INTERVIEW_RETRIEVAL_MIN_SCORE": "0.15",
        "INTERVIEW_RETRIEVAL_MAX_CONTEXT_CHARS": "2500",
    }
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    settings = Settings.load(tmp_path)
    assert settings.host == "0.0.0.0" and settings.port == 19090
    assert settings.data_dir == (tmp_path / "data").resolve()
    assert settings.llm_base_url == "http://localhost:11434/v1"
    assert settings.llm_max_tokens == 2048 and settings.llm_max_retries == 2 and settings.llm_eval_samples == 3
    assert settings.disable_thinking is True
    assert settings.embedding_mode == "remote" and settings.embedding_model == "embed-test"
    assert settings.embedding_dimension == 384 and settings.knowledge_path == (tmp_path / "kb.json").resolve()
    assert settings.static_dir.name == "static" and settings.static_dir.is_dir()
    assert settings.retrieval_top_k == 7 and settings.retrieval_min_score == 0.15
    assert settings.retrieval_max_context_chars == 2500


def test_invalid_numeric_environment_values_fall_back(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("INTERVIEW_PORT", "invalid")
    monkeypatch.setenv("INTERVIEW_LLM_MAX_TOKENS", "1")
    monkeypatch.setenv("INTERVIEW_LLM_EVAL_SAMPLES", "0")
    monkeypatch.setenv("INTERVIEW_RETRIEVAL_TOP_K", "999")
    monkeypatch.setenv("INTERVIEW_RETRIEVAL_MIN_SCORE", "2")
    settings = Settings.load(tmp_path)
    assert settings.port == 8090
    assert settings.llm_max_tokens == 64
    assert settings.llm_eval_samples == 1
    assert settings.retrieval_top_k == 20 and settings.retrieval_min_score == 1.0


def test_explicit_disable_thinking_overrides_model_inference(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("INTERVIEW_LLM_CHAT_MODEL", "qwen3:1.7b")
    monkeypatch.setenv("INTERVIEW_LLM_DISABLE_THINKING", "false")
    assert Settings.load(tmp_path).disable_thinking is False


def test_packaged_runtime_resources_match_development_resources():
    root = Path(__file__).resolve().parents[2]
    packaged = root / "python" / "interview_agent" / "resources"
    for relative in (
        "knowledge/knowledge-base.json",
        "static/app.js",
        "static/favicon.svg",
        "static/index.html",
        "static/styles.css",
    ):
        assert (packaged / relative).read_bytes() == (root / "resources" / relative).read_bytes()
