package io.github.dszsu.smapix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dszsu.smapix.xposed.SmapiXposedModule
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private enum class MainTab(val title: String, val navLabel: String) {
    Home("SMAPIX", "主页"),
    Mods("模组", "模组"),
    Logs("日志", "日志")
}

private enum class LogSource(val label: String) {
    Smapi("SMAPI 日志"),
    Bootstrap("引导日志")
}

class MainActivity : ComponentActivity() {

    private var refreshCounter by mutableIntStateOf(0)
    private var boundService by mutableStateOf<XposedService?>(null)
    private var homeStatusMessage by mutableStateOf("")
    private var modStatusMessage by mutableStateOf("")
    private var modBusy by mutableStateOf(false)

    private val pickSmapiZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePickedSmapiZip(uri)
        }
    }

    private val pickModZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePickedModZip(uri)
        }
    }

    private val serviceListener: (XposedService?) -> Unit = { service ->
        runOnUiThread {
            boundService = service
            refreshCounter++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        App.addServiceListener(serviceListener)

        setContent {
            val dark = isSystemInDarkTheme()
            val scope = rememberCoroutineScope()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                SmapixAppScreen(
                    service = boundService,
                    refreshTick = refreshCounter,
                    homeStatusMessage = homeStatusMessage,
                    modStatusMessage = modStatusMessage,
                    modBusy = modBusy,
                    onImportSmapiZip = {
                        pickSmapiZipLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                        )
                    },
                    onInstallModZip = {
                        pickModZipLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                        )
                    },
                    onToggleSmapi = { enabled ->
                        val prefs = boundService?.getRemotePreferences(SmapiXposedModule.PREFS_CONFIG)
                        if (prefs != null) {
                            prefs.edit().putBoolean(SmapiXposedModule.KEY_ENABLE_SMAPI, enabled).apply()
                            refreshCounter++
                        } else {
                            toast("请先在 LSPosed 中启用 SMAPIX")
                        }
                    },
                    onToggleMod = { mod, enable ->
                        scope.launch {
                            modBusy = true
                            val res = withContext(Dispatchers.IO) {
                                SmapiPackageHelper.setModEnabled(mod.folderName, enable)
                            }
                            modBusy = false
                            res.onSuccess {
                                val msg = if (enable) "已启用 ${mod.displayName}" else "已禁用 ${mod.displayName}"
                                modStatusMessage = msg
                                toast(msg)
                                refreshCounter++
                            }.onFailure { err ->
                                val msg = err.message ?: "操作失败"
                                modStatusMessage = msg
                                toast(msg)
                            }
                        }
                    },
                    onDeleteMod = { mod ->
                        scope.launch {
                            modBusy = true
                            val res = withContext(Dispatchers.IO) {
                                SmapiPackageHelper.deleteModFolder(mod.folderName)
                            }
                            modBusy = false
                            res.onSuccess {
                                val msg = "已删除 ${mod.displayName}"
                                modStatusMessage = msg
                                toast(msg)
                                refreshCounter++
                            }.onFailure { err ->
                                val msg = err.message ?: "删除失败"
                                modStatusMessage = msg
                                toast(msg)
                            }
                        }
                    },
                    onRequestScope = {
                        val svc = boundService ?: run {
                            toast("请先在 LSPosed 中启用 SMAPIX")
                            return@SmapixAppScreen
                        }
                        svc.requestScope(
                            listOf(SmapiXposedModule.TARGET_PACKAGE),
                            object : XposedService.OnScopeEventListener {
                                override fun onScopeRequestApproved(approved: List<String>) {
                                    runOnUiThread {
                                        homeStatusMessage = "已添加游戏作用域"
                                        refreshCounter++
                                    }
                                }

                                override fun onScopeRequestFailed(message: String) {
                                    runOnUiThread { homeStatusMessage = "作用域请求失败: $message" }
                                }
                            }
                        )
                    },
                    onRequestRoot = {
                        scope.launch {
                            val granted = withContext(Dispatchers.IO) {
                                SmapiPackageHelper.requestRootPermission()
                            }
                            homeStatusMessage = if (granted) {
                                "Root 权限已就绪"
                            } else {
                                "请在 KernelSU / Magisk 中为 SMAPIX 授予 Root 权限"
                            }
                            refreshCounter++
                        }
                    },
                    onLaunchGame = { launchStardewValley() },
                    onOpenNexus = { openExternalUrl(URL_NEXUS_SMAPI) },
                    onCopyLog = { label, text -> copyToClipboard(label, text) },
                    onRefresh = { refreshCounter++ }
                )
            }
        }
    }

    override fun onDestroy() {
        App.removeServiceListener(serviceListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshCounter++
    }

    private fun handlePickedSmapiZip(uri: Uri) {
        val result = SmapiPackageHelper.importSmapiZip(this, uri, boundService)
        result.onSuccess { info ->
            homeStatusMessage = "已导入 ${info.versionLabel} (${info.sizeBytes / 1024} KB)"
            toast("SMAPI 导入成功")
            refreshCounter++
        }.onFailure { err ->
            homeStatusMessage = "导入失败: ${err.message}"
            toast("导入失败: ${err.message}")
        }
    }

    private fun handlePickedModZip(uri: Uri) {
        if (modBusy) return
        modBusy = true
        Thread {
            val result = SmapiPackageHelper.installModZip(this, uri)
            runOnUiThread {
                modBusy = false
                result.onSuccess { installed ->
                    val summary = if (installed.installedNames.size == 1) {
                        "已安装 ${installed.installedNames.first()}"
                    } else {
                        "已安装 ${installed.installedNames.size} 个模组"
                    }
                    modStatusMessage = summary
                    toast(summary)
                    refreshCounter++
                }.onFailure { err ->
                    val msg = "安装失败: ${err.message}"
                    modStatusMessage = msg
                    toast(msg)
                }
            }
        }.start()
    }

    private fun launchStardewValley() {
        val intent = packageManager.getLaunchIntentForPackage(SmapiXposedModule.TARGET_PACKAGE)
        if (intent == null) {
            toast("未检测到星露谷物语 (${SmapiXposedModule.TARGET_PACKAGE})")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("无法打开浏览器")
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制日志")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val URL_NEXUS_SMAPI = "https://www.nexusmods.com/stardewvalley/mods/44436"
    }
}

@Composable
private fun SmapixAppScreen(
    service: XposedService?,
    refreshTick: Int,
    homeStatusMessage: String,
    modStatusMessage: String,
    modBusy: Boolean,
    onImportSmapiZip: () -> Unit,
    onInstallModZip: () -> Unit,
    onToggleSmapi: (Boolean) -> Unit,
    onToggleMod: (InstalledModInfo, Boolean) -> Unit,
    onDeleteMod: (InstalledModInfo) -> Unit,
    onRequestScope: () -> Unit,
    onRequestRoot: () -> Unit,
    onLaunchGame: () -> Unit,
    onOpenNexus: () -> Unit,
    onCopyLog: (String, String) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var rootState by remember { mutableStateOf<RootStardewState?>(null) }

    val homeScroll = MiuixScrollBehavior()
    val modsScroll = MiuixScrollBehavior()
    val logsScroll = MiuixScrollBehavior()

    val homeListState = rememberLazyListState()
    val modsListState = rememberLazyListState()
    val logsListState = rememberLazyListState()

    val activeScrollBehavior = when (currentTab) {
        MainTab.Home -> homeScroll
        MainTab.Mods -> modsScroll
        MainTab.Logs -> logsScroll
    }

    LaunchedEffect(refreshTick) {
        rootState = withContext(Dispatchers.IO) {
            SmapiPackageHelper.readRootStardewState(20_000)
        }
    }

    val moduleState = remember(service, refreshTick) {
        val scopeList = runCatching { service?.scope ?: emptyList() }.getOrDefault(emptyList())
        val inScope = scopeList.contains(SmapiXposedModule.TARGET_PACKAGE)
        val prefs = runCatching { service?.getRemotePreferences(SmapiXposedModule.PREFS_CONFIG) }.getOrNull()
        val enableSmapi = prefs?.getBoolean(SmapiXposedModule.KEY_ENABLE_SMAPI, true) ?: true
        val smapiLabel = prefs?.getString("smapi_version_label", "") ?: ""
        val remoteFiles = runCatching { service?.listRemoteFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val hasSmapiZip = remoteFiles.contains(SmapiXposedModule.REMOTE_SMAPI_ZIP)
        val gameVer = SmapiPackageHelper.readStardewVersion(context)
        ModuleSnapshot(
            connected = service != null,
            frameworkDesc = service?.let { "${it.frameworkName} ${it.frameworkVersion} (API ${it.apiVersion})" }
                ?: "未激活 LSPosed 模块",
            inScope = inScope,
            enableSmapi = enableSmapi,
            smapiLabel = smapiLabel,
            hasSmapiZip = hasSmapiZip,
            gameVersion = gameVer
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = currentTab.title,
                scrollBehavior = activeScrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == MainTab.Home,
                    onClick = { currentTab = MainTab.Home },
                    icon = TabIcons.Home,
                    label = MainTab.Home.navLabel
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.Mods,
                    onClick = { currentTab = MainTab.Mods },
                    icon = TabIcons.Mods,
                    label = MainTab.Mods.navLabel
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.Logs,
                    onClick = { currentTab = MainTab.Logs },
                    icon = TabIcons.Logs,
                    label = MainTab.Logs.navLabel
                )
            }
        }
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            start = 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = innerPadding.calculateBottomPadding() + 16.dp
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentTab) {
                MainTab.Home -> HomeTabContent(
                    listState = homeListState,
                    scrollBehavior = homeScroll,
                    contentPadding = contentPadding,
                    moduleState = moduleState,
                    rootState = rootState,
                    statusMessage = homeStatusMessage,
                    onImportSmapiZip = onImportSmapiZip,
                    onToggleSmapi = onToggleSmapi,
                    onRequestScope = onRequestScope,
                    onRequestRoot = onRequestRoot,
                    onLaunchGame = onLaunchGame,
                    onOpenNexus = onOpenNexus
                )

                MainTab.Mods -> ModsTabContent(
                    listState = modsListState,
                    scrollBehavior = modsScroll,
                    contentPadding = contentPadding,
                    rootState = rootState,
                    statusMessage = modStatusMessage,
                    modBusy = modBusy,
                    onInstallModZip = onInstallModZip,
                    onToggleMod = onToggleMod,
                    onDeleteMod = onDeleteMod,
                    onRequestRoot = onRequestRoot,
                    onRefresh = onRefresh
                )

                MainTab.Logs -> LogsTabContent(
                    listState = logsListState,
                    scrollBehavior = logsScroll,
                    contentPadding = contentPadding,
                    rootState = rootState,
                    onRequestRoot = onRequestRoot,
                    onCopyLog = onCopyLog,
                    onRefresh = onRefresh
                )
            }
        }
    }
}

