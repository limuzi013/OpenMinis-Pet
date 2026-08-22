window.__ModuleLoader__.load({
  id: "@openminis/minis-client-settings",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    "use strict";
    var __defProp = Object.defineProperty;
    var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
    var __getOwnPropNames = Object.getOwnPropertyNames;
    var __hasOwnProp = Object.prototype.hasOwnProperty;
    var __export = (target, all) => {
      for (var name in all)
        __defProp(target, name, { get: all[name], enumerable: true });
    };
    var __copyProps = (to, from, except, desc) => {
      if (from && typeof from === "object" || typeof from === "function") {
        for (let key of __getOwnPropNames(from))
          if (!__hasOwnProp.call(to, key) && key !== except)
            __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
      }
      return to;
    };
    var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
    
    // src/client/index.ts
    var index_exports = {};
    __export(index_exports, {
      apply: () => apply,
      inject: () => inject
    });
    module.exports = __toCommonJS(index_exports);
    
    // src/client/contract/types.ts
    function objectOf(value) {
      return typeof value === "object" && value !== null && !Array.isArray(value) ? value : {};
    }
    function arrayOf(value) {
      return Array.isArray(value) ? value : [];
    }
    function objectsOf(value) {
      return arrayOf(value).map(objectOf);
    }
    function textOf(value, fallback = "") {
      if (typeof value === "string") return value;
      if (typeof value === "number" || typeof value === "boolean") return String(value);
      return fallback;
    }
    function numberOf(value, fallback = 0) {
      return typeof value === "number" && Number.isFinite(value) ? value : fallback;
    }
    function booleanOf(value, fallback = false) {
      return typeof value === "boolean" ? value : fallback;
    }
    function cloneJson(value) {
      return JSON.parse(JSON.stringify(value));
    }
    
    // src/client/service/MinisApiService.ts
    var MinisTransportError = class extends Error {
      constructor(message, status) {
        super(message);
        this.status = status;
        this.name = "MinisTransportError";
      }
    };
    var MinisApiService = class {
      constructor(fetcher = globalThis.fetch.bind(globalThis), rpcPath = "/api/rpc") {
        this.fetcher = fetcher;
        this.rpcPath = rpcPath;
      }
      /** Issue one same-origin JSON request and validate that JSON was returned. */
      async request(path, init = {}, signal) {
        const response = await this.fetcher(path, {
          credentials: "same-origin",
          ...init,
          ...signal === void 0 ? {} : { signal }
        });
        let body;
        try {
          body = await response.json();
        } catch {
          throw new MinisTransportError(`\u670D\u52A1\u5668\u8FD4\u56DE\u4E86\u65E0\u6548 JSON\uFF08HTTP ${response.status}\uFF09`, response.status);
        }
        if (response.status === 401) {
          throw new MinisTransportError("\u767B\u5F55\u5DF2\u8FC7\u671F\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55", 401);
        }
        if (!response.ok) {
          const object = objectOf(body);
          throw new MinisTransportError(
            textOf(object.error, `\u8BF7\u6C42\u5931\u8D25\uFF08HTTP ${response.status}\uFF09`),
            response.status
          );
        }
        return body;
      }
      /** Call an Android management RPC without exposing its HTTP path to React. */
      async rpc(method, params = {}, signal) {
        const envelope = objectOf(await this.request(this.rpcPath, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            jsonrpc: "2.0",
            id: this.rpcId(),
            method,
            params
          })
        }, signal));
        const error = objectOf(envelope.error);
        if (Object.keys(error).length > 0) {
          throw new MinisTransportError(textOf(error.message, `${method} \u6267\u884C\u5931\u8D25`));
        }
        if (!Object.prototype.hasOwnProperty.call(envelope, "result")) {
          throw new MinisTransportError(`${method} \u8FD4\u56DE\u7F3A\u5C11 result`);
        }
        return envelope.result ?? null;
      }
      /** POST one Android HTTP control action through the same authenticated seam. */
      post(path, body = {}, signal) {
        return this.request(path, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body)
        }, signal);
      }
      /** PATCH one Android HTTP settings document through the same authenticated seam. */
      patch(path, body, signal) {
        return this.request(path, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body)
        }, signal);
      }
      rpcId() {
        return globalThis.crypto?.randomUUID?.() ?? `minis_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}`;
      }
    };
    
    // src/client/store/MinisSettingsController.ts
    var import_client = require("@deepseek-ai/dsh-client-runtime/client");
    var POLL_INTERVAL_MS = 8e3;
    var TOAST_DURATION_MS = 4200;
    var MinisSettingsController = class {
      constructor(api) {
        this.api = api;
      }
      store = (0, import_client.createSnapshotStore)({
        tab: "overview",
        phase: "idle",
        busy: false,
        pages: {}
      });
      generations = /* @__PURE__ */ new Map();
      requests = /* @__PURE__ */ new Map();
      retainCount = 0;
      pollTimer;
      toastTimer;
      disposed = false;
      toastNonce = 0;
      /** Retain active page synchronization; the returned disposer is idempotent. */
      activate() {
        if (this.disposed) return () => {
        };
        this.retainCount += 1;
        if (this.retainCount === 1) {
          if (this.store.getSnapshot().phase === "idle") void this.load(this.store.getSnapshot().tab);
          this.schedulePoll();
        }
        let active = true;
        return () => {
          if (!active) return;
          active = false;
          this.retainCount = Math.max(0, this.retainCount - 1);
          if (this.retainCount === 0) this.stopPoll();
        };
      }
      /** Tear down timers, listeners, and pending requests on plugin unload. */
      dispose() {
        if (this.disposed) return;
        this.disposed = true;
        this.stopPoll();
        if (this.toastTimer !== void 0) clearTimeout(this.toastTimer);
        for (const request of this.requests.values()) request.abort();
        this.requests.clear();
      }
      selectTab(tab) {
        if (this.store.getSnapshot().tab === tab) return;
        this.store.update((draft) => {
          draft.tab = tab;
          delete draft.error;
        });
        void this.load(tab);
      }
      refresh() {
        void this.load(this.store.getSnapshot().tab, true);
      }
      /** Run a named mutation; components never see fetch, connection, or ctx. */
      async run(command) {
        if (this.disposed || this.store.getSnapshot().busy) return;
        this.store.update((draft) => {
          draft.busy = true;
        });
        let refresh = true;
        let message = "\u5DF2\u5B8C\u6210";
        try {
          const p = command.payload ?? {};
          switch (command.kind) {
            case "provider-models": {
              const instanceId = textOf(p.instanceId);
              const models = await this.api.rpc("provider.models.list", { instanceId });
              this.updatePage("providers", (page) => {
                const current = objectOf(page.models);
                page.models = { ...current, [instanceId]: models };
              });
              refresh = false;
              message = "\u5DF2\u8BFB\u53D6\u6A21\u578B";
              break;
            }
            case "provider-test": {
              const result = objectOf(await this.api.rpc("provider.instances.test", p));
              if (!booleanOf(result.ok)) throw new Error(textOf(objectOf(result.error).message, "\u8FDE\u63A5\u6D4B\u8BD5\u5931\u8D25"));
              refresh = false;
              message = "\u8FDE\u63A5\u6D4B\u8BD5\u6210\u529F";
              break;
            }
            case "provider-toggle":
              await this.api.rpc("provider.instances.update", p);
              break;
            case "provider-delete":
              await this.api.rpc("provider.instances.delete", { ...p, confirm: true });
              break;
            case "provider-create":
              await this.api.rpc("provider.instances.create", p);
              break;
            case "provider-update":
              await this.api.rpc("provider.instances.update", p);
              break;
            case "model-add":
              await this.api.rpc("provider.models.add", p);
              break;
            case "model-delete":
              await this.api.rpc("provider.models.delete", { ...p, confirm: true });
              break;
            case "model-loop":
              await this.api.rpc("provider.models.setAgentLoop", p);
              break;
            case "group-create":
              await this.api.rpc("provider.groups.create", p);
              break;
            case "group-default":
              await this.api.rpc("provider.groups.setDefault", p);
              break;
            case "group-sub-default":
              await this.api.rpc("provider.groups.setSubDefault", p);
              break;
            case "group-delete":
              await this.api.rpc("provider.groups.delete", { ...p, confirm: true });
              break;
            case "skill-toggle":
              await this.api.rpc("skills.toggle", p);
              break;
            case "skill-delete":
              await this.api.rpc("skills.delete", p);
              break;
            case "skill-import-url":
              await this.api.rpc("skills.importUrl", p);
              message = "Skill \u5DF2\u4ECE\u94FE\u63A5\u5BFC\u5165";
              break;
            case "skill-create":
              await this.api.rpc("skills.create", p);
              break;
            case "skill-update":
              await this.api.rpc("skills.update", p);
              break;
            case "skill-edit": {
              const editor = await this.api.rpc("skills.get", p);
              this.updatePage("skills", (page) => {
                page.editor = editor;
              });
              refresh = false;
              message = "\u5DF2\u8F7D\u5165";
              break;
            }
            case "skill-cancel":
              this.deletePageKey("skills", "editor");
              refresh = false;
              message = "\u5DF2\u53D6\u6D88";
              break;
            case "mcp-toggle":
              await this.api.rpc("mcp.toggle", p);
              break;
            case "mcp-delete":
              await this.api.rpc("mcp.delete", p);
              break;
            case "mcp-create":
              await this.api.rpc("mcp.create", p);
              break;
            case "mcp-import-url":
              await this.api.rpc("mcp.importUrl", p);
              message = "MCP \u914D\u7F6E\u5DF2\u4ECE\u94FE\u63A5\u5BFC\u5165";
              break;
            case "mcp-import-json":
              await this.api.rpc("mcp.import", p);
              message = "MCP JSON \u5DF2\u5BFC\u5165";
              break;
            case "memory-global":
              await this.api.rpc("memory.setGlobalEnabled", p);
              break;
            case "memory-edit": {
              const editor = await this.api.rpc("memory.files.read", p);
              this.updatePage("memory", (page) => {
                page.editor = editor;
              });
              refresh = false;
              message = "\u5DF2\u8F7D\u5165";
              break;
            }
            case "memory-cancel":
              this.deletePageKey("memory", "editor");
              refresh = false;
              message = "\u5DF2\u53D6\u6D88";
              break;
            case "memory-delete":
              await this.api.rpc("memory.files.delete", p);
              break;
            case "memory-save":
              await this.api.rpc("memory.files.write", p);
              break;
            case "soul-save":
              await this.api.rpc("soul.save", p);
              break;
            case "capability-toggle": {
              await this.api.rpc("settings.capabilities.set", p);
              const caps = await this.api.rpc("settings.capabilities.get");
              this.updatePage("system", (page) => {
                page.caps = caps;
              });
              refresh = false;
              message = "\u80FD\u529B\u5DF2\u66F4\u65B0\uFF08\u4E24\u7AEF\u540C\u6B65\uFF09";
              break;
            }
            case "permission-set": {
              await this.api.rpc("settings.permissionPreset.set", p);
              break;
            }
            case "env-create":
              await this.api.rpc("environments.create", p);
              break;
            case "env-update":
              await this.api.rpc("environments.update", p);
              break;
            case "env-delete":
              await this.api.rpc("environments.delete", p);
              break;
            case "mount-rename":
              await this.api.rpc("storage.mounts.rename", p);
              break;
            case "mount-write":
              await this.api.rpc("storage.mounts.setWritable", p);
              break;
            case "mount-remove":
              await this.api.rpc("storage.mounts.remove", { ...p, confirm: true });
              break;
            case "task-edit": {
              const response = objectOf(await this.api.rpc("scheduled.get", p));
              this.updatePage("scheduled", (page) => {
                page.editor = response.task ?? null;
              });
              refresh = false;
              message = "\u5DF2\u8F7D\u5165";
              break;
            }
            case "task-cancel":
              this.deletePageKey("scheduled", "editor");
              refresh = false;
              message = "\u5DF2\u53D6\u6D88";
              break;
            case "task-create":
              await this.api.rpc("scheduled.create", p);
              break;
            case "task-update":
              await this.api.rpc("scheduled.update", p);
              break;
            case "task-run":
              await this.api.rpc("scheduled.run", p);
              message = "\u4EFB\u52A1\u5DF2\u4EA4\u7ED9 Android \u540E\u53F0\u8FD0\u884C";
              break;
            case "task-toggle":
              await this.api.rpc("scheduled.toggle", p);
              break;
            case "task-delete":
              await this.api.rpc("scheduled.delete", p);
              break;
            case "agent-settings":
              await this.api.rpc("agent.settings.set", p);
              break;
            case "job-cancel":
              await this.api.rpc("agent.jobs.cancel", p);
              break;
            case "approval-answer":
              await this.api.rpc("agent.approval.answer", p);
              break;
            case "question-answer":
              await this.api.rpc("chat.question.answer", p);
              break;
            case "web-service":
              await this.api.patch("/api/settings", p);
              break;
            case "web-identity":
              await this.api.patch("/api/settings", p);
              message = "\u8D26\u53F7\u5DF2\u66F4\u65B0\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55";
              break;
            case "web-tunnel":
              await this.api.patch("/api/settings", p);
              break;
            case "web-restart":
              await this.api.post("/api/settings/restart");
              refresh = false;
              message = "\u6B63\u5728\u91CD\u542F";
              break;
            case "logout":
              await this.api.post("/api/auth/logout");
              refresh = false;
              message = "\u5DF2\u9000\u51FA";
              if (typeof location !== "undefined") location.reload();
              break;
            case "device-shot": {
              const shot = await this.api.rpc("debug.screenshot", { scale: 0.4 });
              this.updatePage("device", (page) => {
                page.shot = shot;
                page.x = 0;
                page.y = 0;
              });
              refresh = false;
              message = "\u622A\u56FE\u5DF2\u5237\u65B0";
              break;
            }
            case "device-point":
              this.updatePage("device", (page) => {
                page.x = numberOf(p.x);
                page.y = numberOf(p.y);
              });
              refresh = false;
              message = `\u5750\u6807\u5DF2\u8BB0\u5F55\uFF1A${numberOf(p.x)}, ${numberOf(p.y)}`;
              break;
            case "device-tapmode":
              this.updatePage("device", (page) => {
                page.tapMode = booleanOf(p.enabled);
              });
              refresh = false;
              message = booleanOf(p.enabled) ? "\u70B9\u51FB\u6A21\u5F0F\u5DF2\u5F00\u542F" : "\u70B9\u51FB\u6A21\u5F0F\u5DF2\u5173\u95ED";
              break;
            case "device-tap":
              await this.api.rpc("debug.tap", p);
              refresh = false;
              message = "\u5DF2\u53D1\u9001\u70B9\u51FB";
              break;
            case "device-scroll":
              await this.api.rpc("debug.scroll", p);
              refresh = false;
              message = "\u5DF2\u53D1\u9001\u6EDA\u52A8";
              break;
            case "device-input":
              await this.api.rpc("debug.inputText", p);
              refresh = false;
              message = "\u5DF2\u53D1\u9001\u6587\u672C";
              break;
            case "log-read": {
              const data = objectOf(await this.api.rpc("debug.logs.read", p));
              this.updatePage("diagnostics", (page) => {
                page.viewer = {
                  title: `\u65E5\u5FD7\uFF1A${textOf(data.name)}`,
                  content: textOf(data.content),
                  bytes: numberOf(data.bytesRead),
                  modified: data.modified ?? null,
                  truncated: booleanOf(data.truncated)
                };
              });
              refresh = false;
              message = "\u5DF2\u8BFB\u53D6\u65E5\u5FD7";
              break;
            }
            case "crash-read": {
              const data = objectOf(await this.api.rpc("debug.crash.read", p));
              this.updatePage("diagnostics", (page) => {
                page.viewer = {
                  title: `\u5D29\u6E83\uFF1A${textOf(data.name)}`,
                  content: textOf(data.content),
                  bytes: textOf(data.content).length,
                  modified: data.modified ?? null,
                  truncated: booleanOf(data.truncated)
                };
              });
              refresh = false;
              message = "\u5DF2\u8BFB\u53D6\u5D29\u6E83\u62A5\u544A";
              break;
            }
            case "rpc-run": {
              const result = await this.api.rpc(textOf(p.method), objectOf(p.params));
              this.updatePage("advanced", (page) => {
                page.method = textOf(p.method);
                page.params = p.params ?? {};
                page.result = result;
              });
              refresh = false;
              message = "RPC \u5DF2\u6267\u884C";
              break;
            }
            default:
              throw new Error(`\u672A\u77E5 Minis \u64CD\u4F5C\uFF1A${command.kind}`);
          }
          this.showToast(message, false);
          if (refresh) await this.load(this.store.getSnapshot().tab, true);
        } catch (error) {
          this.showToast(error instanceof Error ? error.message : String(error), true);
          if (this.isAuthFailure(error) && typeof location !== "undefined") {
            setTimeout(() => location.reload(), 250);
          }
        } finally {
          if (!this.disposed) this.store.update((draft) => {
            draft.busy = false;
          });
        }
      }
      /** Pull one page from Android. A per-page generation fence drops stale answers. */
      async load(tab, silent = false) {
        if (this.disposed) return;
        const generation = (this.generations.get(tab) ?? 0) + 1;
        this.generations.set(tab, generation);
        this.requests.get(tab)?.abort();
        const request = new AbortController();
        this.requests.set(tab, request);
        if (!silent) {
          this.store.update((draft) => {
            draft.phase = "loading";
            delete draft.error;
          });
        }
        try {
          const page = await this.fetchPage(tab, request.signal);
          if (this.disposed || this.generations.get(tab) !== generation) return;
          this.store.update((draft) => {
            draft.pages[tab] = page;
            if (draft.tab === tab) draft.phase = "ready";
            delete draft.error;
          });
        } catch (error) {
          if (request.signal.aborted || this.disposed || this.generations.get(tab) !== generation) return;
          const message = error instanceof Error ? error.message : String(error);
          this.store.update((draft) => {
            if (draft.tab === tab) draft.phase = "error";
            draft.error = message;
          });
          if (silent) this.showToast(message, true);
        } finally {
          if (this.requests.get(tab) === request) this.requests.delete(tab);
        }
      }
      async fetchPage(tab, signal) {
        switch (tab) {
          case "overview": {
            const [status, remote, appInfo, sessions] = await Promise.all([
              this.api.request("/api/status", {}, signal),
              this.api.request("/api/settings", {}, signal),
              this.api.rpc("debug.appInfo", {}, signal),
              this.api.rpc("chat.sessions.list", { limit: 20, includeEmpty: true }, signal)
            ]);
            return { status, remote, appInfo, sessions };
          }
          case "providers": {
            const previous = this.store.getSnapshot().pages.providers;
            const [types, instances, groups] = await Promise.all([
              this.api.rpc("provider.types", {}, signal),
              this.api.rpc("provider.instances.list", { includeDisabled: true }, signal),
              this.api.rpc("provider.groups.list", { includeMembers: true }, signal)
            ]);
            return { ...objectOf(types), ...objectOf(instances), ...objectOf(groups), models: previous?.models ?? {} };
          }
          case "skills": {
            const page = objectOf(await this.api.rpc("skills.list", {}, signal));
            const editor = this.store.getSnapshot().pages.skills?.editor;
            return editor === void 0 ? page : { ...page, editor };
          }
          case "mcp":
            return objectOf(await this.api.rpc("mcp.list", {}, signal));
          case "memory": {
            const [files, global, soul] = await Promise.all([
              this.api.rpc("memory.files.list", {}, signal),
              this.api.rpc("memory.globalToggle", {}, signal),
              this.api.rpc("soul.get", {}, signal)
            ]);
            const editor = this.store.getSnapshot().pages.memory?.editor;
            return { ...objectOf(files), global, soul, ...editor === void 0 ? {} : { editor } };
          }
          case "system": {
            const [env, shared, mounts, permission, sandbox, caps] = await Promise.all([
              this.api.rpc("environments.list", {}, signal),
              this.api.rpc("storage.shared.list", {}, signal),
              this.api.rpc("storage.mounts.list", {}, signal),
              this.api.rpc("settings.permissionPreset.get", {}, signal),
              this.api.rpc("settings.sandbox.get", {}, signal),
              this.api.rpc("settings.capabilities.get", {}, signal)
            ]);
            return { env, shared, mounts, permission, sandbox, caps };
          }
          case "scheduled": {
            const page = objectOf(await this.api.rpc("scheduled.list", {}, signal));
            const editor = this.store.getSnapshot().pages.scheduled?.editor;
            return editor === void 0 ? page : { ...page, editor };
          }
          case "agent": {
            const [settings, jobs, approvals, questions] = await Promise.all([
              this.api.rpc("agent.settings.get", {}, signal),
              this.api.rpc("agent.jobs.list", {}, signal),
              this.api.rpc("agent.approval.list", {}, signal),
              this.api.rpc("chat.question.pending", {}, signal)
            ]);
            return { settings, jobs, approvals, questions };
          }
          case "web": {
            const [settings, status] = await Promise.all([
              this.api.request("/api/settings", {}, signal),
              this.api.request("/api/status", {}, signal)
            ]);
            return { settings, status };
          }
          case "device":
            return this.store.getSnapshot().pages.device ?? { tapMode: false, x: 0, y: 0 };
          case "diagnostics": {
            const [info, logs, crashes] = await Promise.all([
              this.api.rpc("debug.appInfo", {}, signal),
              this.api.rpc("debug.logs.list", {}, signal),
              this.api.rpc("debug.crash.list", { limit: 50 }, signal)
            ]);
            const viewer = this.store.getSnapshot().pages.diagnostics?.viewer;
            return { info, logs, crashes, ...viewer === void 0 ? {} : { viewer } };
          }
          case "advanced": {
            const existing = this.store.getSnapshot().pages.advanced;
            if (existing !== void 0) return existing;
            const discover = await this.api.rpc("rpc.discover", {}, signal);
            return { discover, method: "", params: {}, result: null };
          }
        }
      }
      updatePage(tab, mutate) {
        this.store.update((draft) => {
          const page = cloneJson(draft.pages[tab] ?? {});
          mutate(page);
          draft.pages[tab] = page;
        });
      }
      deletePageKey(tab, key) {
        this.updatePage(tab, (page) => {
          delete page[key];
        });
      }
      showToast(message, error) {
        if (this.disposed) return;
        this.toastNonce += 1;
        const nonce = this.toastNonce;
        this.store.update((draft) => {
          draft.toast = { message, error, nonce };
        });
        if (this.toastTimer !== void 0) clearTimeout(this.toastTimer);
        this.toastTimer = setTimeout(() => {
          if (this.disposed) return;
          this.store.update((draft) => {
            if (draft.toast?.nonce === nonce) delete draft.toast;
          });
        }, TOAST_DURATION_MS);
      }
      schedulePoll() {
        this.stopPoll();
        if (this.disposed || this.retainCount === 0) return;
        this.pollTimer = setTimeout(async () => {
          this.pollTimer = void 0;
          if (!this.disposed && this.retainCount > 0 && !this.store.getSnapshot().busy) {
            await this.load(this.store.getSnapshot().tab, true);
          }
          this.schedulePoll();
        }, POLL_INTERVAL_MS);
      }
      stopPoll() {
        if (this.pollTimer !== void 0) clearTimeout(this.pollTimer);
        this.pollTimer = void 0;
      }
      isAuthFailure(error) {
        return error instanceof Error && /登录已过期/.test(error.message);
      }
    };
    
    // src/client/settings/MinisSettings.tsx
    var import_react2 = require("react");
    
    // src/client/settings/navigation.ts
    var NAVIGATION = [
      { id: "overview", label: "\u6982\u89C8" },
      { id: "providers", label: "\u4F9B\u5E94\u5546\u4E0E\u6A21\u578B" },
      { id: "skills", label: "Skills" },
      { id: "mcp", label: "MCP" },
      { id: "memory", label: "\u8BB0\u5FC6\u4E0E SOUL" },
      { id: "system", label: "\u73AF\u5883\u4E0E\u5B58\u50A8" },
      { id: "scheduled", label: "\u5B9A\u65F6\u4EFB\u52A1" },
      { id: "agent", label: "Agent" },
      { id: "web", label: "Web \u8FDC\u7A0B" },
      { id: "device", label: "\u8BBE\u5907" },
      { id: "diagnostics", label: "\u8BCA\u65AD" },
      { id: "advanced", label: "\u9AD8\u7EA7\u64CD\u4F5C" }
    ];
    function navigationLabel(id) {
      return NAVIGATION.find((row) => row.id === id)?.label ?? "Minis \u63A7\u5236\u53F0";
    }
    
    // node_modules/clsx/dist/clsx.mjs
    function r(e) {
      var t, f, n = "";
      if ("string" == typeof e || "number" == typeof e) n += e;
      else if ("object" == typeof e) if (Array.isArray(e)) {
        var o = e.length;
        for (t = 0; t < o; t++) e[t] && (f = r(e[t])) && (n && (n += " "), n += f);
      } else for (f in e) e[f] && (n && (n += " "), n += f);
      return n;
    }
    function clsx() {
      for (var e, t, f = 0, n = "", o = arguments.length; f < o; f++) (e = arguments[f]) && (t = r(e)) && (n && (n += " "), n += t);
      return n;
    }
    var clsx_default = clsx;
    
    // src/client/settings/MinisSettings.module.css
    var css = ".om_root_LfS8SW{box-sizing:border-box;width:100%;min-width:0;color:var(--dsw-alias-label-primary);font:var(--dsw-typography-body-2,14px/1.5 var(--dsw-font-family))}.om_root_LfS8SW *,.om_root_LfS8SW :before,.om_root_LfS8SW :after{box-sizing:border-box}.om_header_LfS8SW{border-bottom:1px solid var(--dsw-alias-border-l2);align-items:center;gap:10px;min-height:52px;padding-bottom:12px;display:flex}.om_headerText_LfS8SW{min-width:0}.om_header_LfS8SW h2{margin:0;font-size:16px;font-weight:600;line-height:24px}.om_header_LfS8SW p{color:var(--dsw-alias-label-tertiary);margin:1px 0 0;font-size:12px;line-height:18px}.om_spacer_LfS8SW{flex:1}.om_repo_LfS8SW{color:var(--dsw-alias-state-business-primary);flex:none;font-size:12px;text-decoration:none}.om_repo_LfS8SW:hover{text-decoration:underline}.om_button_LfS8SW,.om_iconButton_LfS8SW{border:1px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-layer-2);color:var(--dsw-alias-label-primary);font:inherit;cursor:pointer;transition:background-color var(--dsw-duration-fast,.12s), border-color var(--dsw-duration-fast,.12s);border-radius:8px}.om_button_LfS8SW{min-height:32px;padding:5px 11px}.om_iconButton_LfS8SW{flex:none;width:30px;height:30px;padding:0;font-size:18px;line-height:28px}.om_button_LfS8SW:hover,.om_iconButton_LfS8SW:hover{background:var(--dsw-alias-interactive-bg-hover)}.om_button_LfS8SW:focus-visible,.om_iconButton_LfS8SW:focus-visible,.om_navButton_LfS8SW:focus-visible,.om_input_LfS8SW:focus-visible,.om_select_LfS8SW:focus-visible,.om_textarea_LfS8SW:focus-visible{outline:2px solid var(--dsw-alias-state-business-primary);outline-offset:2px}.om_primary_LfS8SW{background:var(--dsw-alias-button-info-fill);color:var(--dsw-alias-label-on-color);border-color:#0000}.om_primary_LfS8SW:hover{background:var(--dsw-alias-button-info-hover)}.om_danger_LfS8SW{color:var(--dsw-alias-state-error-primary)}.om_small_LfS8SW{min-height:27px;padding:3px 8px;font-size:12px}.om_button_LfS8SW:disabled,.om_iconButton_LfS8SW:disabled{opacity:.5;cursor:wait}.om_navigation_LfS8SW{flex-wrap:wrap;gap:5px;padding:12px 0;display:flex}.om_navButton_LfS8SW{min-height:32px;color:var(--dsw-alias-label-secondary);font:inherit;cursor:pointer;background:0 0;border:0;border-radius:8px;padding:5px 10px;font-size:13px}.om_navButton_LfS8SW:hover{background:var(--dsw-alias-interactive-bg-hover)}.om_navActive_LfS8SW{background:var(--dsw-specific-sidebar-nav-item-active);color:var(--dsw-alias-state-business-primary);font-weight:500}.om_mobileNavigation_LfS8SW{display:none}.om_content_LfS8SW{min-width:0;padding:2px 0 8px}.om_loading_LfS8SW,.om_errorState_LfS8SW{text-align:center;color:var(--dsw-alias-label-tertiary);padding:54px 12px}.om_errorState_LfS8SW{color:var(--dsw-alias-state-error-primary)}.om_grid_LfS8SW{grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:10px;display:grid}.om_card_LfS8SW{border:1px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-layer-1);border-radius:12px;min-width:0;padding:13px}.om_wide_LfS8SW{grid-column:1/-1}.om_card_LfS8SW h3{margin:0 0 5px;font-size:14px;font-weight:600}.om_card_LfS8SW p{color:var(--dsw-alias-label-tertiary);overflow-wrap:anywhere;margin:3px 0}.om_cardHead_LfS8SW{align-items:flex-start;gap:9px;display:flex}.om_cardHead_LfS8SW>:first-child{flex:1;min-width:0}.om_actions_LfS8SW{flex-wrap:wrap;justify-content:flex-end;gap:6px;display:flex}.om_badge_LfS8SW{background:var(--dsw-alias-bg-layer-3);min-height:20px;color:var(--dsw-alias-state-business-primary);border-radius:10px;align-items:center;padding:1px 7px;font-size:11px;display:inline-flex}.om_badgeOk_LfS8SW{color:var(--dsw-alias-state-success-primary,var(--dsw-alias-state-business-primary))}.om_badgeOff_LfS8SW{color:var(--dsw-alias-label-tertiary)}.om_riskHigh_LfS8SW{color:var(--dsw-alias-state-error-primary)}.om_riskMedium_LfS8SW{color:var(--dsw-alias-state-warning-primary,var(--dsw-alias-label-secondary))}.om_list_LfS8SW{gap:9px;display:grid}.om_sectionTitle_LfS8SW{align-items:center;gap:10px;margin:4px 0 11px;display:flex}.om_sectionTitle_LfS8SW:not(:first-child){margin-top:18px}.om_sectionTitle_LfS8SW h3{margin:0;font-size:15px}.om_sectionTitle_LfS8SW span{color:var(--dsw-alias-label-tertiary);font-size:12px}.om_form_LfS8SW{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;margin-top:11px;display:grid}.om_form_LfS8SW .om_wide_LfS8SW{grid-column:1/-1}.om_field_LfS8SW{color:var(--dsw-alias-label-secondary);gap:5px;font-size:12px;display:grid}.om_input_LfS8SW,.om_select_LfS8SW,.om_textarea_LfS8SW{border:1px solid var(--dsw-alias-border-l2);background:var(--dsw-specific-input-major,var(--dsw-alias-bg-layer-2));width:100%;color:var(--dsw-alias-label-primary);font:inherit;border-radius:8px;padding:7px 9px;font-size:13px;line-height:1.4}.om_input_LfS8SW,.om_select_LfS8SW{height:34px}.om_textarea_LfS8SW{resize:vertical;min-height:106px;font-family:var(--dsw-font-family-mono,ui-monospace, monospace)}.om_check_LfS8SW{min-height:32px;color:var(--dsw-alias-label-secondary);align-items:center;gap:7px;display:flex}.om_muted_LfS8SW{color:var(--dsw-alias-label-tertiary);font-size:12px}.om_keyValues_LfS8SW{gap:6px;font-size:13px;display:grid}.om_keyValueRow_LfS8SW{grid-template-columns:minmax(105px,.42fr) 1fr;gap:11px;display:grid}.om_keyValueRow_LfS8SW dt{color:var(--dsw-alias-label-tertiary)}.om_keyValueRow_LfS8SW dd{overflow-wrap:anywhere;margin:0}.om_empty_LfS8SW{text-align:center;border:1px dashed var(--dsw-alias-border-l2);color:var(--dsw-alias-label-tertiary);border-radius:10px;padding:30px 12px}.om_details_LfS8SW{border-top:1px solid var(--dsw-alias-border-l2);margin-top:9px;padding-top:8px}.om_details_LfS8SW>summary{cursor:pointer;color:var(--dsw-alias-label-secondary);font-size:12px}.om_code_LfS8SW{white-space:pre-wrap;background:var(--dsw-alias-bg-base);max-height:320px;color:var(--dsw-alias-label-secondary);font:12px/1.5 var(--dsw-font-family-mono,ui-monospace, monospace);border-radius:8px;padding:10px;overflow:auto}.om_note_LfS8SW{border-left:3px solid var(--dsw-alias-state-business-primary);background:var(--dsw-alias-bg-layer-1);color:var(--dsw-alias-label-secondary);border-radius:0 8px 8px 0;margin-bottom:11px;padding:9px 11px}.om_dangerNote_LfS8SW{border-left-color:var(--dsw-alias-state-error-primary)}.om_toast_LfS8SW{z-index:1200;background:var(--dsw-alias-bg-layer-3);max-width:min(440px,100% - 48px);color:var(--dsw-alias-label-on-color);box-shadow:var(--dsw-shadow-lv3);border-radius:9px;padding:10px 14px;position:fixed;bottom:20px;right:24px}.om_toastError_LfS8SW{background:var(--dsw-alias-state-error-primary)}.om_deviceStage_LfS8SW{gap:8px;margin:10px 0;display:grid}.om_deviceShot_LfS8SW{border:1px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-base);cursor:crosshair;touch-action:manipulation;border-radius:10px;width:auto;max-width:100%;height:auto;max-height:70vh;margin:0 auto;display:block}.om_center_LfS8SW{text-align:center}@media (width<=760px){.om_header_LfS8SW{align-items:flex-start}.om_header_LfS8SW p,.om_repo_LfS8SW,.om_navigation_LfS8SW{display:none}.om_mobileNavigation_LfS8SW{width:100%;margin:11px 0;display:block}.om_form_LfS8SW,.om_grid_LfS8SW,.om_keyValueRow_LfS8SW{grid-template-columns:1fr}.om_form_LfS8SW .om_wide_LfS8SW,.om_wide_LfS8SW{grid-column:auto}.om_actions_LfS8SW{justify-content:flex-start}.om_cardHead_LfS8SW{flex-wrap:wrap}}@media (prefers-reduced-motion:reduce){.om_root_LfS8SW *{scroll-behavior:auto!important;transition:none!important}}";
    var tagId = "@openminis/minis-client-settings/MinisSettings.module.css";
    if (typeof document !== "undefined" && document.querySelector("style[data-plugin-css=" + JSON.stringify(tagId) + "]") === null) {
      const tag = document.createElement("style");
      tag.dataset.plugin = "@openminis/minis-client-settings";
      tag.dataset.pluginCss = tagId;
      tag.textContent = css;
      document.head.appendChild(tag);
    }
    var MinisSettings_default = { "button": "om_button_LfS8SW", "spacer": "om_spacer_LfS8SW", "navButton": "om_navButton_LfS8SW", "errorState": "om_errorState_LfS8SW", "wide": "om_wide_LfS8SW", "repo": "om_repo_LfS8SW", "select": "om_select_LfS8SW", "iconButton": "om_iconButton_LfS8SW", "actions": "om_actions_LfS8SW", "navActive": "om_navActive_LfS8SW", "navigation": "om_navigation_LfS8SW", "field": "om_field_LfS8SW", "grid": "om_grid_LfS8SW", "primary": "om_primary_LfS8SW", "card": "om_card_LfS8SW", "cardHead": "om_cardHead_LfS8SW", "mobileNavigation": "om_mobileNavigation_LfS8SW", "danger": "om_danger_LfS8SW", "sectionTitle": "om_sectionTitle_LfS8SW", "riskHigh": "om_riskHigh_LfS8SW", "keyValues": "om_keyValues_LfS8SW", "keyValueRow": "om_keyValueRow_LfS8SW", "empty": "om_empty_LfS8SW", "root": "om_root_LfS8SW", "details": "om_details_LfS8SW", "loading": "om_loading_LfS8SW", "form": "om_form_LfS8SW", "dangerNote": "om_dangerNote_LfS8SW", "code": "om_code_LfS8SW", "note": "om_note_LfS8SW", "header": "om_header_LfS8SW", "toast": "om_toast_LfS8SW", "toastError": "om_toastError_LfS8SW", "deviceStage": "om_deviceStage_LfS8SW", "small": "om_small_LfS8SW", "content": "om_content_LfS8SW", "textarea": "om_textarea_LfS8SW", "deviceShot": "om_deviceShot_LfS8SW", "riskMedium": "om_riskMedium_LfS8SW", "list": "om_list_LfS8SW", "center": "om_center_LfS8SW", "badgeOff": "om_badgeOff_LfS8SW", "muted": "om_muted_LfS8SW", "badge": "om_badge_LfS8SW", "badgeOk": "om_badgeOk_LfS8SW", "headerText": "om_headerText_LfS8SW", "check": "om_check_LfS8SW", "input": "om_input_LfS8SW" };
    
    // src/client/settings/components/Common.tsx
    var import_jsx_runtime = require("react/jsx-runtime");
    function Card({ children, wide = false }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("section", { className: clsx_default(MinisSettings_default.card, wide && MinisSettings_default.wide), children });
    }
    function Grid({ children }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", { className: MinisSettings_default.grid, children });
    }
    function SectionTitle({ title, meta }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", { className: MinisSettings_default.sectionTitle, children: [
        /* @__PURE__ */ (0, import_jsx_runtime.jsx)("h3", { children: title }),
        meta === void 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", { children: meta })
      ] });
    }
    function Note({ children, danger = false }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", { className: clsx_default(MinisSettings_default.note, danger && MinisSettings_default.dangerNote), children });
    }
    function Empty({ children }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", { className: MinisSettings_default.empty, children });
    }
    function Badge({ on, yes = "\u542F\u7528", no = "\u505C\u7528" }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", { className: clsx_default(MinisSettings_default.badge, on ? MinisSettings_default.badgeOk : MinisSettings_default.badgeOff), children: on ? yes : no });
    }
    function RiskBadge({ risk, label }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("span", { className: clsx_default(MinisSettings_default.badge, risk === "HIGH" && MinisSettings_default.riskHigh, risk === "MEDIUM" && MinisSettings_default.riskMedium), children: [
        label ?? risk,
        "\u98CE\u9669"
      ] });
    }
    function Button({
      children,
      danger = false,
      primary = false,
      small = false,
      disabled = false,
      type = "button",
      onClick
    }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)(
        "button",
        {
          type,
          className: clsx_default(MinisSettings_default.button, primary && MinisSettings_default.primary, danger && MinisSettings_default.danger, small && MinisSettings_default.small),
          disabled,
          onClick,
          children
        }
      );
    }
    function Actions({ children }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", { className: MinisSettings_default.actions, children });
    }
    function CardHead({ children, actions }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", { className: MinisSettings_default.cardHead, children: [
        /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", { children }),
        actions === void 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", { className: MinisSettings_default.actions, children: actions })
      ] });
    }
    function Field({ label, children, wide = false }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("label", { className: clsx_default(MinisSettings_default.field, wide && MinisSettings_default.wide), children: [
        /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", { children: label }),
        children
      ] });
    }
    function Details({ label, value, open = false }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("details", { className: MinisSettings_default.details, open, children: [
        /* @__PURE__ */ (0, import_jsx_runtime.jsx)("summary", { children: label }),
        /* @__PURE__ */ (0, import_jsx_runtime.jsx)("pre", { className: MinisSettings_default.code, children: jsonText(value) })
      ] });
    }
    function Code({ children }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("pre", { className: MinisSettings_default.code, children });
    }
    function KeyValues({ value, omit = [] }) {
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("dl", { className: MinisSettings_default.keyValues, children: Object.entries(value).filter(([key]) => !omit.includes(key)).map(([key, row]) => /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", { className: MinisSettings_default.keyValueRow, children: [
        /* @__PURE__ */ (0, import_jsx_runtime.jsx)("dt", { children: key }),
        /* @__PURE__ */ (0, import_jsx_runtime.jsx)("dd", { children: typeof row === "object" && row !== null ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("code", { children: jsonText(row) }) : String(row ?? "") })
      ] }, key)) });
    }
    function Form({ children, onSubmit }) {
      const submit = (event) => {
        event.preventDefault();
        onSubmit(formPayload(event.currentTarget));
      };
      return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("form", { className: MinisSettings_default.form, onSubmit: submit, children });
    }
    function formPayload(form) {
      const payload = {};
      for (const [key, value] of new FormData(form).entries()) {
        payload[key] = typeof value === "string" ? value : value.name;
      }
      for (const control of form.elements) {
        if (control instanceof HTMLInputElement && control.type === "checkbox" && control.name !== "") {
          payload[control.name] = control.checked;
        }
      }
      return payload;
    }
    function jsonText(value) {
      try {
        return JSON.stringify(value, null, 2);
      } catch {
        return String(value);
      }
    }
    function formatDate(value) {
      if (value === null || value === void 0 || value === "") return "\u2014";
      const numeric = typeof value === "number" ? value : Number(value);
      if (Number.isFinite(numeric)) {
        const date = new Date(numeric);
        if (!Number.isNaN(date.getTime())) return date.toLocaleString();
      }
      return String(value);
    }
    function formatSize(value) {
      return typeof value === "number" ? `${value} B` : String(value ?? "\u2014");
    }
    function omitBlank(value, keys) {
      const copy = { ...value };
      for (const key of keys) if (copy[key] === "") delete copy[key];
      return copy;
    }
    
    // src/client/settings/pages/OverviewPage.tsx
    var import_jsx_runtime2 = require("react/jsx-runtime");
    function OverviewPage({ data }) {
      const status = objectOf(data.status);
      const remote = objectOf(data.remote);
      const info = objectOf(data.appInfo);
      const sessions = objectsOf(objectOf(data.sessions).sessions);
      const tunnel = objectOf(status.tunnel);
      return /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)(Grid, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)(Card, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime2.jsx)("h3", { children: "\u8FDE\u63A5\u72B6\u6001" }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)("p", { children: [
            /* @__PURE__ */ (0, import_jsx_runtime2.jsx)(Badge, { on: booleanOf(status.ok), yes: "\u5DF2\u8FDE\u63A5", no: "\u672A\u8FDE\u63A5" }),
            " Android \xB7 ",
            textOf(status.bindHost),
            " : ",
            textOf(status.port)
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)("p", { children: [
            "Cloudflare Tunnel\uFF1A",
            booleanOf(tunnel.running) ? "\u8FD0\u884C\u4E2D" : "\u672A\u8FD0\u884C"
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)(Card, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime2.jsx)("h3", { children: "Minis Web" }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)("p", { children: [
            "\u8D26\u53F7\uFF1A",
            textOf(remote.username, "\u2014")
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)("p", { children: [
            "\u5C40\u57DF\u7F51\uFF1A",
            booleanOf(remote.lanAccess) ? "\u5DF2\u5F00\u653E" : "\u4EC5\u672C\u673A/\u96A7\u9053"
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)("p", { children: [
            "\u516C\u5F00\u57DF\u540D\uFF1A",
            textOf(remote.cloudflareHostname, "\u672A\u914D\u7F6E")
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime2.jsx)(SectionTitle, { title: "\u6700\u8FD1\u4F1A\u8BDD", meta: `${sessions.length} \u4E2A` }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsx)("div", { className: MinisSettings_default.list, children: sessions.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime2.jsx)(Empty, { children: "\u8FD8\u6CA1\u6709\u4F1A\u8BDD" }) : sessions.slice(0, 8).map((session, index) => /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)(
            CardHead,
            {
              actions: /* @__PURE__ */ (0, import_jsx_runtime2.jsx)(Badge, { on: booleanOf(session.isRunning), yes: "\u8FD0\u884C\u4E2D", no: "\u7A7A\u95F2" }),
              children: [
                /* @__PURE__ */ (0, import_jsx_runtime2.jsx)("strong", { children: textOf(session.title, "\u65B0\u4F1A\u8BDD") }),
                /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)("p", { children: [
                  textOf(session.modelName, textOf(session.modelId, "\u672A\u9009\u62E9\u6A21\u578B")),
                  " \xB7 ",
                  textOf(session.lastMessagePreview, "\u6682\u65E0\u6D88\u606F"),
                  " \xB7 ",
                  formatDate(session.updatedAt)
                ] })
              ]
            },
            textOf(session.id, String(index))
          )) })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime2.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime2.jsx)("h3", { children: "App \u4FE1\u606F" }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsx)(KeyValues, { value: info }),
          /* @__PURE__ */ (0, import_jsx_runtime2.jsx)(Details, { label: "\u5B8C\u6574\u4FE1\u606F", value: info })
        ] })
      ] });
    }
    
    // src/client/settings/pages/ProvidersPage.tsx
    var import_jsx_runtime3 = require("react/jsx-runtime");
    function ProvidersPage({ data, busy, run }) {
      const types = objectsOf(data.types);
      const instances = objectsOf(data.instances);
      const groups = objectsOf(data.groups);
      const models = objectOf(data.models);
      return /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(import_jsx_runtime3.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Note, { children: "\u51ED\u636E\u4E0D\u4F1A\u4ECE\u624B\u673A\u56DE\u4F20\uFF1B\u8FD9\u91CC\u53EA\u663E\u793A\u201C\u5DF2\u914D\u7F6E\u201D\u3002\u63D0\u4EA4\u65B0 Key \u65F6\u4F1A\u5199\u5165 App \u7684\u5B89\u5168\u5B58\u50A8\u3002" }),
        /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(SectionTitle, { title: "\u4F9B\u5E94\u5546\u5B9E\u4F8B", meta: instances.length }),
        /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("div", { className: MinisSettings_default.list, children: instances.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Empty, { children: "\u5C1A\u672A\u914D\u7F6E\u4F9B\u5E94\u5546" }) : instances.map((provider, index) => {
          const id = textOf(provider.id, String(index));
          const modelPage = objectOf(models[id]);
          const entries = objectsOf(modelPage.entries);
          return /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(import_jsx_runtime3.Fragment, { children: [
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Badge, { on: booleanOf(provider.isEnabled) }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "provider-models", payload: { instanceId: id } }), children: "\u6A21\u578B" }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "provider-test", payload: { instanceId: id } }), children: "\u6D4B\u8BD5" }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "provider-toggle", payload: { instanceId: id, isEnabled: !booleanOf(provider.isEnabled) } }), children: booleanOf(provider.isEnabled) ? "\u505C\u7528" : "\u542F\u7528" }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm("\u5220\u9664\u8BE5\u4F9B\u5E94\u5546\u53CA\u5176\u6A21\u578B\uFF1F")) run({ kind: "provider-delete", payload: { instanceId: id } });
              }, children: "\u5220\u9664" })
            ] }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("h3", { children: textOf(provider.label) }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("p", { children: [
                textOf(provider.providerType),
                " \xB7 ",
                booleanOf(provider.hasCredential) ? "\u51ED\u636E\u5DF2\u914D\u7F6E" : "\u672A\u914D\u7F6E\u51ED\u636E",
                " \xB7 ",
                numberOf(provider.modelEntryCount),
                " \u4E2A\u6A21\u578B"
              ] }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("p", { children: textOf(provider.customBaseURL, "\u9ED8\u8BA4 API \u5730\u5740") })
            ] }),
            Object.keys(modelPage).length === 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("details", { className: MinisSettings_default.details, open: true, children: [
              /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("summary", { children: [
                "\u6A21\u578B\u76EE\u5F55\uFF08",
                entries.length,
                "\uFF09"
              ] }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("div", { className: MinisSettings_default.list, children: [
                entries.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Empty, { children: "\u6CA1\u6709\u6A21\u578B" }) : entries.map((model, modelIndex) => {
                  const entryId = textOf(model.id, String(modelIndex));
                  return /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(import_jsx_runtime3.Fragment, { children: [
                    /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "model-loop", payload: { entryId, inLoop: !booleanOf(model.inAgentLoop) } }), children: booleanOf(model.inAgentLoop) ? "\u79FB\u51FA Agent" : "\u52A0\u5165 Agent" }),
                    /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                      if (confirm("\u5220\u9664\u8BE5\u6A21\u578B\uFF1F")) run({ kind: "model-delete", payload: { entryId } });
                    }, children: "\u5220\u9664" })
                  ] }), children: [
                    /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("strong", { children: textOf(model.displayName) }),
                    /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("p", { children: [
                      textOf(model.modelId),
                      " \xB7 ",
                      booleanOf(model.supportsReasoning) ? "\u652F\u6301\u63A8\u7406" : "\u666E\u901A\u6A21\u578B"
                    ] })
                  ] }, entryId);
                }),
                /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Form, { onSubmit: (payload) => run({ kind: "model-add", payload: { instanceId: id, modelId: textOf(payload.modelId) } }), children: [
                  /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "\u65B0\u589E\u6A21\u578B ID", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, name: "modelId", required: true, placeholder: "provider/model-name" }) }),
                  /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u6DFB\u52A0\u6A21\u578B" })
                ] })
              ] })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("details", { className: MinisSettings_default.details, children: [
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("summary", { children: "\u7F16\u8F91\u5B9E\u4F8B / \u66F4\u65B0\u51ED\u636E" }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Form, { onSubmit: (payload) => run({ kind: "provider-update", payload: { ...omitBlank(payload, ["apiKey"]), instanceId: id } }), children: [
                /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "\u540D\u79F0", children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, name: "label", defaultValue: textOf(provider.label) }) }),
                /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "\u65B0 API Key\uFF08\u7559\u7A7A\u5219\u4FDD\u7559\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, type: "password", name: "apiKey", autoComplete: "new-password" }) }),
                /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "\u81EA\u5B9A\u4E49 Base URL", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, name: "customBaseURL", defaultValue: textOf(provider.customBaseURL) }) }),
                /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4FDD\u5B58" })
              ] })
            ] })
          ] }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("h3", { children: "\u6DFB\u52A0\u4F9B\u5E94\u5546" }),
          /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Form, { onSubmit: (payload) => {
            const type = types.find((row) => textOf(row.id) === textOf(payload.providerType));
            const next = omitBlank(payload, ["apiKey", "customBaseURL"]);
            if (!booleanOf(type?.customBaseURLSupported)) delete next.customBaseURL;
            run({ kind: "provider-create", payload: next });
          }, children: [
            /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "\u7C7B\u578B", children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("select", { className: MinisSettings_default.select, name: "providerType", children: types.map((row) => /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("option", { value: textOf(row.id), children: textOf(row.displayName, textOf(row.id)) }, textOf(row.id))) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "\u540D\u79F0", children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, name: "label", required: true }) }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "API Key / Token", children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, type: "password", name: "apiKey", autoComplete: "new-password" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "Base URL\uFF08\u517C\u5BB9\u7C7B\u578B\u53EF\u9009\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, name: "customBaseURL" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("label", { className: MinisSettings_default.check, children: [
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { type: "checkbox", name: "seedBuiltInModels", defaultChecked: true }),
              " \u6DFB\u52A0\u5185\u7F6E\u6A21\u578B"
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u521B\u5EFA" })
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(SectionTitle, { title: "\u6A21\u578B\u7EC4", meta: groups.length }),
        /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Grid, { children: groups.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Empty, { children: "\u6CA1\u6709\u6A21\u578B\u7EC4" }) : groups.map((group, index) => {
          const id = textOf(group.id, String(index));
          return /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(import_jsx_runtime3.Fragment, { children: [
              booleanOf(group.isDefault) && /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("span", { className: MinisSettings_default.badge, children: "\u4E3B\u9ED8\u8BA4" }),
              booleanOf(group.isSub) && /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("span", { className: MinisSettings_default.badge, children: "\u5B50 Agent" })
            ] }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("h3", { children: textOf(group.name) }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)("p", { children: [
                textOf(group.strategy),
                " \xB7 ",
                objectsOf(group.members).length || (Array.isArray(group.memberEntryIds) ? group.memberEntryIds.length : 0),
                " \u4E2A\u6A21\u578B"
              ] })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Actions, { children: [
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "group-default", payload: { groupId: id } }), children: "\u8BBE\u4E3A\u4E3B\u9ED8\u8BA4" }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "group-sub-default", payload: { groupId: id } }), children: "\u8BBE\u4E3A\u5B50\u9ED8\u8BA4" }),
              /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm("\u5220\u9664\u8BE5\u6A21\u578B\u7EC4\uFF1F")) run({ kind: "group-delete", payload: { groupId: id } });
              }, children: "\u5220\u9664" })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Details, { label: "\u6210\u5458", value: group.members ?? group.memberEntryIds })
          ] }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Card, { wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime3.jsxs)(Form, { onSubmit: (payload) => run({ kind: "group-create", payload: { name: textOf(payload.name) } }), children: [
          /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Field, { label: "\u65B0\u6A21\u578B\u7EC4\u540D\u79F0", children: /* @__PURE__ */ (0, import_jsx_runtime3.jsx)("input", { className: MinisSettings_default.input, name: "name", required: true }) }),
          /* @__PURE__ */ (0, import_jsx_runtime3.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u521B\u5EFA\u6A21\u578B\u7EC4" })
        ] }) })
      ] });
    }
    
    // src/client/settings/pages/SkillsPage.tsx
    var import_jsx_runtime4 = require("react/jsx-runtime");
    function SkillsPage({ data, busy, run }) {
      const skills = objectsOf(data.skills);
      const editor = Object.keys(objectOf(data.editor)).length === 0 ? void 0 : objectOf(data.editor);
      return /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)(import_jsx_runtime4.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(SectionTitle, { title: "Skills", meta: `${skills.length} \u4E2A\uFF0C\u4E0E\u624B\u673A\u7AEF\u5B9E\u65F6\u540C\u6B65` }),
        /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Grid, { children: skills.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Empty, { children: "\u6CA1\u6709 Skill" }) : skills.map((skill, index) => {
          const id = textOf(skill.id, String(index));
          return /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Badge, { on: booleanOf(skill.isEnabled) }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("h3", { children: textOf(skill.name) }),
              /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("p", { children: textOf(skill.description, "\u65E0\u63CF\u8FF0") }),
              /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)("p", { children: [
                "v",
                textOf(skill.version),
                " \xB7 \u4F7F\u7528 ",
                numberOf(skill.useCount),
                " \u6B21",
                textOf(skill.sourceURL) === "" ? "" : ` \xB7 ${textOf(skill.sourceURL)}`
              ] })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "skill-edit", payload: { skillId: id } }), children: "\u7F16\u8F91" }),
              /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "skill-toggle", payload: { skillId: id, enabled: !booleanOf(skill.isEnabled) } }), children: booleanOf(skill.isEnabled) ? "\u505C\u7528" : "\u542F\u7528" }),
              /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm("\u6C38\u4E45\u5220\u9664\u8FD9\u4E2A Skill\uFF1F")) run({ kind: "skill-delete", payload: { skillId: id } });
              }, children: "\u5220\u9664" })
            ] })
          ] }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("h3", { children: "\u901A\u8FC7\u94FE\u63A5\u5BFC\u5165 Skill" }),
          /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("p", { children: "\u652F\u6301 GitHub Skill \u76EE\u5F55\u3001SKILL.md \u94FE\u63A5\u6216\u5176\u4ED6\u516C\u5F00 HTTPS SKILL.md\uFF1B\u540C\u540D Skill \u4F1A\u539F\u4F4D\u66F4\u65B0\u3002" }),
          /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)(Form, { onSubmit: (payload) => run({ kind: "skill-import-url", payload: { url: textOf(payload.url).trim() } }), children: [
            /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Field, { label: "HTTPS / GitHub \u94FE\u63A5", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("input", { className: MinisSettings_default.input, name: "url", type: "url", required: true, placeholder: "https://github.com/owner/repo/tree/main/skill-name" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4ECE\u94FE\u63A5\u5BFC\u5165" })
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("h3", { children: editor === void 0 ? "\u521B\u5EFA Skill" : "\u7F16\u8F91 Skill" }),
          /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)(Form, { onSubmit: (payload) => run(editor === void 0 ? { kind: "skill-create", payload } : { kind: "skill-update", payload: { name: textOf(payload.name), description: textOf(payload.description), body: textOf(payload.body), skillId: textOf(editor.id) } }), children: [
            /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Field, { label: "\u540D\u79F0", children: /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("input", { className: MinisSettings_default.input, name: "name", required: true, defaultValue: textOf(editor?.name) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Field, { label: "\u7248\u672C", children: /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("input", { className: MinisSettings_default.input, name: "version", defaultValue: textOf(editor?.version, "1.0.0"), disabled: editor !== void 0 }) }),
            /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Field, { label: "\u63CF\u8FF0", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("input", { className: MinisSettings_default.input, name: "description", defaultValue: textOf(editor?.description) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Field, { label: "Skill \u6B63\u6587", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime4.jsx)("textarea", { className: MinisSettings_default.textarea, name: "body", defaultValue: textOf(editor?.body) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime4.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4FDD\u5B58" }),
              editor === void 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime4.jsx)(Button, { disabled: busy, onClick: () => run({ kind: "skill-cancel" }), children: "\u53D6\u6D88\u7F16\u8F91" })
            ] })
          ] })
        ] })
      ] });
    }
    
    // src/client/settings/pages/McpPage.tsx
    var import_jsx_runtime5 = require("react/jsx-runtime");
    function McpPage({ data, busy, run }) {
      const servers = objectsOf(data.servers);
      return /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(import_jsx_runtime5.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Note, { children: "MCP \u914D\u7F6E\u5199\u5165 App \u539F\u751F\u4ED3\u5E93\u3002HTTP \u4E0E\u547D\u4EE4\u4F20\u8F93\u4E8C\u9009\u4E00\uFF1B\u654F\u611F Header/\u73AF\u5883\u503C\u4E0D\u4F1A\u4ECE\u7F51\u9875\u54CD\u5E94\u4E2D\u56DE\u663E\u3002" }),
        /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Grid, { children: servers.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Empty, { children: "\u6CA1\u6709 MCP Server" }) : servers.map((server, index) => {
          const id = textOf(server.id, String(index));
          return /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Badge, { on: booleanOf(server.enabled) }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("h3", { children: id }),
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("p", { children: textOf(server.url, textOf(server.command, "\u672A\u914D\u7F6E\u4F20\u8F93")) }),
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("p", { children: textOf(server.note) })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "mcp-toggle", payload: { serverId: id, enabled: !booleanOf(server.enabled) } }), children: booleanOf(server.enabled) ? "\u505C\u7528" : "\u542F\u7528" }),
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm("\u5220\u9664\u8BE5 MCP Server\uFF1F")) run({ kind: "mcp-delete", payload: { serverId: id } });
              }, children: "\u5220\u9664" })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Details, { label: "\u5B8C\u6574\u914D\u7F6E\uFF08\u5DF2\u8131\u654F\uFF09", value: server })
          ] }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("h3", { children: "\u5BFC\u5165 MCP \u914D\u7F6E" }),
          /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(Form, { onSubmit: (payload) => run({ kind: "mcp-import-url", payload: { url: textOf(payload.url).trim() } }), children: [
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Field, { label: "\u516C\u5F00 HTTPS JSON \u94FE\u63A5", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("input", { className: MinisSettings_default.input, name: "url", type: "url", required: true, placeholder: "https://raw.githubusercontent.com/owner/repo/main/mcp.json" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4ECE\u94FE\u63A5\u5BFC\u5165" })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)("details", { className: MinisSettings_default.details, children: [
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("summary", { children: "\u6216\u7C98\u8D34 JSON" }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(Form, { onSubmit: (payload) => run({ kind: "mcp-import-json", payload: { configJson: textOf(payload.configJson) } }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Field, { label: "MCP JSON", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("textarea", { className: MinisSettings_default.textarea, name: "configJson", required: true, placeholder: '{"mcpServers":{"name":{"url":"https://\u2026"}}}' }) }),
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u5BFC\u5165 JSON" })
            ] })
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("h3", { children: "\u6DFB\u52A0 MCP Server" }),
          /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)(Form, { onSubmit: (payload) => {
            const next = omitBlank(payload, ["url", "command", "note"]);
            try {
              next.args = JSON.parse(textOf(next.args, "[]"));
            } catch {
              alert("\u53C2\u6570\u5FC5\u987B\u662F JSON \u6570\u7EC4");
              return;
            }
            if (Boolean(next.url) === Boolean(next.command)) {
              alert("HTTP URL \u4E0E\u672C\u5730\u547D\u4EE4\u5FC5\u987B\u4E14\u53EA\u80FD\u586B\u5199\u4E00\u4E2A");
              return;
            }
            run({ kind: "mcp-create", payload: next });
          }, children: [
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Field, { label: "Server ID", children: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("input", { className: MinisSettings_default.input, name: "serverId", required: true, pattern: "[A-Za-z0-9_-]{1,128}" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Field, { label: "\u5907\u6CE8", children: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("input", { className: MinisSettings_default.input, name: "note" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Field, { label: "HTTP URL", children: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("input", { className: MinisSettings_default.input, name: "url", placeholder: "https://\u2026" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Field, { label: "\u6216\u672C\u5730\u547D\u4EE4", children: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("input", { className: MinisSettings_default.input, name: "command", placeholder: "npx \u2026" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Field, { label: "\u53C2\u6570 JSON \u6570\u7EC4", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("input", { className: MinisSettings_default.input, name: "args", defaultValue: "[]" }) }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsxs)("label", { className: MinisSettings_default.check, children: [
              /* @__PURE__ */ (0, import_jsx_runtime5.jsx)("input", { type: "checkbox", name: "enabled", defaultChecked: true }),
              " \u542F\u7528"
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime5.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u521B\u5EFA" })
          ] })
        ] })
      ] });
    }
    
    // src/client/settings/pages/MemoryPage.tsx
    var import_jsx_runtime6 = require("react/jsx-runtime");
    function MemoryPage({ data, busy, run }) {
      const files = objectsOf(data.files);
      const global = objectOf(data.global);
      const soul = objectOf(data.soul);
      const editorObject = objectOf(data.editor);
      const editor = Object.keys(editorObject).length === 0 ? void 0 : editorObject;
      return /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(import_jsx_runtime6.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Grid, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Badge, { on: booleanOf(global.enabled) }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("h3", { children: "\u5168\u5C40\u8BB0\u5FC6" }),
              /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("p", { children: "GLOBAL.md \u662F\u5426\u6CE8\u5165\u6240\u6709\u4F1A\u8BDD" })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Button, { disabled: busy, onClick: () => run({ kind: "memory-global", payload: { enabled: !booleanOf(global.enabled) } }), children: [
              booleanOf(global.enabled) ? "\u5173\u95ED" : "\u5F00\u542F",
              "\u5168\u5C40\u8BB0\u5FC6"
            ] })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("h3", { children: "SOUL" }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)("p", { children: [
              textOf(soul.name, "Minis"),
              " \xB7 ",
              textOf(soul.style),
              " \xB7 ",
              textOf(soul.lang)
            ] })
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(SectionTitle, { title: "\u8BB0\u5FC6\u6587\u4EF6", meta: files.length }),
        /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Grid, { children: files.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Empty, { children: "\u6CA1\u6709\u8BB0\u5FC6\u6587\u4EF6" }) : files.map((file, index) => {
          const name = textOf(file.name, String(index));
          return /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(CardHead, { actions: booleanOf(file.isGlobal) ? /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("span", { className: MinisSettings_default.badge, children: "\u5168\u5C40" }) : null, children: [
              /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("h3", { children: name }),
              /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("p", { children: textOf(file.preview) }),
              /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)("p", { children: [
                formatSize(file.fileSize),
                " \xB7 ",
                formatDate(file.modifiedDate)
              ] })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "memory-edit", payload: { name } }), children: "\u7F16\u8F91" }),
              /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm(`\u5220\u9664\u8BB0\u5FC6\u6587\u4EF6 ${name}\uFF1F`)) run({ kind: "memory-delete", payload: { name } });
              }, children: "\u5220\u9664" })
            ] })
          ] }, name);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("h3", { children: editor === void 0 ? "\u65B0\u5EFA\u8BB0\u5FC6\u6587\u4EF6" : "\u7F16\u8F91\u8BB0\u5FC6" }),
          /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Form, { onSubmit: (payload) => run({ kind: "memory-save", payload: { name: textOf(payload.name), content: textOf(payload.content) } }), children: [
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Field, { label: "\u6587\u4EF6\u540D", children: /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("input", { className: MinisSettings_default.input, name: "name", required: true, defaultValue: textOf(editor?.name, "MEMORY.md"), readOnly: editor !== void 0 }) }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Field, { label: "\u5185\u5BB9", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("textarea", { className: MinisSettings_default.textarea, name: "content", defaultValue: textOf(editor?.content) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4FDD\u5B58" }),
              editor === void 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Button, { disabled: busy, onClick: () => run({ kind: "memory-cancel" }), children: "\u53D6\u6D88" })
            ] })
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("h3", { children: "\u7F16\u8F91 SOUL" }),
          /* @__PURE__ */ (0, import_jsx_runtime6.jsxs)(Form, { onSubmit: (payload) => run({ kind: "soul-save", payload }), children: [
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Field, { label: "\u540D\u79F0", children: /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("input", { className: MinisSettings_default.input, name: "name", defaultValue: textOf(soul.name) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Field, { label: "\u8BED\u8A00", children: /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("input", { className: MinisSettings_default.input, name: "lang", defaultValue: textOf(soul.lang) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Field, { label: "\u98CE\u683C", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("input", { className: MinisSettings_default.input, name: "style", defaultValue: textOf(soul.style) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Field, { label: "\u6B63\u6587", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime6.jsx)("textarea", { className: MinisSettings_default.textarea, name: "body", defaultValue: textOf(soul.body) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime6.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4FDD\u5B58 SOUL" })
          ] })
        ] })
      ] });
    }
    
    // src/client/settings/pages/SystemPage.tsx
    var import_jsx_runtime7 = require("react/jsx-runtime");
    function SystemPage({ data, busy, run }) {
      const env = objectsOf(objectOf(data.env).entries);
      const mountsData = objectOf(data.mounts);
      const mounts = objectsOf(mountsData.mounts);
      const shared = objectsOf(objectOf(data.shared).folders);
      const permission = objectOf(data.permission);
      const sandbox = objectOf(data.sandbox);
      const caps = objectsOf(objectOf(data.caps).capabilities);
      const permissionManage = caps.find((cap) => textOf(cap.id) === "permission.manage");
      const enabledCount = caps.filter((cap) => booleanOf(cap.enabled)).length;
      return /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(import_jsx_runtime7.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Note, { children: "\u6743\u9650\u4ECE\u201C\u9884\u8BBE\u201D\u7EC6\u5316\u4E3A\u9010\u80FD\u529B\u5F00\u5173\uFF1B\u624B\u673A\u4E0E\u7F51\u9875\u4E24\u7AEF\u770B\u5230\u7684\u662F\u540C\u4E00\u4EFD\u72B6\u6001\u3002\u9884\u8BBE\u6309\u94AE\u53EA\u662F\u6279\u91CF\u5E94\u7528\u3002" }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(Grid, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(Card, { wide: true, children: [
            /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("h3", { children: "\u8FDC\u7A0B\u6743\u9650\u9884\u8BBE" }),
            /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)("p", { children: [
              "\u5F53\u524D\u9884\u8BBE\uFF1A",
              textOf(permission.label, textOf(permission.preset, "\u2014"))
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { disabled: busy, onClick: () => run({ kind: "permission-set", payload: { preset: "workspace-write" } }), children: "\u6062\u590D\u9ED8\u8BA4\uFF08\u5DE5\u4F5C\u533A\u5199\u5165\uFF09" }),
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { danger: true, disabled: busy, onClick: () => {
                if (confirm("\u5168\u90E8\u5F00\u542F\u4F1A\u653E\u5F00\u6240\u6709\u80FD\u529B\uFF08\u542B\u8BBE\u5907\u63A7\u5236\u4E0E\u51ED\u636E\u5BFC\u51FA\uFF09\u3002\u786E\u8BA4\uFF1F")) run({ kind: "permission-set", payload: { preset: "danger-full-access" } });
              }, children: "\u5168\u90E8\u5F00\u542F\uFF08\u5371\u9669\uFF09" })
            ] }),
            permissionManage !== void 0 && !booleanOf(permissionManage.enabled) ? /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("p", { className: MinisSettings_default.muted, children: "\u26A0 \u6743\u9650\u7BA1\u7406\u5DF2\u5728 Web \u7AEF\u5173\u95ED\uFF1A\u4E0B\u9762\u7684\u5F00\u5173\u53EA\u8BFB\uFF0C\u5FC5\u987B\u5728\u624B\u673A\u4E0A\u91CD\u65B0\u5F00\u542F\u3002" }) : null
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("h3", { children: "\u6C99\u7BB1" }),
            /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(KeyValues, { value: sandbox, omit: ["capabilities"] })
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(SectionTitle, { title: `\u9010\u80FD\u529B\u5F00\u5173\uFF08\u5DF2\u5F00\u542F ${enabledCount}/${caps.length}\uFF09`, meta: caps.length }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Grid, { children: caps.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Empty, { children: "\u8BFB\u53D6\u80FD\u529B\u72B6\u6001\u5931\u8D25\uFF08\u6743\u9650\u7BA1\u7406\u662F\u5426\u88AB\u5173\u95ED\uFF1F\uFF09" }) : caps.map((cap) => {
          const id = textOf(cap.id);
          return /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Card, { children: /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)("label", { className: MinisSettings_default.check, children: [
            /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(
              "input",
              {
                type: "checkbox",
                checked: booleanOf(cap.enabled),
                disabled: busy || permissionManage !== void 0 && !booleanOf(permissionManage.enabled),
                onChange: (event) => {
                  const enabled = event.currentTarget.checked;
                  if (id === "permission.manage" && !enabled && !confirm("\u5173\u95ED\u201C\u6743\u9650\u7BA1\u7406\u201D\u540E\uFF0C\u672C\u7F51\u9875\u65E0\u6CD5\u91CD\u65B0\u5F00\u542F\u4EFB\u4F55\u80FD\u529B\uFF0C\u53EA\u80FD\u56DE\u624B\u673A\u6062\u590D\u3002\u786E\u5B9A\u5173\u95ED\uFF1F")) return;
                  run({ kind: "capability-toggle", payload: { capability: id, enabled } });
                }
              }
            ),
            " ",
            booleanOf(cap.enabled) ? "\u5F00\u542F" : "\u5173\u95ED"
          ] }), children: [
            /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)("h3", { children: [
              textOf(cap.label),
              " ",
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(RiskBadge, { risk: textOf(cap.risk), label: textOf(cap.riskLabel, textOf(cap.risk)) })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("p", { children: textOf(cap.description) }),
            /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("p", { className: MinisSettings_default.muted, children: booleanOf(cap.defaultEnabled) ? "\u9ED8\u8BA4\u5F00\u542F" : "\u9ED8\u8BA4\u5173\u95ED" })
          ] }) }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(SectionTitle, { title: "\u73AF\u5883\u53D8\u91CF", meta: "\u503C\u53EA\u5199\u4E0D\u8BFB" }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Grid, { children: env.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Empty, { children: "\u6CA1\u6709\u73AF\u5883\u53D8\u91CF" }) : env.map((row, index) => {
          const id = textOf(row.id, String(index));
          const key = textOf(row.key);
          return /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Badge, { on: booleanOf(row.hasValue), yes: "\u5DF2\u8BBE\u503C", no: "\u7A7A\u503C" }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("h3", { children: key }),
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("p", { children: textOf(row.note) })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { small: true, disabled: busy, onClick: () => {
                const value = prompt(`\u8F93\u5165 ${key} \u7684\u65B0\u503C\uFF08\u4E0D\u4F1A\u56DE\u663E\u65E7\u503C\uFF09`);
                if (value !== null) run({ kind: "env-update", payload: { id, value } });
              }, children: "\u66F4\u65B0\u503C" }),
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm("\u5220\u9664\u8BE5\u73AF\u5883\u53D8\u91CF\uFF1F")) run({ kind: "env-delete", payload: { id } });
              }, children: "\u5220\u9664" })
            ] })
          ] }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Card, { wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(Form, { onSubmit: (payload) => run({ kind: "env-create", payload }), children: [
          /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Field, { label: "\u53D8\u91CF\u540D", children: /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("input", { className: MinisSettings_default.input, name: "key", required: true, pattern: "[A-Za-z_][A-Za-z0-9_]*" }) }),
          /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Field, { label: "\u503C", children: /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("input", { className: MinisSettings_default.input, type: "password", name: "value", required: true, autoComplete: "new-password" }) }),
          /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Field, { label: "\u5907\u6CE8", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("input", { className: MinisSettings_default.input, name: "note" }) }),
          /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u6DFB\u52A0" })
        ] }) }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(SectionTitle, { title: "\u5916\u90E8\u6302\u8F7D", meta: `${mounts.length}/${textOf(mountsData.capacity, "\u2014")} \xB7 \u65B0\u589E\u76EE\u5F55\u9700\u5728\u624B\u673A\u6388\u6743` }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Grid, { children: mounts.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Empty, { children: "\u6CA1\u6709\u5916\u90E8\u6302\u8F7D" }) : mounts.map((mount, index) => {
          const id = textOf(mount.id, String(index));
          return /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Badge, { on: booleanOf(mount.effectiveWritable), yes: "\u53EF\u5199", no: "\u53EA\u8BFB" }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)("h3", { children: textOf(mount.name) }),
              /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)("p", { children: [
                textOf(mount.path),
                " \xB7 ",
                textOf(mount.sourceDisplayName)
              ] })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime7.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { small: true, disabled: busy, onClick: () => {
                const name = prompt("\u65B0\u540D\u79F0", textOf(mount.name));
                if (name) run({ kind: "mount-rename", payload: { id, name } });
              }, children: "\u91CD\u547D\u540D" }),
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "mount-write", payload: { id, allowWrite: !booleanOf(mount.userAllowWrite) } }), children: booleanOf(mount.userAllowWrite) ? "\u8BBE\u4E3A\u53EA\u8BFB" : "\u5141\u8BB8\u5199\u5165" }),
              /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm("\u79FB\u9664\u8BE5\u5916\u90E8\u76EE\u5F55\u6302\u8F7D\uFF1F\u624B\u673A\u4E2D\u7684\u539F\u6587\u4EF6\u4E0D\u4F1A\u5220\u9664\u3002")) run({ kind: "mount-remove", payload: { id } });
              }, children: "\u79FB\u9664" })
            ] })
          ] }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime7.jsx)(Details, { label: "\u5171\u4EAB\u76EE\u5F55", value: shared })
      ] });
    }
    
    // src/client/settings/pages/ScheduledPage.tsx
    var import_jsx_runtime8 = require("react/jsx-runtime");
    function days(value) {
      const source = Array.isArray(value) ? value : textOf(value).split(",");
      return source.map((item) => Number(item)).filter((item) => Number.isInteger(item) && item >= 1 && item <= 7);
    }
    function TaskForm({ task, busy, run }) {
      const id = textOf(task.id);
      return /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)(Form, { onSubmit: (payload) => {
        const next = {
          ...payload,
          hour: Number(payload.hour),
          minute: Number(payload.minute),
          customDays: days(payload.customDays)
        };
        if (id !== "") next.taskId = id;
        run({ kind: id === "" ? "task-create" : "task-update", payload: next });
      }, children: [
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Field, { label: "\u540D\u79F0", children: /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("input", { className: MinisSettings_default.input, name: "label", required: true, defaultValue: textOf(task.label) }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Field, { label: "\u91CD\u590D", children: /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)("select", { className: MinisSettings_default.select, name: "repeatMode", defaultValue: textOf(task.repeatMode, "ONCE"), children: [
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("option", { children: "ONCE" }),
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("option", { children: "DAILY" }),
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("option", { children: "WEEKDAYS" }),
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("option", { children: "CUSTOM" })
        ] }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Field, { label: "\u5C0F\u65F6\uFF080-23\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("input", { className: MinisSettings_default.input, type: "number", min: 0, max: 23, name: "hour", defaultValue: numberOf(task.timeOfDayHour, numberOf(task.hour, 9)) }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Field, { label: "\u5206\u949F\uFF080-59\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("input", { className: MinisSettings_default.input, type: "number", min: 0, max: 59, name: "minute", defaultValue: numberOf(task.timeOfDayMinute, numberOf(task.minute, 0)) }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Field, { label: "\u81EA\u5B9A\u4E49\u661F\u671F\uFF081=\u5468\u65E5\u20267=\u5468\u516D\uFF09", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("input", { className: MinisSettings_default.input, name: "customDays", defaultValue: days(task.customDays).join(",") }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Field, { label: "\u76EE\u6807\u6A21\u5F0F", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("input", { className: MinisSettings_default.input, name: "targetMode", defaultValue: textOf(task.targetMode, "NEW_SESSION") }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Field, { label: "Prompt", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("textarea", { className: MinisSettings_default.textarea, name: "prompt", defaultValue: textOf(task.prompt) }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)("label", { className: MinisSettings_default.check, children: [
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("input", { type: "checkbox", name: "enabled", defaultChecked: task.enabled === void 0 || booleanOf(task.enabled) }),
          " \u542F\u7528"
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)("div", { className: MinisSettings_default.actions, children: [
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: id === "" ? "\u521B\u5EFA\u4EFB\u52A1" : "\u4FDD\u5B58\u4FEE\u6539" }),
          id === "" ? null : /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Button, { disabled: busy, onClick: () => run({ kind: "task-cancel" }), children: "\u53D6\u6D88" })
        ] })
      ] });
    }
    function ScheduledPage({ data, busy, run }) {
      const tasks = objectsOf(data.tasks);
      const editorObject = objectOf(data.editor);
      const editor = Object.keys(editorObject).length === 0 ? void 0 : editorObject;
      return /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)(import_jsx_runtime8.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Note, { children: "\u771F\u6B63\u7684\u8C03\u5EA6\u3001AlarmManager \u6CE8\u518C\u548C\u8FD0\u884C\u5386\u53F2\u4ECD\u7531 Android \u6267\u884C\uFF1B\u7F51\u9875\u4E0E\u624B\u673A\u4F7F\u7528\u540C\u4E00\u4EFD\u4EFB\u52A1\u6570\u636E\u3002" }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Grid, { children: tasks.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Empty, { children: "\u6CA1\u6709\u5B9A\u65F6\u4EFB\u52A1" }) : tasks.map((task, index) => {
          const id = textOf(task.id, String(index));
          return /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Badge, { on: booleanOf(task.enabled) }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("h3", { children: textOf(task.label) }),
              /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)("p", { children: [
                String(numberOf(task.timeOfDayHour, numberOf(task.hour))).padStart(2, "0"),
                ":",
                String(numberOf(task.timeOfDayMinute, numberOf(task.minute))).padStart(2, "0"),
                " \xB7 ",
                textOf(task.repeatMode),
                " \xB7 ",
                textOf(task.targetMode)
              ] }),
              /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)("p", { children: [
                "\u4E0B\u6B21\uFF1A",
                formatDate(task.nextTriggerMs),
                " \xB7 \u5DF2\u8FD0\u884C ",
                numberOf(task.runCount),
                " \u6B21"
              ] })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)("div", { className: MinisSettings_default.actions, children: [
              /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "task-edit", payload: { taskId: id } }), children: "\u7F16\u8F91" }),
              /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "task-run", payload: { taskId: id } }), children: "\u7ACB\u5373\u8FD0\u884C" }),
              /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "task-toggle", payload: { taskId: id, enabled: !booleanOf(task.enabled) } }), children: booleanOf(task.enabled) ? "\u505C\u7528" : "\u542F\u7528" }),
              /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
                if (confirm("\u6C38\u4E45\u5220\u9664\u8BE5\u5B9A\u65F6\u4EFB\u52A1\uFF1F")) run({ kind: "task-delete", payload: { taskId: id } });
              }, children: "\u5220\u9664" })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(Details, { label: "\u8FD0\u884C\u5386\u53F2", value: task.runHistory ?? [] })
          ] }, id);
        }) }),
        /* @__PURE__ */ (0, import_jsx_runtime8.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)("h3", { children: editor === void 0 ? "\u65B0\u5EFA\u5B9A\u65F6\u4EFB\u52A1" : "\u7F16\u8F91\u5B9A\u65F6\u4EFB\u52A1" }),
          /* @__PURE__ */ (0, import_jsx_runtime8.jsx)(TaskForm, { task: editor ?? {}, busy, run })
        ] })
      ] });
    }
    
    // src/client/settings/pages/AgentPage.tsx
    var import_jsx_runtime9 = require("react/jsx-runtime");
    function AgentPage({ data, busy, run }) {
      const settings = objectOf(data.settings);
      const jobs = objectsOf(objectOf(data.jobs).jobs);
      const approvals = objectsOf(objectOf(data.approvals).approvals);
      const questions = objectsOf(objectOf(data.questions).questions);
      return /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(import_jsx_runtime9.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(Grid, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("h3", { children: "\u5B50 Agent \u9650\u5236" }),
            /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(Form, { onSubmit: (payload) => run({ kind: "agent-settings", payload: { maxDepth: Number(payload.maxDepth), timeoutMinutes: Number(payload.timeoutMinutes) } }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Field, { label: "\u6700\u5927\u6DF1\u5EA6", children: /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("input", { className: MinisSettings_default.input, type: "number", min: 1, max: 5, name: "maxDepth", defaultValue: numberOf(settings.maxDepth, 2) }) }),
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Field, { label: "\u8D85\u65F6\uFF08\u5206\u949F\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("input", { className: MinisSettings_default.input, type: "number", min: 1, max: 30, name: "timeoutMinutes", defaultValue: numberOf(settings.timeoutMinutes, 10) }) }),
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4FDD\u5B58" })
            ] })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("h3", { children: "\u7B49\u5F85\u7528\u6237" }),
            /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)("p", { children: [
              approvals.length,
              " \u4E2A\u5BA1\u6279 \xB7 ",
              questions.length,
              " \u4E2A\u95EE\u9898"
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("p", { className: MinisSettings_default.muted, children: "\u4E0E\u624B\u673A\u7AEF\u5BA1\u6279\u548C\u95EE\u9898\u72B6\u6001\u5171\u7528\u540C\u4E00 seam\u3002" })
          ] })
        ] }),
        approvals.length === 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(import_jsx_runtime9.Fragment, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(SectionTitle, { title: "\u5F85\u5BA1\u6279", meta: approvals.length }),
          /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Grid, { children: approvals.map((approval, index) => {
            const id = textOf(approval.id, String(index));
            return /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(Card, { children: [
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("h3", { children: textOf(approval.toolName, "\u5371\u9669\u64CD\u4F5C") }),
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("p", { children: textOf(approval.summary) }),
              /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)("div", { className: MinisSettings_default.actions, children: [
                /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Button, { disabled: busy, onClick: () => run({ kind: "approval-answer", payload: { id, allowed: true } }), children: "\u4EC5\u5141\u8BB8\u4E00\u6B21" }),
                /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Button, { danger: true, disabled: busy, onClick: () => run({ kind: "approval-answer", payload: { id, allowed: false } }), children: "\u62D2\u7EDD" })
              ] })
            ] }, id);
          }) })
        ] }),
        questions.length === 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(import_jsx_runtime9.Fragment, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(SectionTitle, { title: "\u5F85\u56DE\u7B54\u95EE\u9898", meta: questions.length }),
          /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Grid, { children: questions.map((question, index) => {
            const id = textOf(question.id, String(index));
            return /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(Card, { children: [
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("h3", { children: textOf(question.question, "Agent \u95EE\u9898") }),
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Button, { disabled: busy, onClick: () => {
                const answer = prompt(textOf(question.question, "\u8BF7\u8F93\u5165\u56DE\u7B54"));
                if (answer !== null) run({ kind: "question-answer", payload: { id, answer } });
              }, children: "\u56DE\u7B54" }),
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Details, { label: "\u7ED3\u6784\u5316\u5185\u5BB9", value: question })
            ] }, id);
          }) })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(SectionTitle, { title: "\u540E\u53F0\u4F5C\u4E1A", meta: jobs.length }),
        /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Grid, { children: jobs.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Empty, { children: "\u6CA1\u6709\u540E\u53F0\u4F5C\u4E1A" }) : jobs.map((job, index) => {
          const id = textOf(job.id, String(index));
          const status = textOf(job.status);
          return /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("span", { className: MinisSettings_default.badge, children: status }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime9.jsx)("h3", { children: textOf(job.label, id) }),
              /* @__PURE__ */ (0, import_jsx_runtime9.jsxs)("p", { children: [
                textOf(job.kind),
                " \xB7 ",
                textOf(job.detail)
              ] })
            ] }),
            status === "running" || status === "stopping" ? /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Button, { small: true, danger: true, disabled: busy, onClick: () => {
              if (confirm("\u53D6\u6D88\u8FD9\u4E2A\u540E\u53F0\u4F5C\u4E1A\uFF1F")) run({ kind: "job-cancel", payload: { id, reason: "Minis Web user" } });
            }, children: "\u53D6\u6D88" }) : null,
            /* @__PURE__ */ (0, import_jsx_runtime9.jsx)(Details, { label: "\u8F93\u51FA", value: job.output ?? "" })
          ] }, id);
        }) })
      ] });
    }
    
    // src/client/settings/pages/WebPage.tsx
    var import_jsx_runtime10 = require("react/jsx-runtime");
    function WebPage({ data, busy, run }) {
      const settings = objectOf(data.settings);
      const status = objectOf(data.status);
      const tunnel = objectOf(status.tunnel);
      return /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(import_jsx_runtime10.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Note, { danger: true, children: "\u7AEF\u53E3\u6216\u5C40\u57DF\u7F51\u76D1\u542C\u53D8\u66F4\u9700\u8981\u91CD\u542F Web \u8FDC\u7A0B\u670D\u52A1\u3002\u4FEE\u6539\u8D26\u53F7\u6216\u5BC6\u7801\u4F1A\u6CE8\u9500\u6240\u6709\u7F51\u9875\u4F1A\u8BDD\u3002" }),
        /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(Grid, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("h3", { children: "\u670D\u52A1" }),
            /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(Form, { onSubmit: (payload) => run({ kind: "web-service", payload: { port: Number(payload.port), lanAccess: booleanOf(payload.lanAccess) } }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Field, { label: "\u7AEF\u53E3", children: /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { className: MinisSettings_default.input, type: "number", min: 1024, max: 65535, name: "port", defaultValue: numberOf(settings.port, 8765) }) }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)("label", { className: MinisSettings_default.check, children: [
                /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { type: "checkbox", name: "lanAccess", defaultChecked: booleanOf(settings.lanAccess) }),
                " \u5F00\u653E\u5C40\u57DF\u7F51\u76D1\u542C"
              ] }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4FDD\u5B58\u670D\u52A1\u8BBE\u7F6E" })
            ] })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(Card, { children: [
            /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("h3", { children: "\u8D26\u53F7" }),
            /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(Form, { onSubmit: (payload) => run({ kind: "web-identity", payload: omitBlank(payload, ["newPassword"]) }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Field, { label: "\u7528\u6237\u540D", children: /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { className: MinisSettings_default.input, name: "username", defaultValue: textOf(settings.username) }) }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Field, { label: "\u5F53\u524D\u5BC6\u7801", children: /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { className: MinisSettings_default.input, type: "password", name: "currentPassword", autoComplete: "current-password", required: true }) }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Field, { label: "\u65B0\u5BC6\u7801\uFF08\u53EF\u9009\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { className: MinisSettings_default.input, type: "password", name: "newPassword", autoComplete: "new-password" }) }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u66F4\u65B0\u8D26\u53F7" })
            ] })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(Card, { wide: true, children: [
            /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("h3", { children: "Cloudflare Tunnel" }),
            /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)(Form, { onSubmit: (payload) => run({ kind: "web-tunnel", payload: omitBlank(payload, ["cloudflareTunnelToken"]) }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Field, { label: "\u516C\u5F00\u57DF\u540D", children: /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { className: MinisSettings_default.input, name: "cloudflareHostname", defaultValue: textOf(settings.cloudflareHostname) }) }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Field, { label: "\u65B0 Tunnel Token\uFF08\u7559\u7A7A\u4FDD\u7559\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { className: MinisSettings_default.input, type: "password", name: "cloudflareTunnelToken", autoComplete: "new-password" }) }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)("label", { className: MinisSettings_default.check, children: [
                /* @__PURE__ */ (0, import_jsx_runtime10.jsx)("input", { type: "checkbox", name: "cloudflareTunnelEnabled", defaultChecked: booleanOf(settings.cloudflareTunnelEnabled) }),
                " \u542F\u7528\u96A7\u9053"
              ] }),
              /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u4FDD\u5B58\u96A7\u9053\u8BBE\u7F6E" })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)("p", { children: [
              textOf(tunnel.phase),
              " \xB7 ",
              textOf(tunnel.detail)
            ] })
          ] })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime10.jsxs)("div", { className: MinisSettings_default.actions, children: [
          /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Button, { disabled: busy, onClick: () => {
            if (confirm("\u91CD\u542F Web \u670D\u52A1\uFF1F\u5F53\u524D\u8FDE\u63A5\u4F1A\u77ED\u6682\u65AD\u5F00\u3002")) run({ kind: "web-restart" });
          }, children: "\u91CD\u542F Web \u670D\u52A1" }),
          /* @__PURE__ */ (0, import_jsx_runtime10.jsx)(Button, { danger: true, disabled: busy, onClick: () => run({ kind: "logout" }), children: "\u9000\u51FA\u767B\u5F55" })
        ] })
      ] });
    }
    
    // src/client/settings/pages/DevicePage.tsx
    var import_react = require("react");
    var import_jsx_runtime11 = require("react/jsx-runtime");
    function DevicePage({ data, busy, run }) {
      const shot = objectOf(data.shot);
      const hasShot = textOf(shot.base64) !== "";
      const tapMode = booleanOf(data.tapMode);
      const [display, setDisplay] = (0, import_react.useState)("\u2014");
      const point = (event) => {
        const image = event.currentTarget;
        if (image.naturalWidth <= 0 || image.naturalHeight <= 0) return void 0;
        const rect = image.getBoundingClientRect();
        return {
          x: Math.max(0, Math.round((event.clientX - rect.left) * image.naturalWidth / rect.width)),
          y: Math.max(0, Math.round((event.clientY - rect.top) * image.naturalHeight / rect.height))
        };
      };
      return /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)(import_jsx_runtime11.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Note, { children: "\u201C\u67E5\u770B\u624B\u673A\u753B\u9762 / \u8BBE\u5907\u64CD\u4F5C\u201D\u7531\u72EC\u7ACB\u80FD\u529B\u5F00\u5173\u4FDD\u62A4\uFF08\u9ED8\u8BA4\u5173\u95ED\uFF09\u3002\u622A\u56FE\u53EA\u5728\u70B9\u51FB\u6309\u94AE\u65F6\u6293\u53D6\uFF0C\u4E0D\u4F1A\u81EA\u52A8\u8F6E\u8BE2\uFF1B\u64CD\u4F5C\u9700\u8981 Minis App \u505C\u7559\u5728\u524D\u53F0\u3002" }),
        /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Grid, { children: /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)("div", { className: MinisSettings_default.actions, children: [
            /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Button, { primary: true, disabled: busy, onClick: () => run({ kind: "device-shot" }), children: hasShot ? "\u5237\u65B0\u622A\u56FE" : "\u83B7\u53D6\u624B\u673A\u622A\u56FE" }),
            /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)("label", { className: MinisSettings_default.check, children: [
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { type: "checkbox", checked: tapMode, disabled: busy, onChange: (event) => run({ kind: "device-tapmode", payload: { enabled: event.currentTarget.checked } }) }),
              " \u70B9\u51FB\u6A21\u5F0F\uFF08\u70B9\u56FE\u7247=\u70B9\u624B\u673A\uFF09"
            ] })
          ] }),
          hasShot ? /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)("div", { className: MinisSettings_default.deviceStage, children: [
            /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(
              "img",
              {
                className: MinisSettings_default.deviceShot,
                src: `data:image/png;base64,${textOf(shot.base64)}`,
                alt: "\u624B\u673A\u753B\u9762",
                onLoad: (event) => setDisplay(`${event.currentTarget.clientWidth} \xD7 ${event.currentTarget.clientHeight}`),
                onClick: (event) => {
                  const location2 = point(event);
                  if (location2 === void 0) return;
                  if (tapMode) {
                    if (confirm(`\u70B9\u51FB\u624B\u673A (${location2.x}, ${location2.y})\uFF1F`)) run({ kind: "device-tap", payload: location2 });
                  } else run({ kind: "device-point", payload: location2 });
                }
              }
            ),
            /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)("p", { className: `${MinisSettings_default.muted} ${MinisSettings_default.center}`, children: [
              "\u663E\u793A\u5C3A\u5BF8\uFF1A",
              display,
              " \xB7 \u539F\u59CB\u50CF\u7D20\uFF1A",
              numberOf(shot.originalWidth, numberOf(shot.scaledWidth)),
              " \xD7 ",
              numberOf(shot.originalHeight, numberOf(shot.scaledHeight)),
              " \xB7 ",
              formatSize(shot.size ?? shot.sizeBytes),
              " \xB7 \u5750\u6807\uFF1A",
              numberOf(data.x),
              ", ",
              numberOf(data.y)
            ] })
          ] }) : /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Empty, { children: "\u8FD8\u6CA1\u6709\u622A\u56FE\u3002\u5148\u70B9\u51FB\u201C\u83B7\u53D6\u624B\u673A\u622A\u56FE\u201D\u3002" }),
          /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)("details", { className: MinisSettings_default.details, children: [
            /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("summary", { children: "\u624B\u52A8\u70B9\u51FB\uFF08\u4E0D\u4F9D\u8D56\u56FE\u7247\u5750\u6807\uFF09" }),
            /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)(Form, { onSubmit: (payload) => run({ kind: "device-tap", payload: { x: Number(payload.x), y: Number(payload.y) } }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Field, { label: "X\uFF08\u539F\u59CB\u50CF\u7D20\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { className: MinisSettings_default.input, type: "number", name: "x", required: true }) }),
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Field, { label: "Y\uFF08\u539F\u59CB\u50CF\u7D20\uFF09", children: /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { className: MinisSettings_default.input, type: "number", name: "y", required: true }) }),
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u70B9\u51FB" })
            ] })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)("details", { className: MinisSettings_default.details, children: [
            /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("summary", { children: "\u6EDA\u52A8" }),
            /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)(Form, { onSubmit: (payload) => {
              const next = { x: Number(payload.x), y: Number(payload.y), deltaX: Number(payload.deltaX), deltaY: Number(payload.deltaY) };
              if (next.deltaX === 0 && next.deltaY === 0) {
                alert("\u0394X \u4E0E \u0394Y \u81F3\u5C11\u4E00\u4E2A\u975E\u96F6");
                return;
              }
              run({ kind: "device-scroll", payload: next });
            }, children: [
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Field, { label: "\u8D77\u70B9 X", children: /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { className: MinisSettings_default.input, type: "number", name: "x", required: true }) }),
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Field, { label: "\u8D77\u70B9 Y", children: /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { className: MinisSettings_default.input, type: "number", name: "y", required: true }) }),
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Field, { label: "\u0394X", children: /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { className: MinisSettings_default.input, type: "number", name: "deltaX", defaultValue: 0 }) }),
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Field, { label: "\u0394Y", children: /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { className: MinisSettings_default.input, type: "number", name: "deltaY", required: true }) }),
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u6EDA\u52A8" })
            ] })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)("details", { className: MinisSettings_default.details, children: [
            /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("summary", { children: "\u8F93\u5165\u6587\u672C" }),
            /* @__PURE__ */ (0, import_jsx_runtime11.jsxs)(Form, { onSubmit: (payload) => run({ kind: "device-input", payload: { text: textOf(payload.text) } }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Field, { label: "\u6587\u672C\uFF08\u4F18\u5148\u7531 Accessibility ACTION_SET_TEXT \u5904\u7406\uFF09", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime11.jsx)("input", { className: MinisSettings_default.input, name: "text", required: true }) }),
              /* @__PURE__ */ (0, import_jsx_runtime11.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u8F93\u5165" })
            ] })
          ] })
        ] }) })
      ] });
    }
    
    // src/client/settings/pages/DiagnosticsPage.tsx
    var import_jsx_runtime12 = require("react/jsx-runtime");
    function fileObject(value) {
      return typeof value === "string" ? { name: value } : objectOf(value);
    }
    function DiagnosticsPage({ data, busy, run }) {
      const info = objectOf(data.info);
      const logsData = objectOf(data.logs);
      const crashesData = objectOf(data.crashes);
      const logs = arrayOf(logsData.files ?? logsData.logs).map(fileObject);
      const crashes = arrayOf(crashesData.crashes ?? crashesData.files).map(fileObject);
      const viewer = objectOf(data.viewer);
      return /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)(Grid, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("h3", { children: "App \u4E0E\u5B58\u50A8" }),
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)(KeyValues, { value: info })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)(Card, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("h3", { children: "\u65E5\u5FD7" }),
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("div", { className: MinisSettings_default.list, children: logs.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime12.jsx)(Empty, { children: "\u6CA1\u6709\u65E5\u5FD7" }) : logs.map((row, index) => {
            const name = textOf(row.name, textOf(row.fileName, String(index)));
            return /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime12.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "log-read", payload: { name, limit: 65536 } }), children: "\u8BFB\u53D6\u6B63\u6587" }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("strong", { children: name }),
              /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)("p", { children: [
                formatSize(row.size),
                " \xB7 ",
                formatDate(row.modified)
              ] })
            ] }, name);
          }) })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)(Card, { children: [
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("h3", { children: "\u5D29\u6E83\u62A5\u544A" }),
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("div", { className: MinisSettings_default.list, children: crashes.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime12.jsx)(Empty, { children: "\u6CA1\u6709\u5D29\u6E83\u62A5\u544A" }) : crashes.map((row, index) => {
            const name = textOf(row.name, textOf(row.fileName, String(index)));
            return /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)(CardHead, { actions: /* @__PURE__ */ (0, import_jsx_runtime12.jsx)(Button, { small: true, disabled: busy, onClick: () => run({ kind: "crash-read", payload: { name, stackOnly: true, limit: 262144 } }), children: "\u8BFB\u53D6\u6B63\u6587" }), children: [
              /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("strong", { children: name }),
              /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("p", { children: textOf(row.summary) })
            ] }, name);
          }) })
        ] }),
        Object.keys(viewer).length === 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)("h3", { children: textOf(viewer.title) }),
          /* @__PURE__ */ (0, import_jsx_runtime12.jsxs)("p", { className: MinisSettings_default.muted, children: [
            numberOf(viewer.bytes),
            " \u5B57\u7B26 \xB7 ",
            formatDate(viewer.modified),
            booleanOf(viewer.truncated) ? " \xB7 \u5DF2\u622A\u65AD" : ""
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime12.jsx)(Code, { children: textOf(viewer.content) })
        ] })
      ] });
    }
    
    // src/client/settings/pages/AdvancedPage.tsx
    var import_jsx_runtime13 = require("react/jsx-runtime");
    function AdvancedPage({ data, busy, run }) {
      const discover = objectOf(data.discover);
      const methods = objectsOf(discover.methods);
      const caps = objectsOf(discover.capabilities);
      return /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)(import_jsx_runtime13.Fragment, { children: [
        /* @__PURE__ */ (0, import_jsx_runtime13.jsx)(Note, { children: "\u8FD9\u91CC\u53EA\u5217\u51FA\u5F53\u524D\u5DF2\u6620\u5C04\u4E14\u5DF2\u5F00\u542F\u7684 App RPC\u3002\u670D\u52A1\u7AEF\u6309\u80FD\u529B\u5F00\u5173\u9010\u9879\u62E6\u622A\uFF0C\u672A\u767B\u8BB0\u7684\u672A\u6765\u65B9\u6CD5\u4E00\u5F8B\u62D2\u7EDD\u3002" }),
        /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)(Form, { onSubmit: (payload) => {
            let params;
            try {
              const parsed = JSON.parse(textOf(payload.params, "{}"));
              if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error("\u53C2\u6570\u5FC5\u987B\u662F JSON \u5BF9\u8C61");
              params = parsed;
            } catch (error) {
              alert(error instanceof Error ? error.message : "\u53C2\u6570\u4E0D\u662F\u6709\u6548 JSON");
              return;
            }
            run({ kind: "rpc-run", payload: { method: textOf(payload.method), params } });
          }, children: [
            /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)(Field, { label: "\u65B9\u6CD5", wide: true, children: [
              /* @__PURE__ */ (0, import_jsx_runtime13.jsx)("input", { className: MinisSettings_default.input, name: "method", list: "openminis-rpc-methods", defaultValue: textOf(data.method), placeholder: "\u4F8B\u5982 provider.groups.update", required: true }),
              /* @__PURE__ */ (0, import_jsx_runtime13.jsx)("datalist", { id: "openminis-rpc-methods", children: methods.map((method) => /* @__PURE__ */ (0, import_jsx_runtime13.jsx)("option", { value: textOf(method.name), children: textOf(method.description) }, textOf(method.name))) })
            ] }),
            /* @__PURE__ */ (0, import_jsx_runtime13.jsx)(Field, { label: "\u53C2\u6570 JSON", wide: true, children: /* @__PURE__ */ (0, import_jsx_runtime13.jsx)("textarea", { className: MinisSettings_default.textarea, name: "params", defaultValue: jsonText(data.params ?? {}) }) }),
            /* @__PURE__ */ (0, import_jsx_runtime13.jsx)(Button, { type: "submit", primary: true, disabled: busy, children: "\u6267\u884C RPC" })
          ] }),
          data.result === void 0 || data.result === null ? null : /* @__PURE__ */ (0, import_jsx_runtime13.jsx)("pre", { className: MinisSettings_default.code, children: jsonText(data.result) })
        ] }),
        caps.length === 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)("h3", { children: [
            "\u80FD\u529B\u76EE\u5F55\uFF08",
            caps.length,
            "\uFF09"
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime13.jsx)(Details, { label: "\u67E5\u770B\u80FD\u529B\u76EE\u5F55", value: caps })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)(Card, { wide: true, children: [
          /* @__PURE__ */ (0, import_jsx_runtime13.jsxs)("h3", { children: [
            "\u53EF\u7528\u65B9\u6CD5\uFF08",
            methods.length,
            "\uFF09"
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime13.jsx)(Details, { label: "\u67E5\u770B\u53D1\u73B0\u6587\u6863", value: methods })
        ] })
      ] });
    }
    
    // src/client/settings/MinisSettings.tsx
    var import_jsx_runtime14 = require("react/jsx-runtime");
    function Page({ state, run }) {
      const data = state.pages[state.tab] ?? {};
      const common = { data, busy: state.busy, run };
      switch (state.tab) {
        case "overview":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(OverviewPage, { data });
        case "providers":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(ProvidersPage, { ...common });
        case "skills":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(SkillsPage, { ...common });
        case "mcp":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(McpPage, { ...common });
        case "memory":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(MemoryPage, { ...common });
        case "system":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(SystemPage, { ...common });
        case "scheduled":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(ScheduledPage, { ...common });
        case "agent":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(AgentPage, { ...common });
        case "web":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(WebPage, { ...common });
        case "device":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(DevicePage, { ...common });
        case "diagnostics":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(DiagnosticsPage, { ...common });
        case "advanced":
          return /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(AdvancedPage, { ...common });
      }
    }
    function MinisSettings(props) {
      const state = props.useSnapshot((value) => value);
      (0, import_react2.useEffect)(() => props.activate(), [props.activate]);
      const run = (command) => {
        void props.run(command);
      };
      return /* @__PURE__ */ (0, import_jsx_runtime14.jsxs)("section", { className: MinisSettings_default.root, "aria-labelledby": "openminis-settings-title", children: [
        /* @__PURE__ */ (0, import_jsx_runtime14.jsxs)("header", { className: MinisSettings_default.header, children: [
          /* @__PURE__ */ (0, import_jsx_runtime14.jsxs)("div", { className: MinisSettings_default.headerText, children: [
            /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("h2", { id: "openminis-settings-title", children: navigationLabel(state.tab) }),
            /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("p", { children: "\u4E0E Android App \u5171\u7528\u540C\u4E00\u4EFD\u8BBE\u7F6E" })
          ] }),
          /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("span", { className: MinisSettings_default.spacer }),
          /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("a", { className: MinisSettings_default.repo, href: "https://github.com/limuzi013/minis-for-android", target: "_blank", rel: "noopener noreferrer", children: "\u9879\u76EE\u4E0E\u53CD\u9988" }),
          /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("button", { type: "button", className: MinisSettings_default.iconButton, disabled: state.busy, onClick: props.refresh, title: "\u5237\u65B0", "aria-label": "\u5237\u65B0", children: "\u21BB" })
        ] }),
        /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("select", { className: `${MinisSettings_default.select} ${MinisSettings_default.mobileNavigation}`, "aria-label": "\u63A7\u5236\u53F0\u9875\u9762", value: state.tab, onChange: (event) => props.selectTab(event.currentTarget.value), children: NAVIGATION.map((row) => /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("option", { value: row.id, children: row.label }, row.id)) }),
        /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("nav", { className: MinisSettings_default.navigation, "aria-label": "Minis \u63A7\u5236\u53F0", children: NAVIGATION.map((row) => /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("button", { type: "button", className: `${MinisSettings_default.navButton} ${state.tab === row.id ? MinisSettings_default.navActive : ""}`, "aria-current": state.tab === row.id ? "page" : void 0, onClick: () => props.selectTab(row.id), children: row.label }, row.id)) }),
        /* @__PURE__ */ (0, import_jsx_runtime14.jsxs)("div", { className: MinisSettings_default.content, children: [
          state.phase === "loading" && state.pages[state.tab] === void 0 ? /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("div", { className: MinisSettings_default.loading, children: "\u6B63\u5728\u4ECE\u624B\u673A\u8BFB\u53D6\u2026" }) : null,
          state.phase === "error" && state.pages[state.tab] === void 0 ? /* @__PURE__ */ (0, import_jsx_runtime14.jsxs)("div", { className: MinisSettings_default.errorState, children: [
            /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("p", { children: state.error }),
            /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("button", { type: "button", className: MinisSettings_default.button, onClick: props.refresh, children: "\u91CD\u8BD5" })
          ] }) : null,
          state.pages[state.tab] === void 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime14.jsx)(Page, { state, run })
        ] }),
        state.toast === void 0 ? null : /* @__PURE__ */ (0, import_jsx_runtime14.jsx)("div", { className: `${MinisSettings_default.toast} ${state.toast.error ? MinisSettings_default.toastError : ""}`, role: state.toast.error ? "alert" : "status", "aria-live": "polite", children: state.toast.message })
      ] });
    }
    
    // src/client/locales.ts
    var zh = { nav: "Minis \u63A7\u5236\u53F0" };
    var en = { nav: "Minis Console" };
    
    // src/client/index.ts
    var NS = "openminis.settings";
    var inject = ["slots", "locale"];
    function apply(ctx) {
      const api = new MinisApiService();
      ctx.provide("minisApi", api);
      const controller = new MinisSettingsController(api);
      ctx.effect(() => () => controller.dispose(), "openminis settings: controller lifecycle");
      ctx.effect(() => ctx.locale.register(NS, { zh, en }), "openminis settings: locale");
      const t = ctx.locale.bind(NS);
      const injected = {
        hooks: { snapshot: controller.store },
        activate: controller.activate.bind(controller),
        selectTab: controller.selectTab.bind(controller),
        refresh: controller.refresh.bind(controller),
        run: controller.run.bind(controller)
      };
      ctx.slots.inject("settings.section", () => ctx.slots.register({
        name: "settings.section",
        id: "openminis",
        order: 100,
        label: () => t("nav"),
        locale: NS,
        inject: () => injected
      }, MinisSettings));
    }
    
    return module.exports;
  }
});
