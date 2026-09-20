# 验证与测试说明

本文说明「怎么证明系统能用」，以及哪些结论已经实测、哪些仍然待测。

## 一、自动化测试（169 项）

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
| 隐私 | `PrivacyMaskerTest` | 9（含 4 组参数化，共 12 次执行） | 手机号多种写法、座机、邮箱、身份证、链接、地址；技术数字不误伤；日志脱敏先脱后截 |
| 清洗 | `TextCleanerTest` | 9 | 空文本、CRLF/零宽/制表归一化、重复页眉、页码与分隔线、乱码、噪声、过短 |
| 提取 | `DocumentExtractorRouterTest` | 13 | UTF-8/GB18030 TXT、DOCX 段落与表格、空正文、PDF 文字/多页/图片型、伪装扩展名按文件头判定 |
| 结构化 | `ResumeFactExtractorTest` | 10 | 五类区块、项目分块与元数据、编号项目、无空格标题、极简简历不编造、技术栈不误判 Java/JavaScript |
| 检索 | `VectorStoreTest` | 9 | 向量确定性与归一化、元数据过滤隔离、minScore、upsert 幂等、删除 |
| 解析容错 | `StructuredOutputParserTest` | 12 | 代码围栏、前后文截取、中文引号/单引号/尾逗号/无引号键、截断补全、缺字段报错 |
| 状态机 | `InterviewStateMachineTest` | 7 | 全流程合法、终态封闭（11×11）、非法转移提示、出题/答题守卫 |
| 评分与报告 | `HeuristicEvaluatorTest` | 8 | 空/答非所问/详细回答的分数排序、降级上限、雷达样本量、最弱题与知识缺口 |
| 服务层端到端 | `InterviewFlowTest` | 10 | 项目面全闭环、八股面无简历、完整模拟阶段推进、重复提交、空/超短回答、越界、追问上限、人工修正 |
| HTTP 端到端 | `HttpEndToEndTest` | 10 | 真实 Tomcat + multipart 上传、静态资源、配置绑定一致性、全链路（含报告下载）、错误码 400/404/409/422、检索调试、题目上限自动结束 |
| 持久化 | `JsonFileStoreTest` | 4 | 写盘后重新打开仓储（模拟重启）读回简历/会话/问题/回答/评分/报告；损坏文件隔离后仍可写入 |
| 启动索引与绑定 | `VectorIndexInitializerTest` | 8（含 3 项绑定守卫） | 启动期重建检索索引、幂等、空库/空事实跳过；**AppProperties 必须真正绑定外部配置**、配置 record 只能有规范构造器 |
| 多简历回归 | `MultiResumeRegressionTest` | 4 | 4 份不同结构简历批量解析不丢字段、结果可复现、极简简历不编造内容 |
| **MVP 总验收** | `MvpAcceptanceTest` | 14 | 方案书第九节验收标准逐条对应，含全部反面场景（脱离简历、串题、模型失败无提示、隐私泄露、解析失败隐瞒） |
| 评分容错 | `AnswerEvaluatorToleranceTest` | 10 | 真实模型畸形输出：数组形式维度、10 分制、百分制、嵌套 scores、中文维度名、缺维度必须降级而非记 0 分 |
| 模型客户端 | `OpenAiCompatibleClientTest` | 10 | 思考链抑制边界、**HTTP/1.1 强制**、**response_format 协商与降级判定**、请求体构造 |
| 离线评测集 | `EvalCorpusTest` | 4 | 4 份简历的解析召回（人工确认字段不丢）、极简简历不编造、**三档人工回答的评分排序单调性**、评测集自洽 |
| 模板出题 | `MockLlmClientQuestionTest` | 8 | 离线模板出题必须点名真实项目（不得退化成「该项目」）、提示语不得漏进题目正文、题序轮换不重复、题型标签与题目内容一致 |
| 模型连通性诊断 | `LlmDiagnosticsTest` | 6 | 地址可达+模型名匹配才算可用；模型名不存在要点名并给出该改成什么；服务端不返回模型列表时不误判；地址不通要说明探测了哪里、本机该查哪些端口（用真实本地 HTTP 假服务验证） |

合计 **169 次测试执行，全部通过**。

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

### 6.1 工程与规模