@Composable
private fun HomeTabContent(
    listState: LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    contentPadding: PaddingValues,
    moduleState: ModuleSnapshot,
    rootState: RootStardewState?,
    statusMessage: String,
    onImportSmapiZip: () -> Unit,
    onToggleSmapi: (Boolean) -> Unit,
    onRequestScope: () -> Unit,
    onRequestRoot: () -> Unit,
    onLaunchGame: () -> Unit,
    onOpenNexus: () -> Unit
) {
    val hasRoot = rootState?.hasRoot == true
    val allReady = moduleState.connected &&
        moduleState.inScope &&
        hasRoot &&
        moduleState.gameVersion != null &&
        (!moduleState.enableSmapi || moduleState.hasSmapiZip)

    val badgeText = when {
        !moduleState.connected -> "未激活"
        !moduleState.inScope -> "缺作用域"
        rootState == null -> "检测中"
        !hasRoot -> "无 Root"
        moduleState.gameVersion == null -> "缺游戏"
        moduleState.enableSmapi && !moduleState.hasSmapiZip -> "待导入"
        else -> "就绪"
    }

    val headlineText = when {
        moduleState.gameVersion == null -> "未安装星露谷物语"
        !moduleState.connected -> "请启用 LSPosed 模块"
        !moduleState.inScope -> "请添加星露谷作用域"
        rootState != null && !hasRoot -> "请授予 Root 权限"
        moduleState.enableSmapi && !moduleState.hasSmapiZip -> "请导入 SMAPI 核心包"
        else -> "运行环境已就绪"
    }

    val sublineText = buildString {
        append("星露谷 ")
        append(moduleState.gameVersion ?: "未安装")
        append(" · ")
        append(moduleState.frameworkDesc)
        if (rootState != null) {
            append(" · ")
            append(if (hasRoot) "Root 已授权" else "Root 未授权")
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "hero") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = headlineText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceContainer
                        )
                        Text(
                            text = sublineText,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }

                    // Explicit ready green vs theme error token from Miuix color-lookup.md
                    val pillBg = if (allReady) Color(0x1A2EBD66) else Color(0x1AE94634)
                    val pillFg = if (allReady) Color(0xFF1B9A50) else MiuixTheme.colorScheme.error
                    Card(
                        colors = CardDefaults.defaultColors(color = pillBg, contentColor = pillFg),
                        cornerRadius = 10.dp,
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = pillFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (statusMessage.isNotEmpty()) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(top = 10.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary
                    )
                } else if (moduleState.enableSmapi && !moduleState.hasSmapiZip) {
                    Text(
                        text = "Nexus Mods → Files 下载 zip，下方导入",
                        modifier = Modifier.padding(top = 10.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                if (moduleState.enableSmapi && !moduleState.hasSmapiZip) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "下载 SMAPI",
                            onClick = onOpenNexus,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                        TextButton(
                            text = "启动星露谷",
                            onClick = onLaunchGame,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                } else {
                    TextButton(
                        text = if (moduleState.enableSmapi) "启动星露谷 (SMAPI)" else "启动星露谷 (原版)",
                        onClick = onLaunchGame,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }

        item(key = "section-setup") {
            SectionTitle(text = "运行设置")
        }

        item(key = "setup-card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                if (moduleState.connected && !moduleState.inScope) {
                    ArrowPreference(
                        title = "添加游戏作用域",
                        summary = "将星露谷加入 SMAPIX 作用域",
                        onClick = onRequestScope
                    )
                }
                if (rootState != null && !hasRoot) {
                    ArrowPreference(
                        title = "授予 Root 权限",
                        summary = "用于读取模组目录与日志",
                        onClick = onRequestRoot
                    )
                }
                ArrowPreference(
                    title = "SMAPI 核心包",
                    summary = if (moduleState.hasSmapiZip) {
                        "${moduleState.smapiLabel.ifEmpty { "已导入" }} · 点击更换"
                    } else {
                        "未导入 · 点击选择 zip"
                    },
                    onClick = onImportSmapiZip
                )
                ArrowPreference(
                    title = "获取 SMAPI for Android",
                    summary = "Nexus Mods → Files 下载 zip",
                    onClick = onOpenNexus
                )
                SwitchPreference(
                    title = "启用 SMAPI",
                    summary = "开启注入模组，关闭运行原版",
                    checked = moduleState.enableSmapi,
                    onCheckedChange = onToggleSmapi
                )
            }
        }

        item(key = "section-about") {
            SectionTitle(text = "关于")
        }

        item(key = "about-card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "作者:Dszsu",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainer
                    )
                    Text(
                        text = "基于SMAPILoader (NRTnarathip)",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun ModsTabContent(
    listState: LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    contentPadding: PaddingValues,
    rootState: RootStardewState?,
    statusMessage: String,
    modBusy: Boolean,
    onInstallModZip: () -> Unit,
    onToggleMod: (InstalledModInfo, Boolean) -> Unit,
    onDeleteMod: (InstalledModInfo) -> Unit,
    onRequestRoot: () -> Unit,
    onRefresh: () -> Unit
) {
    val hasRoot = rootState?.hasRoot == true
    val mods = rootState?.mods.orEmpty()
    val enabledCount = mods.count { it.enabled }

    var activeMod by remember { mutableStateOf<InstalledModInfo?>(null) }
    var showModDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "mods-action-card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
            ) {
                Text(
                    text = when {
                        rootState == null -> "正在检测 Root 权限..."
                        !hasRoot -> "安装与管理模组需要 Root 权限"
                        mods.isEmpty() -> "尚未安装模组"
                        else -> "已安装 ${mods.size} 个模组 · 已启用 $enabledCount 个"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceContainer
                )
                Text(
                    text = "选取 .zip 模组包自动识别 manifest.json 并解压到 Mods 目录",
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                if (statusMessage.isNotEmpty()) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (rootState != null && !hasRoot) {
                        TextButton(
                            text = "授予 Root",
                            onClick = onRequestRoot,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    } else {
                        TextButton(
                            text = if (modBusy) "处理中..." else "安装模组 (.zip)",
                            onClick = onInstallModZip,
                            enabled = hasRoot && !modBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                    TextButton(
                        text = "刷新",
                        onClick = onRefresh,
                        colors = ButtonDefaults.textButtonColors()
                    )
                }
            }
        }

        if (hasRoot) {
            item(key = "mods-section-title") {
                SectionTitle(
                    text = if (mods.isEmpty()) "模组列表" else "模组列表 · 点击查看或删除"
                )
            }

            if (mods.isEmpty()) {
                item(key = "mods-empty-card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Text(
                            text = "Mods 目录为空，点击上方「安装模组 (.zip)」即可直接安装。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                item(key = "mods-list-card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        mods.forEachIndexed { index, mod ->
                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            val summaryText = buildModRowSummary(mod)
                            BasicComponent(
                                title = mod.displayName,
                                summary = summaryText,
                                enabled = !modBusy,
                                titleColor = BasicComponentDefaults.titleColor(
                                    color = if (mod.enabled) {
                                        MiuixTheme.colorScheme.onSurface
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    }
                                ),
                                endActions = {
                                    Switch(
                                        checked = mod.enabled,
                                        onCheckedChange = { checked -> onToggleMod(mod, checked) },
                                        enabled = !modBusy
                                    )
                                },
                                onClick = {
                                    activeMod = mod
                                    confirmDelete = false
                                    showModDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val dialogMod = activeMod
    OverlayDialog(
        show = showModDialog && dialogMod != null,
        title = dialogMod?.displayName,
        summary = dialogMod?.let { m ->
            buildString {
                if (m.version.isNotEmpty()) append("版本 ${m.version}")
                if (m.author.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(m.author)
                }
                if (m.uniqueId.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(m.uniqueId)
                }
                if (isNotEmpty()) append('\n')
                append("目录：Mods/${m.folderName}")
                if (m.description.isNotEmpty()) {
                    append("\n\n")
                    append(m.description)
                }
            }
        },
        onDismissRequest = {
            showModDialog = false
            confirmDelete = false
        },
        onDismissFinished = {
            activeMod = null
            confirmDelete = false
        }
    ) {
        if (dialogMod != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (confirmDelete) {
                    Text(
                        text = "将彻底删除 Mods/${dialogMod.folderName}，确认继续？",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { confirmDelete = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                        TextButton(
                            text = "确认删除",
                            onClick = {
                                showModDialog = false
                                onDeleteMod(dialogMod)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(
                                color = MiuixTheme.colorScheme.error,
                                textColor = MiuixTheme.colorScheme.onPrimary
                            )
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = if (dialogMod.enabled) "禁用" else "启用",
                            onClick = {
                                showModDialog = false
                                onToggleMod(dialogMod, !dialogMod.enabled)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                        TextButton(
                            text = "删除",
                            onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(
                                color = Color(0x1AE94634),
                                textColor = MiuixTheme.colorScheme.error
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        text = "关闭",
                        onClick = { showModDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsTabContent(
    listState: LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    contentPadding: PaddingValues,
    rootState: RootStardewState?,
    onRequestRoot: () -> Unit,
    onCopyLog: (String, String) -> Unit,
    onRefresh: () -> Unit
) {
    val hasRoot = rootState?.hasRoot == true
    var source by rememberSaveable { mutableStateOf(LogSource.Smapi) }

    val activeLogText = when (source) {
        LogSource.Smapi -> rootState?.latestLog
        LogSource.Bootstrap -> rootState?.bootstrapLog
    }

    val logLines = remember(activeLogText) {
        activeLogText?.lines().orEmpty()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "logs-controls") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LogSource.entries.forEach { entry ->
                        val selected = source == entry
                        TextButton(
                            text = entry.label,
                            onClick = { source = entry },
                            modifier = Modifier.weight(1f),
                            colors = if (selected) {
                                ButtonDefaults.textButtonColorsPrimary()
                            } else {
                                ButtonDefaults.textButtonColors()
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (rootState != null && !hasRoot) {
                        TextButton(
                            text = "授予 Root",
                            onClick = onRequestRoot,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    } else {
                        TextButton(
                            text = "复制日志",
                            onClick = {
                                if (!activeLogText.isNullOrBlank()) {
                                    onCopyLog(source.label, activeLogText)
                                }
                            },
                            enabled = !activeLogText.isNullOrBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                    }
                    TextButton(
                        text = "刷新",
                        onClick = onRefresh,
                        colors = ButtonDefaults.textButtonColors()
                    )
                }
            }
        }

        when {
            rootState == null -> item(key = "logs-loading") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Text(
                        text = "正在读取日志...",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            !hasRoot -> item(key = "logs-noroot") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Text(
                        text = "授予 Root 权限后可查看 SMAPI-latest.txt 与引导日志。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            logLines.isEmpty() -> item(key = "logs-empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Text(
                        text = "暂无${source.label}，启动一次星露谷后会在此生成记录。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            else -> item(key = "logs-body") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(14.dp)
                ) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            logLines.forEach { line ->
                                val lineColor = when {
                                    line.contains(" ERROR ", ignoreCase = true) ||
                                        line.contains(" ERR]", ignoreCase = true) -> MiuixTheme.colorScheme.error
                                    line.contains(" WARN ", ignoreCase = true) ||
                                        line.contains(" WRN]", ignoreCase = true) -> Color(0xFFD97706)
                                    else -> MiuixTheme.colorScheme.onSurfaceContainerVariant
                                }
                                Text(
                                    text = line.ifEmpty { " " },
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = lineColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    // Match Card inner content margin (16.dp) inside the 16.dp LazyColumn contentPadding.
    SmallTitle(
        text = text,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    )
}

private fun buildModRowSummary(mod: InstalledModInfo): String = buildString {
    if (!mod.enabled) append("已禁用")
    if (mod.version.isNotEmpty()) {
        if (isNotEmpty()) append(" · ")
        append("v${mod.version}")
    }
    if (mod.author.isNotEmpty()) {
        if (isNotEmpty()) append(" · ")
        append(mod.author)
    }
    if (isEmpty()) {
        append(if (mod.hasManifest) mod.folderName else "模组包目录")
    }
}

private object TabIcons {
    val Home: ImageVector by lazy {
        ImageVector.Builder("TabHome", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(10f, 20f)
                verticalLineTo(14f)
                horizontalLineTo(14f)
                verticalLineTo(20f)
                horizontalLineTo(19f)
                verticalLineTo(12f)
                horizontalLineTo(22f)
                lineTo(12f, 3f)
                lineTo(2f, 12f)
                horizontalLineTo(5f)
                verticalLineTo(20f)
                close()
            }
        }.build()
    }

    val Mods: ImageVector by lazy {
        ImageVector.Builder("TabMods", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(20.5f, 11f)
                horizontalLineTo(19f)
                verticalLineTo(7f)
                curveTo(19f, 5.9f, 18.1f, 5f, 17f, 5f)
                horizontalLineTo(13f)
                verticalLineTo(3.5f)
                curveTo(13f, 2.12f, 11.88f, 1f, 10.5f, 1f)
                reflectiveCurveTo(8f, 2.12f, 8f, 3.5f)
                verticalLineTo(5f)
                horizontalLineTo(4f)
                curveTo(2.9f, 5f, 2f, 5.9f, 2f, 7f)
                verticalLineTo(10.8f)
                horizontalLineTo(3.5f)
                curveTo(5f, 10.8f, 6.2f, 12f, 6.2f, 13.5f)
                reflectiveCurveTo(5f, 16.2f, 3.5f, 16.2f)
                horizontalLineTo(2f)
                verticalLineTo(20f)
                curveTo(2f, 21.1f, 2.9f, 22f, 4f, 22f)
                horizontalLineTo(7.8f)
                verticalLineTo(20.5f)
                curveTo(7.8f, 19f, 9f, 17.8f, 10.5f, 17.8f)
                reflectiveCurveTo(13.2f, 19f, 13.2f, 20.5f)
                verticalLineTo(22f)
                horizontalLineTo(17f)
                curveTo(18.1f, 22f, 19f, 21.1f, 19f, 20f)
                verticalLineTo(16f)
                horizontalLineTo(20.5f)
                curveTo(21.88f, 16f, 23f, 14.88f, 23f, 13.5f)
                reflectiveCurveTo(21.88f, 11f, 20.5f, 11f)
                close()
            }
        }.build()
    }

    val Logs: ImageVector by lazy {
        ImageVector.Builder("TabLogs", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(14f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(8f)
                lineTo(14f, 2f)
                close()
                moveTo(16f, 18f)
                horizontalLineTo(8f)
                verticalLineTo(16f)
                horizontalLineTo(16f)
                verticalLineTo(18f)
                close()
                moveTo(16f, 14f)
                horizontalLineTo(8f)
                verticalLineTo(12f)
                horizontalLineTo(16f)
                verticalLineTo(14f)
                close()
                moveTo(13f, 9f)
                verticalLineTo(3.5f)
                lineTo(18.5f, 9f)
                horizontalLineTo(13f)
                close()
            }
        }.build()
    }
}

private data class ModuleSnapshot(
    val connected: Boolean,
    val frameworkDesc: String,
    val inScope: Boolean,
    val enableSmapi: Boolean,
    val smapiLabel: String,
    val hasSmapiZip: Boolean,
    val gameVersion: String?
)
