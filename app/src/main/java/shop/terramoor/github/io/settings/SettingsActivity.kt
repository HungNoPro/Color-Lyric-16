package shop.terramoor.github.io.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiTopAppBarButtonDefaults
import com.itos.coui.topappbar.TopAppBarButton
import com.itos.coui.topappbar.TopAppBarSurface
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonColors
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.DividerDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import com.itos.coui.layout.Scaffold as FloatingScaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shop.terramoor.github.io.R
import shop.terramoor.github.io.settings.TerraMoorApp.Connection
import kotlin.math.abs

class SettingsActivity : ComponentActivity() {
    private val app get() = application as TerraMoorApp
    private var backupBusy = mutableStateOf(false)
    private var diagnosticsBusy by mutableStateOf(false)
    private var restartBusy by mutableStateOf(false)
    private var updateResult by mutableStateOf<GitHubUpdates.Result>(GitHubUpdates.Result.Idle)
    private var updatePopupRelease by mutableStateOf<GitHubUpdates.Result.Latest?>(null)
    private val onboarding by lazy { getSharedPreferences("onboarding", MODE_PRIVATE) }
    private var providerStatuses by mutableStateOf<Map<LyricsProvider, Boolean>>(emptyMap())
    private var lockScreenLyricsConfirmed by mutableStateOf(false)
    private var translationHelpHidden by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockScreenLyricsConfirmed = onboarding.getBoolean(LOCK_SCREEN_LYRICS_CONFIRMED, false)
        translationHelpHidden = onboarding.getBoolean(CapsuleBackup.TRANSLATION_HELP_HIDDEN, false)
        enableEdgeToEdge()
        val version = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        if (savedInstanceState == null) {
            checkForUpdates(version, showPopupIfNewer = true)
        }
        setContent {
            val state by app.state.collectAsState()
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) exportSettings(uri)
            }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) importSettings(uri)
            }
            val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri != null) saveDiagnostics(uri)
            }
            COUITheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsPage(
                    state = state,
                    app = app,
                    version = version.versionName.orEmpty(),
                    providerStatuses = providerStatuses,
                    askForProviderCheck = !onboarding.contains(PROVIDER_CHECK),
                    setProviderCheck = ::setProviderCheck,
                    openProvider = ::openProvider,
                    lockScreenLyricsConfirmed = lockScreenLyricsConfirmed,
                    confirmLockScreenLyrics = ::confirmLockScreenLyrics,
                    translationHelpHidden = translationHelpHidden,
                    onHideTranslationHelp = { saveTranslationHelpHidden(true) },
                    restartBusy = restartBusy,
                    onRestartSystemUi = ::restartSystemUi,
                    openRepository = { openWeb(GitHubUpdates.UPSTREAM_REPOSITORY_URL) },
                    updateResult = updateResult,
                    updatePopupRelease = updatePopupRelease,
                    onDismissUpdatePopup = { updatePopupRelease = null },
                    onOpenUpdateRelease = { url ->
                        updatePopupRelease = null
                        openWeb(url)
                    },
                    checkForUpdates = { checkForUpdates(version, showPopupIfNewer = true) },
                    openReleases = { openWeb(GitHubUpdates.RELEASES_URL) },
                    copySupportGroup = ::copySupportGroup,
                    canExportDiagnostics = !diagnosticsBusy,
                    onExportDiagnostics = { exportDiagnostics.launch(DiagnosticReport.fileName()) },
                    canUseBackup = state.connection == Connection.READY && !backupBusy.value,
                    onExport = { export.launch("TerraMoor-settings.json") },
                    onImport = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (app.state.value.connection != Connection.CHECKING) app.refresh()
        refreshProviderStatuses()
    }

    private fun setProviderCheck(allowed: Boolean) {
        onboarding.edit().putBoolean(PROVIDER_CHECK, allowed).apply()
        refreshProviderStatuses()
    }

    private fun confirmLockScreenLyrics() {
        onboarding.edit().putBoolean(LOCK_SCREEN_LYRICS_CONFIRMED, true).apply()
        lockScreenLyricsConfirmed = true
    }

    private fun saveTranslationHelpHidden(hidden: Boolean) {
        onboarding.edit().putBoolean(CapsuleBackup.TRANSLATION_HELP_HIDDEN, hidden).apply()
        translationHelpHidden = hidden
    }

    private fun refreshProviderStatuses() {
        if (onboarding.getBoolean(PROVIDER_CHECK, false)) {
            providerStatuses = LyricsProvider.entries.associateWith { it.isInstalled(packageManager) }
        }
    }

    private fun openProvider(provider: LyricsProvider) {
        val launch = if (providerStatuses[provider] == true) packageManager.getLaunchIntentForPackage(provider.packageId) else null
        if (launch != null) {
            try {
                startActivity(launch)
                return
            } catch (_: ActivityNotFoundException) {
                refreshProviderStatuses()
            }
        }
        openWeb(provider.downloadPage)
    }

    private fun checkForUpdates(version: PackageInfo, showPopupIfNewer: Boolean = false) {
        if (updateResult == GitHubUpdates.Result.Checking) return
        updateResult = GitHubUpdates.Result.Checking
        lifecycleScope.launch {
            val result = GitHubUpdates.check(version.longVersionCode, version.versionName.orEmpty())
            updateResult = result
            if (showPopupIfNewer && result is GitHubUpdates.Result.Latest && result.comparison == GitHubUpdates.Comparison.NEWER) {
                updatePopupRelease = result
            }
        }
    }

    private fun copySupportGroup() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.support_group), SUPPORT_GROUP))
    }

    private fun openWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            notify(R.string.open_link_failed)
        }
    }

    private fun saveDiagnostics(uri: Uri) {
        if (diagnosticsBusy) return
        diagnosticsBusy = true
        val providers = providerStatuses
        val service = app.diagnosticService()
        lifecycleScope.launch {
            try {
                val currentSettings = app.snapshotForBackup()
                val state = app.state.value
                val snapshot = state.copy(settings = currentSettings ?: state.settings)
                val ui = DiagnosticReport.SettingsUi(
                    lockScreenLyricsConfirmed, app.restartReminder.visible.value, translationHelpHidden,
                )
                withContext(Dispatchers.IO) {
                    val report = DiagnosticReport.collect(app, snapshot, providers, service, ui)
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                        it.write(report)
                    }
                }
                notify(R.string.diagnostics_export_ok)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                app.diagnostics.failure("Export diagnostic report", error)
                notify(R.string.diagnostics_export_failed)
            } finally {
                diagnosticsBusy = false
            }
        }
    }

    private fun exportSettings(uri: Uri) {
        if (app.state.value.connection != Connection.READY) {
            notify(R.string.backup_unavailable)
            return
        }
        transferBackup(R.string.backup_export_ok) {
            val settings = checkNotNull(app.snapshotForBackup())
            val hidden = translationHelpHidden
            withContext(Dispatchers.IO) {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).use {
                    it.write(CapsuleBackup.encode(settings, hidden).toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun importSettings(uri: Uri) {
        transferBackup(R.string.backup_import_ok) {
            val decoded = withContext(Dispatchers.IO) {
                checkNotNull(contentResolver.openInputStream(uri)).use(CapsuleBackup::decodeSnapshot)
            }
            check(app.restore(decoded.settings))
            decoded.translationHelpHidden?.let(::saveTranslationHelpHidden)
        }
    }

    private fun transferBackup(@StringRes success: Int, transfer: suspend () -> Unit) {
        if (backupBusy.value) return
        backupBusy.value = true
        lifecycleScope.launch {
            try {
                transfer()
                notify(success)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notify(R.string.backup_failed)
            } finally {
                backupBusy.value = false
            }
        }
    }

    private fun restartSystemUi() {
        if (restartBusy) return
        restartBusy = true
        lifecycleScope.launch {
            try {
                app.diagnostics.note("SystemUI restart requested")
                val appliedRevision = app.revisionBeforeRestart()
                val result = SystemUiRestart.request()
                app.diagnostics.note("SystemUI restart result: $result")
                if (result == SystemUiRestart.Result.RESTARTED) app.restartReminder.systemUiRestarted(appliedRevision)
                notify(when (result) {
                    SystemUiRestart.Result.RESTARTED -> R.string.restart_system_ui_completed
                    SystemUiRestart.Result.ROOT_REQUIRED -> R.string.restart_system_ui_root_required
                    SystemUiRestart.Result.FAILED -> R.string.restart_system_ui_failed
                })
            } finally {
                restartBusy = false
            }
        }
    }

    private fun notify(@StringRes message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PROVIDER_CHECK = "check_provider_installation"
        const val LOCK_SCREEN_LYRICS_CONFIRMED = "lock_screen_lyrics_confirmed"
    }
}

private const val SUPPORT_GROUP = "1102727864"
private const val ASTRAFLOW_PACKAGE = "com.astraflow.tool"

@Composable
private fun SettingsPage(
    state: TerraMoorApp.State,
    app: TerraMoorApp,
    version: String,
    providerStatuses: Map<LyricsProvider, Boolean>,
    askForProviderCheck: Boolean,
    setProviderCheck: (Boolean) -> Unit,
    openProvider: (LyricsProvider) -> Unit,
    lockScreenLyricsConfirmed: Boolean,
    confirmLockScreenLyrics: () -> Unit,
    translationHelpHidden: Boolean,
    onHideTranslationHelp: () -> Unit,
    openRepository: () -> Unit,
    updateResult: GitHubUpdates.Result,
    updatePopupRelease: GitHubUpdates.Result.Latest?,
    onDismissUpdatePopup: () -> Unit,
    onOpenUpdateRelease: (String) -> Unit,
    checkForUpdates: () -> Unit,
    openReleases: () -> Unit,
    copySupportGroup: () -> Unit,
    canUseBackup: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    canExportDiagnostics: Boolean,
    onExportDiagnostics: () -> Unit,
    restartBusy: Boolean,
    onRestartSystemUi: () -> Unit,
) {
    val tabs = SettingsTab.entries
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val pageState = rememberSaveableStateHolder()
    val currentTab = tabs.getOrElse(pager.currentPage) { SettingsTab.HOME }
    val restartReminderVisible by app.restartReminder.visible.collectAsState()
    var showRestartConfirmation by remember { mutableStateOf(false) }
    var showProviderPrompt by rememberSaveable { mutableStateOf(askForProviderCheck) }
    var showLockScreenPrompt by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lockScreenLyricsConfirmed) {
        if (!lockScreenLyricsConfirmed && pager.currentPage == SettingsTab.TUNING.ordinal) {
            pager.scrollToPage(SettingsTab.HOME.ordinal)
        }
    }
    if (showProviderPrompt) {
        ProviderCheckDialog { allowed ->
            showProviderPrompt = false
            setProviderCheck(allowed)
        }
    }
    if (showRestartConfirmation) {
        val dark = isSystemInDarkTheme()
        val dismiss = { showRestartConfirmation = false }
        CouiDialog(
            onDismissRequest = dismiss,
            dark = dark,
            placement = CouiDialogPlacement.Center,
            title = stringResource(R.string.restart_system_ui),
            message = stringResource(R.string.restart_system_ui_confirmation),
            dismissAction = CouiDialogAction(stringResource(android.R.string.cancel), onClick = dismiss),
            confirmAction = CouiDialogAction(
                text = stringResource(R.string.restart_system_ui_confirm),
                enabled = !restartBusy,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
                onClick = {
                    dismiss()
                    onRestartSystemUi()
                },
            ),
        )
    }
    fun selectTab(tab: SettingsTab): Boolean {
        scope.launch { pager.animateScrollToPage(tab.ordinal) }
        return true
    }
    if (showLockScreenPrompt) {
        LockScreenLyricsDialog(
            onDismiss = { showLockScreenPrompt = false },
            onConfirm = {
                confirmLockScreenLyrics()
                showLockScreenPrompt = false
                scope.launch { pager.animateScrollToPage(SettingsTab.TUNING.ordinal) }
            },
        )
    }
    if (updatePopupRelease != null && !showProviderPrompt && !showRestartConfirmation && !showLockScreenPrompt) {
        UpdateAvailableDialog(
            release = updatePopupRelease,
            currentVersion = version,
            onDismiss = onDismissUpdatePopup,
            onDownload = { onOpenUpdateRelease(updatePopupRelease.releaseUrl) },
        )
    }
    BackHandler(currentTab != SettingsTab.HOME) { selectTab(SettingsTab.HOME) }
    val background = CouiPageDefaults.themeColors().background
    FloatingScaffold(
        containerColor = background,
        topBar = {
            TopAppBarSurface(gradientColor = background, dark = isSystemInDarkTheme()) {
                SettingsTopBar(
                    titleVisibility = {
                        val offset = pager.currentPage - SettingsTab.TUNING.ordinal + pager.currentPageOffsetFraction
                        (1f - abs(offset)).coerceIn(0f, 1f)
                    },
                    showTitle = currentTab == SettingsTab.TUNING,
                    showRestartReminder = false,
                    restartAction = {
                        RestartSystemUiButton(enabled = !restartBusy) { showRestartConfirmation = true }
                    },
                )
            }
        },
        bottomBar = { FloatingTabBar(currentTab, ::selectTab) },
    ) { padding ->
        val topPadding = remember(padding) {
            object : PaddingValues by padding {
                override fun calculateBottomPadding() = 0.dp
            }
        }
        // Read the scaffold's padding during measurement, after it measures the shared header.
        val content = Modifier.fillMaxSize().padding(topPadding).consumeWindowInsets(padding)
        val bottomClearance = padding.calculateBottomPadding()
        ProvideCouiMenuBackdrop(isolateContent = false) { backdrop ->
            HorizontalPager(
                state = pager,
                modifier = backdrop.then(content),
                beyondViewportPageCount = 1,
                userScrollEnabled = true,
                key = { tabs[it].name },
            ) { page ->
                val tab = tabs[page]
                pageState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        SettingsTab.HOME -> HomePage(
                            Modifier.fillMaxSize(), bottomClearance, state, app,
                        )
                        SettingsTab.TUNING -> {
                            val visible by remember(pager, page) {
                                derivedStateOf { abs(pager.currentPage - page + pager.currentPageOffsetFraction) < 1f }
                            }
                            TuningPage(Modifier.fillMaxSize(), bottomClearance, background, state, app, visible,
                                translationHelpHidden, onHideTranslationHelp)
                        }
                        SettingsTab.ABOUT -> AboutPage(
                            Modifier.fillMaxSize(), bottomClearance, version, canUseBackup, onExport, onImport,
                            openRepository, updateResult, checkForUpdates, openReleases, copySupportGroup,
                            canExportDiagnostics, onExportDiagnostics,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppWhitelistDialog(
    initialSelected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val accent = CouiTheme.colorSchemeFor(dark).accent
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelected) }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            val musicHints = setOf(
                "music", "spotify", "apple", "soundcloud", "tidal", "deezer", "youtube", "zing",
                "nhaccuatui", "nct", "square", "poweramp", "neutron", "foobar", "netease", "qqmusic",
                "kugou", "kuwo", "gramophone", "salt", "oto", "retro", "musicolet", "symfonium", "plexamp",
            )
            resolved.asSequence()
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    val label = info.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
                    pkg to label
                }
                .distinctBy { it.first }
                .sortedWith(
                    compareByDescending<Pair<String, String>> { it.first in initialSelected }
                        .thenByDescending { (pkg, label) ->
                            val low = "$pkg $label".lowercase()
                            musicHints.any { it in low }
                        }
                        .thenBy { it.second.lowercase() },
                )
                .toList()
        }
        installedApps = loaded
    }

    val filtered = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) installedApps
        else installedApps.filter { (pkg, label) ->
            label.lowercase().contains(q) || pkg.lowercase().contains(q)
        }
    }

    ComposeDialog(onDismissRequest = onDismiss) {
        val surfaceColor = if (dark) Color(0xFF1F2126) else Color(0xFFFDFDFF)
        val fieldBg = if (dark) Color(0xFF2B2E35) else Color(0xFFF1F3F7)
        val titleColor = if (dark) Color.White else Color(0xFF111318)
        val subColor = if (dark) Color(0xFFA0A6B2) else Color(0xFF666D7A)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(surfaceColor)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.app_whitelist_select),
                style = COUITheme.textStyles.title2,
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(fieldBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.app_whitelist_search),
                        color = subColor,
                        style = COUITheme.textStyles.body2,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = COUITheme.textStyles.body2.copy(color = titleColor),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
            ) {
                items(filtered, key = { it.first }) { (pkg, label) ->
                    val checked = pkg in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selected = if (checked) selected - pkg else selected + pkg
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (checked) accent else fieldBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) {
                                Icon(
                                    painterResource(R.drawable.ic_status_check),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                color = titleColor,
                                style = COUITheme.textStyles.body1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = pkg,
                                color = subColor,
                                style = COUITheme.textStyles.body2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismiss,
                )
                Button(
                    onClick = { onSave(selected) },
                    colors = ButtonDefaults.buttonColors(color = accent),
                ) {
                    Text(
                        text = stringResource(R.string.app_whitelist_save),
                        color = Color.White,
                        style = COUITheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    release: GitHubUpdates.Result.Latest,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val header = stringResource(R.string.update_dialog_message, release.tag, currentVersion)
    val body = if (release.releaseNotes.isNotBlank()) "$header\n\n${release.releaseNotes}" else header
    CouiDialog(
        onDismissRequest = onDismiss,
        dark = dark,
        placement = CouiDialogPlacement.Center,
        title = stringResource(R.string.update_dialog_title),
        message = body,
        dismissAction = CouiDialogAction(stringResource(R.string.update_dialog_later), onClick = onDismiss),
        confirmAction = CouiDialogAction(
            text = stringResource(R.string.update_dialog_download),
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
            onClick = onDownload,
        ),
    )
}

@Composable
private fun ProviderCheckDialog(onDecision: (Boolean) -> Unit) {
    val dark = isSystemInDarkTheme()
    CouiDialog(
        onDismissRequest = { onDecision(false) },
        dark = dark,
        placement = CouiDialogPlacement.Center,
        title = stringResource(R.string.provider_check_enable),
        message = stringResource(R.string.provider_check_explanation),
        dismissAction = CouiDialogAction(stringResource(R.string.provider_check_later), onClick = { onDecision(false) }),
        confirmAction = CouiDialogAction(
            text = stringResource(R.string.provider_check_allow),
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
            onClick = { onDecision(true) },
        ),
    )
}

@Composable
private fun LockScreenLyricsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val dark = isSystemInDarkTheme()
    CouiDialog(
        onDismissRequest = onDismiss,
        dark = dark,
        placement = CouiDialogPlacement.Center,
        title = stringResource(R.string.lock_screen_lyrics_check),
        message = stringResource(R.string.lock_screen_lyrics_question),
        dismissAction = CouiDialogAction(stringResource(R.string.lock_screen_lyrics_later), onClick = onDismiss),
        confirmAction = CouiDialogAction(
            text = stringResource(R.string.lock_screen_lyrics_seen),
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
            onClick = onConfirm,
        ),
    ) {
        Image(
            painter = painterResource(R.drawable.lock_screen_lyrics_example),
            contentDescription = stringResource(R.string.lock_screen_lyrics_example_description),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
private fun installedColor() = if (isSystemInDarkTheme()) Color(0xFF56D687) else Color(0xFF15783D)

@Composable
internal fun unavailableColor() = if (isSystemInDarkTheme()) Color(0xFFFF797D) else Color(0xFFB72232)

@Composable
private fun HomePage(
    modifier: Modifier,
    bottomClearance: Dp,
    state: TerraMoorApp.State,
    app: TerraMoorApp,
) {
    val context = LocalContext.current
    val ready = state.connection == Connection.READY
    val (title, hint) = when (state.connection) {
        Connection.CHECKING -> R.string.status_checking to null
        Connection.DISCONNECTED -> R.string.status_disconnected to R.string.status_disconnected_hint
        Connection.NO_SCOPE -> R.string.status_no_scope to R.string.status_no_scope_hint
        Connection.READY -> R.string.status_ready to null
        Connection.FAILED -> R.string.status_failed to R.string.status_failed_hint
    }
    val sourceOptions = listOf(
        LyricSourceMode.AUTO to stringResource(R.string.source_auto),
        LyricSourceMode.APPLE_MUSIC to stringResource(R.string.source_apple_music),
        LyricSourceMode.BETTER_LYRICS to stringResource(R.string.source_better_lyrics),
        LyricSourceMode.LRCLIB to stringResource(R.string.source_lrclib),
        LyricSourceMode.KUGOU to stringResource(R.string.source_kugou),
        LyricSourceMode.LYRICS_OVH to stringResource(R.string.source_lyrics_ovh),
        LyricSourceMode.EXTERNAL_ONLY to stringResource(R.string.source_external_only),
    )
    val selectedSourceIndex = sourceOptions.indexOfFirst { it.first == state.settings.lyricSourceMode }.coerceAtLeast(0)
    val langOptions = TranslateTargetLang.entries.map { lang ->
        lang to if (lang == TranslateTargetLang.SYSTEM) stringResource(R.string.translate_lang_system) else lang.label
    }
    val selectedLangIndex = langOptions.indexOfFirst { it.first == state.settings.translateTargetLang }.coerceAtLeast(0)
    var hookStatus by remember {
        mutableStateOf(shop.terramoor.github.io.lyrics.LyricBridgeProvider.readHookStatus(context))
    }
    var cacheStats by remember {
        mutableStateOf(shop.terramoor.github.io.lyrics.OnlineLyricFetcher.cacheStats(context))
    }
    var showWhitelistDialog by remember { mutableStateOf(false) }
    if (showWhitelistDialog) {
        AppWhitelistDialog(
            initialSelected = state.settings.appWhitelistPackages,
            onDismiss = { showWhitelistDialog = false },
            onSave = { selected ->
                showWhitelistDialog = false
                app.setAppWhitelist(selected)
            },
        )
    }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(
            shop.terramoor.github.io.lyrics.LyricBridgeProvider.HOOK_STATUS_PREFS,
            android.content.Context.MODE_PRIVATE,
        )
        hookStatus = shop.terramoor.github.io.lyrics.LyricBridgeProvider.readHookStatus(context)
        cacheStats = shop.terramoor.github.io.lyrics.OnlineLyricFetcher.cacheStats(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            hookStatus = shop.terramoor.github.io.lyrics.LyricBridgeProvider.readHookStatus(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    SettingsList(modifier, bottomClearance) {
        SettingsPageTitle(R.string.app_name)
        SettingsGroup(stringResource(R.string.section_connection)) {
            BasicComponent(
                title = stringResource(title),
                summary = hint?.let { stringResource(it) },
                endActions = if (state.connection != Connection.CHECKING) {
                    {
                        Icon(painterResource(if (ready) R.drawable.ic_status_check else R.drawable.ic_status_cross),
                            contentDescription = null, tint = if (ready) installedColor() else unavailableColor())
                    }
                } else null,
            )
            val hookRows = listOf(
                R.string.hook_status_fluid to (ready && hookStatus.fluidCloud),
                R.string.hook_status_lockscreen to (ready && hookStatus.lockscreen),
                R.string.hook_status_aod to (ready && hookStatus.aod),
            )
            hookRows.forEach { (labelRes, active) ->
                HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
                BasicComponent(
                    title = stringResource(labelRes),
                    endActions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(if (active) R.string.hook_active else R.string.hook_inactive),
                                color = if (active) installedColor() else COUITheme.colorScheme.onSurfaceVariantSummary,
                                style = COUITheme.textStyles.body2,
                            )
                            Icon(
                                painterResource(if (active) R.drawable.ic_status_check else R.drawable.ic_status_cross),
                                contentDescription = null,
                                tint = if (active) installedColor() else unavailableColor(),
                            )
                        }
                    },
                )
            }
        }
        SettingsGroup(stringResource(R.string.section_capsule)) {
            TactileSwitchPreference(
                checked = state.settings.enabled,
                onCheckedChange = { app.set(CapsuleSettings.ENABLED, it) },
                title = stringResource(R.string.enable_lyrics),
                enabled = ready,
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            TactileSwitchPreference(
                checked = state.settings.preventCapsuleCollapse,
                onCheckedChange = { app.set(CapsuleSettings.PREVENT_CAPSULE_COLLAPSE, it) },
                title = stringResource(R.string.prevent_capsule_collapse),
                summary = stringResource(R.string.prevent_capsule_collapse_summary),
                enabled = ready,
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            TactileSwitchPreference(
                checked = state.settings.appWhitelistEnabled,
                onCheckedChange = { app.set(CapsuleSettings.APP_WHITELIST_ENABLED, it) },
                title = stringResource(R.string.app_whitelist_title),
                summary = stringResource(R.string.app_whitelist_summary),
                enabled = ready,
            )
            if (state.settings.appWhitelistEnabled) {
                HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
                val count = state.settings.appWhitelistPackages.size
                BasicComponent(
                    title = stringResource(R.string.app_whitelist_select),
                    summary = if (count > 0) stringResource(R.string.app_whitelist_selected_count, count)
                    else stringResource(R.string.app_whitelist_none_selected),
                    enabled = ready,
                    onClick = { showWhitelistDialog = true },
                )
            }
        }
        SettingsGroup(stringResource(R.string.section_all_in_one)) {
            TuningDropdown(
                title = stringResource(R.string.lyric_source_mode),
                items = sourceOptions.map { it.second },
                selectedIndex = selectedSourceIndex,
                enabled = ready,
                onSelectedIndexChange = { idx ->
                    sourceOptions.getOrNull(idx)?.first?.let(app::setLyricSourceMode)
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            TactileSwitchPreference(
                checked = state.settings.autoTranslateVi,
                onCheckedChange = { app.set(CapsuleSettings.AUTO_TRANSLATE_VI, it) },
                title = stringResource(R.string.auto_translate_vi),
                summary = stringResource(R.string.auto_translate_vi_summary),
                enabled = ready,
            )
            if (state.settings.autoTranslateVi) {
                HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
                TuningDropdown(
                    title = stringResource(R.string.translate_target_lang),
                    items = langOptions.map { it.second },
                    selectedIndex = selectedLangIndex,
                    enabled = ready,
                    onSelectedIndexChange = { idx ->
                        langOptions.getOrNull(idx)?.first?.let(app::setTranslateTargetLang)
                    },
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            TactileSwitchPreference(
                checked = state.settings.lockscreenBridge,
                onCheckedChange = { app.set(CapsuleSettings.LOCKSCREEN_BRIDGE, it) },
                title = stringResource(R.string.lockscreen_bridge),
                summary = stringResource(R.string.lockscreen_bridge_summary),
                enabled = ready,
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            TactileSwitchPreference(
                checked = state.settings.aodUnlockWhitelist,
                onCheckedChange = { app.set(CapsuleSettings.AOD_UNLOCK_WHITELIST, it) },
                title = stringResource(R.string.aod_unlock_whitelist),
                summary = stringResource(R.string.aod_unlock_whitelist_summary),
                enabled = ready,
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            TactileSwitchPreference(
                checked = state.settings.autoClearCache,
                onCheckedChange = { enabled ->
                    app.set(CapsuleSettings.AUTO_CLEAR_CACHE, enabled)
                    if (enabled) {
                        shop.terramoor.github.io.lyrics.OnlineLyricFetcher.pruneCache(context)
                        cacheStats = shop.terramoor.github.io.lyrics.OnlineLyricFetcher.cacheStats(context)
                    }
                },
                title = stringResource(R.string.auto_clear_lyric_cache),
                summary = stringResource(R.string.auto_clear_lyric_cache_summary),
                enabled = ready,
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            val formattedCacheSize = android.text.format.Formatter.formatShortFileSize(context, cacheStats.totalBytes)
            BasicComponent(
                title = stringResource(R.string.clear_lyric_cache),
                summary = stringResource(R.string.lyric_cache_usage, formattedCacheSize, cacheStats.fileCount),
                onClick = {
                    shop.terramoor.github.io.lyrics.OnlineLyricFetcher.clearCache(context)
                    cacheStats = shop.terramoor.github.io.lyrics.OnlineLyricFetcher.cacheStats(context)
                    Toast.makeText(context, R.string.clear_lyric_cache_done, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun AboutPage(
    modifier: Modifier,
    bottomClearance: Dp,
    version: String,
    canUseBackup: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    openRepository: () -> Unit,
    updateResult: GitHubUpdates.Result,
    checkForUpdates: () -> Unit,
    openReleases: () -> Unit,
    copySupportGroup: () -> Unit,
    canExportDiagnostics: Boolean,
    onExportDiagnostics: () -> Unit,
) {
    SettingsList(modifier, bottomClearance) {
        SettingsPageTitle(R.string.tab_about)
        SettingsGroup(stringResource(R.string.section_app_info)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_name), color = COUITheme.colorScheme.onSurface)
                SettingSummary(stringResource(R.string.app_version, version))
                SettingSummary(stringResource(R.string.app_compatibility))
            }
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            AppLanguagePreference()
        }
        SettingsGroup(stringResource(R.string.section_support)) {
            val aboutContext = LocalContext.current
            BasicComponent(title = stringResource(R.string.open_repository), summary = GitHubUpdates.UPSTREAM_REPOSITORY, onClick = openRepository)
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            BasicComponent(
                title = stringResource(R.string.contributor_title),
                summary = stringResource(R.string.contributor_name),
                onClick = {
                    try {
                        aboutContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdates.REPOSITORY_URL)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(aboutContext, R.string.open_link_failed, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            UpdatePreference(updateResult, checkForUpdates, openReleases)
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            SupportGroupPreference(copySupportGroup)
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            TroubleshootingPreference(canExportDiagnostics, onExportDiagnostics)
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            BasicComponent(
                title = stringResource(R.string.diagnostics_export),
                summary = stringResource(R.string.diagnostics_export_hint),
                enabled = canExportDiagnostics,
                onClick = onExportDiagnostics,
            )
        }
        SettingsGroup(stringResource(R.string.section_backup)) {
            BasicComponent(
                title = stringResource(R.string.backup_export),
                summary = stringResource(R.string.backup_export_hint),
                enabled = canUseBackup,
                onClick = onExport,
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            BasicComponent(
                title = stringResource(R.string.backup_import),
                summary = stringResource(R.string.backup_import_hint),
                enabled = canUseBackup,
                onClick = onImport,
            )
        }
    }
}

@Composable
private fun TroubleshootingPreference(canExportDiagnostics: Boolean, onExportDiagnostics: () -> Unit) {
    val packageManager = LocalContext.current.packageManager
    var astraFlowInstalled by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val title = stringResource(R.string.troubleshoot)
    BasicComponent(title = title, onClick = {
        astraFlowInstalled = try {
            packageManager.getPackageInfo(ASTRAFLOW_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    })

    val installed = astraFlowInstalled ?: return
    val dark = isSystemInDarkTheme()
    val dismiss = { astraFlowInstalled = null }
    val dialogColors = CouiDialogDefaults.themeColors(dark)
    CouiDialog(
        onDismissRequest = dismiss,
        dark = dark,
        placement = CouiDialogPlacement.Center,
        title = title,
        message = if (installed) stringResource(R.string.troubleshoot_astraflow_installed)
            else stringResource(R.string.troubleshoot_send_log, SUPPORT_GROUP),
        colors = if (installed) dialogColors.copy(message = unavailableColor()) else dialogColors,
        dismissAction = if (installed) null else CouiDialogAction(stringResource(android.R.string.cancel), onClick = dismiss),
        confirmAction = CouiDialogAction(
            text = stringResource(if (installed) android.R.string.ok else R.string.diagnostics_export),
            enabled = installed || canExportDiagnostics,
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
            onClick = {
                dismiss()
                if (!installed) onExportDiagnostics()
            },
        ),
    )
}

@Composable
private fun SettingsTopBar(
    titleVisibility: () -> Float,
    showTitle: Boolean,
    showRestartReminder: Boolean,
    restartAction: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
            .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().animateContentSize(tween(220), alignment = Alignment.TopCenter),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showRestartReminder) {
                    Text(
                        text = stringResource(R.string.restart_system_ui_recommended),
                        modifier = Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                        color = unavailableColor(),
                        style = COUITheme.textStyles.body2,
                    )
                    Spacer(Modifier.size(48.dp))
                }
            }
            if (!showRestartReminder) {
                Text(
                    text = stringResource(R.string.tab_tuning),
                    modifier = Modifier.graphicsLayer { alpha = titleVisibility() }
                        .then(if (showTitle) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}),
                    color = COUITheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        // animateContentSize clips its children; keep the COUI shadow outside it.
        Box(Modifier.align(Alignment.TopEnd).heightIn(min = 52.dp), contentAlignment = Alignment.CenterEnd) {
            restartAction()
        }
    }
}

@Composable
private fun SettingsPageTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.semantics { heading() },
        color = COUITheme.colorScheme.onSurface,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun UpdatePreference(result: GitHubUpdates.Result, onCheck: () -> Unit, onOpenReleases: () -> Unit) {
    val checking = result == GitHubUpdates.Result.Checking
    val hasNewVersion = result is GitHubUpdates.Result.Latest && result.comparison == GitHubUpdates.Comparison.NEWER
    val summary = when (result) {
        GitHubUpdates.Result.Idle -> null
        GitHubUpdates.Result.Checking -> stringResource(R.string.update_checking)
        GitHubUpdates.Result.NoRelease -> stringResource(R.string.update_no_release)
        GitHubUpdates.Result.Failed -> stringResource(R.string.update_check_failed)
        is GitHubUpdates.Result.Latest -> {
            val message = when (result.comparison) {
                GitHubUpdates.Comparison.NEWER -> R.string.update_available
                GitHubUpdates.Comparison.UP_TO_DATE -> R.string.update_up_to_date
                GitHubUpdates.Comparison.UNKNOWN -> R.string.update_latest_tag
            }
            stringResource(message, result.tag)
        }
    }
    BasicComponent(
        title = stringResource(R.string.update_title),
        summary = summary,
        endActions = {
            AboutActionButton(stringResource(R.string.check_updates), onCheck, enabled = !checking)
        },
    )
    if (hasNewVersion) {
        BasicComponent(title = stringResource(R.string.update_view_release), onClick = onOpenReleases)
    }
}

@Composable
private fun SupportGroupPreference(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    BasicComponent(
        title = stringResource(R.string.support_group),
        summary = SUPPORT_GROUP,
        endActions = {
            CopySupportGroupButton(copied) {
                onCopy()
                copied = true
            }
        },
    )
}

@Composable
private fun CopySupportGroupButton(copied: Boolean, onClick: () -> Unit) {
    val normalColors = ButtonDefaults.textButtonColors()
    val success = installedColor()
    val background by animateColorAsState(
        targetValue = if (copied) lerp(normalColors.color, success, 0.16f) else normalColors.color,
        animationSpec = tween(220),
        label = "Copy button background",
    )
    val copyLabel = stringResource(R.string.copy_support_group)
    val copiedLabel = stringResource(R.string.support_group_copied)
    Button(
        onClick = onClick,
        minWidth = ButtonDefaults.MinWidthSmall,
        minHeight = ButtonDefaults.MinHeightSmall,
        cornerRadius = ButtonDefaults.CornerRadiusSmall,
        insideMargin = ButtonDefaults.InsideMarginSmall,
        colors = ButtonColors(background, normalColors.disabledColor, normalColors.textColor, normalColors.disabledTextColor),
    ) {
        AnimatedContent(
            targetState = copied,
            modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(if (copied) copiedLabel else copyLabel) },
            contentAlignment = Alignment.Center,
            transitionSpec = {
                (fadeIn(tween(140, delayMillis = 60)) togetherWith fadeOut(tween(90)))
                    .using(SizeTransform { _, _ -> tween(220) })
            },
            label = "Copy button label and size",
        ) { isCopied ->
            Text(
                text = if (isCopied) copiedLabel else copyLabel,
                color = if (isCopied) success else normalColors.textColor,
                style = COUITheme.textStyles.button.copy(fontSize = 14.sp),
            )
        }
    }
}

@Composable
private fun AboutActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        minWidth = ButtonDefaults.MinWidthSmall,
        minHeight = ButtonDefaults.MinHeightSmall,
        cornerRadius = ButtonDefaults.CornerRadiusSmall,
        insideMargin = ButtonDefaults.InsideMarginSmall,
        textStyle = COUITheme.textStyles.button.copy(fontSize = 14.sp),
    )
}

@Composable
private fun RestartSystemUiButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = COUITheme.colorScheme
    val haptic = rememberCouiHaptic()
    TopAppBarButton(
        onClick = {
            haptic(CouiHapticEffect.Strength)
            onClick()
        },
        enabled = enabled,
        style = CouiTopAppBarButtonDefaults.style(dark = isSystemInDarkTheme()),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_restart_rounded),
            contentDescription = stringResource(R.string.restart_system_ui),
            tint = if (enabled) colors.onSurface else colors.disabledOnSecondaryVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun SettingsList(
    modifier: Modifier,
    bottomClearance: Dp,
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.verticalScroll(rememberScrollState())
            .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = 12.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) { content() }
}

@Composable
internal fun SettingsGroup(title: String?, notice: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingSummary(title)
                if (notice != null) Text(notice, color = unavailableColor(), style = COUITheme.textStyles.body2)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = CouiPageDefaults.themeColors().card),
        ) { content() }
    }
}

@Composable
internal fun SettingSummary(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = COUITheme.colorScheme.onSurfaceVariantSummary, style = COUITheme.textStyles.body2)
}