| 指标 | 数值 | 复现方式 |
|---|---|---|
| 自动化测试 | **146 项全通过，0 失败** | `scripts\run-tests.ps1` |
| 打包产物 | `dist/` ≈ 23.9 MB，43 个依赖 jar | `scripts\build-dist.ps1` |
| 启动耗时 | ≈ 3.0 秒（空库 + Mock 模型） | 启动日志 `Started Bootstrap in 2.966 seconds` |
| 知识库 | 31 条 / 8 主题 | `GET /api/health` |
| 单份简历向量化规模 | 几十个片段（暴力检索无压力） | `GET /api/health` 的 `knownChunks` |

### 6.2 真实模型端到端指标

模型：**Ollama qwen3:1.7b**（本地，OpenAI 兼容接口）；评测工具 `LiveEvalMain`；
配置 `app.llm.eval-temperature=0.0`、`app.llm.eval-samples=1`。

| 指标 | 实测值 | 说明 |
|---|---|---|
| 问题来源可核验率 | **8/8 = 100%** | 生成问题的 `sourceIds` 全部回指正确简历事实/知识点 |
| 结构化输出一次成功率 | **100%（16/16 次调用，0 次降级）** | 出题与评分均无格式失败 |
| 优秀回答评分 | 4.25 / 5 | 四维：技术正确性 4、完整性 3、经历匹配 5、表达结构 5 |
| 反馈完整性 | 四维齐全、每维有依据、含遗漏点与参考结构 | `LiveEvalMain` 第 4 项 |
| 出题耗时 | P50 **11.2s** / P95 18.7s（12 次采样） | 含思考链抑制；1.7B 小模型 |
| 评分耗时 | P50 **约 15s** / P95 约 22s | 单次采样 |
| 上下文长度 | 每轮检索 4 个片段，约 4k 字符上限 | 不随轮次线性增长 |

### 6.3 两模型对照（本项目最重要的对照数据）

同一套评测脚本（`LiveEvalMain`）、同一批样本，两个不同能力的本地模型：

| 指标 | qwen3:1.7b（Ollama） | gemma-3-4b（LM Studio） |
|---|---|---|
| 问题来源可核验率 | 100%（8/8） | **100%（8/8）** |
| 结构化输出一次成功率 | 100%（16/16） | 100%（16/16）* |
| 优秀回答评分（重复 3 次） | 4.5 / 4.0 / 4.0（标准差 0.289） | **3.75 ×3（标准差 0.000）** |
| 部分正确回答 | 1.0 ×3 的档位（稳定） | 2.3 / 2.0 / 2.0（标准差 0.173） |
| 答非所问回答 | 0.0~2.0 跳动（标准差 1.155） | **更稳定**（模型更大，边界判定更一致） |
| 出题耗时 P50 | 11.2s | **9.6s** |
| 评分耗时 P50 | 约 15s | 33.4s（P95 84s，4B 模型更慢） |

\* gemma 需要先经历一次 `response_format` 协商失败（见 6.4），之后全部成功。

**结论**：
1. **模型能力直接影响评分稳定性**——4B 在「优秀回答」上标准差为 0.000，而 1.7B 在边界回答上有明显跳动。
   这印证了「评分稳定性受模型规模影响」的判断，也说明用更大模型是有效的改进方向。
2. **但大模型更慢**：评分 P50 从 15s 涨到 33s（P95 84s）。这是本项目在质量与体验之间的真实取舍，
   评测数据可以支撑选型决策，而不是凭感觉。
3. 两个模型的**问题来源可核验率都是 100%**——说明「问题不脱离简历」是由检索与提示词边界保证的，
   不依赖模型规模。

### 6.4 LM Studio 兼容性（实测发现并修复的两个缺陷）

LM Studio 是方案书推荐的首选本地模型之一，接入时暴露了两个**只在它上面出现**的缺陷：

| 缺陷 | 现象 | 根因 | 修复 |
|---|---|---|---|
| HTTP/2 升级无法协商 | 同一地址 curl 92ms 返回 200，JDK HttpClient 却挂到读超时 | JDK 默认先发 `Upgrade: h2c`，LM Studio 的 Express 服务器不响应该升级 | 强制 `HttpClient.Version.HTTP_1_1`（本地服务无 HTTP/2 收益） |
| `response_format` 取值被拒绝 | 每个请求都返回 HTTP 400 `'response_format.type' must be 'json_schema' or 'text'`，全部降级为模板出题/启发式评分 | 代码固定发送 `json_object`（Ollama 接受，LM Studio 拒绝） | 按服务能力协商：默认 `json_object`，失败后自动改用 `json_schema` 并**记住**该服务能力，避免每次重复撞 400 |

