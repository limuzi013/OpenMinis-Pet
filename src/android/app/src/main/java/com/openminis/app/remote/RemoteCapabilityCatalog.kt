package com.openminis.app.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Central, pure capability catalog for the Web Remote surface.
 *
 * Every RPC / HTTP / DSH method reachable from a browser is mapped to exactly
 * one capability id here (or is consciously unlisted, which means "deny").
 * No prefix matching is allowed: a future sensitive method that is not
 * explicitly listed is denied by default (unknown/future → deny).
 *
 * The catalog is pure Kotlin (no Android imports) so JVM unit tests can pin
 * defaults, per-item isolation, preset migration and method→capability
 * mapping without a device.
 */
object RemoteCapabilityCatalog {

    // ------------------------------------------------------------------ model

    enum class Risk(val label: String) {
        NONE("无"),
        LOW("低"),
        MEDIUM("中"),
        HIGH("高"),
    }

    data class RemoteCapability(
        val id: String,
        val label: String,
        val description: String,
        val risk: Risk,
        val defaultEnabled: Boolean,
    )

    /** Stable ids — do not rename (SharedPreferences keys, wire values). */
    const val CHAT = "chat"
    const val FILES_READ = "files.read"
    const val FILES_WRITE = "files.write"
    const val SANDBOX_FS = "sandbox.fs"
    const val SHELL = "shell"
    const val DEVICE_VIEW = "device.view"
    const val DEVICE_CONTROL = "device.control"
    const val UI_INSPECT = "ui.inspect"
    const val BROWSER_VIEW = "browser.view"
    const val BROWSER_EXECUTE = "browser.execute"
    const val PROVIDERS_READ = "providers.read"
    const val PROVIDERS_MANAGE = "providers.manage"
    const val CREDENTIALS_EXPORT = "credentials.export"
    const val SKILLS_MANAGE = "skills.manage"
    const val MEMORY_MANAGE = "memory.manage"
    const val MCP_MANAGE = "mcp.manage"
    const val ENVIRONMENTS_MANAGE = "environments.manage"
    const val STORAGE_MANAGE = "storage.manage"
    const val SCHEDULED_MANAGE = "scheduled.manage"
    const val AGENT_MANAGE = "agent.manage"
    const val PREFERENCES = "preferences"
    const val SERVICE_MANAGE = "service.manage"
    const val PERMISSION_MANAGE = "permission.manage"
    const val DIAGNOSTICS_LIST = "diagnostics.list"
    const val DIAGNOSTICS_CONTENT = "diagnostics.content"
    const val ADMIN = "admin"

