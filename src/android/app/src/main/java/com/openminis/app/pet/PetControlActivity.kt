package com.openminis.app.pet

import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pet settings, rendered with the app's own Compose theme so it does not look
 * like a different application bolted on.
 *
 * State is re-read wholesale in [refresh] on every mutation and in onResume —
 * the overlay service owns the pet at runtime, and the overlay permission can
 * be changed from outside the app entirely, so nothing here may assume its
 * cached snapshot is still true.
 */
class PetControlActivity : ComponentActivity() {

    private var uiState by mutableStateOf(PetUiState())

    private val importPet =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            toast("正在导入并校验…")
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    PetPackageManager.importZip(this@PetControlActivity, uri)
                }
                result.onSuccess { pet ->
                    // First pet installed becomes the selected one; otherwise the
                    // user would import a pack and still see "未选择".
                    if (PetPreferences.selectedPetId(this@PetControlActivity) == null) {
                        PetPreferences.setSelectedPetId(this@PetControlActivity, pet.manifest.id)
                    }
                    toast("已导入：${pet.manifest.displayName}")
                    refresh()
                    reloadOverlay()
                }.onFailure {
                    toast("导入失败：${it.message}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            MinisTheme {
                PetControlScreen(state = uiState, actions = buildActions())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // The overlay permission is granted in a separate system screen. If
        // the user enabled the pet first, the service deliberately stopped
        // rather than showing a misleading forever-notification; this return
        // path is the reliable retry after the grant (and after OEM eviction).
        if (uiState.enabled && uiState.hasOverlayPermission) {
            PetBridge.startIfEnabled(this)
        }
    }

    private fun buildActions() = PetActions(
        onBack = { finish() },
        onToggleEnabled = { enabled ->
            if (enabled && PetPackageManager.selected(this) == null) {
                toast("先导入并选择一个宠物")
                return@PetActions
            }
            PetPreferences.setEnabled(this, enabled)
            if (enabled) {
                if (!Settings.canDrawOverlays(this)) toast("还需要悬浮窗权限")
                PetBridge.startIfEnabled(this)
            } else {
                PetBridge.stop(this)
            }
            refresh()
        },
        onSelectPet = { pet ->
            PetPreferences.setSelectedPetId(this, pet.manifest.id)
            refresh()
            reloadOverlay()
        },
        onImport = {
            // Some file providers only offer the pack under a generic type, so
            // the filter stays broad rather than rejecting a valid ZIP outright.
            importPet.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        },
        onScale = { value ->
            PetPreferences.setScale(this, value)
            refresh()
        },
        onSpeed = { value ->
            PetPreferences.setSpeed(this, value)
            refresh()
        },
        onCommitAppearance = { reloadOverlay() },
        onWander = { value ->
            PetPreferences.setWanderEnabled(this, value)
            refresh()
            reloadOverlay()
        },
        onEdgeSnap = { value ->
            PetPreferences.setEdgeSnapEnabled(this, value)
            refresh()
            reloadOverlay()
        },
        onAutoHide = { value ->
            PetPreferences.setAutoHideEnabled(this, value)
            refresh()
            reloadOverlay()
        },
        onBubble = { value ->
            PetPreferences.setBubbleEnabled(this, value)
            refresh()
            reloadOverlay()
        },
        onTapOpensApp = { value ->
            PetPreferences.setTapOpensApp(this, value)
            refresh()
        },
    )

    private fun refresh() {
        uiState = PetUiState(
            pets = PetPackageManager.listInstalled(this),
            selected = PetPackageManager.selected(this),
            enabled = PetPreferences.isEnabled(this),
            hasOverlayPermission = Settings.canDrawOverlays(this),
            scale = PetPreferences.scale(this),
            speed = PetPreferences.speed(this),
            wander = PetPreferences.wanderEnabled(this),
            edgeSnap = PetPreferences.edgeSnapEnabled(this),
            autoHide = PetPreferences.autoHideEnabled(this),
            bubble = PetPreferences.bubbleEnabled(this),
            tapOpensApp = PetPreferences.tapOpensApp(this),
        )
    }

    /** Behaviour and appearance are read when the overlay is built, so changes
     *  only take effect after the service rebuilds its window. */
    private fun reloadOverlay() {
        if (!PetPreferences.isEnabled(this)) return
        PetBridge.reload(this)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
