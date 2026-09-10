# 验证与测试说明

本文说明「怎么证明系统能用」，以及哪些结论已经实测、哪些仍然待测。

## 一、自动化测试（127 项）

```bat
powershell -ExecutionPolicy Bypass -File scripts\run-tests.ps1
```

流程：`mvn -o test-compile` → `scripts\resolve-classpath.ps1` 解析依赖 → `TestRunner` 用 JUnit Platform API 执行全部用例。

> 为什么不用 `mvn test`：本机离线仓库中 surefire 的 provider 依赖
> （`junit-platform-launcher:1.11.4`）只有 POM 没有 jar，`maven-dependency-plugin`、`exec-maven-plugin` 也缺依赖，
> 因此测试入口改为自带的 `com.dusk4d.interview.testkit.TestRunner`（只依赖 launcher + engine）。
> 在有网环境可直接用 `scripts\mvn.cmd test -DskipTests=false` 走原生 surefire。

### 覆盖矩阵

| 层次 | 测试类 | 数量 | 关键断言 |
|---|---|---|---|
| 隐私 | `PrivacyMaskerTest` | 12 | 手机号 5 种写法、座机、邮箱、身份证、链接、地址；技术数字不误伤；日志脱敏先脱后截 |
| 清洗 | `TextCleanerTest` | 9 | 空文本、CRLF/零宽/制表归一化、重复页眉、页码与分隔线、乱码、噪声、过短 |
| 提取 | `DocumentExtractorRouterTest` | 13 | UTF-8/GB18030 TXT、DOCX 段落与表格、空正文、PDF 文字/多页/图片型、伪装扩展名按文件头判定 |
| 结构化 | `ResumeFactExtractorTest` | 10 | 五类区块、项目分块与元数据、编号项目、无空格标题、极简简历不编造、技术栈不误判 Java/JavaScript |
| 检索 | `VectorStoreTest` | 9 | 向量确定性与归一化、元数据过滤隔离、minScore、upsert 幂等、删除 |
| 解析容错 | `StructuredOutputParserTest` | 11 | 代码围栏、前后文截取、中文引号/单引号/尾逗号/无引号键、截断补全、缺字段报错 |
| 状态机 | `InterviewStateMachineTest` | 7 | 全流程合法、终态封闭（11×11）、非法转移提示、出题/答题守卫 |
| 评分与报告 | `HeuristicEvaluatorTest` | 7 | 空/答非所问/详细回答的分数排序、降级上限、雷达样本量、最弱题与知识缺口 |
| 服务层端到端 | `InterviewFlowTest` | 10 | 项目面全闭环、八股面无简历、完整模拟阶段推进、重复提交、空/超短回答、越界、追问上限、人工修正 |
| HTTP 端到端 | `HttpEndToEndTest` | 9 | 真实 Tomcat + multipart 上传、静态资源、全链路（含报告下载）、错误码 400/404/409/422、检索调试、题目上限自动结束 |
| 持久化 | `JsonFileStoreTest` | 4 | 写盘后重新打开仓储（模拟重启）读回简历/会话/问题/回答/评分/报告；损坏文件隔离后仍可写入 |
| 启动索引 | `VectorIndexInitializerTest` | 4 | 启动期从持久化简历重建检索索引；幂等；空库与空事实跳过 |
| 多简历回归 | `MultiResumeRegressionTest` | 4 | 4 份不同结构简历批量解析不丢字段、结果可复现、极简简历不编造内容 |
| **MVP 总验收** | `MvpAcceptanceTest` | 14 | 方案书第九节验收标准逐条对应，含全部反面场景（脱离简历、串题、模型失败无提示、隐私泄露、解析失败隐瞒） |

### 验收标准对照（`MvpAcceptanceTest`）