    val ALL: List<RemoteCapability> = listOf(
        RemoteCapability(CHAT, "聊天与会话", "浏览会话、发送消息、模型/思考强度切换、删除与压缩等聊天操作", Risk.MEDIUM, true),
        RemoteCapability(FILES_READ, "工作区文件（只读）", "浏览和读取 /var/minis/workspace 下的会话文件", Risk.LOW, true),
        RemoteCapability(FILES_WRITE, "工作区文件（写入）", "在工作区内新建/编辑文件、目录与工作区分组（写入仍限定在工作区内）", Risk.MEDIUM, true),
        RemoteCapability(SANDBOX_FS, "沙箱任意路径文件访问", "对沙箱根文件系统任意路径的列目录 / 读 / 写（调试级）", Risk.HIGH, false),
        RemoteCapability(SHELL, "沙箱 Shell 执行", "在沙箱内执行 Shell 命令", Risk.HIGH, true),
        RemoteCapability(DEVICE_VIEW, "查看手机画面", "截取并查看手机屏幕画面（需要 App 保持在前台）", Risk.MEDIUM, false),
        RemoteCapability(DEVICE_CONTROL, "设备操作", "在手机上模拟点击、滚动和文本输入", Risk.HIGH, false),
        RemoteCapability(UI_INSPECT, "界面检查", "读取界面视图树、搜索控件、高亮节点（调试辅助）", Risk.MEDIUM, false),
        RemoteCapability(BROWSER_VIEW, "浏览器标签查看", "查看内置浏览器打开页面/标题/文本内容", Risk.LOW, false),
        RemoteCapability(BROWSER_EXECUTE, "浏览器执行脚本", "在浏览器标签内执行任意 JavaScript", Risk.HIGH, false),
        RemoteCapability(PROVIDERS_READ, "供应商与模型（只读）", "查看供应商实例、模型目录、模型组结构", Risk.LOW, true),
        RemoteCapability(PROVIDERS_MANAGE, "供应商与模型（修改）", "新增/修改/删除供应商实例、模型与模型组（写入凭据需登录验证，密钥不回传）", Risk.MEDIUM, true),
        RemoteCapability(CREDENTIALS_EXPORT, "凭据导出/导入", "导出或导入含 API Key 的供应商配置", Risk.HIGH, false),
        RemoteCapability(SKILLS_MANAGE, "Skills 管理", "浏览、创建、导入、启停与删除 Skill", Risk.MEDIUM, true),
        RemoteCapability(MEMORY_MANAGE, "记忆与 SOUL", "读写记忆文件与 SOUL、切换全局记忆", Risk.MEDIUM, true),
        RemoteCapability(MCP_MANAGE, "MCP 配置", "浏览、创建、导入、启停与删除 MCP Server（secret 不回传）", Risk.MEDIUM, true),
        RemoteCapability(ENVIRONMENTS_MANAGE, "环境变量", "查看存在性、新增/更新/删除环境变量（值只写不读）", Risk.MEDIUM, true),
        RemoteCapability(STORAGE_MANAGE, "外部挂载", "重命名、切换读写、移除 Android 外部挂载", Risk.MEDIUM, true),
        RemoteCapability(SCHEDULED_MANAGE, "定时任务", "创建、编辑、启停、删除与运行定时任务", Risk.MEDIUM, true),
        RemoteCapability(AGENT_MANAGE, "Agent 状态与作业", "Goal/Todo/Plan/交付物、子 Agent 限制、后台作业、审批与问题作答", Risk.MEDIUM, true),
        RemoteCapability(PREFERENCES, "界面偏好", "主题与语言等外观设置", Risk.LOW, true),
        RemoteCapability(SERVICE_MANAGE, "Web 远程服务配置", "监听端口、局域网访问、账号密码与 Cloudflare Tunnel 设置", Risk.MEDIUM, true),
        RemoteCapability(PERMISSION_MANAGE, "权限管理", "查看与修改以上逐能力开关（在 Web 上关闭后无法从 Web 重新开启）", Risk.HIGH, true),
        RemoteCapability(DIAGNOSTICS_LIST, "诊断（列表）", "App 信息、日志文件名列表、崩溃列表", Risk.LOW, true),
        RemoteCapability(DIAGNOSTICS_CONTENT, "诊断（正文）", "读取日志与崩溃报告正文、LLM 请求记录；正文可能包含敏感信息，请谨慎开启", Risk.HIGH, false),
        RemoteCapability(ADMIN, "管理员操作", "更新检查/下载/安装、DEBUG 离线执行器等", Risk.HIGH, false),
    )

    private val byId: Map<String, RemoteCapability> = ALL.associateBy { it.id }

    fun byId(id: String): RemoteCapability? = byId[id]

    fun defaultState(): Map<String, Boolean> = ALL.associate { it.id to it.defaultEnabled }

    /**
     * Capacity values implied by a legacy preset. Kept for the old preset
     * API (`settings.permissionPreset.*`) and for the one-time migration of
     * an existing `danger-full-access` install.
     */
    fun valuesForPreset(preset: String): Map<String, Boolean> {
        val applyAll = preset == RemotePermissionPolicy.PRESET_DANGER_FULL
        return ALL.associate { cap -> cap.id to (if (applyAll) true else cap.defaultEnabled) }
    }

    fun isKnownPreset(preset: String): Boolean =
        preset == RemotePermissionPolicy.PRESET_WORKSPACE_WRITE ||
            preset == RemotePermissionPolicy.PRESET_DANGER_FULL

