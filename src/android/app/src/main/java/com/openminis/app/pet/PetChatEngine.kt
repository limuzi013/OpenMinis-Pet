package com.openminis.app.pet

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot question answering for the floating pet.
 *
 * This deliberately talks to the configured model DIRECTLY instead of running
 * the agent loop: the bubble can only carry a sentence or two, and an agent
 * turn may run tools for minutes with nothing meaningful to show meanwhile.
 * So the pet can chat and summarise, but cannot execute anything — real work
 * still belongs in a normal session inside the app.
 *
 * Replies are squeezed from both ends: the system prompt asks for one or two
 * sentences, and [condense] enforces it regardless of what the model does,
 * because a model that ignores the instruction would otherwise render a wall
 * of text over the user's screen.
 */
internal class PetChatEngine(private val context: Context) {

    companion object {
        private const val SYSTEM_PROMPT =
            "你是用户手机桌面上的一只小宠物助手，回答会显示在一个很小的气泡里。\n" +
                "严格遵守：\n" +
                "1. 最多两句话，总共不超过 60 个字。\n" +
                "2. 不要用 Markdown、不要列点、不要代码块、不要表情符号堆砌。\n" +
                "3. 直接给结论，不要复述问题，不要说「好的」「当然」这类开场白。\n" +
                "4. 不知道就直说不知道，不要编造。"

        /** Hard ceiling for what a bubble can show before it covers the screen. */
        private const val MAX_REPLY_CHARS = 80

        /** How many past turns to carry; keeps context without bloating the request. */
        private const val HISTORY_TURNS = 3
    }

    private val history = ArrayDeque<LLMMessage>()

    fun clearHistory() = history.clear()

