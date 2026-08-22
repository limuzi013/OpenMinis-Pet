(() => {
  'use strict';

  const REPO = 'https://github.com/limuzi013/OpenMinis-Pet';
  const TABS = [
    ['overview', '概览'], ['providers', '供应商与模型'], ['skills', 'Skills'],
    ['mcp', 'MCP'], ['memory', '记忆与 SOUL'], ['system', '环境与存储'],
    ['scheduled', '定时任务'], ['agent', 'Agent'], ['web', 'Web 远程'],
    ['diagnostics', '诊断'], ['advanced', '高级操作']
  ];
  const state = { tab: 'overview', data: {}, loading: false, viewer: '', toastTimer: 0 };
  const e = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const json = (value) => e(JSON.stringify(value, null, 2));
  const date = (value) => {
    if (value === null || value === undefined || value === '') return '—';
    const numeric = Number(value);
    if (Number.isFinite(numeric)) {
      const parsed = new Date(numeric);
      if (!Number.isNaN(parsed.getTime())) return e(parsed.toLocaleString());
    }
    return e(value);
  };
  const size = value => typeof value === 'number' ? `${e(value)} B` : e(value || '—');
  const checked = (value) => value ? ' checked' : '';
  const selected = (a, b) => String(a) === String(b) ? ' selected' : '';
  const arr = value => Array.isArray(value) ? value : [];
  const dayList = value => (Array.isArray(value) ? value : String(value ?? '').split(','))
    .map(x => Number(String(x).trim())).filter(x => Number.isInteger(x) && x >= 1 && x <= 7);
  const makeRpcId = () => globalThis.crypto?.randomUUID?.()
    || `web_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}`;

  async function request(path, options) {
    const response = await fetch(path, { credentials: 'same-origin', ...options });
    if (response.status === 401) { location.reload(); throw new Error('登录已过期'); }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || `请求失败（${response.status}）`);
    return body;
  }
  async function rpc(method, params = {}) {
    const body = await request('/api/rpc', {
      method: 'POST', headers: {'Content-Type':'application/json'},
      body: JSON.stringify({jsonrpc:'2.0', id: makeRpcId(), method, params})
    });
    if (body.error) throw new Error(body.error.message || `${method} 执行失败`);
    return body.result;
  }
  const api = (path, options) => request(path, options);
  const post = (path, body) => api(path, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body || {})});

  // The stock DSH Settings plugin owns this section's lifecycle. Keeping the
  // controller as ordinary DOM avoids a second modal/focus trap while still
  // letting every card use the same DSH design tokens.
  const root = document.createElement('div');
  root.id = 'minis-control-root';
  root.innerHTML = `
    <section class="mc-shell" aria-labelledby="mc-title">
      <header class="mc-head">
        <div><h2 id="mc-title">Minis 控制台</h2><p>与 Android App 共用同一份设置</p></div>
        <span class="mc-spacer"></span>
        <a class="mc-repo" href="${REPO}" target="_blank" rel="noopener noreferrer">项目与反馈</a>
        <button class="mc-icon-btn" data-action="refresh" title="刷新" aria-label="刷新">↻</button>
      </header>
      <select class="mc-select mc-mobile-tabs" aria-label="控制台页面"></select>
      <nav class="mc-nav" aria-label="Minis 控制台"></nav>
      <div class="mc-content"></div>
      <div class="mc-toast" role="status" aria-live="polite"></div>
    </section>`;
  const nav = root.querySelector('.mc-nav');
  const mobileTabs = root.querySelector('.mc-mobile-tabs');
  const content = root.querySelector('.mc-content');
  const title = root.querySelector('#mc-title');
  const toastNode = root.querySelector('.mc-toast');

  function renderNav() {
    nav.innerHTML = TABS.map(([id, label]) => `<button type="button" data-tab="${id}" aria-selected="${id === state.tab}">${label}</button>`).join('');
    mobileTabs.innerHTML = TABS.map(([id, label]) => `<option value="${id}"${selected(id, state.tab)}>${label}</option>`).join('');
    title.textContent = TABS.find(x => x[0] === state.tab)?.[1] || 'Minis 控制台';
  }
  function toast(message, isError = false) {
    clearTimeout(state.toastTimer);
    toastNode.textContent = message;
    toastNode.className = `mc-toast${isError ? ' error' : ''}`;
    toastNode.setAttribute('data-show', 'true');
    state.toastTimer = setTimeout(() => toastNode.setAttribute('data-show', 'false'), 4200);
  }

  let mountedOnce = false;
  function attachToSettings() {
    const host = document.querySelector('[data-minis-control-host]');
    if (!host || root.parentElement === host) return;
    host.appendChild(root);
    renderNav();
    if (!mountedOnce) {
      mountedOnce = true;
      load(state.tab);
    } else {
      load(state.tab);
    }
  }
  new MutationObserver(attachToSettings).observe(document.documentElement, {childList:true, subtree:true});
  queueMicrotask(attachToSettings);

  // App-side edits publish through the same repositories. Refresh the visible
  // tab when the user is not typing so native changes appear without reopening
  // the page and without destroying an in-progress form.
  setInterval(() => {
    if (!root.isConnected || state.loading) return;
    if (root.querySelector('input:focus,textarea:focus,select:focus')) return;
    load(state.tab);
  }, 8000);

  async function load(tab) {
    state.tab = tab; state.loading = true; state.viewer = ''; renderNav(); render();
    try {
      switch (tab) {
        case 'overview': {
          const [status, remote, appInfo, sessions] = await Promise.all([
            api('/api/status'), api('/api/settings'), rpc('debug.appInfo'), rpc('chat.sessions.list', {limit:20, includeEmpty:true})
          ]);
          state.data.overview = {status, remote, appInfo, sessions}; break;
        }
        case 'providers': {
          const [types, instances, groups] = await Promise.all([
            rpc('provider.types'), rpc('provider.instances.list', {includeDisabled:true}), rpc('provider.groups.list', {includeMembers:true})
          ]);
          state.data.providers = {...types, ...instances, ...groups, models: state.data.providers?.models || {}}; break;
        }
        case 'skills': state.data.skills = await rpc('skills.list'); break;
        case 'mcp': state.data.mcp = await rpc('mcp.list'); break;
        case 'memory': {
          const [files, global, soul] = await Promise.all([rpc('memory.files.list'), rpc('memory.globalToggle'), rpc('soul.get')]);
          state.data.memory = {...files, global, soul, editor: state.data.memory?.editor}; break;
        }
        case 'system': {
          const [env, shared, mounts, permission, sandbox] = await Promise.all([
            rpc('environments.list'), rpc('storage.shared.list'), rpc('storage.mounts.list'),
            rpc('settings.permissionPreset.get'), rpc('settings.sandbox.get')
          ]);
          state.data.system = {env, shared, mounts, permission, sandbox}; break;
        }
        case 'scheduled': state.data.scheduled = await rpc('scheduled.list'); break;
        case 'agent': {
          const [settings, jobs, approvals, questions] = await Promise.all([
            rpc('agent.settings.get'), rpc('agent.jobs.list'), rpc('agent.approval.list'), rpc('chat.question.pending')
          ]);
          state.data.agent = {settings, jobs, approvals, questions}; break;
        }
        case 'web': state.data.web = {settings: await api('/api/settings'), status: await api('/api/status')}; break;
        case 'diagnostics': {
          const [info, logs, crashes] = await Promise.all([rpc('debug.appInfo'), rpc('debug.logs.list'), rpc('debug.crash.list', {limit:50})]);
          state.data.diagnostics = {info, logs, crashes}; break;
        }
        case 'advanced': if (!state.data.advanced) {
          const discover = await rpc('rpc.discover');
          state.data.advanced = {discover, method: '', params: '{}', result: null};
        } break;
      }
    } catch (error) { toast(error.message || String(error), true); }
    finally { state.loading = false; renderNav(); render(); }
  }

  function kv(object, omit = []) {
    return `<dl class="mc-kv">${Object.entries(object || {}).filter(([k]) => !omit.includes(k)).map(([k,v]) => `<dt>${e(k)}</dt><dd>${typeof v === 'object' ? `<code>${e(JSON.stringify(v))}</code>` : e(v)}</dd>`).join('')}</dl>`;
  }
  const badge = (on, yes='启用', no='停用') => `<span class="mc-badge ${on ? 'ok' : 'off'}">${on ? yes : no}</span>`;
  const empty = text => `<div class="mc-empty">${e(text)}</div>`;
  const codeDetails = (label, value) => `<details class="mc-details"><summary>${e(label)}</summary><pre class="mc-code">${json(value)}</pre></details>`;

  function renderOverview() {
    const d = state.data.overview || {}, status=d.status||{}, remote=d.remote||{}, info=d.appInfo||{}, sessions=arr(d.sessions?.sessions);
    return `<div class="mc-grid">
      <section class="mc-card"><h3>连接状态</h3><p>${badge(status.ok, '已连接', '未连接')} Android · ${e(status.bindHost||'')} : ${e(status.port||'')}</p><p>Cloudflare Tunnel：${status.tunnel?.running ? '运行中' : '未运行'}</p></section>
      <section class="mc-card"><h3>Minis Web</h3><p>账号：${e(remote.username||'—')}</p><p>局域网：${remote.lanAccess?'已开放':'仅本机/隧道'}</p><p>公开域名：${e(remote.cloudflareHostname||'未配置')}</p></section>
      <section class="mc-card wide"><div class="mc-section-title"><h3>最近会话</h3><span>${sessions.length} 个</span></div><div class="mc-list">${sessions.length ? sessions.slice(0,8).map(s=>`<div class="mc-card-head"><div><strong>${e(s.title||'新会话')}</strong><p>${e(s.modelName||s.modelId||'未选择模型')} · ${e(s.lastMessagePreview||'暂无消息')} · ${date(s.updatedAt)}</p></div>${badge(s.isRunning,'运行中','空闲')}</div>`).join('') : empty('还没有会话')}</div></section>
      <section class="mc-card wide"><h3>App 信息</h3>${kv(info)}${codeDetails('完整信息',info)}</section>
    </div>`;
  }

  function renderProviders() {
    const d=state.data.providers||{}, types=arr(d.types), instances=arr(d.instances), groups=arr(d.groups), models=d.models||{};
    return `<div class="mc-note">凭据不会从手机回传；这里只显示“已配置”。提交新 Key 时会写入 App 的安全存储。</div>
      <div class="mc-section-title"><h3>供应商实例</h3><span>${instances.length}</span></div>
      <div class="mc-list">${instances.length ? instances.map(p=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(p.label)}</h3><p>${e(p.providerType)} · ${p.hasCredential?'凭据已配置':'未配置凭据'} · ${e(p.modelEntryCount||0)} 个模型</p><p>${e(p.customBaseURL||'默认 API 地址')}</p></div><div class="mc-actions">${badge(p.isEnabled)}<button class="mc-btn small" data-action="provider-models" data-id="${e(p.id)}">模型</button><button class="mc-btn small" data-action="provider-test" data-id="${e(p.id)}">测试</button><button class="mc-btn small" data-action="provider-toggle" data-id="${e(p.id)}" data-enabled="${!p.isEnabled}">${p.isEnabled?'停用':'启用'}</button><button class="mc-btn small danger" data-action="provider-delete" data-id="${e(p.id)}">删除</button></div></div>
        ${models[p.id] ? `<details class="mc-details" open><summary>模型目录（${arr(models[p.id].entries).length}）</summary><div class="mc-list">${arr(models[p.id].entries).map(m=>`<div class="mc-card-head"><div><strong>${e(m.displayName)}</strong><p>${e(m.modelId)} · ${m.supportsReasoning?'支持推理':'普通模型'}</p></div><div class="mc-actions"><button class="mc-btn small" data-action="model-loop" data-id="${e(m.id)}" data-enabled="${!m.inAgentLoop}">${m.inAgentLoop?'移出 Agent':'加入 Agent'}</button><button class="mc-btn small danger" data-action="model-delete" data-id="${e(m.id)}">删除</button></div></div>`).join('')||empty('没有模型')}<form class="mc-form" data-form="model-add" data-instance="${e(p.id)}"><label class="mc-field wide">新增模型 ID<input class="mc-input" name="modelId" required placeholder="provider/model-name"></label><button class="mc-btn primary" type="submit">添加模型</button></form></div></details>`:''}
        <details class="mc-details"><summary>编辑实例 / 更新凭据</summary><form class="mc-form" data-form="provider-update" data-id="${e(p.id)}"><label class="mc-field">名称<input class="mc-input" name="label" value="${e(p.label)}"></label><label class="mc-field">新 API Key（留空则保留）<input class="mc-input" type="password" name="apiKey" autocomplete="new-password"></label><label class="mc-field wide">自定义 Base URL<input class="mc-input" name="customBaseURL" value="${e(p.customBaseURL||'')}"></label><button class="mc-btn primary" type="submit">保存</button></form></details></section>`).join('') : empty('尚未配置供应商')}</div>
      <section class="mc-card wide"><h3>添加供应商</h3><form class="mc-form" data-form="provider-create"><label class="mc-field">类型<select class="mc-select" name="providerType">${types.map(t=>`<option value="${e(t.id)}">${e(t.displayName||t.id)}</option>`).join('')}</select></label><label class="mc-field">名称<input class="mc-input" name="label" required></label><label class="mc-field">API Key / Token<input class="mc-input" type="password" name="apiKey" autocomplete="new-password"></label><label class="mc-field">Base URL（OpenAI/Anthropic 兼容类型可选）<input class="mc-input" name="customBaseURL"></label><label class="mc-check"><input type="checkbox" name="seedBuiltInModels" checked> 添加内置模型</label><button class="mc-btn primary" type="submit">创建</button></form></section>
      <div class="mc-section-title"><h3>模型组</h3><span>${groups.length}</span></div><div class="mc-grid">${groups.map(g=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(g.name)}</h3><p>${e(g.strategy)} · ${arr(g.memberEntryIds).length} 个模型</p></div><div>${g.isDefault?'<span class="mc-badge ok">主默认</span>':''} ${g.isSub?'<span class="mc-badge">子 Agent</span>':''}</div></div><div class="mc-actions"><button class="mc-btn small" data-action="group-default" data-id="${e(g.id)}">设为主默认</button><button class="mc-btn small" data-action="group-sub-default" data-id="${e(g.id)}">设为子默认</button><button class="mc-btn small danger" data-action="group-delete" data-id="${e(g.id)}">删除</button></div>${codeDetails('成员',g.members||g.memberEntryIds)}</section>`).join('')||empty('没有模型组')}</div>
      <section class="mc-card wide"><form class="mc-form" data-form="group-create"><label class="mc-field">新模型组名称<input class="mc-input" name="name" required></label><button class="mc-btn primary" type="submit">创建模型组</button></form></section>`;
  }

  function renderSkills() {
    const d=state.data.skills||{}, skills=arr(d.skills), draft=d.editor;
    return `<div class="mc-section-title"><h3>Skills</h3><span>${skills.length} 个，与手机端实时同步</span></div><div class="mc-grid">${skills.map(s=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(s.name)}</h3><p>${e(s.description||'无描述')}</p><p>v${e(s.version)} · 使用 ${e(s.useCount||0)} 次${s.sourceURL?` · ${e(s.sourceURL)}`:''}</p></div>${badge(s.isEnabled)}</div><div class="mc-actions"><button class="mc-btn small" data-action="skill-edit" data-id="${e(s.id)}">编辑</button><button class="mc-btn small" data-action="skill-toggle" data-id="${e(s.id)}" data-enabled="${!s.isEnabled}">${s.isEnabled?'停用':'启用'}</button><button class="mc-btn small danger" data-action="skill-delete" data-id="${e(s.id)}">删除</button></div></section>`).join('')||empty('没有 Skill')}</div>
      <section class="mc-card wide"><h3>通过链接导入 Skill</h3><p>支持 GitHub Skill 目录、SKILL.md 链接或其他公开 HTTPS SKILL.md；同名 Skill 会原位更新。</p><form class="mc-form" data-form="skill-import-url"><label class="mc-field wide">HTTPS / GitHub 链接<input class="mc-input" name="url" type="url" required placeholder="https://github.com/owner/repo/tree/main/skill-name"></label><button class="mc-btn primary" type="submit">从链接导入</button></form></section>
      <section class="mc-card wide"><h3>${draft?'编辑 Skill':'创建 Skill'}</h3><form class="mc-form" data-form="${draft?'skill-update':'skill-create'}" ${draft?`data-id="${e(draft.id)}"`:''}><label class="mc-field">名称<input class="mc-input" name="name" required value="${e(draft?.name||'')}"></label><label class="mc-field">版本<input class="mc-input" name="version" value="${e(draft?.version||'1.0.0')}" ${draft?'disabled':''}></label><label class="mc-field wide">描述<input class="mc-input" name="description" value="${e(draft?.description||'')}"></label><label class="mc-field wide">Skill 正文<textarea class="mc-textarea" name="body">${e(draft?.body||'')}</textarea></label><div class="mc-actions"><button class="mc-btn primary" type="submit">保存</button>${draft?'<button class="mc-btn" type="button" data-action="skill-cancel">取消编辑</button>':''}</div></form></section>`;
  }

  function renderMcp() {
    const servers=arr(state.data.mcp?.servers);
    return `<div class="mc-note">MCP 配置写入 App 原生仓库。HTTP 与命令传输二选一；敏感 Header/环境值不会从网页响应中回显。</div><div class="mc-grid">${servers.map(s=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(s.id)}</h3><p>${e(s.url||s.command||'未配置传输')}</p><p>${e(s.note||'')}</p></div>${badge(s.enabled)}</div><div class="mc-actions"><button class="mc-btn small" data-action="mcp-toggle" data-id="${e(s.id)}" data-enabled="${!s.enabled}">${s.enabled?'停用':'启用'}</button><button class="mc-btn small danger" data-action="mcp-delete" data-id="${e(s.id)}">删除</button></div>${codeDetails('完整配置（已脱敏）',s)}</section>`).join('')||empty('没有 MCP Server')}</div>
      <section class="mc-card wide"><h3>导入 MCP 配置</h3><form class="mc-form" data-form="mcp-import-url"><label class="mc-field wide">公开 HTTPS JSON 链接<input class="mc-input" name="url" type="url" required placeholder="https://raw.githubusercontent.com/owner/repo/main/mcp.json"></label><button class="mc-btn primary" type="submit">从链接导入</button></form><details class="mc-details"><summary>或粘贴 JSON</summary><form class="mc-form" data-form="mcp-import-json"><label class="mc-field wide">MCP JSON<textarea class="mc-textarea" name="configJson" required placeholder='{"mcpServers":{"name":{"url":"https://…"}}}'></textarea></label><button class="mc-btn primary" type="submit">导入 JSON</button></form></details></section>
      <section class="mc-card wide"><h3>添加 MCP Server</h3><form class="mc-form" data-form="mcp-create"><label class="mc-field">Server ID<input class="mc-input" name="serverId" required pattern="[A-Za-z0-9_-]{1,128}"></label><label class="mc-field">备注<input class="mc-input" name="note"></label><label class="mc-field">HTTP URL<input class="mc-input" name="url" placeholder="https://…"></label><label class="mc-field">或本地命令<input class="mc-input" name="command" placeholder="npx …"></label><label class="mc-field wide">参数 JSON 数组<input class="mc-input" name="args" value="[]"></label><label class="mc-check"><input type="checkbox" name="enabled" checked> 启用</label><button class="mc-btn primary" type="submit">创建</button></form></section>`;
  }

  function renderMemory() {
    const d=state.data.memory||{}, files=arr(d.files), soul=d.soul||{}, editor=d.editor;
    return `<div class="mc-grid"><section class="mc-card"><div class="mc-card-head"><div><h3>全局记忆</h3><p>GLOBAL.md 是否注入所有会话</p></div>${badge(d.global?.enabled)}</div><button class="mc-btn" data-action="memory-global" data-enabled="${!d.global?.enabled}">${d.global?.enabled?'关闭':'开启'}全局记忆</button></section>
      <section class="mc-card"><h3>SOUL</h3><p>${e(soul.name||'Minis')} · ${e(soul.style||'')} · ${e(soul.lang||'')}</p></section></div>
      <div class="mc-section-title"><h3>记忆文件</h3><span>${files.length}</span></div><div class="mc-grid">${files.map(f=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(f.name)}</h3><p>${e(f.preview||'')}</p><p>${size(f.fileSize)} · ${date(f.modifiedDate)}</p></div>${f.isGlobal?'<span class="mc-badge">全局</span>':''}</div><div class="mc-actions"><button class="mc-btn small" data-action="memory-edit" data-id="${e(f.name)}">编辑</button><button class="mc-btn small danger" data-action="memory-delete" data-id="${e(f.name)}">删除</button></div></section>`).join('')||empty('没有记忆文件')}</div>
      <section class="mc-card wide"><h3>${editor?'编辑记忆':'新建记忆文件'}</h3><form class="mc-form" data-form="memory-save"><label class="mc-field">文件名<input class="mc-input" name="name" required value="${e(editor?.name||'MEMORY.md')}" ${editor?'readonly':''}></label><label class="mc-field wide">内容<textarea class="mc-textarea" name="content">${e(editor?.content||'')}</textarea></label><div class="mc-actions"><button class="mc-btn primary" type="submit">保存</button>${editor?'<button class="mc-btn" type="button" data-action="memory-cancel">取消</button>':''}</div></form></section>
      <section class="mc-card wide"><h3>编辑 SOUL</h3><form class="mc-form" data-form="soul-save"><label class="mc-field">名称<input class="mc-input" name="name" value="${e(soul.name||'')}"></label><label class="mc-field">语言<input class="mc-input" name="lang" value="${e(soul.lang||'')}"></label><label class="mc-field wide">风格<input class="mc-input" name="style" value="${e(soul.style||'')}"></label><label class="mc-field wide">正文<textarea class="mc-textarea" name="body">${e(soul.body||'')}</textarea></label><button class="mc-btn primary" type="submit">保存 SOUL</button></form></section>`;
  }

  function renderSystem() {
    const d=state.data.system||{}, env=arr(d.env?.entries), mounts=arr(d.mounts?.mounts), shared=arr(d.shared?.folders), permission=d.permission||{};
    return `<div class="mc-grid"><section class="mc-card"><h3>远程权限预设</h3><p>${e(permission.label||permission.preset||'')}</p><p>${permission.danger?'⚠ 高风险：可写完整沙箱':'仅允许工作区写入'}</p><div class="mc-actions"><button class="mc-btn" data-action="permission-set" data-id="workspace-write">工作区写入</button><button class="mc-btn danger" data-action="permission-set" data-id="danger-full-access">完整访问</button></div></section><section class="mc-card"><h3>沙箱</h3>${kv(d.sandbox||{})}</section></div>
      <div class="mc-section-title"><h3>环境变量</h3><span>值只写不读</span></div><div class="mc-grid">${env.map(v=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(v.key)}</h3><p>${e(v.note||'')}</p></div>${badge(v.hasValue,'已设值','空值')}</div><div class="mc-actions"><button class="mc-btn small" data-action="env-secret" data-id="${e(v.id)}" data-key="${e(v.key)}">更新值</button><button class="mc-btn small danger" data-action="env-delete" data-id="${e(v.id)}">删除</button></div></section>`).join('')||empty('没有环境变量')}</div><section class="mc-card wide"><form class="mc-form" data-form="env-create"><label class="mc-field">变量名<input class="mc-input" name="key" required pattern="[A-Za-z_][A-Za-z0-9_]*"></label><label class="mc-field">值<input class="mc-input" type="password" name="value" required autocomplete="new-password"></label><label class="mc-field wide">备注<input class="mc-input" name="note"></label><button class="mc-btn primary" type="submit">添加</button></form></section>
      <div class="mc-section-title"><h3>外部挂载</h3><span>${mounts.length}/${e(d.mounts?.capacity||'—')} · 新增目录需在手机授权</span></div><div class="mc-grid">${mounts.map(m=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(m.name)}</h3><p>${e(m.path)} · ${e(m.sourceDisplayName)}</p></div>${badge(m.effectiveWritable,'可写','只读')}</div><div class="mc-actions"><button class="mc-btn small" data-action="mount-rename" data-id="${e(m.id)}" data-name="${e(m.name)}">重命名</button><button class="mc-btn small" data-action="mount-write" data-id="${e(m.id)}" data-enabled="${!m.userAllowWrite}">${m.userAllowWrite?'设为只读':'允许写入'}</button><button class="mc-btn small danger" data-action="mount-remove" data-id="${e(m.id)}">移除</button></div></section>`).join('')||empty('没有外部挂载')}</div>${codeDetails('共享目录',shared)}`;
  }

  function taskForm(task={}) {
    return `<form class="mc-form" data-form="${task.id?'task-update':'task-create'}" ${task.id?`data-id="${e(task.id)}"`:''}><label class="mc-field">名称<input class="mc-input" name="label" required value="${e(task.label||'')}"></label><label class="mc-field">重复<select class="mc-select" name="repeatMode"><option${selected(task.repeatMode,'ONCE')}>ONCE</option><option${selected(task.repeatMode,'DAILY')}>DAILY</option><option${selected(task.repeatMode,'WEEKDAYS')}>WEEKDAYS</option><option${selected(task.repeatMode,'CUSTOM')}>CUSTOM</option></select></label><label class="mc-field">小时（0-23）<input class="mc-input" type="number" min="0" max="23" name="hour" value="${e(task.timeOfDayHour??task.hour??9)}"></label><label class="mc-field">分钟（0-59）<input class="mc-input" type="number" min="0" max="59" name="minute" value="${e(task.timeOfDayMinute??task.minute??0)}"></label><label class="mc-field wide">自定义星期（1=周日…7=周六）<input class="mc-input" name="customDays" value="${e(dayList(task.customDays).join(','))}"></label><label class="mc-field wide">目标模式<input class="mc-input" name="targetMode" value="${e(task.targetMode||'NEW_SESSION')}"></label><label class="mc-field wide">Prompt<textarea class="mc-textarea" name="prompt">${e(task.prompt||'')}</textarea></label><label class="mc-check"><input type="checkbox" name="enabled"${checked(task.enabled!==false)}> 启用</label><div class="mc-actions"><button class="mc-btn primary" type="submit">${task.id?'保存修改':'创建任务'}</button>${task.id?'<button class="mc-btn" type="button" data-action="task-cancel">取消</button>':''}</div></form>`;
  }
  function renderScheduled() {
    const d=state.data.scheduled||{}, tasks=arr(d.tasks), draft=d.editor;
    return `<div class="mc-note">真正的调度、AlarmManager 注册和运行历史仍由 Android 执行；网页与手机使用同一份任务数据。</div><div class="mc-grid">${tasks.map(t=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(t.label)}</h3><p>${e(String(t.timeOfDayHour??t.hour??0).padStart(2,'0'))}:${e(String(t.timeOfDayMinute??t.minute??0).padStart(2,'0'))} · ${e(t.repeatMode)} · ${e(t.targetMode)}</p><p>下次：${date(t.nextTriggerMs)} · 已运行 ${e(t.runCount||0)} 次</p></div>${badge(t.enabled)}</div><div class="mc-actions"><button class="mc-btn small" data-action="task-edit" data-id="${e(t.id)}">编辑</button><button class="mc-btn small" data-action="task-run" data-id="${e(t.id)}">立即运行</button><button class="mc-btn small" data-action="task-toggle" data-id="${e(t.id)}" data-enabled="${!t.enabled}">${t.enabled?'停用':'启用'}</button><button class="mc-btn small danger" data-action="task-delete" data-id="${e(t.id)}">删除</button></div>${codeDetails('运行历史',t.runHistory||[])}</section>`).join('')||empty('没有定时任务')}</div><section class="mc-card wide"><h3>${draft?'编辑定时任务':'新建定时任务'}</h3>${taskForm(draft||{})}</section>`;
  }

  function renderAgent() {
    const d=state.data.agent||{}, jobs=arr(d.jobs?.jobs), approvals=arr(d.approvals?.approvals), questions=arr(d.questions?.questions), s=d.settings||{};
    return `<div class="mc-grid"><section class="mc-card"><h3>子 Agent 限制</h3><form class="mc-form" data-form="agent-settings"><label class="mc-field">最大深度<input class="mc-input" type="number" min="1" max="5" name="maxDepth" value="${e(s.maxDepth||2)}"></label><label class="mc-field">超时（分钟）<input class="mc-input" type="number" min="1" max="30" name="timeoutMinutes" value="${e(s.timeoutMinutes||10)}"></label><button class="mc-btn primary" type="submit">保存</button></form></section><section class="mc-card"><h3>等待用户</h3><p>${approvals.length} 个审批 · ${questions.length} 个问题</p><p class="mc-muted">聊天页会实时显示原生审批和问题卡片。</p></section></div>
      <div class="mc-section-title"><h3>后台作业</h3><span>${jobs.length}</span></div><div class="mc-grid">${jobs.map(j=>`<section class="mc-card"><div class="mc-card-head"><div><h3>${e(j.label||j.id)}</h3><p>${e(j.kind)} · ${e(j.detail||'')}</p></div><span class="mc-badge">${e(j.status)}</span></div>${['running','stopping'].includes(j.status)?`<button class="mc-btn small danger" data-action="job-cancel" data-id="${e(j.id)}">取消</button>`:''}${codeDetails('输出',j.output||'')}</section>`).join('')||empty('没有后台作业')}</div>${codeDetails('待审批',approvals)}${codeDetails('待回答问题',questions)}`;
  }

  function renderWeb() {
    const d=state.data.web||{}, s=d.settings||{}, status=d.status||{};
    return `<div class="mc-note mc-danger-note">端口或局域网监听变更需要重启 Web 远程服务。修改账号或密码会注销所有网页会话。</div><div class="mc-grid"><section class="mc-card"><h3>服务</h3><form class="mc-form" data-form="web-service"><label class="mc-field">端口<input class="mc-input" type="number" min="1024" max="65535" name="port" value="${e(s.port||8765)}"></label><label class="mc-check"><input type="checkbox" name="lanAccess"${checked(s.lanAccess)}> 开放局域网监听</label><button class="mc-btn primary" type="submit">保存服务设置</button></form></section><section class="mc-card"><h3>账号</h3><form class="mc-form" data-form="web-identity"><label class="mc-field">用户名<input class="mc-input" name="username" value="${e(s.username||'')}"></label><label class="mc-field">当前密码<input class="mc-input" type="password" name="currentPassword" autocomplete="current-password" required></label><label class="mc-field">新密码（可选）<input class="mc-input" type="password" name="newPassword" autocomplete="new-password"></label><button class="mc-btn primary" type="submit">更新账号</button></form></section><section class="mc-card wide"><h3>Cloudflare Tunnel</h3><form class="mc-form" data-form="web-tunnel"><label class="mc-field">公开域名<input class="mc-input" name="cloudflareHostname" value="${e(s.cloudflareHostname||'')}"></label><label class="mc-field">新 Tunnel Token（留空保留）<input class="mc-input" type="password" name="cloudflareTunnelToken" autocomplete="new-password"></label><label class="mc-check"><input type="checkbox" name="cloudflareTunnelEnabled"${checked(s.cloudflareTunnelEnabled)}> 启用隧道</label><button class="mc-btn primary" type="submit">保存隧道设置</button></form><p>${e(status.tunnel?.phase||'')} · ${e(status.tunnel?.detail||'')}</p></section></div><div class="mc-actions" style="margin-top:14px"><button class="mc-btn" data-action="web-restart">重启 Web 服务</button><button class="mc-btn danger" data-action="logout">退出登录</button></div>`;
  }

  function renderDiagnostics() {
    const d=state.data.diagnostics||{}, logs=arr(d.logs?.files||d.logs?.logs), crashes=arr(d.crashes?.crashes||d.crashes?.files);
    return `<div class="mc-grid"><section class="mc-card wide"><h3>App 与存储</h3>${kv(d.info||{})}</section><section class="mc-card"><h3>日志</h3><div class="mc-list">${logs.map(x=>{const n=typeof x==='string'?x:(x.name||x.fileName);return `<div class="mc-card-head"><div><strong>${e(n)}</strong><p>${e(x.size||'')}</p></div><span class="mc-badge off">内容仅手机可读</span></div>`}).join('')||empty('没有日志')}</div></section><section class="mc-card"><h3>崩溃报告</h3><div class="mc-list">${crashes.map(x=>{const n=typeof x==='string'?x:(x.name||x.fileName);return `<div class="mc-card-head"><div><strong>${e(n)}</strong><p>${e(x.summary||'')}</p></div><span class="mc-badge off">内容仅手机可读</span></div>`}).join('')||empty('没有崩溃报告')}</div></section>${state.viewer?`<section class="mc-card wide"><h3>查看内容</h3><pre class="mc-code">${e(state.viewer)}</pre></section>`:''}</div>`;
  }

  function renderAdvanced() {
    const d=state.data.advanced||{}, webPrefixes=['provider.','chat.','skills.','memory.','soul.','mcp.','scheduled.','environments.','storage.','agent.','settings.','debug.logs.','debug.crash.'];
    const methods=arr(d.discover?.methods).filter(m=>(m.name==='rpc.discover'||m.name==='debug.appInfo'||webPrefixes.some(p=>m.name.startsWith(p)))&&!['provider.export','provider.import','debug.logs.setEnabled','debug.logs.read','debug.crash.read'].includes(m.name));
    return `<div class="mc-note">这里覆盖所有已开放但尚未做成可视化表单的 App RPC。敏感的凭据导出、设备点击、截图、任意文件与 Shell 调试能力不会向公网 Web 开放。</div><section class="mc-card wide"><form class="mc-form" data-form="rpc-run"><label class="mc-field wide">方法<input class="mc-input" name="method" list="mc-methods" value="${e(d.method||'')}" placeholder="例如 provider.groups.update" required><datalist id="mc-methods">${methods.map(m=>`<option value="${e(m.name)}">${e(m.description||'')}</option>`).join('')}</datalist></label><label class="mc-field wide">参数 JSON<textarea class="mc-textarea" name="params">${e(d.params||'{}')}</textarea></label><button class="mc-btn primary" type="submit">执行 RPC</button></form>${d.result!==null&&d.result!==undefined?`<pre class="mc-code">${json(d.result)}</pre>`:''}</section><section class="mc-card wide"><h3>可用方法（${methods.length}）</h3>${codeDetails('查看发现文档',methods)}</section>`;
  }

  function render() {
    if (state.loading) { content.innerHTML = '<div class="mc-loading">正在从手机读取…</div>'; return; }
    const fn = ({overview:renderOverview,providers:renderProviders,skills:renderSkills,mcp:renderMcp,memory:renderMemory,system:renderSystem,scheduled:renderScheduled,agent:renderAgent,web:renderWeb,diagnostics:renderDiagnostics,advanced:renderAdvanced})[state.tab];
    content.innerHTML = fn ? fn() : empty('页面不可用');
  }

  async function perform(action, success='已完成', reload=true) {
    try { await action(); toast(success); if (reload) await load(state.tab); else render(); }
    catch (error) { toast(error.message || String(error), true); }
  }
  const bool = value => String(value) === 'true';
  root.addEventListener('click', async event => {
    const tabButton = event.target.closest('[data-tab]');
    if (tabButton) { await load(tabButton.dataset.tab); return; }
    const button = event.target.closest('[data-action]'); if (!button) return;
    const a=button.dataset.action, id=button.dataset.id, enabled=bool(button.dataset.enabled);
    if (a==='refresh') return load(state.tab);
    if (a==='provider-models') return perform(async()=>{const d=await rpc('provider.models.list',{instanceId:id});state.data.providers.models[id]=d},'已读取模型',false);
    if (a==='provider-test') return perform(async()=>{const result=await rpc('provider.instances.test',{instanceId:id});if(!result.ok)throw new Error(result.error?.message||'连接测试失败')},'连接测试成功',false);
    if (a==='provider-toggle') return perform(()=>rpc('provider.instances.update',{instanceId:id,isEnabled:enabled}));
    if (a==='provider-delete' && confirm('删除该供应商及其模型？')) return perform(()=>rpc('provider.instances.delete',{instanceId:id,confirm:true}));
    if (a==='model-delete' && confirm('删除该模型？')) return perform(()=>rpc('provider.models.delete',{entryId:id,confirm:true}));
    if (a==='model-loop') return perform(()=>rpc('provider.models.setAgentLoop',{entryId:id,inLoop:enabled}));
    if (a==='group-default') return perform(()=>rpc('provider.groups.setDefault',{groupId:id}));
    if (a==='group-sub-default') return perform(()=>rpc('provider.groups.setSubDefault',{groupId:id}));
    if (a==='group-delete' && confirm('删除该模型组？')) return perform(()=>rpc('provider.groups.delete',{groupId:id,confirm:true}));
    if (a==='skill-toggle') return perform(()=>rpc('skills.toggle',{skillId:id,enabled}));
    if (a==='skill-delete' && confirm('永久删除这个 Skill？')) return perform(()=>rpc('skills.delete',{skillId:id}));
    if (a==='skill-edit') return perform(async()=>{state.data.skills.editor=await rpc('skills.get',{skillId:id})},'已载入',false);
    if (a==='skill-cancel') { delete state.data.skills.editor; return render(); }
    if (a==='mcp-toggle') return perform(()=>rpc('mcp.toggle',{serverId:id,enabled}));
    if (a==='mcp-delete' && confirm('删除该 MCP Server？')) return perform(()=>rpc('mcp.delete',{serverId:id}));
    if (a==='memory-global') return perform(()=>rpc('memory.setGlobalEnabled',{enabled}));
    if (a==='memory-edit') return perform(async()=>{state.data.memory.editor=await rpc('memory.files.read',{name:id})},'已载入',false);
    if (a==='memory-cancel') { delete state.data.memory.editor; return render(); }
    if (a==='memory-delete' && confirm(`删除记忆文件 ${id}？`)) return perform(()=>rpc('memory.files.delete',{name:id}));
    if (a==='env-secret') { const value=prompt(`输入 ${button.dataset.key} 的新值（不会回显旧值）`); if(value!==null)return perform(()=>rpc('environments.update',{id,value})); }
    if (a==='env-delete' && confirm('删除该环境变量？')) return perform(()=>rpc('environments.delete',{id}));
    if (a==='mount-rename') { const name=prompt('新名称',button.dataset.name); if(name)return perform(()=>rpc('storage.mounts.rename',{id,name})); }
    if (a==='mount-write') return perform(()=>rpc('storage.mounts.setWritable',{id,allowWrite:enabled}));
    if (a==='mount-remove' && confirm('移除该外部目录挂载？手机中的原文件不会删除。')) return perform(()=>rpc('storage.mounts.remove',{id,confirm:true}));
    if (a==='permission-set') { if(id==='danger-full-access'&&!confirm('完整访问允许网页修改沙箱工作区以外的文件。确认开启？'))return; return perform(()=>rpc('settings.permissionPreset.set',{preset:id})); }
    if (a==='task-edit') return perform(async()=>{state.data.scheduled.editor=(await rpc('scheduled.get',{taskId:id})).task},'已载入',false);
    if (a==='task-cancel') { delete state.data.scheduled.editor; return render(); }
    if (a==='task-run') return perform(()=>rpc('scheduled.run',{taskId:id}),'任务已交给 Android 后台运行');
    if (a==='task-toggle') return perform(()=>rpc('scheduled.toggle',{taskId:id,enabled}));
    if (a==='task-delete' && confirm('永久删除该定时任务？')) return perform(()=>rpc('scheduled.delete',{taskId:id}));
    if (a==='job-cancel' && confirm('取消这个后台作业？')) return perform(()=>rpc('agent.jobs.cancel',{id,reason:'Minis Web user'}));
    if (a==='web-restart' && confirm('重启 Web 服务？当前连接会短暂断开。')) return perform(()=>post('/api/settings/restart',{}),'正在重启',false);
    if (a==='logout') return perform(async()=>{await post('/api/auth/logout',{});location.reload()},'已退出',false);
  });
  mobileTabs.addEventListener('change', () => load(mobileTabs.value));

  function formObject(form) { const o={}; for(const [k,v] of new FormData(form)) o[k]=v; form.querySelectorAll('input[type=checkbox]').forEach(x=>o[x.name]=x.checked); return o; }
  const omitBlank = (o, keys) => { keys.forEach(k=>{if(o[k]==='')delete o[k]}); return o; };
  root.addEventListener('submit', event => {
    const form=event.target.closest('[data-form]'); if(!form)return; event.preventDefault();
    const name=form.dataset.form, v=formObject(form);
    if(name==='provider-create') { const type=arr(state.data.providers?.types).find(x=>x.id===v.providerType);if(!type?.customBaseURLSupported)delete v.customBaseURL;return perform(()=>rpc('provider.instances.create',omitBlank(v,['apiKey','customBaseURL']))); }
    if(name==='provider-update') { v.instanceId=form.dataset.id; return perform(()=>rpc('provider.instances.update',omitBlank(v,['apiKey']))); }
    if(name==='model-add') return perform(()=>rpc('provider.models.add',{instanceId:form.dataset.instance,modelId:v.modelId}));
    if(name==='group-create') return perform(()=>rpc('provider.groups.create',{name:v.name}));
    if(name==='skill-create') return perform(()=>rpc('skills.create',v));
    if(name==='skill-import-url') return perform(()=>rpc('skills.importUrl',{url:v.url.trim()}),'Skill 已从链接导入');
    if(name==='skill-update') { v.skillId=form.dataset.id; delete v.version; return perform(()=>rpc('skills.update',v)); }
    if(name==='mcp-create') { try{v.args=JSON.parse(v.args||'[]')}catch{return toast('参数必须是 JSON 数组',true)};omitBlank(v,['url','command','note']);if(Boolean(v.url)===Boolean(v.command))return toast('HTTP URL 与本地命令必须且只能填写一个',true);return perform(()=>rpc('mcp.create',v)); }
    if(name==='mcp-import-url') return perform(()=>rpc('mcp.importUrl',{url:v.url.trim()}),'MCP 配置已从链接导入');
    if(name==='mcp-import-json') return perform(()=>rpc('mcp.import',{configJson:v.configJson}),'MCP JSON 已导入');
    if(name==='memory-save') return perform(()=>rpc('memory.files.write',v));
    if(name==='soul-save') return perform(()=>rpc('soul.save',v));
    if(name==='env-create') return perform(()=>rpc('environments.create',v));
    if(name==='task-create'||name==='task-update') { v.hour=Number(v.hour);v.minute=Number(v.minute);v.customDays=dayList(v.customDays);if(name==='task-update')v.taskId=form.dataset.id;return perform(()=>rpc(name==='task-create'?'scheduled.create':'scheduled.update',v)); }
    if(name==='agent-settings') return perform(()=>rpc('agent.settings.set',{maxDepth:Number(v.maxDepth),timeoutMinutes:Number(v.timeoutMinutes)}));
    if(name==='web-service') return perform(()=>api('/api/settings',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({port:Number(v.port),lanAccess:v.lanAccess})}));
    if(name==='web-identity') return perform(()=>api('/api/settings',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(omitBlank(v,['newPassword']))}),'账号已更新，请重新登录');
    if(name==='web-tunnel') return perform(()=>api('/api/settings',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(omitBlank(v,['cloudflareTunnelToken']))}));
    if(name==='rpc-run') { let params;try{params=JSON.parse(v.params||'{}')}catch{return toast('参数不是有效 JSON',true)};state.data.advanced.method=v.method;state.data.advanced.params=v.params;return perform(async()=>{state.data.advanced.result=await rpc(v.method,params)},'RPC 已执行',false); }
  });

  renderNav();
})();