    // ------------------------------------------------------------ RPC mapping
    //
    // One row per method; nothing shares a prefix. `capabilityForRpcMethod`
    // returns null for unknown/unlisted methods → the caller must deny.

    private val RPC_METHOD_CAPABILITY: Map<String, String> = buildMap {
        // Metadata — always reachable, handled separately by the server.
        // "rpc.discover" intentionally absent here.

        put("debug.appInfo", DIAGNOSTICS_LIST)

        put("debug.screenshot", DEVICE_VIEW)
        put("debug.screenshot.capture", DEVICE_VIEW)
        put("debug.screenshot.list", DEVICE_VIEW)
        put("debug.screenshot.get", DEVICE_VIEW)
        put("debug.screenshot.clear", DEVICE_VIEW)
        put("debug.browser.screenshot", DEVICE_VIEW)

        put("debug.ls", SANDBOX_FS)
        put("debug.rawLs", SANDBOX_FS)
        put("debug.readFile", SANDBOX_FS)
        put("debug.writeFile", SANDBOX_FS)
        put("debug.shellExecute", SHELL)

        put("debug.tap", DEVICE_CONTROL)
        put("debug.scroll", DEVICE_CONTROL)
        put("debug.inputText", DEVICE_CONTROL)

        put("debug.viewTree", UI_INSPECT)
        put("debug.search", UI_INSPECT)
        put("debug.inspect", UI_INSPECT)
        put("debug.highlight", UI_INSPECT)

        put("debug.browser.listTabs", BROWSER_VIEW)
        put("debug.browser.pageInfo", BROWSER_VIEW)
        put("debug.browser.getReadable", BROWSER_VIEW)
        put("debug.browser.getText", BROWSER_VIEW)
        put("debug.browser.executeJS", BROWSER_EXECUTE)

        put("debug.logs.list", DIAGNOSTICS_LIST)
        put("debug.logs.read", DIAGNOSTICS_CONTENT)
        put("debug.logs.setEnabled", DIAGNOSTICS_CONTENT)
        put("debug.crash.list", DIAGNOSTICS_LIST)
        put("debug.crash.read", DIAGNOSTICS_CONTENT)
        put("debug.llmRequests", DIAGNOSTICS_CONTENT)
        put("debug.llmRequests.clear", DIAGNOSTICS_CONTENT)
        put("debug.agentTrace", DIAGNOSTICS_CONTENT)
        put("debug.fetch", DIAGNOSTICS_CONTENT)
        put("debug.cloudSync", DIAGNOSTICS_LIST)
        put("debug.permissions.list", ADMIN)

        put("debug.update.check", ADMIN)
        put("debug.update.download", ADMIN)
        put("debug.update.install", ADMIN)
        put("debug.shizuku.exec", ADMIN)
        put("debug.modelUse.exec", ADMIN)
        put("debug.sessions.exec", ADMIN)
        put("debug.minisConfig.exec", ADMIN)

        put("provider.types", PROVIDERS_READ)
        put("provider.instances.list", PROVIDERS_READ)
        put("provider.models.list", PROVIDERS_READ)
        put("provider.groups.list", PROVIDERS_READ)
        put("provider.quickTest", PROVIDERS_READ)
        put("provider.export", CREDENTIALS_EXPORT)
        put("provider.import", CREDENTIALS_EXPORT)
        put("provider.instances.create", PROVIDERS_MANAGE)
        put("provider.instances.update", PROVIDERS_MANAGE)
        put("provider.instances.delete", PROVIDERS_MANAGE)
        put("provider.instances.test", PROVIDERS_MANAGE)
        put("provider.models.add", PROVIDERS_MANAGE)
        put("provider.models.update", PROVIDERS_MANAGE)
        put("provider.models.delete", PROVIDERS_MANAGE)
        put("provider.models.refresh", PROVIDERS_MANAGE)
        put("provider.models.setAgentLoop", PROVIDERS_MANAGE)
        put("provider.groups.create", PROVIDERS_MANAGE)
        put("provider.groups.update", PROVIDERS_MANAGE)
        put("provider.groups.delete", PROVIDERS_MANAGE)
        put("provider.groups.setDefault", PROVIDERS_MANAGE)
        put("provider.groups.setSubDefault", PROVIDERS_MANAGE)
        put("provider.groups.setAgentLoop", PROVIDERS_MANAGE)

        for (m in listOf(
            "chat.sessions.list", "chat.sessions.get", "chat.sessions.usage",
            "chat.messages.list", "chat.models.list",
            "chat.prompt", "chat.uiPrompt", "chat.retry", "chat.rerunFromToolBlock",
            "chat.session.status", "chat.session.cancel", "chat.session.selectModel",
            "chat.session.selectThinkingLevel", "chat.session.delete",
            "chat.compact.before", "chat.compact.markers.list", "chat.compact.revert",
            "chat.question.pending", "chat.question.answer", "chat.search",
            "chat.feedback.put", "chat.feedback.delete", "chat.feedback.listForMessages",
        )) put(m, CHAT)

        for (m in listOf(
            "agent.goal.get", "agent.goal.set", "agent.goal.setActive",
            "agent.todo.get", "agent.todo.replace",
            "agent.plan.get", "agent.plan.set",
            "agent.deliverables.list", "agent.deliverables.clear",
            "agent.approval.list", "agent.approval.answer",
            "agent.jobs.list", "agent.jobs.cancel",
            "agent.settings.get", "agent.settings.set",
        )) put(m, AGENT_MANAGE)

        put("settings.permissionPreset.get", PERMISSION_MANAGE)
        put("settings.permissionPreset.set", PERMISSION_MANAGE)
        put("settings.capabilities.get", PERMISSION_MANAGE)
        put("settings.capabilities.set", PERMISSION_MANAGE)
        put("settings.sandbox.get", PERMISSION_MANAGE)

        for (m in listOf(
            "skills.list", "skills.get", "skills.create", "skills.importUrl",
            "skills.update", "skills.toggle", "skills.delete",
        )) put(m, SKILLS_MANAGE)

        for (m in listOf(
            "memory.files.list", "memory.files.read", "memory.files.write",
            "memory.files.delete", "memory.globalToggle", "memory.setGlobalEnabled",
            "soul.get", "soul.save",
        )) put(m, MEMORY_MANAGE)

        for (m in listOf(
            "mcp.list", "mcp.get", "mcp.create", "mcp.update", "mcp.import",
            "mcp.importUrl", "mcp.toggle", "mcp.delete",
        )) put(m, MCP_MANAGE)

        for (m in listOf(
            "environments.list", "environments.create", "environments.update", "environments.delete",
        )) put(m, ENVIRONMENTS_MANAGE)

        for (m in listOf(
            "storage.shared.list", "storage.mounts.list", "storage.mounts.rename",
            "storage.mounts.setWritable", "storage.mounts.remove",
        )) put(m, STORAGE_MANAGE)

        for (m in listOf(
            "scheduled.list", "scheduled.get", "scheduled.create", "scheduled.update",
            "scheduled.toggle", "scheduled.delete", "scheduled.run", "scheduled.runs",
        )) put(m, SCHEDULED_MANAGE)
    }

