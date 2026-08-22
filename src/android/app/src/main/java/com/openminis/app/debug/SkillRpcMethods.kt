package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * `skills.*` RPC handlers shared by the debug server and Web Remote.
 * Mutations deliberately go through [SkillRepository], the same source of
 * truth as Settings, so Web edits are visible to native chat immediately.
 */
internal object SkillRpcMethods {

    private fun repo(context: Context): SkillRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).skillRepository

    fun list(context: Context): JSONObject {
        val skills = repo(context).skills.value
        val arr = JSONArray()
        for (s in skills) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("description", s.description)
                put("version", s.version)
                put("importSource", s.importSource.value)
                put("sourceURL", s.sourceURL ?: JSONObject.NULL)
                put("isEnabled", s.isEnabled)
                put("installedAt", s.installedAt)
                put("updatedAt", s.updatedAt)
                put("useCount", s.useCount)
            })
        }
        return JSONObject().put("skills", arr)
    }

    fun get(context: Context, params: JSONObject): JSONObject {
        val skillId = params.optString("skillId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'skillId' param")
        }
        val r = repo(context)
        val skill = r.skills.value.find { it.id == skillId }
            ?: throw RPCException(-32602, "Skill not found: $skillId")
        return JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("version", skill.version)
            put("importSource", skill.importSource.value)
            put("sourceURL", skill.sourceURL ?: JSONObject.NULL)
            put("isEnabled", skill.isEnabled)
            put("installedAt", skill.installedAt)
            put("updatedAt", skill.updatedAt)
            put("useCount", skill.useCount)
            put("body", skill.body)
        }
    }

    fun create(context: Context, params: JSONObject): JSONObject {
        val name = params.optString("name", "").trim().ifEmpty {
            throw RPCException(-32602, "Missing 'name' param")
        }
        val body = params.optString("body", "")
        val description = params.optString("description", "").trim()
        val version = params.optString("version", "1.0.0").trim().ifEmpty { "1.0.0" }
        val skill = repo(context).add(
            name = name,
            description = description,
            body = body,
            version = version,
            source = SkillRepository.ImportSource.FILE,
        ) ?: throw RPCException(-32602, "A skill with this name already exists or the name is invalid")
        return JSONObject().put("skill", skillToJson(skill, includeBody = true))
    }

    suspend fun importUrl(context: Context, params: JSONObject): JSONObject {
        val url = params.optString("url", "").trim().ifEmpty {
            throw RPCException(-32602, "Missing 'url' param")
        }
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase().orEmpty() }
            .getOrDefault("")
        val r = repo(context)
        val skill = if (host == "github.com" || host == "www.github.com" ||
            host == "raw.githubusercontent.com" || host == "gist.github.com" ||
            host == "gist.githubusercontent.com"
        ) {
            r.importFromGitHub(url)
        } else {
            val content = SafeRemoteImporter.downloadText(url, maxBytes = 512 * 1024)
            r.importFromContent(content, SkillRepository.ImportSource.URL, sourceURL = url)
        } ?: throw RPCException(
            -32602,
            "The URL did not provide a valid SKILL.md (YAML frontmatter with a name is required)",
        )
        return JSONObject().put("skill", skillToJson(skill, includeBody = true))
    }

    fun update(context: Context, params: JSONObject): JSONObject {
        val skillId = params.optString("skillId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'skillId' param")
        }
        val r = repo(context)
        val current = r.skills.value.find { it.id == skillId }
            ?: throw RPCException(-32602, "Skill not found: $skillId")
        val name = if (params.has("name")) params.optString("name").trim().ifEmpty {
            throw RPCException(-32602, "Skill name cannot be empty")
        } else null
        val description = if (params.has("description")) params.optString("description") else null
        val body = if (params.has("body")) params.optString("body") else null
        if (!r.update(skillId, name = name, description = description, body = body)) {
            throw RPCException(-32000, "Failed to update skill: $skillId")
        }
        val updated = r.skills.value.find { it.id == skillId } ?: current
        return JSONObject().put("skill", skillToJson(updated, includeBody = true))
    }

    fun toggle(context: Context, params: JSONObject): JSONObject {
        val skillId = params.optString("skillId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'skillId' param")
        }
        if (!params.has("enabled")) {
            throw RPCException(-32602, "Missing 'enabled' param")
        }
        val enabled = params.optBoolean("enabled", true)
        val r = repo(context)
        if (r.skills.value.none { it.id == skillId }) {
            throw RPCException(-32602, "Skill not found: $skillId")
        }
        r.setEnabled(skillId, enabled)
        return JSONObject().put("ok", true)
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val skillId = params.optString("skillId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'skillId' param")
        }
        val r = repo(context)
        if (r.skills.value.none { it.id == skillId }) {
            throw RPCException(-32602, "Skill not found: $skillId")
        }
        r.delete(skillId)
        return JSONObject().put("ok", true)
    }

    private fun skillToJson(skill: SkillRepository.Skill, includeBody: Boolean): JSONObject =
        JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("version", skill.version)
            put("importSource", skill.importSource.value)
            put("sourceURL", skill.sourceURL ?: JSONObject.NULL)
            put("isEnabled", skill.isEnabled)
            put("installedAt", skill.installedAt)
            put("updatedAt", skill.updatedAt)
            put("useCount", skill.useCount)
            if (includeBody) put("body", skill.body)
        }
}