| 方案书验收项 | 对应测试方法 | 断言要点 |
|---|---|---|
| 支持一种文件格式 + 一种文本兜底 | `acceptance1_importAllFormats` | TXT/DOCX/PDF 三种 + 粘贴文本均可解析；英文 PDF 无中文区块标题时状态为 NEEDS_REVIEW 并提示 |
| 展示解析结果 + 人工修正 | `acceptance2_reviewAndCorrectFacts` | 修正后事实与检索索引同步更新，检索上下文包含修正内容 |
| 项目面 / 八股面两种模式 | `acceptance3_twoModes` | 项目面来源全部为 `resume-*`、引用为「简历片段」；八股面来源为 `knowledge-*`、引用为「知识点」 |
| 出题、回答、评分、纠错 | `acceptance4_questionAnswerEvaluate` | 四维分数均有依据；评分可反查到 answerId；参考结构非空 |
| 追问或换题 | `acceptance5_followUpAndNextQuestion` | 追问带 `parentQuestionId`，换题不带上一题上下文、序号连续 |
| 结束并生成文字复盘 | `acceptance6_finishAndReport` | 报告含雷达、最弱题、知识缺口、行动建议与 Markdown 七个章节 |
| 空回答 / 模型超时 / 非法 JSON / 检索为空 | `acceptance7_failurePaths` | 四类失败各自有明确结果与标注；检索为空时回退到事实原文且**保留 sourceIds** |
| 隐私 | `acceptance8_privacy` | 入库文本、事实内容、检索上下文、出题内容均无手机号/邮箱 |
| 跨重启恢复 | `acceptance9_persistenceAcrossRestart` | 重启后数据完整、报告可读、检索索引已重建、无 `.corrupt` |
| 反面：问题脱离简历 | `noQuestionWithoutResumeEvidence` | 每题 sourceIds 必须属于该简历的事实 |
| 反面：串题 / 重复提交 | `noCrossQuestionContamination` | 重复提交 409；追问只针对当前题；终态不可继续 |
| 反面：模型失败后页面一直等待 | `noInfiniteWaitingOnModelFailure` | 降级立即返回且字段完整 |
| 反面：解析失败隐瞒 | `parseFailuresAreExplicit` | 图片 PDF / 旧版 doc / 空文件 / 纯符号各自给出明确原因 |

### 异常与边界场景清单（均有测试）

* 空回答、超短回答 → 400，状态保持可重试
* 重复提交同一题 → 409
* 未出题就回答 / 结束后回答 → 409，并给出可操作提示
* 达到题目上限 → 自动结束 + 409 提示（不让前端卡住）
* 追问超限 → 409，提示进入下一题
* 模型连接失败 / 超时 / 非法 JSON / 空响应 → 降级并标注（`MockLlmClient.failing/malformed/emptyLlm` 构造这四类）
* 检索为空 → 回退到简历事实原文，不编造
* 上传图片型 PDF、旧版 .doc、空文件、超大文件、非法 JSON、未知路由 → 4xx + 稳定错误码，无 500

## 二、进程级冒烟验收（打包后真实运行）

```bat
powershell -ExecutionPolicy Bypass -File scripts\build-dist.ps1
dist\app.cmd
scripts\smoke-test.cmd            :: 默认 http://127.0.0.1:8090
```

`SmokeTestMain` 对运行中的实例断言 26 项：健康状态与知识库、前端页面、粘贴导入简历、脱敏、
检索来源引用、会话创建、出题有据（`sourceIds` 非空）、四维评分、追问、题目配额、结束、报告与 Markdown 下载、
以及 400/404/409 错误码映射。

实测输出（2026-09-10，Mock 模型，Windows + JDK 21.0.10）：

```text
ALL SMOKE CHECKS PASSED
score=2.5 nextAction=FOLLOW_UP   （故意提交不完整回答）
knowledgeItems=31 chunks=31
```

同一冒烟脚本在**模型不可达**的实例上也全部通过（见第四节），证明降级路径是完整的闭环。

## 三、重启持久化与索引重建（真实进程实测）

| 步骤 | 结果 |
|---|---|
| 启动 → 导入简历 → 出题 → 回答（4.0 分）→ 结束 → 生成报告 | 6 个数据文件写入 `dist/data/` |
| 杀掉进程并重启 | 6 个文件全部载入：`已从 resumes.json 载入 1 条记录` … `已从 reports.json 载入 1 条记录` |
| 检查是否误判损坏 | `*.corrupt` 文件数 = **0** |
| 重启后检索 | 返回 3 个片段 + 3 条来源引用（启动期 `VectorIndexInitializer` 重建了 5 个简历片段） |
| 重启后读取历史报告 | 同一 session 的报告可读，`overallScore=4.0`，Markdown 长度 2532 |

> 这两个问题（持久化读回失败、向量索引不重建）是在本轮回测中发现的真实缺陷，
> 修复后已补上 `JsonFileStoreTest` 与 `VectorIndexInitializerTest` 做回归防护。

## 四、模型不可用时的降级实测

