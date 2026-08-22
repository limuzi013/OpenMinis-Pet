package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.remote.CloudflareTunnelManager
import com.openminis.app.remote.RemoteAccessPrefs
import com.openminis.app.remote.RemoteCapabilityCatalog
import com.openminis.app.remote.RemotePermissionPolicy
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.remote.RemoteAccessService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Web Remote settings built from the same grouped-card primitives as the app. */
@Composable
fun RemoteAccessSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tunnelStatus by CloudflareTunnelManager.status.collectAsState()

    var enabled by remember { mutableStateOf(RemoteAccessPrefs.isEnabled(context)) }
    var portText by remember { mutableStateOf(RemoteAccessPrefs.port(context).toString()) }
    var lanAccess by remember { mutableStateOf(RemoteAccessPrefs.lanAccessEnabled(context)) }
    var username by remember { mutableStateOf(RemoteAccessPrefs.username(context)) }
    var passwordConfigured by remember { mutableStateOf(RemoteAccessPrefs.hasPassword(context)) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var tunnelEnabled by remember { mutableStateOf(RemoteAccessPrefs.cloudflareTunnelEnabled(context)) }
    var tunnelToken by remember { mutableStateOf("") }
    var tunnelTokenConfigured by remember { mutableStateOf(RemoteAccessPrefs.hasCloudflareTunnelToken(context)) }
    var hostname by remember { mutableStateOf(RemoteAccessPrefs.cloudflareHostname(context)) }
    var apiToken by remember { mutableStateOf(RemoteAccessPrefs.token(context)) }
    var permissionPreset by remember { mutableStateOf(RemotePermissionPolicy.preset(context)) }
    var capabilityState by remember { mutableStateOf(RemotePermissionPolicy.capabilityState(context)) }
    var pendingDangerConfirm by remember { mutableStateOf(false) }
    // Refreshed on ON_RESUME below so a Wi-Fi switch updates the shown IP.
    var lanIp by remember { mutableStateOf(localIpv4Address() ?: "<phone-ip>") }

    LaunchedEffect(Unit) { CloudflareTunnelManager.refresh(context) }

    // Re-read persisted state whenever the screen comes back to the
    // foreground: the notification-bar "Stop" action and the Web /api/settings
    // PATCH both write prefs behind this screen's back, and the remember{}
    // snapshots above would otherwise keep showing stale switches.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = RemoteAccessPrefs.isEnabled(context)
                portText = RemoteAccessPrefs.port(context).toString()
                lanAccess = RemoteAccessPrefs.lanAccessEnabled(context)
                username = RemoteAccessPrefs.username(context)
                passwordConfigured = RemoteAccessPrefs.hasPassword(context)
                tunnelEnabled = RemoteAccessPrefs.cloudflareTunnelEnabled(context)
                tunnelTokenConfigured = RemoteAccessPrefs.hasCloudflareTunnelToken(context)
                hostname = RemoteAccessPrefs.cloudflareHostname(context)
                permissionPreset = RemotePermissionPolicy.preset(context)
                capabilityState = RemotePermissionPolicy.capabilityState(context)
                lanIp = localIpv4Address() ?: "<phone-ip>"
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun restartIfEnabled() {
        if (enabled) RemoteAccessService.restart(context)
    }

    SettingsScaffold(title = "Web 远程控制", onBack = onBack) {
        SettingsSection(
            header = "服务",
            footer = "默认只监听 127.0.0.1。使用 Cloudflare Tunnel 时不需要打开局域网访问，也不需要公网 IP。",
        ) {
            SettingsSwitchRow(
                title = "启用 Web 远程控制",
                subtitle = if (passwordConfigured) "运行带登录认证的浏览器控制服务" else "请先在下面设置登录密码",
                checked = enabled,
                icon = Icons.Outlined.Language,
                onCheckedChange = { value ->
                    if (value && !passwordConfigured) {
                        toast("先设置至少 10 位登录密码")
                        return@SettingsSwitchRow
                    }
                    enabled = value
                    RemoteAccessPrefs.setEnabled(context, value)
                    if (value) RemoteAccessService.start(context) else RemoteAccessService.stop(context)
                },
            )
            SettingsSwitchRow(
                title = "局域网访问",
                subtitle = if (lanAccess) "监听 0.0.0.0，可通过 http://$lanIp:${RemoteAccessPrefs.port(context)} 访问" else "关闭时仅本机和 Cloudflare Connector 可访问",
                checked = lanAccess,
                icon = Icons.Outlined.Router,
                onCheckedChange = { value ->
                    lanAccess = value
                    RemoteAccessPrefs.setLanAccessEnabled(context, value)
                    restartIfEnabled()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = "登录",
            footer = "浏览器使用用户名 + 密码登录，成功后只保存 HttpOnly 会话 Cookie。模型供应商 API Key 不会下发到网页。",
        ) {
            SettingsCardBlock {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(64) },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(if (passwordConfigured) "新密码（留空则不修改）" else "登录密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("确认密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = {
                        val cleanUser = username.trim()
                        if (cleanUser.length < 3) {
                            toast("用户名至少 3 个字符")
                        } else if (!passwordConfigured && newPassword.length < 10) {
                            toast("密码至少 10 个字符")
                        } else if (newPassword.isNotEmpty() && newPassword.length < 10) {
                            toast("密码至少 10 个字符")
                        } else if (newPassword != confirmPassword) {
                            toast("两次输入的密码不一致")
                        } else {
                            runCatching {
                                RemoteAccessPrefs.setUsername(context, cleanUser)
                                if (newPassword.isNotEmpty()) RemoteAccessPrefs.setPassword(context, newPassword.toCharArray())
                            }.onSuccess {
                                passwordConfigured = RemoteAccessPrefs.hasPassword(context)
                                newPassword = ""
                                confirmPassword = ""
                                toast("登录设置已保存")
                                restartIfEnabled()
                            }.onFailure { toast(it.message ?: "保存失败") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text(if (passwordConfigured) "保存登录设置" else "创建登录密码") }
            }
        }

        SettingsSection(
            header = "监听",
            footer = "修改端口会重启 Web Remote。若 Cloudflare Published Application 的 Service URL 使用旧端口，也要在 Cloudflare 中同步改成新的 localhost 端口。",
        ) {
            SettingsCardBlock {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("监听端口") },
                    supportingText = { Text("1024–65535") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1024..65535) {
                            toast("端口必须在 1024–65535")
                        } else {
                            RemoteAccessPrefs.setPort(context, port)
                            restartIfEnabled()
                            toast("端口已应用")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("应用端口") }
            }
        }

        SettingsSection(
            header = "Cloudflare Tunnel",
            footer = "先在 Cloudflare Zero Trust / Dashboard 创建 remotely-managed Tunnel，并把公开主机名的 Service URL 指向 http://127.0.0.1:${RemoteAccessPrefs.port(context)}。然后把该 Tunnel 的 Token 粘贴到这里。",
        ) {
            SettingsSwitchRow(
                title = "启用 Cloudflare Tunnel",
                subtitle = if (tunnelStatus.running) "已连接${hostname.takeIf { it.isNotBlank() }?.let { " · https://$it" } ?: ""}" else tunnelStatus.detail.ifBlank { "通过出站连接把手机 Web Remote 接入你的域名" },
                checked = tunnelEnabled,
                icon = Icons.Outlined.Cloud,
                enabled = enabled,
                onCheckedChange = { value ->
                    if (value && !tunnelTokenConfigured && tunnelToken.isBlank()) {
                        toast("先填写 Tunnel Token")
                        return@SettingsSwitchRow
                    }
                    if (tunnelToken.isNotBlank()) {
                        RemoteAccessPrefs.setCloudflareTunnelToken(context, tunnelToken)
                        tunnelToken = ""
                        tunnelTokenConfigured = true
                    }
                    tunnelEnabled = value
                    RemoteAccessPrefs.setCloudflareTunnelEnabled(context, value)
                    restartIfEnabled()
                },
            )
            SettingsCardBlock {
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text("公开域名（仅用于显示）") },
                    placeholder = { Text("remote.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tunnelToken,
                    onValueChange = { tunnelToken = it },
                    label = { Text(if (tunnelTokenConfigured) "Tunnel Token（已配置，输入新值可替换）" else "Tunnel Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            RemoteAccessPrefs.setCloudflareHostname(context, hostname)
                            if (tunnelToken.isNotBlank()) {
                                RemoteAccessPrefs.setCloudflareTunnelToken(context, tunnelToken)
                                tunnelToken = ""
                                tunnelTokenConfigured = true
                            }
                            toast("Tunnel 设置已保存")
                            restartIfEnabled()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                    Button(
                        onClick = {
                            scope.launch {
                                val result = CloudflareTunnelManager.installOrUpdate(context)
                                toast(result.fold({ "cloudflared 已就绪" }, { it.message ?: "安装失败" }))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (tunnelStatus.installed) "更新组件" else "安装组件") }
                }
                Text(
                    text = buildString {
                        append("状态：").append(tunnelStatus.phase)
                        if (tunnelStatus.version.isNotBlank()) append(" · ").append(tunnelStatus.version)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SettingsSection(
            header = "高级",
            footer = "浏览器正常登录不需要此 Token。它只用于 CLI / 自动化以 Authorization: Bearer 方式调用 API；请当作密码保管。",
        ) {
            SettingsRow(
                title = "备用 API Token",
                subtitle = apiToken.take(12) + "…",
                icon = Icons.Outlined.VpnKey,
                showChevron = false,
                trailing = {
                    Button(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Minis Remote API Token", apiToken))
                        toast("已复制")
                    }) { Text("复制") }
                },
            )
            SettingsRow(
                title = "重新生成 API Token",
                subtitle = "会使旧的 CLI Bearer Token 失效",
                icon = Icons.Outlined.Key,
                showChevron = false,
                showDivider = false,
                onClick = {
                    apiToken = RemoteAccessPrefs.regenerateToken(context)
                    restartIfEnabled()
                    toast("已重新生成")
                },
            )
        }

        SettingsSection(
            header = "权限（逐能力开关）",
            footer = "“工作区写入 / 完整访问”预设已细化为逐能力开关；手机与网页两端看到同一份状态、任何一侧改动立即生效。危险能力（设备控制、凭据导出、管理员操作等）默认关闭。",
        ) {
            // 快速预设（兼容旧 API 语义：批量应用）
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = permissionPreset != RemotePermissionPolicy.PRESET_WORKSPACE_WRITE,
                    onClick = {
                        if (RemotePermissionPolicy.setPreset(context, RemotePermissionPolicy.PRESET_WORKSPACE_WRITE)) {
                            permissionPreset = RemotePermissionPolicy.PRESET_WORKSPACE_WRITE
                            capabilityState = RemotePermissionPolicy.capabilityState(context)
                            toast("已恢复默认能力（工作区写入）")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("恢复默认") }
                Button(
                    enabled = permissionPreset != RemotePermissionPolicy.PRESET_DANGER_FULL,
                    onClick = { pendingDangerConfirm = true },
                    modifier = Modifier.weight(1f),
                ) { Text("全部开启（危险）") }
            }
            var previousRisk: String? = null
            for (cap in RemoteCapabilityCatalog.ALL) {
                if (cap.risk.name != previousRisk) {
                    previousRisk = cap.risk.name
                    Text(
                        text = cap.risk.label + "风险",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                SettingsSwitchRow(
                    title = cap.label,
                    subtitle = cap.description + "（默认" + if (cap.defaultEnabled) "开启" else "关闭" + "）",
                    checked = capabilityState[cap.id] == true,
                    icon = Icons.Outlined.Lock,
                    onCheckedChange = { value ->
                        if (RemotePermissionPolicy.setCapability(context, cap.id, value)) {
                            capabilityState = RemotePermissionPolicy.capabilityState(context)
                            if (cap.id == RemoteCapabilityCatalog.PERMISSION_MANAGE) {
                                toast(if (value) "已开启权限管理" else "权限管理已关闭：网页端将无法再修改任何能力开关")
                            } else {
                                toast("能力已更新：" + cap.label)
                            }
                        }
                    },
                    showDivider = false,
                )
            }
        }
    }

    if (pendingDangerConfirm) {
        MinisAlertDialog(
            onDismissRequest = { pendingDangerConfirm = false },
            title = "开启全部能力？",
            text = "“全部开启”会放开所有能力（含设备控制、界面检查、凭据导出、任意路径文件访问与管理员操作），且会覆盖当前的逐能力配置。确定？",
            confirmText = "全部开启",
            dismissText = "取消",
            onConfirm = {
                if (RemotePermissionPolicy.setPreset(context, RemotePermissionPolicy.PRESET_DANGER_FULL)) {
                    permissionPreset = RemotePermissionPolicy.PRESET_DANGER_FULL
                    capabilityState = RemotePermissionPolicy.capabilityState(context)
                    toast("已开启全部能力")
                }
                pendingDangerConfirm = false
            },
            onDismiss = { pendingDangerConfirm = false },
        )
    }
}

private fun localIpv4Address(): String? = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
