package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidApkInspectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `discovers only real Gradle apk output directories newest first`() {
        val root = temp.newFolder("project")
        val oldApk = File(root, "feature/build/outputs/apk/debug/feature-debug.apk").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)); setLastModified(1_000L)
        }
        val newest = File(root, "app/build/outputs/apk/demo/debug/app-demo-debug.apk").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(2)); setLastModified(2_000L)
        }
        File(root, "downloads/not-a-gradle-output.apk").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(3)); setLastModified(3_000L)
        }
        val discovered = AndroidApkInspector.discover(root)
        assertEquals(listOf(newest.canonicalPath, oldApk.canonicalPath), discovered.map { it.canonicalPath })
    }

    @Test
    fun `empty or unrelated project has no guessed app debug path`() {
        val root = temp.newFolder("empty")
        assertTrue(AndroidApkInspector.discover(root).isEmpty())
    }
}