用 `-Dapp.llm.base-url=http://127.0.0.1:59999/v1`（不可达端口）启动实例：

| 检查项 | 结果 |
|---|---|
| `/api/health` | `status=UP`，`llmAvailable=false`，`llmProvider=openai-compatible` |
| 出题 | 239ms 内返回，`degraded=true`，原因：「模型服务未连接，已使用模板出题…」 |
| 评分 | `degraded=true`，四个维度齐全，总分 2.25（启发式上限 3.5），总评明确标注降级原因 |
| 完整冒烟（26 项） | 全部通过（导入 → 出题 → 回答 → 追问 → 报告 → 下载） |
| 是否需要等待超时 | 不需要：连接失败在 `connect-timeout-ms`（700ms）内返回，不阻塞页面 |

## 五、手工验证路径（面试演示顺序）

1. `dist\app.cmd` 启动 → 打开 `http://127.0.0.1:8090/index.html`
2. 「① 简历导入」点「载入示例简历」→「解析粘贴内容」→ 展示事实/项目/技术栈，并指出联系方式已被脱敏
3. 在「事实明细」里改一个项目名 →「保存修正」→ 说明检索索引已重建
4. 打开 `http://127.0.0.1:8090/api/retrieval/search?q=Redis 分布式锁&mode=PROJECT&resumeId=<id>`
   → 证明「问题有依据」可被外部复核
5. 「② 单题练习」选项目面 → 开始 → 故意给一个不完整回答 → 展示分项评分、遗漏点、纠错建议、参考结构
6. 点「继续追问」→ 展示追问来自遗漏点，且不串题
7. 「③ 完整模拟面试」→ 展示阶段推进条（自我介绍→项目→深挖→基础→反问→总结）
8. 结束 → 「④ 复盘报告」→ 能力雷达、最弱题、知识缺口、项目风险、行动建议 → 下载 Markdown

## 六、已实测的量化数据（可复现）

| 指标 | 数值 | 复现方式 |
|---|---|---|
| 自动化测试 | 127 项全通过，0 失败 | `scripts\run-tests.ps1` |
| 打包产物 | `dist/` ≈ 23.9 MB，43 个依赖 jar | `scripts\build-dist.ps1` |
| 启动耗时 | ≈ 3.0 秒（空库 + Mock 模型） | `dist/run-out.log` 中 `Started Bootstrap in 2.966 seconds` |
| 知识库 | 31 条 / 8 主题 | `GET /api/health` |
| 示例简历解析 | 6 条事实、1 个项目、14 项技术栈 | `scripts\smoke-test.cmd` 输出 |
| 单份简历向量化规模 | 几十个片段（暴力检索无压力） | `GET /api/health` 的 `knownChunks` |

## 七、待测指标（**不要写进简历当作结论**）

| 指标 | 需要的样本与方法 | 现状 |
|---|---|---|
| 问题相关性 | 10 份不同结构简历 + 人工判定问题是否回指正确项目 | 待测 |
| 评分一致性 | 同一回答重复运行 N 次，统计各维度方差 | 待测（已有降级评分可做基线） |
| 结构化输出成功率 | 连续 N 次调用中 JSON 一次解析成功的比例 | 待测（需真实模型） |
| 解析字段召回 | 10 份简历人工标注字段 vs 系统抽取 | 待测 |
| 平均响应时间 | 真实模型下的 P50/P95 | 待测（依赖本地模型选型） |
| 完整面试完成率 | 真实用户跑完整场的比例 | 待测 |
| 隐私泄露 | 日志/数据库/错误页全文检索手机号与邮箱 | 已做静态检查（代码中无原文日志），自动化断言覆盖响应体 |

建议的评测集放置方式：`src/test/resources/eval/` 下按简历编号组织
（`resume-XX.txt` + `expected-facts.json` + `approved-questions.txt` + `answers/*.txt`），
评测脚本可复用 `TestRunner` 直接跑批量断言。

## 八、已知问题与限制

1. 默认哈希向量不是语义模型：同义改写召回依赖关键词召回兜底，接远端 embedding 效果更好。
2. 评分存在模型主观性：已用固定 Rubric + 结构化输出降低漂移，但未做方差量化。
3. 无 OCR、无语音、无多租户：属于第一版明确不做的范围。
4. `app.storage.mode=file` 适合单用户；并发写入依赖进程内锁 + 原子替换，多实例部署需要换 PostgreSQL。