两个缺陷都已加回归测试：
- `OpenAiCompatibleClientTest#forcesHttp11` 锁定 HTTP 版本；
- `#negotiatesResponseFormat` 与 `#detectsUnsupportedResponseFormat` 锁定格式协商与降级判定。

**这条记录本身就是工程证据**：说明「模型不可用/不兼容时系统会降级」不是口号——
在修复前，LM Studio 用户虽然能看到完整界面，但每个问题都是模板题、每次评分都是启发式。

### 6.5 评分可复现性专项（1.7B 模型）

同一回答重复评分 5 次，统计总分标准差（越小越稳定）：

| 配置 | 优秀回答 | 部分正确回答 | 答非所问回答 |
|---|---|---|---|
| **eval-temp=0.0，单次采样** | 4.25 ×5 → **标准差 0.000** | 3.0 ×5 → **0.000** | 2.0 ×5 → **0.000** |
| eval-temp=0.0，中位数 3 次 | 4.25 ×5 → 0.000 | 3.0 ×5 → 0.000 | 2.0 ×5 → 0.000 |
| eval-temp=0.3，单次采样 | 4.5~5.0 → 0.177 | 4.0~5.0 → 0.548 | 5.0 ×5 → 0.000 |
| eval-temp=0.3，中位数 3 次 | 4.25~5.0 → 0.285 | 3.0~5.0 → 0.837 | 5.0 ×5 → 0.000 |

**结论**：把评分温度从 0.3 降到 0.0 后，同一回答的评分完全可复现（标准差 0.000）。
温度 0.3 时极差可达 1.0 分——这正是最初「评分漂移」的来源。

**重要说明（避免过度解读）**：
- 上表使用「与问题匹配的真实检索上下文」，因此同一回答的分数稳定。
- 在完整评测流程里，如果把**互不匹配**的问题与回答组合，1.7B 模型会观察到 `0.0 / 2.0 / 2.0`
  这类跳动（平均标准差 0.481）。根因是 1.7B 对「擦边回答属于 0 分还是 2 分」的边界判定本身不稳定，
  **不是评分链路缺陷**；6.3 节的 4B 对照数据（标准差 0.173）验证了这一点。
- 因此本项目对外只主张：**在固定问题与上下文下，评分可复现**；不主张「绝对客观准确」。

### 6.6 多次采样（中位数）的实测取舍

为验证「多次采样能否进一步稳定分数」，实现了 `app.llm.eval-samples`（默认 1）：

- 在**可复现区间内**（temp=0.0）中位数没有额外收益，因为单次已经是 0.000；
- 在温度 0.3 时中位数反而让标准差略升（0.177→0.285），因为中位数会从多次偏高采样里取中值；
- 代价是评分耗时与调用量成倍增加（1 次 → 约 12s；3 次 → 约 36s）。

**因此默认关闭（=1）**，仅在需要对边界回答做离线稳定性分析时手动开启。

### 6.7 解析健壮性：区块标题别名覆盖（评测集回归发现的真实缺陷）

评测集回归过程中发现了一个真实解析缺口：fixture 里写的标题是「**获奖荣誉**」，
而别名表只有「获奖情况 / 荣誉奖项 / 奖项荣誉 / 荣誉」，且匹配用的是 `startsWith`——
它既不是任何别名的前缀，也不完全相同，于是**整个奖项区块被静默丢弃，且没有产生任何告警**。

修复（`ResumeFactExtractor`）：
1. 补充常见写法：「获奖荣誉」「荣誉奖励」「竞赛获奖」「获奖」「奖项」；
2. 新增**强关键词包含匹配**：短于 12 个字符的标题行只要包含「获奖 / 奖项 / 荣誉 / 奖学金 / 竞赛」
   等强关键词，即按对应区块处理（按命中关键词长度取最具体者），避免同类漏识别反复出现。

回归防护：`EvalCorpusTest#parsingRecall` 断言 4 份简历必须抽到人工确认的全部区块类型
（含 AWARD），任何一类丢失都会直接失败；`EvalCorpusTest` 同时校验评测集自身自洽
（三档回答齐备且互不相同），避免评测集本身不完整导致漏测。

### 6.8 离线模板出题：项目名解析（手工走查发现的真实缺陷）

`scripts\start.cmd mock` 是给用户「不装模型也能点完整流程」的入口，手工走查时发现它的问题是**看起来坏了**：

