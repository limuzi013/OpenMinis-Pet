package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {
    @Test
    fun `pet build and matching release normalize identically`() {
        assertEquals("1.12.pet.15", UpdateChecker.normalizeTag("1.12-pet.15-SNAPSHOT"))
        assertEquals("1.12.pet.15", UpdateChecker.normalizeTag("v1.12-pet.15"))
    }

    @Test
    fun `pet counter remains part of update ordering`() {
        val local = UpdateChecker.normalizeTag("1.12-pet.15-SNAPSHOT")
        val older = UpdateChecker.normalizeTag("v1.12-pet.14")
        val newer = UpdateChecker.normalizeTag("v1.12-pet.16")

        assertTrue(UpdateChecker.compareVersions(local, older) > 0)
        assertTrue(UpdateChecker.compareVersions(newer, local) > 0)
        assertEquals(0, UpdateChecker.compareVersions(local, UpdateChecker.normalizeTag("v1.12-pet.15")))
    }

    @Test
    fun `ordinary labels stay equal to their numeric base and legacy rc is old`() {
        assertEquals("1.12.3", UpdateChecker.normalizeTag("v1.12.3-preview"))
        assertEquals("1.12.3", UpdateChecker.normalizeTag("1.12.3-rc1"))
        assertTrue(
            UpdateChecker.compareVersions(
                UpdateChecker.normalizeTag("v1.12-pet.15"),
                UpdateChecker.normalizeTag("rc9"),
            ) > 0,
        )
    }
}