    /**
     * Ask the default model. Runs on a background dispatcher chosen by the
     * caller — this method blocks on network I/O.
     */
    suspend fun ask(question: String): Result<String> = try {
        val app = context.applicationContext as? MinisApp
            ?: error("应用尚未初始化")
        val repo = app.providerRepositoryOrNull
            ?: error("模型配置尚未加载完成")

        // ProviderRepository starts with an EMPTY placeholder config and loads
        // the persisted one off-thread (see its _config comment). Reading
        // config.value directly can therefore land in that window and look
        // exactly like "user has no models configured" — which is what the pet
        // used to report even with everything set up. The overlay hits this far
        // more often than the UI does: it is a foreground service that outlives
        // the app process, so it frequently asks right after a fresh rebuild.
        withTimeoutOrNull(8_000L) { repo.awaitConfigLoaded() }
            ?: error("模型配置加载超时，稍后再试")

        val config = repo.config.value

        // Prefer the user's default group, but fall back to any usable model
        // rather than refusing to answer: a config with models but no default
        // group bound is a perfectly normal state, and the pet refusing there
        // reads as "my API key is broken" when nothing is broken.
        val entry = resolveEntry(repo, config)
            ?: error("没有可用的模型，请先在 App 里添加供应商并选择模型")

        val apiKey = repo.loadApiKey(entry.providerInstanceId)
            ?: error("这个模型还没配置 API Key")
        val instance = repo.instance(entry.providerInstanceId)
            ?: error("供应商实例不存在")

        val provider = ProviderFactory.create(instance, apiKey, entry.model, context)
        val messages = history.toList() + LLMMessage(role = LLMMessage.Role.USER, content = question)
        // The pet intentionally is not an Agent turn, but it is still a real
        // user turn. Record it before the network call so App history never
        // claims that a failed/cancelled pet question was never asked. A later
        // successful reply is appended to the same ordinary chat session.
        val persistedSessionId = try {
            persistUserTurn(app, entry, question)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("PetChatEngine", "could not persist pet question: ${t.message}")
            null
        }

        val response = withTimeoutOrNull(60_000L) {
            // Transient network/5xx failures retried with jittered backoff
            // (DeepSeek Harness LLMRetryPolicy) before the pet reports an error.
            com.openminis.app.provider.LLMRetryPolicy.withRetry {
                provider.sendMessage(
                    messages = messages,
                    systemPrompt = SYSTEM_PROMPT,
                    // A reasoning model needs headroom to finish thinking before it can
                    // emit even a short answer, the same budget shape title-gen uses.
                    maxTokens = if (entry.model.supportsReasoning == true) 2048 else 400,
                    // null, not a number — the gpt-5.x family rejects any temperature
                    // other than 1 and would 400 the whole request.
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                )
            }
        } ?: error("模型响应超时，请稍后再试")

        val raw = response.text.trim()
        if (raw.isEmpty()) error("模型没有返回内容")

        val reply = condense(raw)
        remember(question, reply)
        if (persistedSessionId != null) {
            try {
                persistAssistantTurn(app, persistedSessionId, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("PetChatEngine", "could not persist pet reply: ${t.message}")
            }
        }
        // The try block must itself yield a Result for the expression to type
        // as Result<String> alongside the failure branch.
        Result.success(reply)
    } catch (e: CancellationException) {
        // A cancelled ask() (superseded by a newer one) must keep propagating
        // instead of running onFailure over the new request's UI.
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    /**
     * Mirror the exchange into a real chat session, so pet conversations show
     * up in the app's session list rather than disappearing with the bubble.
     *
     * Everything lands in one long-lived session ("桌面宠物") instead of one
     * session per question — a list flooded with single-line sessions would be
     * worse than no history at all.
     */
    private suspend fun persistUserTurn(
        app: MinisApp,
        entry: com.openminis.app.data.model.ModelEntry,
        question: String,
    ): String? {
        val chat = app.chatRepositoryOrNull ?: return null
        val existing = PetPreferences.chatSessionId(context)
            ?.takeIf { chat.getSession(it) != null }
        val sessionId = existing ?: chat.createSession(
            modelId = entry.model.id,
            title = "桌面宠物",
        ).id.also { PetPreferences.setChatSessionId(context, it) }

        chat.appendMessage(sessionId, "user", textParts(question))
        return sessionId
    }

    private suspend fun persistAssistantTurn(
        app: MinisApp,
        sessionId: String,
        reply: String,
    ) {
        val chat = app.chatRepositoryOrNull ?: return
        chat.appendMessage(sessionId, "assistant", textParts(reply))
    }

    /** Message body shape used across the app: a single text part. */
    private fun textParts(text: String): String =
        org.json.JSONArray()
            .put(org.json.JSONObject().put("type", "text").put("value", text))
            .toString()

    /**
     * Pick the model to ask: the default group's first member, else the first
     * non-hidden entry that has a provider instance behind it.
     */
    private fun resolveEntry(
        repo: com.openminis.app.data.repository.ProviderRepository,
        config: com.openminis.app.data.model.ProviderConfig,
    ): com.openminis.app.data.model.ModelEntry? {
        val fromGroup = config.defaultPrimaryGroupId
            ?.let { repo.group(it) }
            ?.memberEntryIds
            ?.firstNotNullOfOrNull { id -> config.modelEntries.firstOrNull { it.id == id } }
        if (fromGroup != null) return fromGroup

        return config.modelEntries.firstOrNull { candidate ->
            !candidate.isHidden && repo.instance(candidate.providerInstanceId) != null
        }
    }

    private fun remember(question: String, reply: String) {
        history.addLast(LLMMessage(role = LLMMessage.Role.USER, content = question))
        history.addLast(LLMMessage(role = LLMMessage.Role.ASSISTANT, content = reply))
        while (history.size > HISTORY_TURNS * 2) {
            history.removeFirst()
        }
    }

    /**
     * Squeeze a reply down to what a bubble can actually display.
     *
     * Cuts at a sentence boundary when there is one inside the budget, so the
     * result reads as a finished thought rather than a truncation.
     */
    private fun condense(text: String): String {
        val flat = text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_#>`]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (flat.length <= MAX_REPLY_CHARS) return flat

        val cut = takeByCodePoints(flat, MAX_REPLY_CHARS)
        val lastStop = cut.indexOfLast { it in "。！？!?." }
        return if (lastStop >= MAX_REPLY_CHARS / 3) {
            cut.substring(0, lastStop + 1)
        } else {
            cut.trimEnd() + "…"
        }
    }

    /**
     * Truncate by Unicode code points so an emoji surrogate pair is never split;
     * backs off one char if the cut would land between a pair.
     */
    private fun takeByCodePoints(text: String, maxCodePoints: Int): String {
        var end = 0
        var count = 0
        while (end < text.length && count < maxCodePoints) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        // Defensive: never leave a dangling half of a surrogate pair behind.
        if (end in 1 until text.length && Character.isLowSurrogate(text[end])) {
            end--
        }
        return text.substring(0, end)
    }
}
