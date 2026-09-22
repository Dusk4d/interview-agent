import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from interview_agent.models import FactType, Resume, ResumeFact
from interview_agent.rag import Retriever

KNOWLEDGE = Path(__file__).resolve().parents[2] / "resources" / "knowledge" / "knowledge-base.json"


def resume(resume_id: str, fact_id: str, text: str) -> Resume:
    fact = ResumeFact(id=fact_id, resumeId=resume_id, type=FactType.PROJECT,
                      label=text.split()[0], content=text, sourceOrder=1, metadata=["Redis"])
    return Resume(id=resume_id, fileName=f"{resume_id}.txt", fileType="txt", fileSize=len(text),
                  rawText=text, maskedText=text, status="PARSED", facts=[fact])


def test_local_embedding_is_deterministic_and_normalized():
    retriever = Retriever(KNOWLEDGE, 128)
    first = retriever.embed("Redis 分布式锁 Java")
    second = retriever.embed("Redis 分布式锁 Java")
    assert first == second
    assert len(first) == 128
    assert abs(sum(value * value for value in first) - 1.0) < 1e-9


def test_resume_search_isolated_by_resume_and_delete_is_effective():
    retriever = Retriever(KNOWLEDGE)
    retriever.index_resume(resume("r1", "fact-r1", "订单中台 Redis 分布式锁 幂等"))
    retriever.index_resume(resume("r2", "fact-r2", "推荐系统 Kafka 消息队列"))
    hits = retriever.search("Redis 幂等", "PROJECT", "r1", 5)
    assert hits and all(hit.chunk.resume_id == "r1" for hit in hits)
    assert hits[0].chunk.id == "fact-r1"
    assert not retriever.search("Redis 幂等", "PROJECT", "r2", 5)
    retriever.delete_resume("r1")
    assert not retriever.search("Redis 幂等", "PROJECT", "r1", 5)


def test_knowledge_and_mixed_search_preserve_source_metadata_and_topk():
    retriever = Retriever(KNOWLEDGE)
    retriever.index_resume(resume("r1", "fact-r1", "HashMap 扩容 红黑树"))
    knowledge = retriever.search("HashMap 扩容 树化", "KNOWLEDGE", None, 2)
    assert 1 <= len(knowledge) <= 2
    assert all(hit.chunk.kind == "KNOWLEDGE" and hit.chunk.topic for hit in knowledge)
    mixed = retriever.search("HashMap 扩容", "FULL", "r1", 10)
    assert any(hit.chunk.kind == "KNOWLEDGE" for hit in mixed)
    assert any(hit.chunk.kind == "RESUME" for hit in mixed)


def test_upsert_same_resume_is_idempotent():
    retriever = Retriever(KNOWLEDGE)
    item = resume("r1", "fact-r1", "Python FastAPI 服务")
    before = len(retriever.chunks)
    retriever.index_resume(item)
    retriever.index_resume(item)
    assert len(retriever.chunks) == before + 1


def test_configured_min_score_does_not_force_irrelevant_results():
    retriever = Retriever(KNOWLEDGE, min_score=0.9)
    retriever.index_resume(resume("r1", "fact-r1", "订单系统 Redis 分布式锁"))
    assert retriever.search("完全不相关的量子物理内容", "PROJECT", "r1", 5) == []
    assert retriever.search("", "PROJECT", "r1", 5)[0].chunk.id == "fact-r1"


class EmbeddingHandler(BaseHTTPRequestHandler):
    calls = 0

    def log_message(self, *_args):
        pass

    def do_POST(self):
        type(self).calls += 1
        length = int(self.headers["Content-Length"])
        payload = json.loads(self.rfile.read(length))
        values = payload["input"] if isinstance(payload["input"], list) else [payload["input"]]
        data = [{"index": index, "embedding": [float(len(text)), 1.0, float(index + 1)]}
                for index, text in enumerate(values)]
        body = json.dumps({"data": data}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def test_remote_embedding_batches_and_runtime_failure_rebuilds_local_index():
    EmbeddingHandler.calls = 0
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), EmbeddingHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    retriever = Retriever(KNOWLEDGE, embedding_mode="remote",
                          base_url=f"http://127.0.0.1:{httpd.server_port}/v1", embedding_model="stub")
    assert retriever.embedding_provider == "remote" and retriever.dimension == 3, retriever.embedding_degradation_reason
    assert EmbeddingHandler.calls == 1  # all knowledge items in one batch
    retriever.index_resume(resume("r1", "fact-r1", "Redis 幂等"))
    assert EmbeddingHandler.calls == 2
    assert retriever.search("Redis", "PROJECT", "r1", 2)
    httpd.shutdown()
    httpd.server_close()
    # Query-time outage switches all vectors to the same local space; no mixed
    # remote/local cosine calculation is allowed.
    assert retriever.search("Redis", "PROJECT", "r1", 2)
    assert retriever.embedding_provider == "local-hash-fallback"
    assert all(len(vector) == retriever.dimension for vector in retriever.vectors.values())


def test_remote_embedding_startup_failure_falls_back_without_blocking_app():
    retriever = Retriever(KNOWLEDGE, 64, embedding_mode="remote", base_url="http://127.0.0.1:1/v1", timeout=0.2)
    assert retriever.embedding_provider == "local-hash-fallback"
    assert retriever.search("volatile 可见性", "KNOWLEDGE", None, 3)
