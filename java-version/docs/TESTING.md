# 手工测试指南

本机已实测可用的启动方式与测试路径。**前端不需要单独启动**——Spring Boot 会把 `index.html`
直接托管在根路径，浏览器打开 `http://127.0.0.1:8090/` 即可（无需 npm、无需 Vite）。

---

## 一、启动（三种模式任选）

### 模式 A：Mock 模式（最快，不需要模型，推荐先跑通界面）

```bat
scripts\start.cmd mock
```

- 出题用模板、评分用启发式规则，`degraded=true` 会在界面上标注
- 用来验证：界面、上传解析、事实修正、状态机、追问、报告导出等**全部流程**
- 这是第一次测试最省事的入口（秒级响应，不像真模型要等 10~30 秒）

### 模式 B：Ollama（真实模型，目前最顺）

前提：Ollama 已启动且已下载 `qwen3:1.7b`（本机已具备）。

```bat
:: 如果 Ollama 没在跑，先启动（模型目录在 D:\Ollama_models）
set OLLAMA_MODELS=D:\Ollama_models
start D:\Ollama\ollama.exe serve

:: 然后启动服务
scripts\start.cmd ollama
```

### 模式 C：LM Studio（真实模型）

前提：LM Studio 里**同时**满足两件事，否则会看到「模型未连接」：

1. **服务已开启**：`lms server start`
2. **模型已加载**：`lms load google/gemma-3-4b -y`（可用 `lms ps` 确认）

```bat
scripts\start.cmd lmstudio
```

> 注意：LM Studio 的模型是「按需加载」的。如果 `lms ps` 显示 `No models are currently
> loaded`，接口里就看不到对话模型，应用启动时会提示模型服务不可用（此时仍可降级运行）。

### 换端口 / 换数据目录

```bat
scripts\start.cmd mock 9000
```

数据默认落在 `项目根目录\data\*.json`（不是 dist/data），改目录用环境变量：

```bat
set INTERVIEW_DATA_DIR=D:\temp\interview-data
scripts\start.cmd mock
```

### 停止

在运行的窗口按 `Ctrl+C`；如果是后台窗口，用任务管理器结束 `java.exe`，或：

```bat
powershell -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*app.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
```

---

## 二、打开界面

```
http://127.0.0.1:8090/
```

右上角有两个状态标签，**先看这两个**：

| 标签 | 含义 | 不正常时怎么办 |
|---|---|---|
| 模型状态 | `mock` / `openai-compatible · 模型名`，绿点表示已连接 | 黄点=模型未连接，系统会降级但能用；检查模型服务与模型名 |
| 知识库/片段 | 知识库 31 条 · 片段 N · 检索 local-hash | 片段数应随导入简历增长 |

---

## 三、测试路径（按顺序，每步都有明确预期）

### ① 简历导入

1. 「① 简历导入」→ 点 **载入示例简历** → **解析粘贴内容**
2. **预期**：
   - 右侧出现事实数 / 项目数 / 技术栈数三个数字
   - 提示中出现「手机号已脱敏」等占位符，**原文里的 13812345678 与邮箱应变成占位符**
   - 下方列出项目卡片（FinanceAgent / 云中摄影…）与技术栈标签
3. **边界测试**（值得试，验证「不静默失败」）：
   - 粘贴 `★☆★☆ ※※※※` 这类纯符号 → 应报「没有可用文本」
   - 上传一个扫描版 PDF → 应报「图片型文档暂不支持 OCR」
   - 上传一个把扩展名改成 .pdf 的 txt → 应按文本正常解析（按文件头判定）

### ② 事实修正（验证 RAG 索引会重建）

1. 在「事实明细」里把某个项目名改成 `测试项目名`，或补一句「补充：我负责了压测」
2. 点 **保存修正** → 提示「检索索引已重建」
3. 终端里执行（把 `<resumeId>` 换成页面 URL 里看不到时用 `GET /api/resumes` 取）：

   ```bat
   curl "http://127.0.0.1:8090/api/retrieval/search?q=测试项目名&mode=PROJECT&resumeId=<resumeId>"
   ```

   **预期**：`chunks` 非空，且文本里包含你刚补的内容 → 证明出题依据确实来自修正后的事实

### ③ 单题练习（项目面 / 八股面）