    /** Capability guarding an RPC method, or null when the method is not mapped (deny). */
    fun capabilityForRpcMethod(method: String): String? = RPC_METHOD_CAPABILITY[method]

    /** Methods reachable even before capability checks (pure metadata). */
    fun isUnconditionalRpcMethod(method: String): Boolean = method == "rpc.discover"

    // ------------------------------------------------------------ HTTP routes

    /** Capability guard for direct /api-prefixed routes; null means "no capability gate". */
    fun capabilityForHttpRoute(method: String, path: String): String? = when (path) {
        "/api/status" -> null
        "/api/settings" -> if (method == "GET") null else SERVICE_MANAGE
        "/api/auth/status", "/api/auth/login", "/api/auth/logout" -> null
        "/api/settings/restart" -> SERVICE_MANAGE
        "/api/sessions", "/api/messages", "/api/models", "/api/usage", "/api/respond" -> CHAT
        "/api/session/status", "/api/session/model", "/api/session/thinking",
        "/api/session/delete", "/api/session/title", "/api/session/new" -> CHAT
        "/api/prompt", "/api/cancel", "/api/compact" -> CHAT
        "/api/files" -> FILES_READ
        "/api/file" -> if (method == "GET") FILES_READ else FILES_WRITE
        "/api/edit" -> FILES_WRITE
        "/api/shell" -> SHELL
        "/api/permissions" -> PERMISSION_MANAGE
        "/api/events/session", "/api/events.mux", "/api/events.host" -> CHAT
        else -> null // unknown route → handled by the 404 path
    }