1. 第一版问题正文是「在你的**「该项目」**中……」，用户看不出问的是哪个项目。
   原因：`MockLlmClient` 用 `【简历片段…】` 去检索上下文里抓项目名，而检索上下文实际格式是
   `[1] 简历片段：项目名`，两边写法不一致 → 一直取不到。
2. 修好取值来源后，问题变成「在你的「**星轨推荐引擎（请只针对这个项目提问，不要涉及其它项目）**」中……」——
   提示词的约束句和项目名挤在同一行，被整行当成项目名吃掉了。
3. 再修之后还剩一个质量问题：模板是固定一句话，一轮 4 道题**一模一样**。

修复：
- `Prompts.questionUser` 把约束句拆到**单独一行**，项目名那一行保持干净（真实模型同样受益，约束更明确）；
- `MockLlmClient` 优先读「【当前聚焦项目】」行，`sanitizeProject` 兜底剥离残留的「（请…）」提示语；
- 项目题按**题序轮换 6 个考察角度**（职责取舍 / 最难技术点 / 量化指标 / 十倍流量 / 团队协作 / 复盘决策），
  题型标签与模板一一对应，不再出现「标着边界题却在问量化指标」。

回归防护：`MockLlmClientQuestionTest`（8 项）断言必须点名真实项目、不得含「该项目」与提示语、
题序轮换不重复、量化题必须标为 `PROJECT_RESULT`。

mock 模式实测（4 题连问，2026-09-20）：

```
Q1 [PROJECT_BOUNDARY] 在你的「星轨推荐引擎」中，你具体负责了哪一部分？……
Q2 [PROJECT_TRADEOFF] 「星轨推荐引擎」里最难的技术点是什么？你当时如何定位问题、如何验证解决效果？
Q3 [PROJECT_RESULT]   在「星轨推荐引擎」中，有哪些指标可以证明你的工作产生了效果？请给出具体数字……
Q4 [PROJECT_TRADEOFF] 如果「星轨推荐引擎」的流量或数据量再涨十倍，现有的哪个设计会最先出问题？你会怎么改？
评分：4.5 分 / 4 个维度 / degraded=false / 引用：简历片段：星轨推荐引擎（sourceIds=resume-project-003）
```

### 6.9 启动脚本：cmd.exe 变量长度截断（把冒烟测试跑挂的真实缺陷）

用户按 README 走「打包后冒烟」时 `scripts\smoke-test.cmd` 直接崩：

```
java.lang.NoClassDefFoundError: com/fasterxml/jackson/databind/ObjectMapper
```

原因不在业务代码，而在脚本：它用 `set /p DEPS=<target\resolved-classpath.txt` 把 classpath
读进环境变量，而 **cmd.exe 的 `set /p` 在 1023 个字符处静默截断**。本机 classpath 长 7125 字符，
于是绝大多数 jar（含 jackson-databind）根本没进 classpath，但脚本自身完全不报错——
前一步 `mvn`、classpath 解析都显示成功，只有 java 报一个看起来像「代码写错了」的异常。

修复：
- `resolve-classpath.ps1` 额外输出 `target/test-classpath.args`（Java `@argfile`），
  `smoke-test.cmd` 改为 `java -Dfile.encoding=UTF-8 @"target\test-classpath.args" <MainClass>`，
  彻底绕开环境变量长度限制；
- argfile 里的路径必须用**正斜杠**：`@argfile` 中反斜杠是转义字符，`target\classes` 会被读成 `targetclasses`；
- `-cp` 必须**整体**放进 argfile：Java 启动器允许后出现的 `-cp` 覆盖先出现的，
  一半写在命令行、一半写在 argfile 会静默丢掉一半；
- 同类问题（PowerShell 把 `-Dfile.encoding=UTF-8` 按点号拆开，java 收到 `.encoding=UTF-8`）
  在 `smoke-test.ps1` 中一并修掉，改为数组形式传参。

验证：`.cmd` 与 `.ps1` 两个冒烟入口在 mock 模式下均 `ALL SMOKE CHECKS PASSED (26/26)`
（修复前两个入口分别失败于 Jackson 缺失与 `.encoding=UTF-8`）。

### 6.10 「模型未连接」说不出原因（用户实际反馈）

用户反馈「我后台开着 Ollama，为什么还显示模型服务未连接」。实测复现并确认这不是 Ollama 的问题：

```text
llmProvider   = openai-compatible
llmBaseUrl    = http://127.0.0.1:1234/v1      ← 默认值指向 LM Studio
llmModel      = qwen2.5-7b-instruct           ← Ollama 里只有 qwen3:1.7b
llmAvailable  = false
```

