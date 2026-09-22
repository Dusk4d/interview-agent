# Java → Python 功能兼容矩阵

这里以项目方案书、Java API 和 Java 测试类别为契约，不以“代码能启动”作为完成标准。

| 契约领域 | Python 实现 | 主要验证证据 | 状态 |
|---|---|---|---|
| 健康检查、配置诊断、静态前端、统一 HTTP 错误结构 | `api.py`、`resources/static` | API 测试 + 进程 smoke + 真实 Chrome 端到端 | 已验证 |
| PDF/DOCX/TXT/粘贴文本 | `parsing.py` | `test_parsing.py`、`test_api.py` | 已验证 |
| 结构化事实与人工修正 | `parsing.py`、`service.py` | 四份 Java fixtures 回归、API 测试 | 已验证 |
| 隐私脱敏 | `privacy.py` | 手机/座机/邮箱/证件/URL/地址测试 | 已验证 |
| 知识库与混合检索 | `rag.py` | 阈值过滤、隔离、远端降级、`test_rag.py` 与检索 API smoke | 已验证 |
| 状态机、选题覆盖与题量限制 | `service.py` | 多项目去重选题、聚焦项目/覆盖主题、项目/八股/完整面试、末题重答、自动结束、追问与并发测试 | 已验证 |
| 模板与真实模型出题 | `service.py`、`llm.py` | Mock 测试 + Ollama 实测 | 已验证 |
| 四维评分与模型畸形容错 | `service.py`、`llm.py` | `test_llm.py` + Ollama 实测 | 已验证 |
| 重答、换题、来源不串题 | `service.py` | API 回归与统计断言 | 已验证 |
| 报告与 Markdown 下载 | `service.py`、`api.py` | API 测试 + 两种进程 smoke | 已验证 |
| JSON 持久化与索引恢复 | `storage.py`、引擎启动 | 全聚合字段重开、更新/删除落盘、损坏隔离 + 真实双进程恢复 | 已验证 |
| OpenAI 兼容协议与诊断 | `llm.py` | 本地 stub server + Ollama 实测 | 已验证 |
| Windows 启动入口 | `start.cmd` | 真实 cmd 启动 + 26 项 smoke | 已验证 |
| Docker 部署 | Dockerfile / Compose | Compose 配置解析 | 部分验证；Engine 未启动 |

“已验证”仅表示矩阵当前列出的行为已有直接测试证据，不代表 Java 186 项测试已全部一一移植。
