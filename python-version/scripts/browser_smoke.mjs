const baseUrl = (process.argv[2] || "http://127.0.0.1:8090").replace(/\/$/, "");
const debuggerUrl = (process.argv[3] || "http://127.0.0.1:9222").replace(/\/$/, "");
const timeoutMs = Number(process.argv[4] || 20000);

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const deadline = () => Date.now() + timeoutMs;

async function waitForTarget() {
  const until = deadline();
  let lastError;
  while (Date.now() < until) {
    try {
      const response = await fetch(`${debuggerUrl}/json/list`);
      if (!response.ok) throw new Error(`DevTools HTTP ${response.status}`);
      const targets = await response.json();
      const pages = targets.filter((target) => target.type === "page" && target.webSocketDebuggerUrl);
      if (pages.length) return pages.find((page) => page.url.startsWith(baseUrl)) || pages[0];
    } catch (error) {
      lastError = error;
    }
    await sleep(200);
  }
  throw new Error(`浏览器 DevTools 未就绪：${lastError || "没有可用页面"}`);
}

function openCdp(webSocketUrl) {
  const socket = new WebSocket(webSocketUrl);
  const pending = new Map();
  const events = [];
  let sequence = 0;

  socket.addEventListener("message", (event) => {
    const message = JSON.parse(String(event.data));
    if (message.id) {
      const waiter = pending.get(message.id);
      if (!waiter) return;
      pending.delete(message.id);
      if (message.error) waiter.reject(new Error(`${message.error.code}: ${message.error.message}`));
      else waiter.resolve(message.result || {});
      return;
    }
    events.push(message);
  });

  const ready = new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", () => reject(new Error("无法连接浏览器 DevTools WebSocket")), { once: true });
  });

  function send(method, params = {}) {
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      socket.send(JSON.stringify({ id, method, params }));
    });
  }

  return { socket, events, ready, send };
}

const target = await waitForTarget();
const cdp = openCdp(target.webSocketDebuggerUrl);
await cdp.ready;

async function evaluate(expression) {
  const result = await cdp.send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails) {
    const detail = result.exceptionDetails.exception?.description || result.exceptionDetails.text;
    throw new Error(`浏览器脚本异常：${detail}`);
  }
  return result.result?.value;
}

async function waitFor(expression, label) {
  const until = deadline();
  let lastValue;
  while (Date.now() < until) {
    lastValue = await evaluate(expression);
    if (lastValue) return lastValue;
    await sleep(100);
  }
  throw new Error(`等待超时：${label}；最后结果=${JSON.stringify(lastValue)}`);
}

async function click(id) {
  const exists = await evaluate(`Boolean(document.getElementById(${JSON.stringify(id)}))`);
  if (!exists) throw new Error(`页面元素不存在：#${id}`);
  await evaluate(`document.getElementById(${JSON.stringify(id)}).click(); true`);
}

async function setValue(id, value, eventName = "input") {
  await evaluate(`(() => {
    const node = document.getElementById(${JSON.stringify(id)});
    if (!node) throw new Error(${JSON.stringify(`页面元素不存在：#${id}`)});
    node.value = ${JSON.stringify(value)};
    node.dispatchEvent(new Event(${JSON.stringify(eventName)}, { bubbles: true }));
    return true;
  })()`);
}

let failure;
try {
  await Promise.all([
    cdp.send("Page.enable"),
    cdp.send("Runtime.enable"),
    cdp.send("Log.enable"),
    cdp.send("Network.enable"),
  ]);
  if (!target.url.startsWith(baseUrl)) {
    await cdp.send("Page.navigate", { url: baseUrl });
  }
  await waitFor("document.readyState === 'complete'", "首页加载完成");
  await waitFor("document.getElementById('llm-text')?.textContent.toLowerCase().includes('mock')", "健康检查渲染");

  await click("btn-demo-resume");
  await waitFor("document.getElementById('paste-text')?.value.includes('FinanceAgent')", "示例简历填入");
  await click("btn-import-text");
  await waitFor("document.getElementById('panel-practice')?.classList.contains('active')", "导入后切换练习面板");
  await waitFor("document.getElementById('resume-count')?.textContent.includes('1')", "简历列表刷新");

  await setValue("practice-max", "1", "change");
  await click("btn-practice-start");
  await waitFor("document.querySelectorAll('#practice-chat .bubble.interviewer').length === 1", "首题渲染");
  await waitFor("document.getElementById('btn-practice-submit')?.disabled === false", "回答按钮启用");

  const answer = "首先说明我负责文档解析和检索链路，然后解释分段、向量检索与重排机制，最后通过来源引用和测试验证结果。";
  await setValue("practice-answer", answer);
  const counter = await waitFor(
    "document.getElementById('practice-counter')?.textContent !== '0 字' && document.getElementById('practice-counter')?.textContent",
    "回答字数更新",
  );
  await click("btn-practice-submit");
  await waitFor("document.querySelector('#practice-feedback .score')?.textContent.includes('/ 5')", "评分渲染");
  await waitFor("document.getElementById('btn-practice-finish')?.disabled === false", "结束按钮启用");

  await click("btn-practice-finish");
  await waitFor("document.getElementById('panel-report')?.classList.contains('active')", "切换报告面板");
  await waitFor("document.getElementById('report-markdown')?.textContent.includes('AI 面试复盘报告')", "Markdown 报告渲染");
  const metrics = await waitFor("document.querySelectorAll('#report-body .metric').length === 4 && 4", "报告指标渲染");
  const radarDimensions = await waitFor(
    "document.querySelectorAll('#report-body .radar-wrap table tr').length === 8 && 7",
    "七维能力雷达渲染",
  );

  const pageErrors = cdp.events.filter((event) =>
    event.method === "Runtime.exceptionThrown"
    || (event.method === "Log.entryAdded" && ["error", "warning"].includes(event.params?.entry?.level))
    || (event.method === "Runtime.consoleAPICalled" && ["error", "warning"].includes(event.params?.type))
    || event.method === "Network.loadingFailed"
  );
  const errorToasts = await evaluate("document.querySelectorAll('#toasts .toast.error').length");
  if (pageErrors.length || errorToasts) {
    throw new Error(`页面存在异常：events=${JSON.stringify(pageErrors)} errorToasts=${errorToasts}`);
  }

  const result = await evaluate(`({
    title: document.title,
    resumeCount: document.getElementById('resume-count').textContent,
    question: document.querySelector('#practice-chat .bubble.interviewer p').textContent,
    score: document.querySelector('#practice-feedback .score').textContent,
    reportHeading: document.querySelector('#report-body p').textContent,
    markdownLength: document.getElementById('report-markdown').textContent.length
  })`);
  console.log(JSON.stringify({ ok: true, counter, metrics, radarDimensions, ...result }, null, 2));
} catch (error) {
  failure = error;
  console.error(error.stack || error);
} finally {
  try {
    await Promise.race([cdp.send("Browser.close"), sleep(1500)]);
  } catch {
    cdp.socket.close();
  }
}

if (failure) process.exitCode = 1;