1. 选「项目面」→ 题目数量 3 → **开始练习**
2. 问题气泡里应显示：题型、难度、**依据来源（可核对）**、`sourceIds`
3. 故意写一个**不完整**回答（例如「用 Redis 加锁保证幂等，细节记不清了。」）→ **提交回答**
4. **预期**：
   - 显示总分 + 四个维度（技术正确性 / 内容完整性 / 经历匹配度 / 表达结构），每维都有打分依据
   - 列出遗漏点、需要纠正、建议补充、参考回答结构
   - 有 **参考回答（示范表达，不是标准答案）** 一段：如果是真模型，它应该只讲你刚才说过的内容；
     如果你没提过任何数字，它**不应该凭空出现百分比或 QPS**（出现说明防线失效，请反馈）
   - `nextAction` 为「继续追问」时，「继续追问」按钮可点
   - 「重新回答」按钮此时可点；「换一道题」此时不可点（本题已作答）
5. 点 **继续追问** → **预期**：追问带「追问」标记，且针对上一题的遗漏点，不串题
6. 测 **换一道题**：新建一次练习 → 拿到题后（先别作答）点 **换一道题**
   → **预期**：换上一道不同的题，题目序号仍是第 1 题、右上角题量计数不变（换题不消耗配额）
7. 测 **重新回答**：随便答一句不完整的 → 提交 → 点 **重新回答**
   → **预期**：提示「原分数 X.XX 已作废」，上次的回答被填回输入框方便修改，反馈区清空
   → 改好再提交一次 → 去报告里确认这道题**只出现一次**且分数是新的那个
8. 再测八股面：新建练习 → 选「八股面」→ 来源应显示为「知识点：xxx」

### ④ 异常与边界（这些是方案书明确要求的）

| 操作 | 预期 |
|---|---|
| 提交空回答（或只打空格） | 提示「回答不能为空」，**状态保留可重答**，不消耗模型调用 |
| 提交「不会」（少于 8 字） | 提示「回答过短」 |
| 同一题重复提交 | 提示「已经提交过回答」；想重来请点 **重新回答**（它会把旧分数作废） |
| 追问第二次（同一题） | 提示「本题已经追问过 1 次，请进入下一题」 |
| 换题超过 3 次 | 提示「本场已经换过 3 次题，不能再换了」（上限见 `app.interview.max-question-skips`） |
| 已作答的题点「换一道题」 | 提示「当前题已经回答过了，请先重新回答或直接进入下一题」 |
| 到题目上限后再点下一题 | 自动结束本场并提示，不会卡住 |
| 结束后再提交回答 | 提示已结束 |
| 浏览器直接访问 `/api/interviews/不存在的id` | 返回 404 JSON，不是 500 堆栈 |

### ⑤ 完整模拟面试（模式 FULL）

1. 「③ 完整模拟面试」→ 6 题 → **开始模拟面试**
2. **预期**：顶部阶段条按「自我介绍 → 项目经历 → 项目深挖 → 基础知识 → 反问 → 总结」推进
3. 走完 → **结束面试** → 自动跳转报告

### ⑥ 复盘报告

1. 「④ 复盘报告」→ 选择刚结束的会话 → **加载**
2. **预期**：
   - 平均得分、题目数、回答数、时长
   - **能力雷达图**（SVG），样本量为 0 的维度显示「暂无数据」而不是 0 分
   - 最需要重答的题目（带分数）、知识缺口、项目追问风险、下一步行动建议
   - 右侧原始 Markdown
3. 点 **下载 Markdown** → 应下载 `.md` 文件

### ⑦ 重启恢复（验证持久化）

1. `Ctrl+C` 停止服务 → 重新 `scripts\start.cmd mock`
2. 打开报告页 → **预期**：历史会话与报告仍在；`data\*.json` 不应出现 `.corrupt` 文件
3. 终端启动日志应出现「已从 xxx.json 载入 N 条记录」与「检索索引重建完成」

---

## 四、用 curl 直接测后端（不开浏览器）

