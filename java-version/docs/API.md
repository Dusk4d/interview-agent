# API 接口文档

Base URL：`http://127.0.0.1:8090`

所有请求与响应均为 UTF-8 JSON（文件上传为 `multipart/form-data`）。

错误响应统一结构：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "回答过短（少于 8 字），请把思路写清楚一些再提交。",
  "path": "/api/interviews/xxx/answers",
  "timestamp": "2026-09-10T20:00:00Z",
  "details": []
}
```

| HTTP | 错误码 | 触发场景 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 参数缺失/非法（模式、类型、长度、空回答） |
| 400 | `INVALID_REQUEST_BODY` | 请求体不是合法 JSON |
| 404 | `NOT_FOUND` | 简历/会话/问题/报告/路由不存在 |
| 405 | `METHOD_NOT_ALLOWED` | 方法不支持 |
| 409 | `INVALID_SESSION_STATE` | 状态机拒绝（未出题就回答、重复回答、已结束、追问超限） |
| 413 | `FILE_TOO_LARGE` | 上传超过大小限制 |
| 422 | `UNSUPPORTED_FILE` | 旧版 doc、图片型 PDF、加密 PDF、非 docx 包 |
| 422 | `EMPTY_RESUME_TEXT` | 文件为空或清洗后无可用文本 |
| 502 | `LLM_BAD_RESPONSE` / `LLM_INVALID_STRUCTURE` | 模型返回非法响应/结构不合法 |
| 503 | `LLM_UNAVAILABLE` | 模型服务未启动（出题与评分会降级，接口本身不失败） |
| 504 | `LLM_TIMEOUT` | 模型读取超时 |
| 500 | `INTERNAL_ERROR` | 未预期异常（不含内部堆栈，仅记日志） |

---

## 1. 系统状态

### GET `/api/health`

```json
{
  "status": "UP",
  "version": "0.1.0",
  "llmProvider": "openai-compatible",
  "llmModel": "qwen2.5-7b-instruct",
  "llmAvailable": false,
  "llmBaseUrl": "http://127.0.0.1:1234/v1",
  "llmStatus": "当前配置的地址 http://127.0.0.1:1234/v1 连不上（模型 qwen2.5-7b-instruct），但检测到 Ollama 正在 http://127.0.0.1:11434/v1 提供服务，可用模型：qwen3:1.7b",
  "llmHint": "把地址指过去即可：set INTERVIEW_LLM_BASE_URL=http://127.0.0.1:11434/v1 与 set INTERVIEW_LLM_CHAT_MODEL=qwen3:1.7b；或直接用 scripts\\start.cmd ollama 一键配好，然后重启服务。",
  "llmAvailableModels": ["qwen3:1.7b"],
  "thinkingDisabled": false,
  "embeddingProvider": "local-hash",
  "embeddingDimension": 256,
  "knownChunks": 31,
  "knowledgeItems": 31,
  "resumeCount": 2,
  "sessionCount": 5,
  "storageMode": "file",
  "topics": ["Java", "Spring", "MySQL", "Redis", "Network", "OS", "Architecture", "AI"]
}
```

`llmAvailable=false` 时系统仍可工作：出题与评分走降级路径并在响应中标注。

| 字段 | 含义 |
|---|---|
| `llmBaseUrl` | **实际生效**的服务地址（不是配置文件的默认值），用于排查「环境变量没生效」 |
| `llmStatus` | 连通性结论：探测了哪个地址、模型名是否存在于服务端模型列表 |
| `llmHint` | 不可用时的修复建议（该设哪个环境变量 / 该用哪个启动命令），可用时为 `null` |
| `llmAvailableModels` | 服务端 `/v1/models` 实际返回的模型名，拿不到时为空数组 |

> 为什么 `llmAvailable` 会把「模型名不存在」也算作不可用：只要端口通就报「已连接」的话，
> 模型名写错时圆点是绿的、每次调用却都在降级，用户只会觉得「分数怎么怪怪的」。
> 服务端不返回可解析的模型列表时（少数兼容实现），退化为只校验可达，不会误判为不可用。


---

## 2. 简历

### POST `/api/resumes/import`

`multipart/form-data`，字段 `file`。支持 `.pdf` / `.docx` / `.txt`（按文件头识别，扩展名只是弱提示）。

```json
{
  "resumeId": "8f1c…",
  "fileName": "张伟-简历.txt",
  "fileType": "txt",
  "status": "PARSED",
  "factCount": 6,
  "confidence": 0.85,
  "warnings": ["未识别到明确的技术栈关键词。"],
  "preview": "张伟 手机：[手机号已脱敏] …"
}
```

失败示例（图片型 PDF，HTTP 422）：

```json
{
  "code": "UNSUPPORTED_FILE",
  "message": "检测到图片型/扫描版文档，第一版不支持 OCR，因此无法解析内容。请上传可选中文字的 PDF，或改用 DOCX/TXT，也可以直接粘贴文本。"
}
```

### POST `/api/resumes/text`

```json
{ "text": "张伟\n教育经历\n…", "fileName": "手动粘贴的简历.txt" }
```

响应同上传接口。

### GET `/api/resumes/{id}`

```json
{
  "id": "8f1c…",
  "fileName": "张伟-简历.txt",
  "fileType": "txt",
  "fileSize": 2089,
  "status": "PARSED",
  "errorMessage": null,
  "warnings": [],
  "facts": [
    { "id": "resume-project-004", "type": "PROJECT", "typeLabel": "项目经历",
      "label": "FinanceAgent 智能财务问答系统", "content": "…",
      "sourceOrder": 4, "confidence": 0.9,
      "metadata": ["时间：2023.09-2024.05", "Java", "pgvector"] }
  ],
  "projects": [
    { "id": "resume-project-004", "name": "FinanceAgent 智能财务问答系统",
      "content": "…", "techStack": ["Java", "pgvector"], "period": "时间：2023.09-2024.05" }
  ],
  "techStack": ["Java", "Spring Boot", "pgvector"],
  "text": "（清洗并脱敏后的原文，联系方式已替换为占位符）",
  "createdAt": "2026-09-10T20:00:00Z"
}
```

### PUT `/api/resumes/{id}/facts`

人工修正解析结果。保存后**重建该简历的检索索引**。

```json
{
  "facts": [
    { "id": "resume-project-004", "type": "PROJECT",
      "label": "FinanceAgent 财务问答平台", "content": "项目背景：…\n个人职责：…" }
  ]
}
```

响应为更新后的 `GET /api/resumes/{id}` 结构。内容为空或类型非法返回 400。

### DELETE `/api/resumes/{id}`

```json
{ "ok": true, "message": "简历已删除，相关检索片段已清理。" }
```

---

## 3. 面试会话

### POST `/api/interviews`

```json
{ "resumeId": "8f1c…", "mode": "PROJECT", "maxQuestions": 6 }
```

* `mode`：`PROJECT`（需简历）/ `KNOWLEDGE`（无需简历）/ `FULL`（需简历）
* 八股面未导入简历时，会话直接进入 `INTERVIEW_STARTED`

```json
{
  "id": "3a7d…",
  "resumeId": "8f1c…",
  "mode": "PROJECT",
  "modeLabel": "项目面",
  "stage": "SINGLE_QUESTION",
  "status": "RESUME_READY",
  "stateDescription": "简历已就绪，可以开始面试",
  "currentQuestionId": null,
  "questionCount": 0, "answerCount": 0, "followUpCount": 0,
  "maxQuestions": 6,
  "coveredTopics": [], "recentFeedback": [],
  "endReason": null,
  "createdAt": "…", "startedAt": null, "endedAt": null
}
```

### POST `/api/interviews/{id}/next-question`

```json
{
  "id": "q-1",
  "sessionId": "3a7d…",
  "sequence": 1,
  "type": "PROJECT_TECH",
  "difficulty": "EASY",
  "content": "在「FinanceAgent 智能财务问答系统」中，你具体负责了哪一部分？…",
  "intent": "考察项目事实、技术选型动机与取舍",
  "focus": ["个人职责", "技术选型", "边界条件"],
  "sourceIds": ["resume-project-004"],
  "citations": ["简历片段：FinanceAgent 智能财务问答系统"],
  "followUpPlan": "若只回答做了什么，追问技术机制与量化结果",
  "parentQuestionId": null,
  "followUp": false,
  "stage": "SINGLE_QUESTION",
  "degraded": false,
  "degradationReason": null
}
```

* `sourceIds` 是**可核验的来源**：只包含检索到的片段 ID，模型自造的 ID 会被过滤掉。
* `degraded=true` 表示模板出题，`degradationReason` 给出原因。
* 达到题目上限时返回 409，并已自动结束该会话（避免页面卡住）。

### POST `/api/interviews/{id}/answers`

```json
{ "questionId": "q-1", "content": "我负责文档解析与检索链路…" }
```

```json
{
  "answerId": "a-1",
  "questionId": "q-1",
  "evaluation": {
    "id": "e-1",
    "answerId": "a-1",
    "questionId": "q-1",
    "totalScore": 3.25,
    "dimensions": [
      { "key": "technical_correctness", "label": "技术正确性", "score": 3.0, "weight": 0.25,
        "reason": "提到了实现机制，但缺少边界与对比。" },
      { "key": "completeness", "label": "内容完整性", "score": 3.5, "weight": 0.25, "reason": "…" },
      { "key": "experience_match", "label": "经历匹配度", "score": 3.0, "weight": 0.25, "reason": "…" },
      { "key": "structure", "label": "表达结构", "score": 3.5, "weight": 0.25, "reason": "…" }
    ],
    "strengths": ["给出了具体实现手段"],
    "missingPoints": ["缺少量化结果或验证方式"],
    "corrections": [],
    "suggestedAdditions": ["补充该技术方案的适用边界与替代方案对比"],
    "referenceAnswerStructure": "背景 → 个人职责 → 技术机制 → 难点取舍 → 结果验证",
    "referenceAnswer": "背景：大促时网关被刷接口导致误杀。我负责限流与熔断：用 Redis 令牌桶做分布式限流，Sentinel 做熔断降级。结果：误杀率从 1.2% 降至 0.1%，核心链路可用性从 99.5% 提升至 99.95%。",
    "evidenceWarnings": ["提到了效果提升但没有给出可核验的数字或验证方式"],
    "followUpRecommended": true,
    "followUpFocus": "深入追问技术机制与边界条件",
    "summary": "回答覆盖了主要方向，补充机制细节与量化结果会更有说服力。",
    "degraded": false,
    "createdAt": "…"
  },
  "session": { "…": "同会话视图" },
  "nextAction": "FOLLOW_UP",
  "nextActionHint": "评估建议继续追问，可以点击「继续追问」，也可以直接进入下一题。"
}
```

`nextAction` 取值：`FOLLOW_UP` / `NEXT_QUESTION` / `FINISH`。

边界行为：

* 空回答 → 400（不会调用模型，不消耗额度）
* 过短回答（<`min-answer-chars`，默认 8）→ 400，状态保持可重试
* 同一题重复提交 → 409（想重答请用下面的 `retry`，它会把旧成绩作废）
* 未出题就提交 / 会话已结束 → 409

### POST `/api/interviews/{id}/retry`

「重新回答当前题」（方案书场景一）。语义是**旧成绩作废、本题重来**。

```json
{
  "question": { "…": "需要重答的原题，内容不变" },
  "session": { "…": "状态回到 WAITING_ANSWER，answerCount 减 1" },
  "discardedScore": 3.25
}
```

* 只有「刚答完这道题、还没下发下一题」时才允许（状态 `NEXT_QUESTION` / `FOLLOW_UP`）；否则 409。
* 旧回答与旧评分会被**真删除**（不是标记），因此 `GET /evaluation` 会重新返回 404——
  这样报告的平均分、能力雷达、薄弱点都不会把同一道题算两次。
* `discardedScore` 此前没有评分时为 `null`，前端据此提示用户旧分数已失效。

### POST `/api/interviews/{id}/replace-question`

「换一道题」（方案书场景一）。响应同 `next-question`。

* 只有「已下发、尚未作答」时才允许（状态 `WAITING_ANSWER`）；已作答的题要先 `retry` 或进入下一题，否则 409。
* 被换掉的题会被删除且**不消耗题量配额**（`questionCount` 不变），但它的指纹会留在
  `askedQuestionDigests` 里，避免模型又出一道一样的。
* 每场最多换 `app.interview.max-question-skips` 次（默认 3）；超限返回 409。

### GET `/api/interviews/{id}/evaluation`

返回最近一次评估与当前会话状态；从未提交过回答（或刚执行过 `retry`）时返回 404。

### POST `/api/interviews/{id}/follow-up`

条件：上一题已提交、评估建议追问、未超过每题追问上限（默认 1）、仍有题目配额。
否则返回 409 并说明原因（例如「本题已经追问过 1 次，请进入下一题。」）。

响应同 `next-question`，其中 `followUp=true`、`parentQuestionId` 指向原问题。

### POST `/api/interviews/{id}/finish`

```json
{ "reason": "用户主动结束" }
```

可重复调用（已完成时直接返回当前会话）。响应为会话视图，`status=FINISHED`。

### POST `/api/interviews/{id}/cancel`

取消会话，`status=CANCELLED`。终态不可再提交回答。

---

## 4. 报告

### POST `/api/interviews/{id}/report`

会话未结束时自动先结束再生成。

### GET `/api/interviews/{id}/report`

需先生成，否则 404。

```json
{
  "report": {
    "id": "r-1",
    "sessionId": "3a7d…",
    "mode": "PROJECT",
    "modeLabel": "项目面",
    "startedAt": "…", "endedAt": "…", "durationSeconds": 245,
    "questionCount": 4, "answerCount": 4,
    "overallScore": 3.12,
    "abilityRadar": [
      { "key": "project_expression", "label": "项目表达", "score": 3.25, "sample": 2 },
      { "key": "database", "label": "数据库", "score": 1.0, "sample": 1 },
      { "key": "network_os", "label": "网络与操作系统", "score": 0.0, "sample": 0 }
    ],
    "weakestQuestions": ["[1.0 分] 请说明 B+ 树索引…"],
    "knowledgeGaps": [
      { "topic": "索引原理", "reason": "回答明显不足（低于 2 分）", "evidence": ["把 B+ 树说成了二叉树"] }
    ],
    "projectRisks": ["深入追问技术机制与边界条件"],
    "actionItems": ["重答题目「…」，按参考结构组织后再提交一次"],
    "summary": "本场项目面共评估 4 道题，平均得分 3.12 / 5。…"
  },
  "markdown": "# 面试复盘报告\n\n| 项目 | 内容 |\n…"
}
```

`sample=0` 表示该维度本场没有对应题目，前端显示「暂无数据」而不是 0 分。

### GET `/api/interviews/{id}/report.md`

`text/markdown;charset=UTF-8`，带 `Content-Disposition: attachment`。

---

## 5. 知识库与检索调试

### GET `/api/knowledge/topics` → `["Java","Spring","MySQL",…]`

### GET `/api/knowledge/items?topic=Redis`

```json
[{ "id": "knowledge-redis-lock", "topic": "Redis", "subtopic": "分布式锁",
   "difficulty": "HARD", "title": "Redis 分布式锁的正确实现",
   "keyPoints": ["SET NX PX 原子加锁", "唯一 value + Lua 解锁"] }]
```

### GET `/api/retrieval/search?q=Redis 分布式锁&mode=PROJECT&resumeId=8f1c…&topK=5`

用于**复核问题依据**：能看到命中的片段、来源、相似度与融合分。

```json
{
  "query": "Redis 分布式锁",
  "empty": false,
  "citations": ["简历片段：FinanceAgent 智能财务问答系统"],
  "chunks": [
    { "id": "resume-project-004", "kind": "RESUME_FACT", "label": "FinanceAgent 智能财务问答系统",
      "score": 0.42, "fusedScore": 0.0107, "text": "…" }
  ],
  "contextPreview": "[1] 简历片段：…"
}
```

`mode=KNOWLEDGE` 时命中的 `kind` 应为 `KNOWLEDGE`（八股面只检索知识库）。
