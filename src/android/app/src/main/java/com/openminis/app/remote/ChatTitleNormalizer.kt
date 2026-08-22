package com.openminis.app.remote

import org.json.JSONObject

/**
 * Pure normalization for session titles crossing the Web boundary.
 *
 * The native DB stores nullable titles; JSONObject.NULL on the wire becomes a
 * JS `null`, which the DSH frontend renders as the literal string "null" for
 * `title: null` projections. Normalizing once here (null/blank → "") lets
 * every consumer (chat.* RPC, /api/session/status, DSH projections) use
 * `isNotEmpty()`-style checks instead of null chains.
 */
object ChatTitleNormalizer {

    /** null, JSONObject.NULL or blank → "" ; otherwise trimmed string. */
    fun normalize(value: Any?): String = when {
        value == null || value == JSONObject.NULL -> ""
        value is String -> value.trim()
        else -> value.toString().trim()
    }

    /** True when the value would not render as an empty title on the wire. */
    fun hasContent(value: Any?): Boolean = normalize(value).isNotEmpty()
}