```bat
:: 健康检查：模型、向量化、知识库、数据量
curl http://127.0.0.1:8090/api/health

:: 配置诊断：env（环境变量）/ resolved（Spring 解析值）/ bound（最终生效值）三段必须一致
curl http://127.0.0.1:8090/api/diagnostics/config

:: 粘贴导入简历
curl -X POST http://127.0.0.1:8090/api/resumes/text -H "Content-Type: application/json" -d "{\"text\":\"张三\n教育经历\n2020.09-2024.06 某大学 计算机 本科\n项目经历\n订单系统 2023.01-2023.06\n技术栈：Java、Redis\n个人职责：负责幂等设计\n结果：重复下单为 0\",\"fileName\":\"测试.txt\"}"

:: 创建项目面会话（把 <resumeId> 换成上一步返回的）
curl -X POST http://127.0.0.1:8090/api/interviews -H "Content-Type: application/json" -d "{\"resumeId\":\"<resumeId>\",\"mode\":\"PROJECT\",\"maxQuestions\":3}"

:: 出题 / 回答 / 追问 / 结束 / 报告（把 <sessionId> 换掉）
curl -X POST http://127.0.0.1:8090/api/interviews/<sessionId>/next-question
curl -X POST http://127.0.0.1:8090/api/interviews/<sessionId>/answers -H "Content-Type: application/json" -d "{\"questionId\":\"<questionId>\",\"content\":\"我负责订单幂等，用 Redis 加锁配合唯一索引，压测重复下单为 0。\"}"
curl -X POST http://127.0.0.1:8090/api/interviews/<sessionId>/follow-up
curl -X POST http://127.0.0.1:8090/api/interviews/<sessionId>/finish -H "Content-Type: application/json" -d "{\"reason\":\"测试结束\"}"
curl -X POST http://127.0.0.1:8090/api/interviews/<sessionId>/report
curl "http://127.0.0.1:8090/api/interviews/<sessionId>/report.md"
```

> Windows 的 curl 对含中文的 `-d` 容易乱码。中文内容建议直接用界面粘贴，或把 JSON 存成
> UTF-8 文件后用 `curl -d "@body.json"`。

---

## 五、常见问题

| 现象 | 原因与处理 |
|---|---|
| 打开页面右上角是黄点「模型未连接」 | **先看提示**：鼠标悬停右上角状态条（或看右下角弹出的黄条），里面会写明「当前配置的是哪个地址、连不上、本机哪儿有服务」。最常见的两种：① 只开了 Ollama，但应用默认找的是 LM Studio 的 `1234` → 改用 `scripts\start.cmd ollama`；② 地址对了但模型名不在服务端列表里 → 提示会列出可用模型名，设给 `INTERVIEW_LLM_CHAT_MODEL`。改完环境变量要**重启服务**；只是模型刚启动好，点一下状态条即可重新探测 |
| LM Studio 显示未连接，但 `lms ps` 有模型 | 需要 `lms server start`（仅加载模型不会开服务） |
| 出题提示「已使用模板出题」 | 模型不可用或返回格式不合法，系统按设计降级；看终端日志里的具体原因 |
| 报告页没有历史会话 | 「结束面试」或「结束并看报告」后才会出现在下拉框 |
| 想从零开始 | 停止服务后删除数据目录（`scripts\start.cmd` 用 `项目根目录\data\`，`dist\app.cmd` 用 `dist\data\`） |
| 端口被占用 | `scripts\start.cmd mock 9000` |
| 终端中文日志是乱码 | 控制台代码页问题，不影响功能；用 `chcp 65001` 切 UTF-8 |

---

## 六、一次跑通的自动化等价物

如果你想先确认「环境本身没问题」，再开始点界面：

```bat
powershell -File scripts\run-tests.ps1        :: 192 项自动化测试（离线，不需要模型）

:: 另开一个窗口，先起服务，再对「正在运行的实例」做 36 项 HTTP 冒烟
scripts\start.cmd mock
scripts\smoke-test.cmd http://127.0.0.1:8090  :: 默认端口可省略；.ps1 版本等价
```

`smoke-test.cmd` 会覆盖：健康检查、前端可达、简历导入与脱敏、检索来源引用、出题有据、
四维评分、追问、题目配额、结束、报告与 Markdown 下载、错误码 400/404/409。

> 冒烟脚本报 `NoClassDefFoundError: com/fasterxml/jackson/databind/ObjectMapper`
> 属于历史缺陷（cmd.exe 的 `set /p` 把 classpath 截断到 1023 字符），已在
> `resolve-classpath.ps1` + `smoke-test.cmd` 中用 Java `@argfile` 修掉；如果你拿到的是旧版本，
> 重新跑一次 `scripts\run-tests.ps1` 让 classpath 文件刷新即可。
