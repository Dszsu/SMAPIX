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
import androidx.annotation.StringRes
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
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

private enum class MainTab(@param:StringRes val titleRes: Int, @param:StringRes val navLabelRes: Int) {
    Home(R.string.tab_home_title, R.string.tab_home_nav),
    Mods(R.string.tab_mods_title, R.string.tab_mods_nav),
    Logs(R.string.tab_logs_title, R.string.tab_logs_nav)
}

private enum class LogSource(@param:StringRes val labelRes: Int) {
    Smapi(R.string.log_source_smapi),
    Bootstrap(R.string.log_source_bootstrap)
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
                            prefs.edit { putBoolean(SmapiXposedModule.KEY_ENABLE_SMAPI, enabled) }
                            refreshCounter++
                        } else {
                            toast(getString(R.string.msg_enable_lsposed_first))
                        }
                    },
                    onToggleMod = { mod, enable ->
                        scope.launch {
                            modBusy = true
                            val res = withContext(Dispatchers.IO) {
                                SmapiPackageHelper.setModEnabled(this@MainActivity, mod.folderName, enable)
                            }
                            modBusy = false
                            res.onSuccess {
                                val msg = getString(
                                    if (enable) R.string.msg_mod_enabled else R.string.msg_mod_disabled,
                                    mod.displayName
                                )
                                modStatusMessage = msg
                                toast(msg)
                                refreshCounter++
                            }.onFailure { err ->
                                val msg = err.message ?: getString(R.string.msg_operation_failed)
                                modStatusMessage = msg
                                toast(msg)
                            }
                        }
                    },
                    onDeleteMod = { mod ->
                        scope.launch {
                            modBusy = true
                            val res = withContext(Dispatchers.IO) {
                                SmapiPackageHelper.deleteModFolder(this@MainActivity, mod.folderName)
                            }
                            modBusy = false
                            res.onSuccess {
                                val msg = getString(R.string.msg_mod_deleted, mod.displayName)
                                modStatusMessage = msg
                                toast(msg)
                                refreshCounter++
                            }.onFailure { err ->
                                val msg = err.message ?: getString(R.string.msg_delete_failed)
                                modStatusMessage = msg
                                toast(msg)
                            }
                        }
                    },
                    onRequestScope = {
                        val svc = boundService ?: run {
                            toast(getString(R.string.msg_enable_lsposed_first))
                            return@SmapixAppScreen
                        }
                        svc.requestScope(
                            listOf(SmapiXposedModule.TARGET_PACKAGE),
                            object : XposedService.OnScopeEventListener {
                                override fun onScopeRequestApproved(approved: List<String>) {
                                    runOnUiThread {
                                        homeStatusMessage = getString(R.string.msg_scope_added)
                                        refreshCounter++
                                    }
                                }

                                override fun onScopeRequestFailed(message: String) {
                                    runOnUiThread {
                                        homeStatusMessage = getString(R.string.msg_scope_failed, message)
                                    }
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
                                getString(R.string.msg_root_ready)
                            } else {
                                getString(R.string.msg_root_grant_hint)
                            }
                            refreshCounter++
                        }
                    },
                    onLaunchGame = { launchStardewValley() },
                    onOpenNexus = { openNexusMods() },
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
            homeStatusMessage = getString(R.string.msg_smapi_imported, info.versionLabel, info.sizeBytes / 1024)
            toast(getString(R.string.toast_smapi_imported))
            refreshCounter++
        }.onFailure { err ->
            val msg = getString(R.string.msg_import_failed, err.message ?: "")
            homeStatusMessage = msg
            toast(msg)
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
                        getString(R.string.msg_mod_installed_one, installed.installedNames.first())
                    } else {
                        resources.getQuantityString(
                            R.plurals.msg_mod_installed_multi,
                            installed.installedNames.size,
                            installed.installedNames.size
                        )
                    }
                    modStatusMessage = summary
                    toast(summary)
                    refreshCounter++
                }.onFailure { err ->
                    val msg = getString(R.string.msg_install_failed, err.message ?: "")
                    modStatusMessage = msg
                    toast(msg)
                }
            }
        }.start()
    }

    private fun launchStardewValley() {
        val intent = packageManager.getLaunchIntentForPackage(SmapiXposedModule.TARGET_PACKAGE)
        if (intent == null) {
            toast(getString(R.string.msg_game_not_found, SmapiXposedModule.TARGET_PACKAGE))
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun openNexusMods() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, URL_NEXUS_SMAPI.toUri()))
        }.onFailure {
            toast(getString(R.string.msg_open_browser_failed))
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        @Suppress("UsePropertyAccessSyntax")
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(getString(R.string.msg_log_copied))
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
            frameworkDesc = service?.let { "${it.frameworkName} ${it.frameworkVersion} (API ${it.apiVersion})" },
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
                title = stringResource(currentTab.titleRes),
                scrollBehavior = activeScrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == MainTab.Home,
                    onClick = { currentTab = MainTab.Home },
                    icon = TabIcons.Home,
                    label = stringResource(MainTab.Home.navLabelRes)
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.Mods,
                    onClick = { currentTab = MainTab.Mods },
                    icon = TabIcons.Mods,
                    label = stringResource(MainTab.Mods.navLabelRes)
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.Logs,
                    onClick = { currentTab = MainTab.Logs },
                    icon = TabIcons.Logs,
                    label = stringResource(MainTab.Logs.navLabelRes)
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

    val badgeText = stringResource(
        when {
            !moduleState.connected -> R.string.badge_inactive
            !moduleState.inScope -> R.string.badge_no_scope
            rootState == null -> R.string.badge_checking
            !hasRoot -> R.string.badge_no_root
            moduleState.gameVersion == null -> R.string.badge_no_game
            moduleState.enableSmapi && !moduleState.hasSmapiZip -> R.string.badge_need_smapi
            else -> R.string.badge_ready
        }
    )

    val headlineText = stringResource(
        when {
            moduleState.gameVersion == null -> R.string.headline_no_game
            !moduleState.connected -> R.string.headline_enable_lsposed
            !moduleState.inScope -> R.string.headline_add_scope
            rootState != null && !hasRoot -> R.string.headline_grant_root
            moduleState.enableSmapi && !moduleState.hasSmapiZip -> R.string.headline_import_smapi
            else -> R.string.headline_ready
        }
    )

    val gameVerLabel = moduleState.gameVersion ?: stringResource(R.string.subline_game_not_installed)
    val frameworkLabel = moduleState.frameworkDesc ?: stringResource(R.string.status_framework_inactive)
    val rootSuffix = when (rootState) {
        null -> ""
        else -> " · " + stringResource(
            if (hasRoot) R.string.subline_root_granted else R.string.subline_root_denied
        )
    }
    val sublineText = stringResource(R.string.subline_game_framework, gameVerLabel, frameworkLabel) + rootSuffix

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
                        text = stringResource(R.string.hint_nexus_download_import),
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
                            text = stringResource(R.string.btn_download_smapi),
                            onClick = onOpenNexus,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                        TextButton(
                            text = stringResource(R.string.btn_launch_game),
                            onClick = onLaunchGame,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                } else {
                    TextButton(
                        text = stringResource(
                            if (moduleState.enableSmapi) R.string.btn_launch_smapi else R.string.btn_launch_vanilla
                        ),
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
            SectionTitle(text = stringResource(R.string.section_setup))
        }

        item(key = "setup-card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                if (moduleState.connected && !moduleState.inScope) {
                    ArrowPreference(
                        title = stringResource(R.string.pref_add_scope_title),
                        summary = stringResource(R.string.pref_add_scope_summary),
                        onClick = onRequestScope
                    )
                }
                if (rootState != null && !hasRoot) {
                    ArrowPreference(
                        title = stringResource(R.string.pref_grant_root_title),
                        summary = stringResource(R.string.pref_grant_root_summary),
                        onClick = onRequestRoot
                    )
                }
                val smapiSummary = if (moduleState.hasSmapiZip) {
                    val label = moduleState.smapiLabel.ifEmpty {
                        stringResource(R.string.pref_smapi_zip_imported_default)
                    }
                    stringResource(R.string.pref_smapi_zip_summary_imported, label)
                } else {
                    stringResource(R.string.pref_smapi_zip_summary_empty)
                }
                ArrowPreference(
                    title = stringResource(R.string.pref_smapi_zip_title),
                    summary = smapiSummary,
                    onClick = onImportSmapiZip
                )
                ArrowPreference(
                    title = stringResource(R.string.pref_get_smapi_title),
                    summary = stringResource(R.string.pref_get_smapi_summary),
                    onClick = onOpenNexus
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_enable_smapi_title),
                    summary = stringResource(R.string.pref_enable_smapi_summary),
                    checked = moduleState.enableSmapi,
                    onCheckedChange = onToggleSmapi
                )
            }
        }

        item(key = "section-about") {
            SectionTitle(text = stringResource(R.string.section_about))
        }

        item(key = "about-card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.about_author),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainer
                    )
                    Text(
                        text = stringResource(R.string.about_based_on),
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
                        rootState == null -> stringResource(R.string.mods_checking_root)
                        !hasRoot -> stringResource(R.string.mods_need_root)
                        mods.isEmpty() -> stringResource(R.string.mods_none_installed)
                        else -> pluralStringResource(R.plurals.mods_count_summary, mods.size, mods.size, enabledCount)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceContainer
                )
                Text(
                    text = stringResource(R.string.mods_install_hint),
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
                            text = stringResource(R.string.btn_grant_root),
                            onClick = onRequestRoot,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    } else {
                        TextButton(
                            text = stringResource(if (modBusy) R.string.btn_processing else R.string.btn_install_mod_zip),
                            onClick = onInstallModZip,
                            enabled = hasRoot && !modBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.btn_refresh),
                        onClick = onRefresh,
                        colors = ButtonDefaults.textButtonColors()
                    )
                }
            }
        }

        if (hasRoot) {
            item(key = "mods-section-title") {
                SectionTitle(
                    text = stringResource(
                        if (mods.isEmpty()) R.string.mods_list_empty_title else R.string.mods_list_title
                    )
                )
            }

            if (mods.isEmpty()) {
                item(key = "mods-empty-card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mods_empty_desc),
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
    val versionLine = if (dialogMod != null && dialogMod.version.isNotEmpty()) {
        stringResource(R.string.mod_dialog_version, dialogMod.version)
    } else {
        ""
    }
    val folderLine = if (dialogMod != null) {
        stringResource(R.string.mod_dialog_folder, dialogMod.folderName)
    } else {
        ""
    }
    val dialogSummary = dialogMod?.let { m ->
        buildString {
            if (versionLine.isNotEmpty()) append(versionLine)
            if (m.author.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(m.author)
            }
            if (m.uniqueId.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(m.uniqueId)
            }
            if (isNotEmpty()) append('\n')
            append(folderLine)
            if (m.description.isNotEmpty()) {
                append("\n\n")
                append(m.description)
            }
        }
    }
    OverlayDialog(
        show = showModDialog && dialogMod != null,
        title = dialogMod?.displayName,
        summary = dialogSummary,
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
                        text = stringResource(R.string.mod_dialog_confirm_delete, dialogMod.folderName),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.btn_cancel),
                            onClick = { confirmDelete = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                        TextButton(
                            text = stringResource(R.string.btn_confirm_delete),
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
                            text = stringResource(if (dialogMod.enabled) R.string.btn_disable else R.string.btn_enable),
                            onClick = {
                                showModDialog = false
                                onToggleMod(dialogMod, !dialogMod.enabled)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                        TextButton(
                            text = stringResource(R.string.btn_delete),
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
                        text = stringResource(R.string.btn_close),
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
    val sourceLabel = stringResource(source.labelRes)

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
                            text = stringResource(entry.labelRes),
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
                            text = stringResource(R.string.btn_grant_root),
                            onClick = onRequestRoot,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.btn_copy_log),
                            onClick = {
                                if (!activeLogText.isNullOrBlank()) {
                                    onCopyLog(sourceLabel, activeLogText)
                                }
                            },
                            enabled = !activeLogText.isNullOrBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors()
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.btn_refresh),
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
                        text = stringResource(R.string.logs_loading),
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
                        text = stringResource(R.string.logs_no_root),
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
                        text = stringResource(R.string.logs_empty, sourceLabel),
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

@Composable
private fun buildModRowSummary(mod: InstalledModInfo): String {
    val disabledText = stringResource(R.string.mod_row_disabled)
    val folderFallback = stringResource(R.string.mod_row_folder_fallback)
    return buildString {
        if (!mod.enabled) append(disabledText)
        if (mod.version.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("v${mod.version}")
        }
        if (mod.author.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(mod.author)
        }
        if (isEmpty()) {
            append(if (mod.hasManifest) mod.folderName else folderFallback)
        }
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
    val frameworkDesc: String?,
    val inScope: Boolean,
    val enableSmapi: Boolean,
    val smapiLabel: String,
    val hasSmapiZip: Boolean,
    val gameVersion: String?
)