即：默认配置找的是 LM Studio 的 1234，而用户只开了 Ollama 的 11434；界面上只有一句
「模型未连接（可降级运行）」，**既不说是哪个地址连不上，也不说服务端有哪些模型可用**，
用户只能猜。附带发现的第二个坑更隐蔽：旧逻辑只判断 `/v1/models` 是否返回 2xx，
所以「地址对了但模型名写错」时圆点是绿的，而每次真实调用都 404 并静默降级。

修复（新增 `LlmDiagnostics`）：

1. `llmAvailable` 现在同时要求「地址可达」与「模型名存在于服务端模型列表」；
   服务端不返回可解析列表时退化为只校验可达，避免误判。
2. `/api/health` 新增 `llmStatus` / `llmHint` / `llmAvailableModels`，把结论与**可执行的下一步**直接给出；
   配置地址不通时依次探测 11434 / 1234 / 8000 / 8080，命中就点名报出。
3. 前端把原因和建议显示在黄条里，状态条悬停可见详情，点一下即可重新探测（不必刷页）。

实测（默认配置 + 本机 Ollama 正在运行）：

```text
llmStatus = 当前配置的地址 http://127.0.0.1:1234/v1 连不上（模型 qwen2.5-7b-instruct），
            但检测到 Ollama 正在 http://127.0.0.1:11434/v1 提供服务，可用模型：qwen3:1.7b
llmHint   = 把地址指过去即可：set INTERVIEW_LLM_BASE_URL=http://127.0.0.1:11434/v1
            与 set INTERVIEW_LLM_CHAT_MODEL=qwen3:1.7b；或直接用 scripts\start.cmd ollama 一键配好，然后重启服务。
```

按提示改配置后实测（真实模型，同一份简历）：

| 指标 | 结果 |
|---|---|
| `llmAvailable` | `true`，`llmStatus` =「已连接 http://127.0.0.1:11434/v1，模型 qwen3:1.7b 可用」 |
| 出题 | 14s，`type=PROJECT_TECH`，`degraded=false`，问题为「Kafka 事务消息如何保证订单中台重构后的最终一致性？」——回指简历里真实存在的项目 |
| 评分 | 32s，总分 5.0，4 个维度齐全，`degraded=false`，`nextAction=FOLLOW_UP` |
| 冒烟 | mock 实例与真实模型实例均 `ALL SMOKE CHECKS PASSED (26/26)` |

## 七、待测指标（**不要写进简历当作结论**）

| 指标 | 需要的样本与方法 | 现状 |
|---|---|---|
| 解析字段召回 | 10 份简历人工标注字段 vs 系统抽取 | 已完成 4 份结构的批量回归（不丢字段），扩到 10 份待补 |
| 问题相关性（人工判定） | 10 份不同结构简历 + 人工判定问题是否回指正确项目 | 系统侧来源可核验率已测 100%；**人工语义相关性判定待做** |
| 完整面试完成率 | 真实用户跑完整场的比例 | 待测（需真实用户） |
| 用户满意度 | 主观问卷 | 待测（需真实用户） |
| 更大模型的对照 | 同一评测集在 7B/14B 模型上的表现 | 待测（当前仅 1.7B 本地模型） |

建议的评测集放置方式：`src/test/resources/eval/` 下按简历编号组织
（`resume-XX.txt` + `expected-facts.json` + `approved-questions.txt` + `answers/*.txt`），
评测脚本可直接复用 `LiveEvalMain`（已支持 `--model` / `--runs` / `--questions` / `--eval-samples`）。

## 八、已知问题与限制

1. 默认哈希向量不是语义模型：同义改写召回依赖关键词召回兜底，接远端 embedding（`app.embedding.mode=remote`）效果更好。
2. 评分在**固定问题与上下文**下可复现（标准差 0.000）；但小模型对「擦边回答」的边界判定不稳定（见 6.3 的说明）。换更大模型或开启多次采样可缓解。
3. 当前实测仅覆盖 1.7B 本地模型；更大模型的耗时与质量对照待测。
4. 无 OCR、无语音、无多租户：属于第一版明确不做的范围。
5. `app.storage.mode=file` 适合单用户；并发写入依赖进程内锁 + 原子替换，多实例部署需要换 PostgreSQL。
6. `scripts/*.cmd` 必须保持纯 ASCII：cmd.exe 以 OEM 代码页读取脚本，含中文会导致整段脚本解析失败（已踩过）。
