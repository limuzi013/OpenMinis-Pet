window.__ModuleLoader__.load({
  id: "@openminis/minis-input-resources",
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
      FILE_PREFIX: () => FILE_PREFIX,
      SESSION_PREFIX: () => SESSION_PREFIX,
      apply: () => apply,
      inject: () => inject
    });
    module.exports = __toCommonJS(index_exports);
    var FILE_PREFIX = "file:";
    var SESSION_PREFIX = "session:";
    var inject = ["inputTriggers", "connection"];
    function apply(ctx) {
      const scope = ctx;
      const inputTriggers = scope.get("inputTriggers");
      const connection = scope.get("connection");
      if (inputTriggers === void 0 || connection === void 0) {
        return;
      }
      const rpc = connection.rpc;
      const fetchResources = async (session, query, signal) => {
        const carried = await rpc.call("/api", "resources/list", {
          args: { agentId: session.sessionId, query }
        });
        signal.throwIfAborted?.();
        if (!carried.ok || carried.value === void 0) return [];
        const value = carried.value;
        const candidates = [];
        for (const entry of value.files ?? []) {
          candidates.push({
            id: `${FILE_PREFIX}${entry.path}`,
            name: entry.name,
            description: entry.kind === "dir" ? `\u6587\u4EF6\u5939 \xB7 ${entry.path}` : `\u6587\u4EF6 \xB7 ${entry.path}`,
            icon: entry.kind === "dir" ? "\u{1F4C1}" : "\u{1F4C4}",
            ref: `${FILE_PREFIX}${entry.path}`
          });
        }
        for (const entry of value.sessions ?? []) {
          candidates.push({
            id: `${SESSION_PREFIX}${entry.sessionId}`,
            name: entry.title,
            description: `\u5386\u53F2\u4F1A\u8BDD \xB7 ${entry.sessionId.slice(0, 8)}`,
            icon: "\u{1F4AC}",
            ref: `${SESSION_PREFIX}${entry.sessionId}`
          });
        }
        const needle = query.toLowerCase();
        return (needle.length === 0 ? candidates : candidates.filter((c) => c.name.toLowerCase().includes(needle) || (c.description ?? "").toLowerCase().includes(needle))).slice(0, 60);
      };
      const source = {
        trigger: "@",
        name: "resources",
        order: 1,
        async candidates(session, req) {
          return await fetchResources(session, req.query, req.signal);
        },
        warm(session) {
          fetchResources(session, "", new AbortController().signal).catch(() => {
          });
        },
        onPick(pick) {
          const ref = pick.candidate.ref;
          return {
            insert: {
              source: "resources",
              ref
            }
          };
        },
        codec: {
          clipboardText: (ref) => ref,
          serialize: (ref) => {
            if (ref.startsWith(FILE_PREFIX)) {
              return Promise.resolve(`<<minis-file:${ref.slice(FILE_PREFIX.length)}>>`);
            }
            if (ref.startsWith(SESSION_PREFIX)) {
              return Promise.resolve(`<<minis-session:${ref.slice(SESSION_PREFIX.length)}>>`);
            }
            return Promise.resolve(ref);
          }
        }
      };
      scope.effect(() => inputTriggers.registerSource(source), "openminis: resources @ source");
    }
    
    return module.exports;
  }
});