    // -------------------------------------------------------------- DSH unary

    /**
     * Capability guard for one DSH unary method. Payload-aware for settings
     * namespaces: the wide `settings.update/replace/mutate` method must not
     * grant the whole settings family when only the permission namespace is
     * dangerous.
     */
    fun capabilityForDshRequest(method: String, payload: JSONObject?): String? = when (method) {
        "session.list", "session.create", "session.history", "session.models",
        "session.selectModel", "session.rename", "session.fork", "session.prompt",
        "session.attachment", "session.updateQueue", "session.cancel", "session.search",
        "subagent.list", "subagent.history", "subagent.prompt", "subagent.interrupt" -> CHAT

        "host.describe", "host.listDirectory", "host.pickDirectory" -> FILES_READ
        "host.createDirectory", "host.openPath" -> FILES_WRITE

        "workspace.list" -> FILES_READ
        "workspace.create", "workspace.rename", "workspace.delete",
        "workspace.insertBefore", "workspace.insertSessionBefore", "workspace.archiveSession" -> FILES_WRITE

        "skill.list" -> SKILLS_MANAGE

        "agentPreset.list", "agentPreset.select", "agentPreset.read",
        "agentPreset.copy", "agentPreset.openDocument", "agentPreset.remove" -> AGENT_MANAGE

        "goal.create", "goal.edit", "goal.pause", "goal.resume", "goal.complete", "goal.clear" -> AGENT_MANAGE

        // DSH generic Connection RPC methods (slash endpoint path). Each maps
        // to exactly one capability; unlisted endpoints stay denied.
        "commands/list", "commands/execute" -> CHAT
        "messageFeedback/list", "messageFeedback/put", "messageFeedback/delete" -> CHAT
        "goals/create", "goals/edit", "goals/pause", "goals/resume", "goals/complete", "goals/clear" -> AGENT_MANAGE

        "settings.describe" -> PREFERENCES
        "settings.openDocument" -> PREFERENCES
        "settings.update", "settings.replace", "settings.mutate" -> {
            when (payload?.optString("ns", "")) {
                "permission" -> PERMISSION_MANAGE
                "agent-presets" -> AGENT_MANAGE
                else -> PREFERENCES
            }
        }

        "credentials.describe" -> PROVIDERS_READ
        "credentials.set", "credentials.unset" -> CREDENTIALS_EXPORT

        "llm.providers", "llm.models", "llm.discoverModels" -> PROVIDERS_READ

        else -> null // unlisted → deny
    }

    // --------------------------------------------------------------- helpers

    fun toJson(cap: RemoteCapability, enabled: Boolean): JSONObject = JSONObject().apply {
        put("id", cap.id)
        put("label", cap.label)
        put("description", cap.description)
        put("risk", cap.risk.name)
        put("riskLabel", cap.risk.label)
        put("defaultEnabled", cap.defaultEnabled)
        put("enabled", enabled)
    }

    fun capabilitiesJson(state: Map<String, Boolean>): JSONArray = JSONArray().apply {
        for (cap in ALL) put(toJson(cap, state[cap.id] ?: cap.defaultEnabled))
    }
}
