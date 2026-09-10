# 部署说明

## 1. 本机直接运行（最简单）

```bat
powershell -ExecutionPolicy Bypass -File scripts\build-dist.ps1
dist\app.cmd
:: 打开 http://127.0.0.1:8090/index.html
```

数据在 `dist/data/*.json`；换端口：`dist\app.cmd --server.port=9000`。

## 2. Docker

```bash
cd deploy

# 只起应用（模型服务跑在宿主机）
docker compose up -d --build

# 连同 Ollama 一起起
docker compose --profile ollama up -d --build
docker compose exec ollama ollama pull qwen2.5:7b

# 查看状态与日志
docker compose ps
docker compose logs -f interview-agent
curl http://127.0.0.1:8090/api/health
```

`Dockerfile` 有两个构建参数需要注意：

* `OFFLINE=true`（默认）：把仓库内的 `.m2repo/` 复制进镜像并用 `mvn -o` 离线构建。
  这适合本机这种无外网环境，代价是构建上下文较大。
* `OFFLINE=false`：标准 Maven 依赖下载模式，需要外网与 `maven:3.9-eclipse-temurin-21` 基础镜像。

### 模型地址

| 场景 | `INTERVIEW_LLM_BASE_URL` |
|---|---|
| 宿主机 LM Studio（Docker Desktop / Windows / macOS） | `http://host.docker.internal:1234/v1` |
| 宿主机 Ollama | `http://host.docker.internal:11434/v1` |
| compose 内的 ollama 服务 | `http://ollama:11434/v1` |
| Linux 原生 Docker（无 host-gateway） | `http://172.17.0.1:1234/v1` |

没有模型也可以先跑演示：`INTERVIEW_LLM_PROVIDER=mock docker compose up -d`。

## 3. 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `INTERVIEW_LLM_BASE_URL` | `http://127.0.0.1:1234/v1` | OpenAI 兼容服务地址 |
| `INTERVIEW_LLM_API_KEY` | `lm-studio` | 本地服务通常任意值 |
| `INTERVIEW_LLM_CHAT_MODEL` | `qwen2.5-7b-instruct` | 对话模型 |
| `INTERVIEW_LLM_EMBEDDING_MODEL` | `text-embedding-nomic-embed-text-v1.5` | 远程向量模型 |
| `INTERVIEW_LLM_PROVIDER` | `local` | 设为 `mock` 使用离线确定性 Mock |
| `INTERVIEW_EMBEDDING_MODE` | `local` | `local` 哈希向量 / `remote` 远端向量 |
| `INTERVIEW_RETRIEVAL_TOP_K` | `5` | 检索条数 |
| `INTERVIEW_STORAGE_MODE` | `file` | `file` / `memory` |
| `INTERVIEW_DATA_DIR` | `<app.home>/data` | 数据目录 |
| `INTERVIEW_KNOWLEDGE_PATH` | - | 外部知识库 JSON（覆盖/追加种子数据） |
| `JAVA_OPTS` | - | 额外 JVM 参数 |

## 4. 生产化建议（当前版本之外的下一步）

* **数据库**：`InterviewRepository` 换成 PostgreSQL 实现即可；表结构可直接映射
  `resume / resume_fact / interview_session / interview_question / interview_answer / answer_evaluation / interview_report`，
  向量检索换 pgvector（`VectorStore` 已是接口）。
* **多实例**：需要把会话状态与文件存储移出进程；同时给问答接口加限流。
* **鉴权**：当前为单用户本地演示，未做账号与权限；对外暴露前需要补齐。
* **可观测**：建议接入指标（降级率、模型耗时 P95、结构化输出失败率）与链路追踪。
* **隐私合规**：默认本地模型；若改用云端模型，需在界面上明确告知并考虑合同/合规要求。
