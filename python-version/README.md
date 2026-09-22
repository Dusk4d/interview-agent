# AI 面试陪练系统 — Python 版

这是原 Java/Spring Boot 项目的独立 Python 重写，全部文件位于 `python-version/`，不会读取或覆盖 Java 版的 `data/`。

## 已迁移能力

- FastAPI HTTP 接口，保持原 `/api/*` 路径和主要 JSON 字段兼容；
- 原网页前端，可直接访问 `http://127.0.0.1:8090/index.html`；
- PDF、DOCX、TXT 与粘贴文本导入，结构化事实、隐私脱敏和人工修正；
- 本地哈希向量 + 关键词混合检索，复用 31 条知识库；
- 项目面、八股面、完整面试会话，含状态机、追问、重答、换题和报告；
- OpenAI 兼容模型接口，失败时降级为模板出题与启发式评分；
- 单用户 JSON 持久化、自动化 API 测试和进程级冒烟脚本。

## 启动

首次安装：

```bat
cd python-version
setup.cmd
```

启动 Mock 模式（默认，无需模型）：

```bat
start.cmd
```

也可以显式选择模式和端口：

```bat
start.cmd mock 8090
start.cmd ollama 8090
start.cmd lmstudio 8090
```

连接 LM Studio / Ollama 等 OpenAI 兼容服务：

```bat
set INTERVIEW_LLM_PROVIDER=openai-compatible
set INTERVIEW_LLM_BASE_URL=http://127.0.0.1:11434/v1
set INTERVIEW_LLM_CHAT_MODEL=qwen3:8b
start.cmd
```

默认数据目录为 `python-version/python-data/`。可用 `INTERVIEW_DATA_DIR` 修改。

常用配置：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `INTERVIEW_PORT` | `8090` | HTTP 端口 |
| `INTERVIEW_STORAGE_MODE` | `file` | `file` 或 `memory` |
| `INTERVIEW_DATA_DIR` | `python-data/` | JSON 数据目录 |
| `INTERVIEW_LLM_PROVIDER` | `mock` | `mock` 或 `openai-compatible` |
| `INTERVIEW_LLM_BASE_URL` | `http://127.0.0.1:1234/v1` | OpenAI 兼容地址 |
| `INTERVIEW_LLM_CHAT_MODEL` | `qwen2.5-7b-instruct` | 对话模型名 |
| `INTERVIEW_LLM_MAX_TOKENS` | `1600` | 单次最大输出 token |
| `INTERVIEW_LLM_MAX_RETRIES` | `0` | 连接/服务端失败重试次数 |
| `INTERVIEW_LLM_EVAL_SAMPLES` | `1` | 评分采样数，大于 1 时取中位数 |
| `INTERVIEW_EMBEDDING_MODE` | `local` | `local` 或 `remote`，远端失败自动回退 |
| `INTERVIEW_LLM_EMBEDDING_MODEL` | `nomic-embed-text` | 远程向量模型 |
| `INTERVIEW_KNOWLEDGE_PATH` | 内置 JSON | 外部知识库文件 |
| `INTERVIEW_RETRIEVAL_TOP_K` | `5` | 默认检索条数（限制为 1–20） |
| `INTERVIEW_RETRIEVAL_MIN_SCORE` | `0.08` | 最低融合分，低于阈值不强行召回 |
| `INTERVIEW_RETRIEVAL_MAX_CONTEXT_CHARS` | `4000` | 检索调试上下文最大字符数 |

## 测试

```bat
cd python-version
set PYTHONDONTWRITEBYTECODE=1
.venv\Scripts\python.exe -m pytest -q
.venv\Scripts\python.exe -m ruff check --no-cache python scripts
```

对已启动的进程做完整 HTTP 冒烟：

```bat
.venv\Scripts\python.exe scripts\smoke_test.py http://127.0.0.1:8090
```

真实 Chrome 页面端到端测试（需要 Node.js 22+，测试完成后脚本会关闭该临时浏览器实例）：

```bat
"C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu --no-first-run --remote-debugging-address=127.0.0.1 --remote-debugging-port=9222 --user-data-dir="%TEMP%\interview-agent-browser-smoke" http://127.0.0.1:8090/
node scripts\browser_smoke.mjs http://127.0.0.1:8090 http://127.0.0.1:9222
```

脚本会实际执行示例简历导入、项目题生成、回答提交、评分和报告渲染，并把未捕获异常、控制台错误、错误提示及失败网络请求视为失败。

Docker 部署：

```bat
docker compose up --build
```

Compose 使用名为 `interview-data` 的 Docker volume 保存 JSON 数据，重建容器不会清空数据。

当前测试范围与尚未验证项见 [`docs/VERIFICATION.md`](docs/VERIFICATION.md)，Java/Python 行为映射见
[`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md)。不要用较少的 Python 用例数量直接替代 Java 版 192 项测试结论。

## 与 Java 版的差异

- Python 版使用 FastAPI/Pydantic，而非 Spring Boot/Java record。
- Python 版 PDF 解析使用 pypdf，DOCX 使用 python-docx。
- 启发式评分有意限制在 3.5 分以内并标记 `degraded=true`；真实模型成功返回结构化评分后才解除降级标记。
- 目前迁移的是产品行为与 API，不追求 Java 类的一对一翻译。原 Java 的 192 项测试不能直接运行在 Python 上，Python 版有独立测试套件。
