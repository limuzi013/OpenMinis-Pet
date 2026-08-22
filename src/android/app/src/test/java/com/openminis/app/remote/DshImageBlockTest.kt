package com.openminis.app.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-Web image wire contract（追加任务 #1/#5/#6/#8）。
 *
 * Pins the two failure modes fixed in 1.01-beta:
 *  1. `session.attachment` `data` must be a base64 STRING — the DSH schema is
 *     `data: string()` (client.js:5437) and the runtime decodes it with
 *     `atob()` (client.js:7271); a byte array fails the zod parse and atob.
 *  2. Image blocks are emitted only when the translator receives a non-null
 *     context; no caller may translate events without one, or refresh loses
 *     every image (live AND history).
 */
class DshImageBlockTest {

    @Test
    fun `attachment data is standard base64 that atob can decode`() {
        val bytes = byteArrayOf(0, 1, 2, 127, -1, 10, 9, 8)
        val data = DshApiAdapter.encodeAttachmentData(bytes)
        // 标准 base64 字母表 — atob() 对其它字符抛 InvalidCharacterError。
        assertTrue("not atob-compatible base64: $data", data.matches(Regex("^[A-Za-z0-9+/]*={0,2}$")))
        val roundTrip = java.util.Base64.getDecoder().decode(data)
        assertTrue("round-trip mismatch", bytes.contentEquals(roundTrip))
    }

    @Test
    fun `imageAttachmentProto matches imageAttachmentRefSchema shape`() {
        val proto = DshApiAdapter.imageAttachmentProto(
            attachmentId = "ref-42",
            mediaType = "image/png",
            bytes = 4096,
            width = 1280,
            height = 720,
            name = "截图.png",
        )
        assertEquals("ref-42", proto.getString("attachmentId"))
        assertEquals("image/png", proto.getString("mediaType"))
        assertEquals(4096L, proto.getLong("bytes"))
        assertEquals(1280, proto.getInt("width"))
        assertEquals(720, proto.getInt("height"))
        assertEquals("截图.png", proto.getString("name"))
    }

    @Test
    fun `imageAttachmentProto name optional and integers stay positive`() {
        val proto = DshApiAdapter.imageAttachmentProto(
            attachmentId = "ref-7",
            mediaType = "image/jpeg",
            bytes = 0,
            width = -1,
            height = 0,
            name = null,
        )
        assertFalse("name must be optional", proto.has("name"))
        assertEquals(1L, proto.getLong("bytes"))
        assertEquals(1, proto.getInt("width"))
        assertEquals(1, proto.getInt("height"))
    }

    @Test
    fun `image block shape nests attachment inside type image`() {
        val proto = DshApiAdapter.imageAttachmentProto(
            attachmentId = "ref-9",
            mediaType = "image/webp",
            bytes = 100,
            width = 2,
            height = 2,
            name = null,
        )
        val block = JSONObject().put("type", "image").put("attachment", proto)
        assertEquals("image", block.getString("type"))
        assertEquals(proto, block.getJSONObject("attachment"))
    }

    @Test
    fun `resolveImageRefs emits nothing without context - the live and history trap`() {
        val message = JSONObject().put("imageRefs", JSONArray().put(
            JSONObject()
                .put("id", "ref-1")
                .put("relativePath", "s1/a.png")
                .put("mimeType", "image/png"),
        ))
        assertNull("context==null must not fabricate metadata", DshApiAdapter.resolveImageRefs(null, "s1", message))
    }

    @Test
    fun `resolveImageRefs emits nothing for empty refs`() {
        val message = JSONObject().put("imageRefs", JSONArray())
        assertNull(DshApiAdapter.resolveImageRefs(null, "s1", message))
    }

    @Test
    fun `resolveImageRefs returns null when message has no imageRefs`() {
        assertNull(DshApiAdapter.resolveImageRefs(null, "s1", JSONObject().put("content", "hi")))
    }
}
