package com.openminis.app.remote

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.MinisApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level checks for the SharedPreferences-capability store:
 * per-item isolation, legacy preset migration/compat and the
 * "permission.manage off ⇒ no writes" server rule (via SettingsRpcMethods).
 */
@RunWith(AndroidJUnit4::class)
class RemotePermissionPolicyInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<MinisApp>()

    @Before
    fun snapshot() {
        RemotePermissionPolicy.setPreset(context, RemotePermissionPolicy.PRESET_WORKSPACE_WRITE)
    }

    @After
    fun restore() {
        RemotePermissionPolicy.setPreset(context, RemotePermissionPolicy.PRESET_WORKSPACE_WRITE)
    }

    @Test
    fun flippingOneCapabilityLeavesEveryOtherUntouched() {
        RemotePermissionPolicy.setCapability(context, RemoteCapabilityCatalog.DEVICE_CONTROL, true)
        val state = RemotePermissionPolicy.capabilityState(context)
        assertEquals(true, state[RemoteCapabilityCatalog.DEVICE_CONTROL])
        for ((id, enabled) in RemoteCapabilityCatalog.defaultState()) {
            if (id == RemoteCapabilityCatalog.DEVICE_CONTROL) continue
            assertEquals("$id must stay at default", enabled, state[id])
        }
    }

    @Test
    fun unknownCapabilityWritesAreRejected() {
        assertFalse(RemotePermissionPolicy.setCapability(context, "not.a.real.capability", true))
    }

    @Test
    fun presetDangerFullAccessEnablesEveryCapability() {
        assertTrue(RemotePermissionPolicy.setPreset(context, RemotePermissionPolicy.PRESET_DANGER_FULL))
        val state = RemotePermissionPolicy.capabilityState(context)
        assertTrue(state.values.all { it })
        assertEquals(RemotePermissionPolicy.PRESET_DANGER_FULL, RemotePermissionPolicy.preset(context))
    }

    @Test
    fun presetWorkspaceWriteResetsToCatalogDefaults() {
        RemotePermissionPolicy.setCapability(context, RemoteCapabilityCatalog.DEVICE_VIEW, true)
        RemotePermissionPolicy.setPreset(context, RemotePermissionPolicy.PRESET_WORKSPACE_WRITE)
        val state = RemotePermissionPolicy.capabilityState(context)
        assertEquals(RemoteCapabilityCatalog.defaultState(), state)
    }

    @Test
    fun permissionManageOffBlocksCapabilityWrites() {
        RemotePermissionPolicy.setCapability(context, RemoteCapabilityCatalog.PERMISSION_MANAGE, false)
        val thrown = runCatching {
            com.openminis.app.debug.SettingsRpcMethods.capabilitiesSet(
                context,
                org.json.JSONObject().put("capability", RemoteCapabilityCatalog.PERMISSION_MANAGE).put("enabled", true),
            )
        }.exceptionOrNull()
        assertTrue("capabilitiesSet must refuse writes when permission.manage is off", thrown != null)
        assertFalse(RemotePermissionPolicy.allowsCapability(context, RemoteCapabilityCatalog.PERMISSION_MANAGE))
    }
}
