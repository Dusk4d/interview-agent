/* ============================================================================
 * AI 面试陪练系统 · 前端（零依赖，纯浏览器 JS）
 *
 * 设计取舍：第一版不引入构建工具与框架——离线环境无法安装 npm 依赖，
 * 而本项目的重点在后端流程与可解释反馈，前端只需把接口结果如实呈现。
 * 因此这里是「一个文件 + 原生 DOM」，任何静态服务器（含 Spring Boot 本身）都能直接托管。
 * ==========================================================================*/
(function () {
  'use strict';

  // ---------------------------------------------------------------- 基础工具
  const $ = (id) => document.getElementById(id);
  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  };
  const escapeHtml = (value) => String(value === null || value === undefined ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

  const state = {
    resumeId: null,
    resumes: [],
    practice: { sessionId: null, question: null, canFollowUp: false },
    mock: { sessionId: null, question: null, canFollowUp: false },
    reportId: null,
    factsDirty: false
  };

  // ---------------------------------------------------------------- HTTP
  const api = {
    async request(method, path, body, isForm) {
      const options = { method, headers: {} };
      if (body !== undefined && body !== null) {
        if (isForm) {
          options.body = body;
        } else {
          options.headers['Content-Type'] = 'application/json';
          options.body = JSON.stringify(body);
        }
      }
      let response;
      try {
        response = await fetch(path, options);
      } catch (networkError) {
        throw { code: 'NETWORK_ERROR', message: '无法连接后端服务，请确认服务正在运行。' };
      }
      const text = await response.text();
      let payload = null;
      if (text) {
        try { payload = JSON.parse(text); } catch (e) { payload = { raw: text }; }
      }
      if (!response.ok) {
        const error = payload && payload.message
          ? payload
          : { code: 'HTTP_' + response.status, message: text || ('请求失败：HTTP ' + response.status) };
        error.status = response.status;
        throw error;
      }
      return payload;
    },
    get(path) { return this.request('GET', path); },
    post(path, body) { return this.request('POST', path, body); },
    put(path, body) { return this.request('PUT', path, body); },
    del(path) { return this.request('DELETE', path); },
    upload(path, formData) { return this.request('POST', path, formData, true); }
  };

  // ---------------------------------------------------------------- 提示
  function toast(kind, title, body, timeoutMs) {
    const node = el('div', 'toast ' + (kind || ''));
    node.appendChild(el('div', 'title', title));
    if (body) node.appendChild(el('div', 'body', body));
    $('toasts').appendChild(node);
    setTimeout(() => node.remove(), timeoutMs || (kind === 'error' ? 6500 : 3800));
  }
  function showError(error) {
    const code = error && error.code ? '[' + error.code + '] ' : '';
    toast('error', code + '操作失败', (error && error.message) || '未知错误');
    if (window.console) console.warn('API error', error);
  }
  function busy(id, active) {
    const node = $(id);
    if (node) node.classList.toggle('hidden', !active);
  }
  function setButtons(disabled, ids) {
    ids.forEach((id) => { const node = $(id); if (node) node.disabled = disabled; });
  }

  // ---------------------------------------------------------------- 面板切换
  function activate(panelId) {
    document.querySelectorAll('#tabs .tab').forEach((tab) => {
      tab.classList.toggle('active', tab.dataset.panel === panelId);
    });
    document.querySelectorAll('.panel').forEach((panel) => {
      panel.classList.toggle('active', panel.id === panelId);
    });
    if (location.hash !== '#' + panelId) {
      history.replaceState(null, '', '#' + panelId);
    }
    if (panelId === 'panel-report') refreshReportOptions();
  }

  // ---------------------------------------------------------------- 渲染：评分
  function scoreClass(score) {
    if (score >= 4) return 'high';
    if (score >= 2.5) return 'mid';
    return 'low';
  }
  function dimensionBars(evaluation) {
    const wrap = el('div', 'dims');
    (evaluation.dimensions || []).forEach((dim) => {
      const row = el('div', 'dim');
      row.appendChild(el('span', null, dim.label));
      const bar = el('div', 'bar');
      const fill = el('i');
      fill.style.width = clamp((dim.score / 5) * 100, 0, 100) + '%';
      bar.appendChild(fill);
      row.appendChild(bar);
      row.appendChild(el('span', 'score ' + scoreClass(dim.score), dim.score.toFixed(1)));
      const reason = el('div', 'reason', dim.reason || '');
      row.appendChild(reason);
      wrap.appendChild(row);
    });
    return wrap;
  }
  function listBlock(title, items) {
    if (!items || !items.length) return null;
    const box = el('div', 'meta');
    box.appendChild(el('div', null, title));
    const ul = el('ul', 'clean');
    items.forEach((item) => ul.appendChild(el('li', null, item)));
    box.appendChild(ul);
    return box;
  }

  function renderFeedback(container, evaluation, session) {
    container.innerHTML = '';
    if (!evaluation) {
      container.appendChild(el('div', 'empty', '提交回答后这里会显示分项评分与改进建议。'));
      return;
    }
    const head = el('div', 'row');
    head.appendChild(el('span', 'score ' + scoreClass(evaluation.totalScore),
      evaluation.totalScore.toFixed(2) + ' / 5'));
    head.appendChild(el('span', 'badge brand', '加权总分'));
    if (evaluation.degraded) {
      head.appendChild(el('span', 'badge warn', '降级评估'));
    }
    if (evaluation.followUpRecommended) {
      head.appendChild(el('span', 'badge', '建议追问'));
    }
    container.appendChild(head);
    container.appendChild(el('p', 'small', evaluation.summary || ''));
    container.appendChild(dimensionBars(evaluation));

    [['可保留的内容', evaluation.strengths],
      ['遗漏点', evaluation.missingPoints],
      ['需要纠正', evaluation.corrections],
      ['建议补充', evaluation.suggestedAdditions],
      ['没有证据的强主张', evaluation.evidenceWarnings]].forEach(([title, items]) => {
      const block = listBlock(title, items);
      if (block) container.appendChild(block);
    });

    if (evaluation.referenceAnswer) {
      const box = el('div', 'meta');
      box.appendChild(el('span', null, '参考回答（示范表达，不是标准答案）：'));
      const text = el('div');
      text.style.marginTop = '4px';
      text.textContent = evaluation.referenceAnswer;
      box.appendChild(text);
      container.appendChild(box);
    }
    if (evaluation.referenceAnswerStructure) {
      const box = el('div', 'meta');
      box.appendChild(el('span', null, '参考回答结构：'));
      box.appendChild(el('span', 'mono', evaluation.referenceAnswerStructure));
      container.appendChild(box);
    }
    if (evaluation.degraded) {
      container.appendChild(el('p', 'small',
        '本次评分由启发式规则生成（模型不可用或输出格式不合法）。分数上限被刻意压低，请以遗漏点与纠正建议为主。'));
    }
    if (session) {
      container.appendChild(el('p', 'small', '当前状态：' + (session.stateDescription || session.status)));
    }
  }

  // ---------------------------------------------------------------- 渲染：问题气泡
  function questionBubble(question) {
    const bubble = el('div', 'bubble interviewer');
    const who = el('div', 'who');
    who.appendChild(el('span', null, '面试官 · 第 ' + question.sequence + ' 题'));
    who.appendChild(el('span', 'badge', question.type));
    who.appendChild(el('span', 'badge', question.difficulty));
    if (question.stage) who.appendChild(el('span', 'badge', question.stage));
    if (question.followUp) who.appendChild(el('span', 'badge brand', '追问'));
    if (question.degraded) who.appendChild(el('span', 'badge warn', '降级出题'));
    bubble.appendChild(who);
    bubble.appendChild(el('p', null, question.content));
    if (question.intent) {
      bubble.appendChild(el('div', 'meta', '考察意图：' + question.intent));
    }
    if (question.focus && question.focus.length) {
      const tags = el('div', 'tags');
      question.focus.forEach((f) => tags.appendChild(el('span', 'tag', f)));
      const meta = el('div', 'meta');
      meta.appendChild(tags);
      bubble.appendChild(meta);
    }
    if (question.citations && question.citations.length) {
      const meta = el('div', 'meta');
      meta.appendChild(el('div', null, '依据来源（可核对）：'));
      const ul = el('ul', 'clean');
      question.citations.forEach((c) => ul.appendChild(el('li', null, c)));
      meta.appendChild(ul);
      bubble.appendChild(meta);
    }
    if (question.sourceIds && question.sourceIds.length) {
      bubble.appendChild(el('div', 'meta small mono', 'sourceIds: ' + question.sourceIds.join(', ')));
    }
    if (question.degraded && question.degradationReason) {
      const warn = el('div', 'meta');
      warn.appendChild(el('span', 'badge warn', '提示'));
      warn.appendChild(el('span', null, ' ' + question.degradationReason));
      bubble.appendChild(warn);
    }
    return bubble;
  }
  function answerBubble(answer, evaluation) {
    const bubble = el('div', 'bubble user');
    bubble.appendChild(el('div', 'who', '我 · ' + (answer.questionId || '').slice(0, 8)));
    bubble.appendChild(el('p', null, answer.content));
    if (evaluation) {
      const meta = el('div', 'meta');
      meta.appendChild(el('span', 'score ' + scoreClass(evaluation.totalScore),
        '得分 ' + evaluation.totalScore.toFixed(2) + ' / 5'));
      if (evaluation.degraded) meta.appendChild(el('span', 'badge warn', '降级评估'));
      bubble.appendChild(meta);
    }
    return bubble;
  }

  // ---------------------------------------------------------------- 简历模块
  async function loadHealth() {
    try {
      const health = await api.get('/api/health');
      const dot = $('llm-dot');
      dot.className = 'dot ' + (health.llmAvailable ? 'ok' : 'warn');
      $('llm-text').textContent = health.llmAvailable
        ? (health.llmProvider + ' · ' + health.llmModel)
        : '模型未连接（可降级运行）';
      // 悬停能看到「到底探测了哪个地址、服务端有哪些模型」，否则用户只能猜。
      const chip = $('llm-chip');
      if (chip) {
        chip.title = [health.llmStatus, health.llmHint].filter(Boolean).join('\n');
      }
      $('storage-chip').title = '存储：' + health.storageMode + '；向量库片段：' + health.knownChunks;
      $('storage-text').textContent =
        '知识库 ' + health.knowledgeItems + ' 条 · 片段 ' + health.knownChunks + ' · 检索 ' + health.embeddingProvider;
      if (!health.llmAvailable) {
        // 直接把后端算好的原因和建议贴出来：只说「未连接」会让「我明明开着 Ollama」变成死胡同。
        toast('warn', '模型服务未连接',
          (health.llmStatus || '') + (health.llmHint ? '\n\n' + health.llmHint : ''), 15000);
      }
    } catch (error) {
      $('llm-dot').className = 'dot bad';
      $('llm-text').textContent = '后端不可用';
      showError(error);
    }
  }

  async function loadResumes(selectId) {
    const resumes = await api.get('/api/resumes');
    state.resumes = resumes;
    $('resume-count').textContent = '已导入 ' + resumes.length + ' 份';
    const select = $('resume-select');
    select.innerHTML = '';
    resumes.forEach((resume) => {
      const option = el('option', null, resume.fileName + '（' + resume.facts.length + ' 条事实）');
      option.value = resume.id;
      select.appendChild(option);
    });
    const target = selectId || state.resumeId || (resumes[0] && resumes[0].id);
    if (target) {
      select.value = target;
      await selectResume(target);
    } else {
      $('resume-detail').innerHTML = '<div class="empty">还没有导入简历。</div>';
      $('facts-editor').innerHTML = '<div class="empty">导入简历后可编辑。</div>';
    }
  }

  async function selectResume(resumeId) {
    state.resumeId = resumeId;
    const resume = state.resumes.find((r) => r.id === resumeId) || await api.get('/api/resumes/' + resumeId);
    renderResumeDetail(resume);
    renderFactsEditor(resume);
  }

  function renderResumeDetail(resume) {
    const box = $('resume-detail');
    box.innerHTML = '';
    const head = el('div', 'row');
    head.appendChild(el('span', 'badge ' + (resume.status === 'PARSED' ? 'ok' : 'warn'), resume.status));
    head.appendChild(el('span', 'badge', resume.fileType));
    head.appendChild(el('span', 'badge', Math.round(resume.fileSize / 1024) + ' KB'));
    box.appendChild(head);

    const metrics = el('div', 'metrics');
    [['事实条数', resume.facts.length], ['项目数', resume.projects.length],
      ['技术栈', (resume.techStack || []).length]].forEach(([label, value]) => {
      const metric = el('div', 'metric');
      metric.appendChild(el('div', 'value', String(value)));
      metric.appendChild(el('div', 'label', label));
      metrics.appendChild(metric);
    });
    const metricsWrap = el('div', null);
    metricsWrap.style.margin = '12px 0';
    metricsWrap.appendChild(metrics);
    box.appendChild(metricsWrap);

    if (resume.warnings && resume.warnings.length) {
      const warn = el('div', 'meta');
      warn.appendChild(el('div', null, '解析提示：'));
      const ul = el('ul', 'clean');
      resume.warnings.forEach((w) => ul.appendChild(el('li', null, w)));
      warn.appendChild(ul);
      box.appendChild(warn);
    }

    if (resume.techStack && resume.techStack.length) {
      const tags = el('div', 'tags');
      resume.techStack.forEach((t) => tags.appendChild(el('span', 'tag', t)));
      const wrap = el('div', 'meta');
      wrap.appendChild(el('div', null, '识别到的技术栈：'));
      wrap.appendChild(tags);
      box.appendChild(wrap);
    }

    (resume.projects || []).forEach((project) => {
      const card = el('div', 'bubble interviewer');
      card.style.marginTop = '10px';
      const who = el('div', 'who');
      who.appendChild(el('span', null, project.name || '未命名项目'));
      if (project.period) who.appendChild(el('span', 'badge', project.period));
      card.appendChild(who);
      if (project.techStack && project.techStack.length) {
        const tags = el('div', 'tags');
        project.techStack.forEach((t) => tags.appendChild(el('span', 'tag', t)));
        card.appendChild(tags);
      }
      const pre = el('pre', 'markdown', project.content);
      pre.style.maxHeight = '200px';
      card.appendChild(pre);
      box.appendChild(card);
    });
  }

  function renderFactsEditor(resume) {
    const box = $('facts-editor');
    box.innerHTML = '';
    if (!resume.facts.length) {
      box.appendChild(el('div', 'empty', '没有可编辑的事实。'));
      return;
    }
    resume.facts.forEach((fact, index) => {
      const row = el('div', 'card');
      row.style.marginBottom = '10px';
      row.style.background = 'rgba(27,36,71,.5)';
      const head = el('div', 'row');
      head.appendChild(el('span', 'badge', fact.typeLabel));
      const labelInput = el('input');
      labelInput.type = 'text';
      labelInput.value = fact.label || '';
      labelInput.style.maxWidth = '280px';
      labelInput.dataset.role = 'label';
      labelInput.dataset.index = String(index);
      head.appendChild(labelInput);
      head.appendChild(el('span', 'small mono', fact.id + ' · 置信度 ' + fact.confidence));
      head.appendChild(el('span', 'spacer'));
      row.appendChild(head);
      const textarea = el('textarea');
      textarea.value = fact.content;
      textarea.dataset.role = 'content';
      textarea.dataset.index = String(index);
      textarea.style.minHeight = '90px';
      textarea.style.marginTop = '8px';
      row.appendChild(textarea);
      box.appendChild(row);
    });
    $('btn-save-facts').disabled = false;
    box.querySelectorAll('input, textarea').forEach((input) => {
      input.addEventListener('input', () => { state.factsDirty = true; });
    });
  }

  async function saveFacts() {
    if (!state.resumeId) return;
    const resume = state.resumes.find((r) => r.id === state.resumeId);
    if (!resume) return;
    const labels = Array.from(document.querySelectorAll('#facts-editor input[data-role="label"]'));
    const contents = Array.from(document.querySelectorAll('#facts-editor textarea[data-role="content"]'));
    const facts = resume.facts.map((fact, index) => ({
      id: fact.id,
      type: fact.type,
      label: labels[index] ? labels[index].value : fact.label,
      content: contents[index] ? contents[index].value : fact.content
    }));
    try {
      await api.put('/api/resumes/' + state.resumeId + '/facts', { facts });
      state.factsDirty = false;
      toast('ok', '修正已保存', '检索索引已重建，后续问题将基于修正后的事实。');
      await loadResumes(state.resumeId);
    } catch (error) { showError(error); }
  }

  async function importFile(file) {
    if (!file) return;
    busy('resume-busy', true);
    try {
      const form = new FormData();
      form.append('file', file);
      const result = await api.upload('/api/resumes/import', form);
      renderImportWarnings(result);
      toast('ok', '简历解析完成', '识别到 ' + result.factCount + ' 条事实，置信度 ' +
        Math.round(result.confidence * 100) + '%。');
      await loadResumes(result.resumeId);
      activate('panel-practice');
    } catch (error) {
      renderImportWarnings(null, error);
      showError(error);
    } finally {
      busy('resume-busy', false);
    }
  }

  function renderImportWarnings(result, error) {
    const box = $('resume-warnings');
    box.innerHTML = '';
    if (error) {
      const warn = el('div', 'bubble');
      warn.style.borderColor = '#5c2432';
      warn.appendChild(el('div', 'who', '解析失败 · ' + (error.code || '')));
      warn.appendChild(el('p', null, error.message));
      box.appendChild(warn);
      return;
    }
    if (result && result.warnings && result.warnings.length) {
      const warn = el('div', 'bubble');
      warn.appendChild(el('div', 'who', '解析提示'));
      const ul = el('ul', 'clean');
      result.warnings.forEach((w) => ul.appendChild(el('li', null, w)));
      warn.appendChild(ul);
      box.appendChild(warn);
    }
  }

  // ---------------------------------------------------------------- 面试控制器
  const STAGE_ORDER = ['SELF_INTRO', 'PROJECT', 'PROJECT_DEEP_DIVE', 'FUNDAMENTALS', 'CANDIDATE_QUESTIONS', 'WRAP_UP'];
  const STAGE_LABEL = {
    SELF_INTRO: '自我介绍', PROJECT: '项目经历', PROJECT_DEEP_DIVE: '项目深挖',
    FUNDAMENTALS: '基础知识', CANDIDATE_QUESTIONS: '反问环节', WRAP_UP: '总结',
    SINGLE_QUESTION: '单题练习'
  };

  function renderStages(containerId, currentStage) {
    const box = $(containerId);
    box.innerHTML = '';
    if (!currentStage) return;
    const currentIndex = STAGE_ORDER.indexOf(currentStage);
    STAGE_ORDER.forEach((stage, index) => {
      const node = el('span', 'stage ' + (index < currentIndex ? 'done' : (index === currentIndex ? 'current' : '')),
        STAGE_LABEL[stage] || stage);
      box.appendChild(node);
    });
  }

  function controller(mode) {
    const isMock = mode === 'mock';
    return {
      key: isMock ? 'mock' : 'practice',
      chatId: isMock ? 'mock-chat' : 'practice-chat',
      feedbackId: isMock ? 'mock-feedback' : 'practice-feedback',
      answerId: isMock ? 'mock-answer' : 'practice-answer',
      counterId: isMock ? 'mock-counter' : 'mock-counter',
      statusId: isMock ? 'mock-status' : 'practice-status',
      busyId: isMock ? 'mock-busy' : 'practice-busy',
      nextId: isMock ? 'btn-mock-next' : 'btn-practice-next',
      followId: isMock ? 'btn-mock-followup' : 'btn-practice-followup',
      // 重答与换题属于方案书「场景一 单题项目练习」，完整模拟面试不做这两件事
      retryId: isMock ? null : 'btn-practice-retry',
      replaceId: isMock ? null : 'btn-practice-replace',
      finishId: isMock ? 'btn-mock-finish' : 'btn-practice-finish',
      submitId: isMock ? 'btn-mock-submit' : 'btn-practice-submit',
      startId: isMock ? 'btn-mock-start' : 'btn-practice-start'
    };
  }

  function chatAppend(controllerRef, node) {
    const chat = $(controllerRef.chatId);
    const empty = chat.querySelector('.empty');
    if (empty) empty.remove();
    chat.appendChild(node);
    chat.scrollTop = chat.scrollHeight;
  }
  function chatClear(controllerRef, placeholder) {
    const chat = $(controllerRef.chatId);
    chat.innerHTML = '';
    chat.appendChild(el('div', 'empty', placeholder));
  }

  async function startInterview(mode) {
    const ref = controller(mode);
    busy(ref.busyId, true);
    try {
      const payload = {
        mode: mode === 'mock' ? 'FULL' : $('practice-mode').value,
        resumeId: state.resumeId,
        maxQuestions: Number(mode === 'mock' ? $('mock-max').value : $('practice-max').value)
      };
      const session = await api.post('/api/interviews', payload);
      state[ref.key].sessionId = session.id;
      state[ref.key].question = null;
      chatClear(ref, '面试已开始，正在生成第一道题…');
      $(ref.statusId).textContent = session.modeLabel + ' · ' + session.questionCount + '/' + session.maxQuestions;
      $(ref.statusId).className = 'badge brand';
      if (mode === 'mock') renderStages('mock-stages', session.stage);
      await nextQuestion(mode);
    } catch (error) {
      showError(error);
    } finally {
      busy(ref.busyId, false);
    }
  }

  async function nextQuestion(mode) {
    const ref = controller(mode);
    const sessionId = state[ref.key].sessionId;
    if (!sessionId) return;
    busy(ref.busyId, true);
    setButtons(true, [ref.nextId, ref.followId, ref.finishId, ref.submitId]);
    try {
      const question = await api.post('/api/interviews/' + sessionId + '/next-question');
      state[ref.key].question = question;
      state[ref.key].canFollowUp = false;
      chatAppend(ref, questionBubble(question));
      setButtons(false, [ref.submitId, ref.finishId, ref.replaceId]);
      setButtons(true, [ref.retryId]);
      const session = await api.get('/api/interviews/' + sessionId);
      $(ref.statusId).textContent = session.modeLabel + ' · ' + session.questionCount + '/' + session.maxQuestions;
      if (mode === 'mock') renderStages('mock-stages', session.stage);
    } catch (error) {
      showError(error);
      // 达到上限时后端会自动结束并提示，这里刷新状态与报告入口
      if (error.code === 'INVALID_SESSION_STATE') {
        try {
          const session = await api.get('/api/interviews/' + sessionId);
          $(ref.statusId).textContent = session.stateDescription;
          setButtons(false, [ref.finishId]);
        } catch (inner) { /* 忽略：以原始错误为准 */ }
      }
    } finally {
      busy(ref.busyId, false);
    }
  }

  async function submitAnswer(mode) {
    const ref = controller(mode);
    const sessionId = state[ref.key].sessionId;
    const question = state[ref.key].question;
    const content = $(ref.answerId).value;
    if (!sessionId || !question) {
      toast('warn', '还没有题目', '请先获取一道题再提交回答。');
      return;
    }
    if (!content.trim()) {
      toast('warn', '回答为空', '请先写下你的回答，哪怕只是思路框架。');
      return;
    }
    busy(ref.busyId, true);
    setButtons(true, [ref.submitId, ref.nextId, ref.followId, ref.finishId, ref.retryId, ref.replaceId]);
    try {
      const result = await api.post('/api/interviews/' + sessionId + '/answers', {
        questionId: question.id,
        content: content
      });
      chatAppend(ref, answerBubble({ questionId: question.id, content: content }, result.evaluation));
      state[ref.key].lastAnswer = content;
      renderFeedback($(ref.feedbackId), result.evaluation, result.session);
      $(ref.answerId).value = '';
      updateCounter(ref.answerId, ref.counterId);
      $(ref.statusId).textContent = result.session.stateDescription;
      state[ref.key].canFollowUp = result.nextAction === 'FOLLOW_UP';
      setButtons(false, [ref.finishId, ref.nextId]);
      // 刚答完、还没下发下一题：此时允许「重新回答」，但不允许「换一道题」（本题已作答）
      setButtons(false, [ref.retryId]);
      setButtons(true, [ref.replaceId]);
      if (state[ref.key].canFollowUp) {
        setButtons(false, [ref.followId]);
      } else {
        $(ref.followId).disabled = true;
      }
      toast(result.evaluation.degraded ? 'warn' : 'ok',
        '本题得分 ' + result.evaluation.totalScore.toFixed(2) + ' / 5',
        result.nextActionHint);
      refreshReportOptions();
    } catch (error) {
      showError(error);
      setButtons(false, [ref.submitId]);
    } finally {
      busy(ref.busyId, false);
    }
  }

  /**
   * 重新回答当前题：旧分数会在服务端作废，这里把上次的回答填回输入框方便修改。
   */
  async function retryAnswer(mode) {
    const ref = controller(mode);
    const sessionId = state[ref.key].sessionId;
    if (!sessionId) return;
    busy(ref.busyId, true);
    setButtons(true, [ref.submitId, ref.nextId, ref.followId, ref.retryId, ref.replaceId]);
    try {
      const result = await api.post('/api/interviews/' + sessionId + '/retry');
      state[ref.key].question = result.question;
      state[ref.key].canFollowUp = false;
      renderFeedback($(ref.feedbackId), null, null);
      $(ref.answerId).value = state[ref.key].lastAnswer || '';
      updateCounter(ref.answerId, ref.counterId);
      $(ref.statusId).textContent = result.session.stateDescription;
      chatAppend(ref, el('div', 'meta',
        '本题重新作答' + (result.discardedScore === null || result.discardedScore === undefined
          ? '' : '（原分数 ' + Number(result.discardedScore).toFixed(2) + ' 已作废）') + '，改完再提交一次即可。'));
      setButtons(false, [ref.submitId, ref.finishId]);
    } catch (error) {
      showError(error);
      setButtons(false, [ref.retryId, ref.finishId]);
    } finally {
      busy(ref.busyId, false);
    }
  }

  /** 换一道题：被换掉的题不计入题量与报告。 */
  async function replaceQuestion(mode) {
    const ref = controller(mode);
    const sessionId = state[ref.key].sessionId;
    if (!sessionId) return;
    busy(ref.busyId, true);
    setButtons(true, [ref.submitId, ref.replaceId, ref.nextId, ref.retryId, ref.finishId]);
    try {
      const question = await api.post('/api/interviews/' + sessionId + '/replace-question');
      state[ref.key].question = question;
      state[ref.key].canFollowUp = false;
      chatAppend(ref, questionBubble(question));
      setButtons(false, [ref.submitId, ref.finishId, ref.replaceId]);
      const session = await api.get('/api/interviews/' + sessionId);
      $(ref.statusId).textContent = session.modeLabel + ' · ' + session.questionCount + '/' + session.maxQuestions;
    } catch (error) {
      showError(error);
      setButtons(false, [ref.submitId, ref.replaceId, ref.finishId]);
    } finally {
      busy(ref.busyId, false);
    }
  }

  async function requestFollowUp(mode) {
    const ref = controller(mode);
    const sessionId = state[ref.key].sessionId;
    if (!sessionId) return;
    busy(ref.busyId, true);
    setButtons(true, [ref.followId, ref.nextId, ref.submitId, ref.finishId, ref.retryId, ref.replaceId]);
    try {
      const question = await api.post('/api/interviews/' + sessionId + '/follow-up');
      state[ref.key].question = question;
      state[ref.key].canFollowUp = false;
      chatAppend(ref, questionBubble(question));
      // 追问也是一道待回答的新题：可以作答或换掉它，但不能「重答」（还没答过）
      setButtons(false, [ref.submitId, ref.finishId, ref.nextId, ref.replaceId]);
      setButtons(true, [ref.retryId]);
    } catch (error) {
      showError(error);
      setButtons(false, [ref.nextId, ref.finishId]);
    } finally {
      busy(ref.busyId, false);
    }
  }

  async function finishInterview(mode) {
    const ref = controller(mode);
    const sessionId = state[ref.key].sessionId;
    if (!sessionId) return;
    busy(ref.busyId, true);
    setButtons(true, [ref.nextId, ref.followId, ref.submitId, ref.finishId, ref.retryId, ref.replaceId]);
    try {
      await api.post('/api/interviews/' + sessionId + '/finish', { reason: '用户主动结束' });
      toast('ok', '面试已结束', '正在生成复盘报告…');
      const report = await api.post('/api/interviews/' + sessionId + '/report');
      renderReport(report);
      activate('panel-report');
      await refreshReportOptions();
    } catch (error) {
      showError(error);
      setButtons(false, [ref.finishId]);
    } finally {
      busy(ref.busyId, false);
      setButtons(false, [ref.startId]);
    }
  }

  function updateCounter(inputId, counterId) {
    const value = $(inputId).value || '';
    $(counterId).textContent = value.trim().length + ' 字';
  }

  // ---------------------------------------------------------------- 报告模块
  function radarSvg(abilities) {
    const size = 260;
    const center = size / 2;
    const radius = center - 42;
    if (!abilities || !abilities.length) {
      return '<svg width="' + size + '" height="' + size + '"><text x="' + center +
        '" y="' + center + '" fill="#6d78a8" font-size="12" text-anchor="middle">暂无维度数据</text></svg>';
    }
    // 计算每个维度的单位方向与半径比例，轴与网格共用，避免重复推导
    const axes = abilities.map((ability, index) => {
      const angle = (Math.PI * 2 * index) / abilities.length - Math.PI / 2;
      const dx = Math.cos(angle);
      const dy = Math.sin(angle);
      const ratio = ability.sample === 0 ? 0.05 : clamp(ability.score / 5, 0.05, 1);
      return {
        dx: dx,
        dy: dy,
        ratio: ratio,
        x: center + dx * radius * ratio,
        y: center + dy * radius * ratio,
        lx: center + dx * (radius + 22),
        ly: center + dy * (radius + 22),
        label: ability.label,
        score: ability.score,
        sample: ability.sample
      };
    });

    const polygon = axes.map((p) => p.x.toFixed(1) + ',' + p.y.toFixed(1)).join(' ');
    const grid = [0.25, 0.5, 0.75, 1].map((ratio) => {
      const ring = axes.map((p) => (center + p.dx * radius * ratio).toFixed(1) + ',' +
        (center + p.dy * radius * ratio).toFixed(1)).join(' ');
      return '<polygon points="' + ring + '" fill="none" stroke="#26315a" stroke-width="1"/>';
    }).join('');
    const spokes = axes.map((p) => '<line x1="' + center + '" y1="' + center + '" x2="' +
      (center + p.dx * radius).toFixed(1) + '" y2="' + (center + p.dy * radius).toFixed(1) +
      '" stroke="#26315a" stroke-width="1"/>').join('');
    const labels = axes.map((p) => {
      const anchor = p.lx > center + 10 ? 'start' : (p.lx < center - 10 ? 'end' : 'middle');
      const text = p.sample === 0 ? p.label + '（暂无）' : p.label + ' ' + p.score.toFixed(1);
      return '<text x="' + p.lx.toFixed(1) + '" y="' + (p.ly + 4).toFixed(1) +
        '" fill="#9aa6d4" font-size="11" text-anchor="' + anchor + '">' + escapeHtml(text) + '</text>';
    }).join('');

    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size +
      '" role="img" aria-label="能力雷达图">' + grid + spokes +
      '<polygon points="' + polygon + '" fill="rgba(109,140,255,.28)" stroke="#6d8cff" stroke-width="2"/>' +
      axes.map((p) => '<circle cx="' + p.x.toFixed(1) + '" cy="' + p.y.toFixed(1) +
        '" r="2.6" fill="#43d6a5"/>').join('') + labels + '</svg>';
  }

  function listHtml(items, formatter) {
    if (!items || !items.length) return '<p class="small">（无）</p>';
    return '<ul class="clean">' + items.map((item) => '<li>' + formatter(item) + '</li>').join('') + '</ul>';
  }

  function renderReport(payload) {
    const report = payload.report;
    const box = $('report-body');
    box.innerHTML = '';

    const metrics = el('div', 'metrics');
    [['平均得分', report.overallScore.toFixed(2)],
      ['题目', report.questionCount], ['回答', report.answerCount],
      ['时长(秒)', report.durationSeconds]].forEach(([label, value]) => {
      const metric = el('div', 'metric');
      metric.appendChild(el('div', 'value', String(value)));
      metric.appendChild(el('div', 'label', label));
      metrics.appendChild(metric);
    });
    const metricWrap = el('div', null);
    metricWrap.style.marginBottom = '14px';
    metricWrap.appendChild(metrics);
    box.appendChild(metricWrap);
    box.appendChild(el('p', null, report.summary));

    const radar = el('div', 'radar-wrap');
    radar.innerHTML = radarSvg(report.abilityRadar || []);
    const table = el('table', 'data');
    table.innerHTML = '<tr><th>能力维度</th><th>得分</th><th>样本</th></tr>' +
      (report.abilityRadar || []).map((ability) => '<tr><td>' + escapeHtml(ability.label) + '</td><td>' +
        (ability.sample === 0 ? '暂无数据' : ability.score.toFixed(2)) + '</td><td>' + ability.sample + '</td></tr>').join('');
    radar.appendChild(table);
    box.appendChild(radar);

    const sections = [
      ['最需要重新回答的题目', listHtml(report.weakestQuestions, (item) => escapeHtml(item))],
      ['知识缺口', listHtml(report.knowledgeGaps, (gap) =>
        '<strong>' + escapeHtml(gap.topic) + '</strong>：' + escapeHtml(gap.reason) +
        (gap.evidence && gap.evidence.length ? '（依据：' + gap.evidence.map(escapeHtml).join('；') + '）' : ''))],
      ['项目追问风险', listHtml(report.projectRisks, (item) => escapeHtml(item))],
      ['下一步行动建议', listHtml(report.actionItems, (item) => escapeHtml(item))]
    ];
    sections.forEach(([title, html]) => {
      const card = el('div');
      card.style.marginTop = '14px';
      card.appendChild(el('h3', null, title));
      const body = el('div');
      body.innerHTML = html;
      card.appendChild(body);
      box.appendChild(card);
    });

    $('report-markdown').textContent = payload.markdown || '（本场没有生成 Markdown）';
    $('btn-report-download').disabled = !report.sessionId;
    state.reportId = report.sessionId;
  }

  async function refreshReportOptions() {
    try {
      const sessions = await api.get('/api/interviews');
      const select = $('report-select');
      const previous = select.value;
      select.innerHTML = '';
      sessions.filter((s) => s.status === 'FINISHED' || s.status === 'REPORT_READY').forEach((session) => {
        const option = el('option', null, session.modeLabel + ' · ' + session.questionCount + ' 题 · ' +
          (session.status === 'REPORT_READY' ? '已生成' : '待生成'));
        option.value = session.id;
        select.appendChild(option);
      });
      if (previous) select.value = previous;
      if (!select.options.length) {
        const option = el('option', null, '（暂无已结束的面试）');
        option.value = '';
        select.appendChild(option);
      }
    } catch (error) {
      // 静默：报告面板的刷新失败不应打断当前操作
    }
  }

  async function loadReport() {
    const sessionId = $('report-select').value;
    if (!sessionId) {
      toast('warn', '没有可加载的报告', '先完成一场面试，或点击「结束并看报告」。');
      return;
    }
    try {
      let payload;
      try {
        payload = await api.get('/api/interviews/' + sessionId + '/report');
      } catch (error) {
        if (error.code === 'NOT_FOUND') {
          payload = await api.post('/api/interviews/' + sessionId + '/report');
        } else {
          throw error;
        }
      }
      renderReport(payload);
    } catch (error) { showError(error); }
  }

  // ---------------------------------------------------------------- 事件绑定
  function bind() {
    // 面板切换
    $('tabs').addEventListener('click', (event) => {
      const tab = event.target.closest('.tab');
      if (tab) activate(tab.dataset.panel);
    });

    // 模型状态：点一下重新探测。改完环境变量要重启服务，但「模型刚启动好」这种情况
    // 不必再刷新整个页面。
    $('llm-chip').addEventListener('click', () => loadHealth());
    $('llm-chip').style.cursor = 'pointer';

    // 简历：拖放/选择/粘贴
    const dropzone = $('dropzone');
    const fileInput = $('file-input');
    dropzone.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', () => importFile(fileInput.files[0]));
    ['dragenter', 'dragover'].forEach((type) => dropzone.addEventListener(type, (event) => {
      event.preventDefault();
      dropzone.classList.add('dragover');
    }));
    ['dragleave', 'drop'].forEach((type) => dropzone.addEventListener(type, (event) => {
      event.preventDefault();
      dropzone.classList.remove('dragover');
    }));
    dropzone.addEventListener('drop', (event) => {
      const file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      importFile(file);
    });

    $('btn-import-text').addEventListener('click', async () => {
      const text = $('paste-text').value;
      if (!text.trim()) {
        toast('warn', '内容为空', '请先粘贴简历文本。');
        return;
      }
      busy('resume-busy', true);
      try {
        const result = await api.post('/api/resumes/text', { text: text, fileName: '手动粘贴的简历.txt' });
        renderImportWarnings(result);
        toast('ok', '解析完成', '识别到 ' + result.factCount + ' 条事实。');
        await loadResumes(result.resumeId);
        activate('panel-practice');
      } catch (error) {
        renderImportWarnings(null, error);
        showError(error);
      } finally {
        busy('resume-busy', false);
      }
    });

    $('btn-demo-resume').addEventListener('click', () => {
      $('paste-text').value = DEMO_RESUME;
      toast('ok', '已填入示例简历', '点击「解析粘贴内容」即可体验完整流程（示例数据仅用于演示）。');
    });

    $('resume-select').addEventListener('change', (event) => {
      if (state.factsDirty && !confirm('有未保存的修正，确定要切换简历吗？')) return;
      state.factsDirty = false;
      selectResume(event.target.value);
    });
    $('btn-save-facts').addEventListener('click', saveFacts);

    // 单题练习
    $('btn-practice-start').addEventListener('click', () => startInterview('practice'));
    $('btn-practice-next').addEventListener('click', () => nextQuestion('practice'));
    $('btn-practice-followup').addEventListener('click', () => requestFollowUp('practice'));
    $('btn-practice-retry').addEventListener('click', () => retryAnswer('practice'));
    $('btn-practice-replace').addEventListener('click', () => replaceQuestion('practice'));
    $('btn-practice-finish').addEventListener('click', () => finishInterview('practice'));
    $('btn-practice-submit').addEventListener('click', () => submitAnswer('practice'));
    $('practice-answer').addEventListener('input', () => updateCounter('practice-answer', 'practice-counter'));

    // 完整模拟
    $('btn-mock-start').addEventListener('click', () => startInterview('mock'));
    $('btn-mock-next').addEventListener('click', () => nextQuestion('mock'));
    $('btn-mock-followup').addEventListener('click', () => requestFollowUp('mock'));
    $('btn-mock-finish').addEventListener('click', () => finishInterview('mock'));
    $('btn-mock-submit').addEventListener('click', () => submitAnswer('mock'));
    $('mock-answer').addEventListener('input', () => updateCounter('mock-answer', 'mock-counter'));

    // 报告
    $('btn-report-load').addEventListener('click', loadReport);
    $('btn-report-download').addEventListener('click', () => {
      if (!state.reportId) return;
      window.open('/api/interviews/' + state.reportId + '/report.md', '_blank');
    });

    // 快捷键：Ctrl/Cmd + Enter 提交回答（当前激活面板）
    document.addEventListener('keydown', (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        const practiceActive = $('panel-practice').classList.contains('active');
        const mockActive = $('panel-mock').classList.contains('active');
        if (practiceActive) submitAnswer('practice');
        else if (mockActive) submitAnswer('mock');
      }
    });
  }

  // ---------------------------------------------------------------- 示例简历
  const DEMO_RESUME = [
    '张伟',
    '手机：13812345678  邮箱：zhangwei_dev@example.com',
    '',
    '教育经历',
    '2021.09 - 2025.06  北京邮电大学  计算机科学与技术  本科',
    '',
    '实习经历',
    '2024.06 - 2024.12  杭州云启科技有限公司  Java 后端开发实习生',
    '参与订单中心重构，负责优惠券核销链路，使用 Redis 分布式锁保证并发下单幂等',
    '',
    '项目经历',
    '2023.09 - 2024.05  FinanceAgent 智能财务问答系统',
    '项目背景：面向中小企业的财务数据问答平台，需要把自然语言问题转成可执行查询',
    '技术栈：Java 21、Spring Boot 3、PostgreSQL、pgvector、Vue3',
    '个人职责：负责文档解析与检索链路，设计分段与元数据方案，实现向量检索与重排',
    '难点：财务报表 PDF 结构差异大，采用按标题分级切分并保留来源引用',
    '结果：支持 12 类财务问题，平均响应 1.8 秒',
    '',
    '专业技能',
    '编程语言：Java、Python、SQL',
    '框架与中间件：Spring Boot、MyBatis-Plus、Redis、Kafka',
    '数据库：MySQL、PostgreSQL、pgvector',
    '获奖情况',
    '2023 年全国大学生服务外包创新创业大赛 国家级二等奖'
  ].join('\n');

  // ---------------------------------------------------------------- 启动
  document.addEventListener('DOMContentLoaded', async () => {
    bind();
    const panelFromHash = (location.hash || '').replace('#', '');
    if (panelFromHash && document.getElementById(panelFromHash)) {
      activate(panelFromHash);
    }
    await loadHealth();
    try {
      await loadResumes();
    } catch (error) { showError(error); }
    await refreshReportOptions();
    renderFeedback($('practice-feedback'), null, null);
    renderFeedback($('mock-feedback'), null, null);
    updateCounter('practice-answer', 'practice-counter');
    updateCounter('mock-answer', 'mock-counter');
    setInterval(loadHealth, 60000);
  });
})();
