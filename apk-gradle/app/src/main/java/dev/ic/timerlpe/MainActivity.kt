package dev.ic.timerlpe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.NavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.blur.BlendColorEntry
import io.github.suqi8.coui.kmp.blur.BlurColors
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.blur.textureBlur
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.preference.WindowDropdownPreference
import io.github.suqi8.coui.kmp.shader.isRenderEffectSupported
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.utils.PressFeedbackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.FileChannel

private const val PROJECT_URL = "https://github.com/kzktor/ic-timer-lpe"
private const val COMMUNITY_URL = "https://qm.qq.com/q/MBM9NcDXYy"

private data class LicenseText(
    val name: String,
    val url: String? = null,
    val asset: String? = null,
)

private data class LicenseItem(
    val name: String,
    val author: String? = null,
    val website: String? = null,
    val licenses: List<LicenseText> = emptyList(),
)

private enum class AboutPage { HOME, LICENSES }

class MainActivity : ComponentActivity() {
    private val logs = mutableStateListOf<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    private var busy by mutableStateOf(false)
    private var authorized by mutableStateOf(false)
    private var binderReady by mutableStateOf(false)
    private var bound by mutableStateOf(false)
    private var kernelVersion by mutableStateOf("未知")
    private var deviceModel by mutableStateOf("未知")
    private var selinuxText by mutableStateOf("未知")
    private var ksuIndex by mutableStateOf(0)
    private var safeMode by mutableStateOf(false)
    private var tab by mutableStateOf(AppTab.HOME)
    private var showManual by mutableStateOf(false)
    private var runStage by mutableStateOf("")
    private var runStageIndex = -1
    private var offsetPrompt by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<Notice?>(null)
    private var showDonate by mutableStateOf(false)

    private var service: IIcService? = null
    private var logPath: String? = null
    private var logOff = 0
    private var polling = false
    private var runStamp = ""

    private val prefs by lazy { getSharedPreferences("ic_timer", MODE_PRIVATE) }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ_SHIZUKU) {
            authorized = result == PackageManager.PERMISSION_GRANTED
            addLog(
                if (authorized) "Shizuku 权限: 已允许" else "Shizuku 权限: 被拒绝($result)"
            )
            if (authorized) bindUserService()
            refreshAuth()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        binderReady = Shizuku.pingBinder()
        refreshAuth()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderReady = false
        bound = false
        service = null
        refreshAuth()
    }

    private val parsePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            addLog("[解析偏移] 已取消")
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        parseSelected(uri)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IIcService.Stub.asInterface(binder)
            bound = true
            lifecycleScope.launch {
                val id = withContext(Dispatchers.IO) {
                    runCatching { service?.identity() }.getOrNull() ?: "读取身份失败"
                }
                val dev = withContext(Dispatchers.IO) {
                    runCatching {
                        service?.runCmd(
                            "getprop ro.product.model; " +
                                "getprop ro.build.display.id; uname -r"
                        )
                    }.getOrNull() ?: ""
                }
                addLog("[service] $id")
                if (dev.isNotBlank()) addLog("[设备] $dev")
                refreshStaticState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        binderReady = Shizuku.pingBinder()
        refreshAuth()
        ksuIndex = prefs.getInt("ksu_index", 0).coerceIn(0, KSU_NAMES.lastIndex)
        safeMode = prefs.getBoolean("safe_mode", false)
        kernelVersion = runCatching { android.system.Os.uname().release }.getOrDefault("未知")
        deviceModel = resolveDeviceName()
        refreshStaticState()
        checkDeviceAndKernel()
        if (authorized && binderReady) bindUserService()

        setContent {
            val themeController = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            COUITheme(themeController) {
                val appBackdrop = rememberAppBackdrop()
                Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                (slideInHorizontally(tween(240)) { it / 4 * dir } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(tween(240)) { -it / 4 * dir } + fadeOut(tween(240)))
                            },
                            label = "tab",
                            modifier = Modifier.fillMaxSize()
                        ) { t ->
                            when (t) {
                                AppTab.LOGS -> LogScreen(state = logs.toList(), backdrop = appBackdrop)
                                AppTab.ABOUT -> AboutScreen(backdrop = appBackdrop)
                                AppTab.HOME -> HomeScreen(backdrop = appBackdrop)
                            }
                        }
                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            BlurredBar(backdrop = appBackdrop) {
                                BottomBar(selected = tab, onSelect = { tab = it })
                            }
                        }
                        CleanupDialog()
                        OffsetPromptDialog()
                        NoticeDialog()
                        DonateDialog(show = showDonate, onDismiss = { showDonate = false })
                        AnimatedVisibility(
                            visible = showManual,
                            enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)),
                            exit = slideOutHorizontally(tween(240)) { it } + fadeOut(tween(240))
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                ManualScreen(onBack = { showManual = false })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAuth()
        if (authorized && !bound) bindUserService()
        refreshStaticState()
    }

    /* ------------------------------------------------------------------ */
    /* Home                                                                */
    /* ------------------------------------------------------------------ */

    @Composable
    private fun rememberAppBackdrop(): LayerBackdrop? {
        if (!isRenderEffectSupported()) return null
        val surfaceColor = COUITheme.colorScheme.surface
        return rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    }

    @Composable
    private fun BlurredBar(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
        Box(
            modifier = if (backdrop != null) {
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = 25f,
                    colors = BlurColors(
                        blendColors = listOf(
                            BlendColorEntry(color = COUITheme.colorScheme.surface.copy(0.87f))
                        )
                    )
                )
            } else {
                Modifier
            }
        ) {
            content()
        }
    }

    @Composable
    private fun HomeScreen(backdrop: LayerBackdrop?) {
        val scrollBehavior = COUIScrollBehavior()
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
            topBar = {
                BlurredBar(backdrop = backdrop) {
                    TopAppBar(
                        title = "IC Timer LPE",
                        scrollBehavior = scrollBehavior,
                        color = Color.Transparent
                    )
                }
            },
            popupHost = { }
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
                    )
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = inner.calculateTopPadding() + 12.dp,
                        bottom = 12.dp + 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        StatusCard(granted = binderReady && authorized && bound)
                    }
                    item {
                        TextButton(
                            text = when {
                                busy -> runStage.ifBlank { "运行中…" }
                                runStage.isNotEmpty() -> runStage
                                !binderReady -> "Shizuku 未运行（点击打开）"
                                !authorized -> "授权 Shizuku"
                                else -> "一键越狱"
                            },
                            onClick = {
                                if (busy) return@TextButton
                                when {
                                    !binderReady -> {
                                        addLog("先启动 Shizuku（无线调试 / ADB），不能用 root 启动")
                                        openManager()
                                    }
                                    !authorized -> Shizuku.requestPermission(REQ_SHIZUKU)
                                    else -> runCore()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                WindowDropdownPreference(
                                    title = "ROOT管理器",
                                    items = KSU_NAMES,
                                    selectedIndex = ksuIndex,
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Tag,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    },
                                    onSelectedIndexChange = {
                                        ksuIndex = it
                                        prefs.edit().putInt("ksu_index", it).apply()
                                    }
                                )
                                HorizontalDivider()
                                SwitchPreference(
                                    title = "安全模式",
                                    checked = safeMode,
                                    onCheckedChange = {
                                        safeMode = it
                                        prefs.edit().putBoolean("safe_mode", it).apply()
                                    },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Security,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "使用手册",
                                    summary = "规则、支持机型与常见问题",
                                    onClick = { showManual = true },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.MenuBook,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "支持开发者",
                                    summary = "赞赏二维码",
                                    onClick = { showDonate = true },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                ArrowPreference(
                                    title = "解析偏移",
                                    summary = "从全量包或 boot.img 解析偏移",
                                    onClick = { parseOffsets() },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Tune,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "清理",
                                    summary = "清理本工具临时文件 / 清理 KSU",
                                    onClick = { cleanup() },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.BugReport,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "诊断",
                                    summary = "输出设备身份、符号表与管理器状态",
                                    onClick = { diag() },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Info,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                            }
                        }
                    }
                    item {
                        InfoCard(
                            kernelVersion = kernelVersion,
                            exploitLabel = "CVE-2026-98260（POSIX CPU timer UAF）",
                            deviceModel = deviceModel,
                            fingerprint = Build.FINGERPRINT,
                            selinux = selinuxText,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusCard(granted: Boolean) {
        val green = if (isSystemInDarkTheme()) Color(0xFF1A3825) else Color(0xFFDFFAE4)
        val greenTint = Color(0xFF36D167)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (granted) {
                CardDefaults.defaultColors(color = green)
            } else {
                CardDefaults.defaultColors()
            },
            showIndication = false,
            pressFeedbackType = PressFeedbackType.Tilt
        ) {
            Box(Modifier.fillMaxWidth().height(140.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(100.dp),
                        tint = if (granted) greenTint else Color(0xFF9AA0A6)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    Column {
                        Text(
                            text = if (granted) "Shizuku 已连接" else "Shizuku 未连接",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (granted) "shell 用户服务已就绪，可尝试越狱"
                            else "请用无线调试 / ADB 启动 Shizuku 并授权",
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun InfoText(
        icon: ImageVector,
        title: String,
        content: String,
        bottomPadding: Dp = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(24.dp),
                tint = COUITheme.colorScheme.onSurface
            )
            Column {
                Text(
                    text = title,
                    fontSize = COUITheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = COUITheme.colorScheme.onSurface
                )
                Text(
                    text = content,
                    fontSize = COUITheme.textStyles.body2.fontSize,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun InfoCard(
        kernelVersion: String,
        exploitLabel: String,
        deviceModel: String,
        fingerprint: String,
        selinux: String,
        modifier: Modifier = Modifier
    ) {
        Card(modifier = modifier) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(Icons.Filled.DeveloperBoard, "内核版本", kernelVersion)
                InfoText(Icons.Filled.BugReport, "漏洞利用方式", exploitLabel)
                InfoText(Icons.Filled.Smartphone, "设备型号", deviceModel)
                InfoText(Icons.Filled.Fingerprint, "系统指纹", fingerprint)
                InfoText(Icons.Filled.Security, "SELinux 状态", selinux, bottomPadding = 0.dp)
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Logs                                                                */
    /* ------------------------------------------------------------------ */

    @Composable
    private fun LogScreen(state: List<String>, backdrop: LayerBackdrop?) {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop) {
                    TopAppBar(
                        title = "日志",
                        color = Color.Transparent,
                        navigationIcon = {
                            IconButton(onClick = { logs.clear() }) {
                                Icon(
                                    imageVector = COUIIcons.Notes,
                                    contentDescription = "清空日志",
                                    tint = COUITheme.colorScheme.onSurface
                                )
                            }
                        }
                    )
                }
            },
            popupHost = { }
        ) { inner ->
            if (state.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(inner),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无日志",
                        fontSize = 14.sp,
                        color = COUITheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(state.size) {
                    listState.scrollToItem(state.lastIndex)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = inner.calculateTopPadding() + 12.dp,
                            bottom = 12.dp + 100.dp,
                            start = 12.dp,
                            end = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state) { line ->
                            Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ManualScreen(onBack: () -> Unit) {
        BackHandler(onBack = onBack)
        val context = LocalContext.current
        val markdown = remember {
            runCatching {
                context.assets.open("manual.md").bufferedReader().use { it.readText() }
            }.getOrElse { "# 使用手册\n\n手册内容读取失败。" }
        }
        val blocks = remember(markdown) { parseManualMarkdown(markdown) }
        Scaffold(popupHost = { }) { inner ->
            val codeBackground = COUITheme.colorScheme.onSurface.copy(alpha = 0.08f)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = inner.calculateTopPadding() + 16.dp,
                    start = 20.dp,
                    end = 20.dp,
                    bottom = inner.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(blocks) { _, block ->
                    MdBlockView(block, codeBackground)
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* About                                                               */
    /* ------------------------------------------------------------------ */

    @Composable
    private fun AboutScreen(backdrop: LayerBackdrop?) {
        var page by remember { mutableStateOf(AboutPage.HOME) }
        var selected by remember { mutableStateOf<LicenseItem?>(null) }
        BackHandler(enabled = selected != null || page == AboutPage.LICENSES) {
            if (selected != null) selected = null else page = AboutPage.HOME
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState == AboutPage.LICENSES) {
                    (slideInHorizontally(tween(240)) { it } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(240)) { -it / 4 } + fadeOut(tween(240)))
                } else {
                    (slideInHorizontally(tween(240)) { -it / 4 } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(240)) { it } + fadeOut(tween(240)))
                }
            },
            label = "aboutPage"
        ) { current ->
            if (current == AboutPage.HOME) {
                AboutHomeScreen(
                    backdrop = backdrop,
                    onLicenses = { page = AboutPage.LICENSES }
                )
            } else {
                LicenseListScreen(
                    backdrop = backdrop,
                    onBack = { page = AboutPage.HOME },
                    onSelect = { selected = it }
                )
            }
        }
        LicenseDetailDialog(selected, onDismiss = { selected = null })
    }

    @Composable
    private fun AboutHomeScreen(
        backdrop: LayerBackdrop?,
        onLicenses: () -> Unit
    ) {
        val uriHandler = LocalUriHandler.current
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
            topBar = {
                BlurredBar(backdrop = backdrop) {
                    TopAppBar(title = "关于", color = Color.Transparent)
                }
            },
            popupHost = { }
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = inner.calculateTopPadding() + 12.dp,
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp + 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "IC Timer LPE",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    fontSize = 13.sp,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                                )
                                Text(
                                    text = "本软件免费开源，作者酷安@散花神木",
                                    fontSize = 13.sp,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                ArrowPreference(
                                    title = "查看源代码",
                                    summary = "在 GitHub 上查看源代码",
                                    onClick = { uriHandler.openUri(PROJECT_URL) },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Code,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "加入社区",
                                    summary = "加入 QQ 群",
                                    onClick = { uriHandler.openUri(COMMUNITY_URL) },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Group,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "问题反馈",
                                    summary = "在 GitHub Issues 提交问题",
                                    onClick = { uriHandler.openUri("$PROJECT_URL/issues") },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.BugReport,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "支持开发者",
                                    summary = "赞赏二维码",
                                    onClick = { showDonate = true },
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                                HorizontalDivider()
                                ArrowPreference(
                                    title = "开放源代码许可",
                                    summary = "本项目与所参考开源项目的许可证信息",
                                    onClick = onLicenses,
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Filled.Copyright,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                            tint = COUITheme.colorScheme.onBackground
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DonateDialog(show: Boolean, onDismiss: () -> Unit) {
        OverlayDialog(
            show = show,
            title = "支持开发者",
            onDismissRequest = onDismiss,
            content = {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                ) {
                    DonateQr(label = "赞赏码", assetPath = "donate.png", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "关闭",
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }

    @Composable
    private fun DonateQr(label: String, assetPath: String, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val bitmap = remember(assetPath) {
            runCatching {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
            Text(
                text = label,
                fontSize = 13.sp,
                color = COUITheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }

    @Composable
    private fun LicenseListScreen(
        backdrop: LayerBackdrop?,
        onBack: () -> Unit,
        onSelect: (LicenseItem) -> Unit
    ) {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop) {
                    TopAppBar(
                        title = "开放源代码许可",
                        color = Color.Transparent,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = COUIIcons.Back,
                                    contentDescription = "返回",
                                    tint = COUITheme.colorScheme.onSurface
                                )
                            }
                        }
                    )
                }
            },
            popupHost = { }
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = inner.calculateTopPadding() + 12.dp,
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp + 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { SectionLabel("本项目") }
                    items(licenseItems) { item ->
                        LicenseCard(item) { onSelect(item) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }

    @Composable
    private fun LicenseChip(text: String) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(COUITheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                color = COUITheme.colorScheme.onSecondaryContainer
            )
        }
    }

    @Composable
    private fun LicenseCard(item: LicenseItem, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    item.author?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    if (item.licenses.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.licenses.take(3).forEach { LicenseChip(it.name) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LicenseDetailDialog(item: LicenseItem?, onDismiss: () -> Unit) {
        var lastItem by remember { mutableStateOf(item) }
        if (item != null) lastItem = item
        val display = lastItem ?: return
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val contents = remember(display) {
            display.licenses.map { license ->
                val text = license.asset?.let { asset ->
                    runCatching {
                        context.assets.open(asset).bufferedReader().use { it.readText() }
                    }.getOrNull()
                }
                license to text
            }
        }
        OverlayDialog(
            show = item != null,
            title = display.name,
            onDismissRequest = onDismiss,
            content = {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                ) {
                    if (display.licenses.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            display.licenses.forEach { LicenseChip(it.name) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (contents.isEmpty()) {
                                Text("未声明许可证文本", fontSize = 13.sp)
                            }
                            contents.forEach { (license, body) ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = license.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = COUITheme.colorScheme.primary
                                    )
                                    Text(
                                        text = body ?: "未内置许可证文本",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        display.website?.let { url ->
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = "访问主页",
                                onClick = { uriHandler.openUri(url) }
                            )
                        }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = "关闭",
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }

    @Composable
    private fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
        NavigationBar(color = Color.Transparent) {
            AppTab.entries.forEach { t ->
                NavigationBarItem(
                    selected = t == selected,
                    onClick = { onSelect(t) },
                    icon = t.icon,
                    label = t.label
                )
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Backend                                                             */
    /* ------------------------------------------------------------------ */

    /** Returns the bound user service, or tells the user what is still missing. */
    private fun requireService(action: String): IIcService? {
        val s = service
        if (s != null) return s
        val msg = when {
            !binderReady -> "Shizuku 未运行：请先用无线调试 / ADB 启动 Shizuku"
            !authorized -> "Shizuku 未授权：请先允许授权"
            else -> "用户服务未绑定：请稍候或重新打开应用"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        addLog("[$action] $msg")
        return null
    }

    private fun refreshAuth() {
        authorized = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        binderReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    private fun bindUserService() {
        runCatching {
            val cn = ComponentName(this, IcService::class.java)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(true)
                .debuggable(false)
                .version(1)
                .tag("ic-lpe")
                .processNameSuffix(":icsvc")
            Shizuku.bindUserService(args, conn)
            addLog("正在绑定 shell 用户服务…")
        }.onFailure { addLog("绑定失败: $it") }
    }

    private fun openManager() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) {
            runCatching { startActivity(intent) }
        } else {
            Toast.makeText(this, "未安装 Shizuku", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStaticState() {
        lifecycleScope.launch {
            val selinux = withContext(Dispatchers.IO) {
                runCatching {
                    val s = service
                    if (s != null) s.runCmd("getenforce") else shellGetEnforce()
                }.getOrNull()?.trim().orEmpty()
            }
            selinuxText = mapSelinux(selinux)
        }
    }

    private fun shellGetEnforce(): String = runCatching {
        val p = Runtime.getRuntime().exec("getenforce")
        p.inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun mapSelinux(raw: String): String = when (raw.trim().lowercase()) {
        "enforcing" -> "enforcing"
        "permissive" -> "permissive"
        "disabled" -> "disabled"
        "" -> "未知"
        else -> raw.trim()
    }

    /**
     * The chain was only validated on vivo/iQOO 6.1.145 kernels.  Anything
     * else gets a startup notice (not a hard block): the tool still runs, but
     * the user is told the result is not expected to be meaningful.
     */
    private fun checkDeviceAndKernel() {
        val brand = getSystemProperty("ro.product.brand")
        val manufacturer = getSystemProperty("ro.product.manufacturer")
        val vendor = "$brand $manufacturer".lowercase()
        val isVivo = vendor.contains("vivo") || vendor.contains("iqoo")
        val kernelOk = kernelVersion.contains("6.1.145")
        if (isVivo && kernelOk) return
        val reasons = buildList {
            if (!isVivo) add("不是 vivo / iQOO 设备（${brand.ifBlank { manufacturer }}）")
            if (!kernelOk) add("内核不是 6.1.145（$kernelVersion）")
        }
        notice = Notice(
            "设备提示",
            buildString {
                append("本工具只适配 vivo / iQOO 且内核为 6.1.145 的机型。\n\n")
                append("检测到：\n· ")
                append(reasons.joinToString("\n· "))
                append("\n\n仍然可以继续使用，但提权大概率无效，")
                append("也可能因为错误的地址写入导致设备异常。请自行确认风险。")
            }
        )
        addLog("[设备] 不在适配范围: ${reasons.joinToString("；")}")
    }

    private fun resolveDeviceName(): String = runCatching {
        val brand = getSystemProperty("ro.product.brand")
        val model = getSystemProperty("ro.product.model")
        val device = getSystemProperty("ro.product.device")
        listOf(brand, model, device).filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { "未知" }
    }.getOrDefault("未知")

    private fun getSystemProperty(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        get.invoke(null, key) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun managerInstalled(pkg: String): Boolean =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun anyManagerInstalled(): Boolean =
        KSU_PKGS.any { it.isNotEmpty() && managerInstalled(it) }

    private fun runCore() {
        val s = requireService("运行") ?: return
        val ksuPkg = KSU_PKGS[ksuIndex]
        /* The endgame hands root to a manager's own ksud; without one there
         * is nothing to hand off to, so ask before starting anything. */
        if (ksuPkg.isNotEmpty()) {
            if (!managerInstalled(ksuPkg)) {
                notice = Notice(
                    "未检测到 ROOT 管理器",
                    "所选的管理器「${KSU_NAMES[ksuIndex]}」（$ksuPkg）没有安装。\n\n" +
                        "请先安装对应的管理器 APK，或在「ROOT管理器」里改选「自动检测」。"
                )
                return
            }
        } else if (!anyManagerInstalled()) {
            notice = Notice(
                "未检测到 ROOT 管理器",
                "没有检测到任何 KernelSU 系列管理器。\n\n" +
                    "本工具在拿到 root 后要把 root 交给管理器（由管理器自己的内核模块" +
                    "late-load 完成），所以需要先安装 KernelSU / KernelSU-Next / " +
                    "SukiSU Ultra / BakaSU / SukiSU 等之一，再点一键越狱。"
            )
            return
        }
        val safe = safeMode
        val stamp = (System.currentTimeMillis() / 1000).toString()
        val bin = "$TMP/ic-timer-a$stamp"
        val script = "$TMP/ic-lpe-a$stamp.sh"
        val logFile = "$TMP/ic-app-a$stamp.log"
        /* The manager dropdown has no "off" entry any more: every choice runs
         * the KSU handoff, and an empty package means "auto-detect". */
        val handoff = "$TMP/ksu-h$stamp.sh"
        val proof = DEFAULT_CMD
        val payload = "sh $handoff '$ksuPkg' '' '${if (safe) "1" else "0"}' ; $proof"
        val extraEnv = "IC_SYMS_FILE=/data/local/tmp/ic-syms.txt" +
            (if (safe) "\nIC_SAFE_MODE=1" else "") +
            "\nIC_KSU=1"

        busy = true
        runStageIndex = 0
        runStage = "1/${RUN_STAGES.size} ${RUN_STAGES.first().first}"
        runStamp = stamp
        lifecycleScope.launch {
            val refused = withContext(Dispatchers.IO) {
                try {
                    val id0 = s.identity()
                    if (!id0.startsWith("uid=2000 ") || !id0.contains("ctx=u:r:shell")) {
                        addLogOnMain(
                            "[run] 拒绝执行：用户服务身份不是 shell\n$id0\n" +
                                "请用 ADB / 无线调试启动 Shizuku（不要用 root 启动），再点“授权 Shizuku”。"
                        )
                        return@withContext true
                    }
                    s.truncate(script)
                    push(s, SCHEME_ASSET, script)
                    s.makeExec(script)

                    s.truncate(bin)
                    push(s, "ic-timer", bin)
                    s.makeExec(bin)

                    s.truncate(handoff)
                    push(s, "ksu_handoff.sh", handoff)
                    s.makeExec(handoff)

                    /* Preflight: ask the core whether this build has offsets
                     * (built-in anchor table / kallsyms / ic-syms.txt).  A run
                     * without them cannot do anything but fail, so stop here
                     * and point the user at the boot.img parser. */
                    val info = s.runCmd("$bin info 2>&1")
                    addLogOnMain("[info] " + info.trim())
                    val haveCore = Regex("have_core=(\\d)").find(info)?.groupValues?.get(1)
                    val haveFake = Regex("have_fake=(\\d)").find(info)?.groupValues?.get(1)
                    if (haveCore == null) {
                        postUi {
                            offsetPrompt = "无法确认当前版本的内核偏移（内核可能不受支持）。\n\n" +
                                "请点「解析偏移」，选择该机型的 boot.img 或全量包生成符号表。"
                        }
                        return@withContext true
                    }
                    if (haveCore != "1" || haveFake != "1") {
                        postUi {
                            offsetPrompt = "没有当前版本的偏移，请手动解析偏移。\n\n" +
                                "点「解析偏移」选择该机型的 boot.img 或全量包，生成 " +
                                "/data/local/tmp/ic-syms.txt 后再点一键越狱。"
                        }
                        return@withContext true
                    }

                    s.truncate(logFile)
                    val r = s.start(script, bin, logFile, ATTEMPTS, payload, extraEnv)
                    addLogOnMain(
                        "[run] 方案: $SCHEME_ASSET  KSU: ${KSU_NAMES[ksuIndex]}\n$r"
                    )
                    logPath = logFile
                    logOff = 0
                    startPolling()
                    false
                } catch (t: Throwable) {
                    addLogOnMain("[run] 失败: $t")
                    true
                }
            }
            /* busy stays true until the core prints "[core] exit=" so the
             * button keeps showing the live stage for the whole run. */
            if (refused) {
                busy = false
                runStage = ""
            }
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        lifecycleScope.launch(Dispatchers.IO) {
            while (polling) {
                val s = service
                val path = logPath
                if (s == null || path == null) break
                try {
                    val len = s.length(path)
                    while (logOff < len) {
                        val want = minOf(64 * 1024, len - logOff)
                        val b = s.read(path, logOff, want)
                        if (b == null || b.isEmpty()) break
                        logOff += b.size
                        val text = String(b, Charsets.UTF_8)
                        withContext(Dispatchers.Main) { raw(text) }
                    }
                } catch (_: Throwable) {
                }
                delay(1000)
            }
            polling = false
        }
    }

    private fun diag() {
        val s = requireService("诊断") ?: return
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    s.runCmd(
                        "id; cat /proc/self/attr/current; getenforce; " +
                            "echo ---; uname -r; " +
                            "getprop ro.product.device; getprop ro.product.model; " +
                            "echo ---; ls -l /data/local/tmp/ | head -20; " +
                            "echo ---; head -n 2 /data/local/tmp/ic-syms.txt " +
                            "2>/dev/null; ls -d /data/adb/ksu 2>/dev/null; " +
                            "echo ---; pm list packages 2>/dev/null | " +
                            "grep -Ei 'kernelsu|sukisu|resukisu|bakasu|sakisu|" +
                            "sevenk|vivoksu|ksunext|fina'"
                    )
                }.getOrElse { "诊断失败: $it" }
            }
            addLog("[诊断]\n$out")
        }
    }

    private fun parseOffsets() {
        requireService("解析偏移") ?: return
        runCatching {
            parsePicker.launch(arrayOf("*/*"))
        }.onFailure { addLog("[解析偏移] 无法打开文件选择器: $it") }
    }

    /** Thrown when the picked file cannot be used; the message goes to a dialog. */
    private class ParseAbort(message: String) : Exception(message)

    private data class Notice(val title: String, val message: String)

    private fun parseSelected(uri: Uri) {
        val s = requireService("解析偏移") ?: return
        val name = uri.lastPathSegment ?: "所选文件"
        runStage = ""
        busy = true
        lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) { runParse(s, uri, name) }
                addLog("[解析偏移]\n$out")
                if (!out.contains("parse_rc=0")) {
                    val tail = out.lines().filter { it.isNotBlank() }.takeLast(14)
                        .joinToString("\n")
                    notice = Notice("解析失败", "没有生成符号表。\n\n$tail")
                }
            } catch (a: ParseAbort) {
                addLog("[解析偏移] ${a.message}")
                notice = Notice("无法解析", a.message ?: "未知错误")
            } catch (t: Throwable) {
                addLog("[解析偏移] 失败: $t")
                notice = Notice("解析失败", "读取或解析所选文件时出错：\n$t")
            } finally {
                busy = false
            }
        }
    }

    private fun runParse(s: IIcService, uri: Uri, name: String): String {
        val stamp = (System.currentTimeMillis() / 1000).toString()
        val bin = "$TMP/ic-syms-a$stamp"
        val img = "$TMP/ic-boot-a$stamp.img"
        val syms = "$TMP/ic-syms.txt"
        val log = StringBuilder()
        fun note(l: String) {
            log.append(l).append('\n')
        }

        s.truncate(bin)
        push(s, "ic-timer", bin)
        s.makeExec(bin)
        s.truncate(img)

        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw ParseAbort("无法打开所选文件（文件选择器没有返回可读句柄）")
        val ch = FileInputStream(pfd.fileDescriptor).channel
        try {
            val size = ch.size()
            val head = readAt(ch, 0, 8)
            note("[解析] 文件: $name  $size bytes")
            when {
                head.size >= 4 && head[0] == 'P'.code.toByte() &&
                    head[1] == 'K'.code.toByte() -> {
                    note("[解析] 类型: ZIP（全量包）")
                    extractFromZip(s, ch, size, img, ::note)
                }
                head.size >= 8 && String(head, 0, 8, Charsets.US_ASCII) == "ANDROID!" -> {
                    val hdr = readAt(ch, 0, 64)
                    val ksize = u32(hdr, 8)
                    val hver = u32(hdr, 40)
                    note("[解析] 类型: Android boot 镜像 v$hver，内核 $ksize bytes")
                    if (ksize == 0L) {
                        throw ParseAbort(
                            "所选镜像是 init_boot / vendor_boot（不含内核），无法解析偏移。\n\n" +
                                "请选择该机型固件里的 boot.img，或直接选全量 OTA 包。"
                        )
                    }
                    if (size <= 64) throw ParseAbort("boot 镜像大小异常（$size bytes）")
                    checkBootIdentity(ch, size, ::note)
                    val pushed = pushChannel(s, img, ch, 0, size)
                    note("[解析] 已推送 boot.img: $pushed bytes")
                }
                else -> throw ParseAbort(
                    "无法识别的文件：请选择 boot.img（Android boot 镜像）" +
                        "或全量 OTA 包（.zip）。\n\ninit_boot / vendor_boot / 其他镜像都不支持。"
                )
            }
            val r = s.runCmd(
                "ls -l $img 2>&1; $bin syms $img $syms 2>&1; " +
                    "echo parse_rc=$?; sed -n '1,3p' $syms 2>/dev/null; rm -f $img"
            )
            note(r)
            return log.toString()
        } finally {
            try {
                ch.close()
            } catch (_: Throwable) {
            }
            try {
                pfd.close()
            } catch (_: Throwable) {
            }
        }
    }

    private data class Fingerprint(
        val vendor: String,
        val device: String,
        val incremental: String,
    )

    private fun parseFingerprint(s: String): Fingerprint? {
        val parts = s.split('/')
        if (parts.size < 5) return null
        val inc = parts[4].substringBefore(':')
        if (parts[0].isBlank() || parts[1].isBlank() || inc.isBlank()) return null
        return Fingerprint(parts[0].trim(), parts[1].trim(), inc.trim())
    }

    /**
     * Tells whether a bare boot.img belongs to this device *without* the OTA
     * package: boot v3/v4 images carry the build fingerprint
     * (com.android.build.boot.fingerprint) written at build time, which shares
     * vendor/device/incremental with ro.build.fingerprint.  Falls back to the
     * kernel version banner when the image has no bootconfig.
     */
    private fun checkBootIdentity(ch: FileChannel, size: Long, note: (String) -> Unit) {
        val imgFpStr = scanAsciiValue(ch, size, "com.android.build.boot.fingerprint")
        val devFpStr = getSystemProperty("ro.build.fingerprint")
        val imgFp = imgFpStr?.let { parseFingerprint(it) }
        val devFp = parseFingerprint(devFpStr)
        if (imgFp != null && devFp != null) {
            note("[解析] boot 指纹: $imgFpStr")
            note("[解析] 本机指纹: $devFpStr")
            val same = imgFp.vendor.equals(devFp.vendor, ignoreCase = true) &&
                imgFp.device.equals(devFp.device, ignoreCase = true) &&
                imgFp.incremental == devFp.incremental
            if (!same) {
                throw ParseAbort(
                    "所选 boot.img 与本机不是同一个 build。\n\n" +
                        "镜像：${imgFp.vendor}/${imgFp.device} … ${imgFp.incremental}\n" +
                        "本机：${devFp.vendor}/${devFp.device} … ${devFp.incremental}\n\n" +
                        "请选择当前系统对应的 boot.img 或全量包。"
                )
            }
            note("[解析] 版本校验通过（boot 指纹一致）")
            return
        }
        val banner = scanKernelBanner(ch, size)
        if (banner != null) {
            note("[解析] 镜像内核: $banner，本机内核: $kernelVersion")
            if (banner != kernelVersion && !kernelVersion.contains(banner)) {
                throw ParseAbort(
                    "boot.img 的内核（$banner）与本机内核（$kernelVersion）不一致。\n\n" +
                        "请选择当前系统对应的 boot.img 或全量包。"
                )
            }
            note("[解析] 版本校验通过（仅内核版本）")
            return
        }
        note("[解析] 无法从 boot.img 读取 build 指纹或内核版本，跳过版本校验")
    }

    private fun extractFromZip(
        s: IIcService,
        ch: FileChannel,
        size: Long,
        img: String,
        note: (String) -> Unit,
    ) {
        val zip = ZipReader(ch, size)
        val meta = zip.find("META-INF/com/android/metadata")
        if (meta != null) {
            val text = zip.readSmall(meta, 64 * 1024)?.toString(Charsets.UTF_8)
            if (text != null) {
                val kv = HashMap<String, String>()
                text.lineSequence().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) kv[line.substring(0, i).trim()] = line.substring(i + 1).trim()
                }
                val pkgDev = kv["pre-device"].orEmpty()
                val pkgVer = kv["post-version"].orEmpty()
                note("[解析] 包信息: device=$pkgDev version=$pkgVer")
                checkPackageMatch(pkgDev, pkgVer)
            }
        } else {
            note("[解析] 包内没有 META-INF/com/android/metadata，跳过设备/版本校验")
        }

        val payload = zip.find("payload.bin")
        val bootEntry = zip.find("boot.img") ?: zip.find("boot/boot.img")
        when {
            payload != null -> {
                if (payload.method != 0) {
                    throw ParseAbort("payload.bin 在 zip 里是压缩存储，手机端暂不支持；请用电脑的 tool/parse_offsets.py。")
                }
                note("[解析] payload.bin: ${payload.compressedSize} bytes @ ${payload.dataOffset}")
                val plan = BootExtractor.manifest(ch, payload.dataOffset)
                note("[解析] boot 分区: ${plan.size} bytes, ${plan.ops.size} ops, block=${plan.blockSize}")
                BootExtractor.extract(ch, payload.dataOffset, plan, note) { off, bytes, from, len ->
                    var done = 0
                    while (done < len) {
                        val n = minOf(CHUNK, len - done)
                        s.put(img, (off + done).toInt(), bytes.copyOfRange(from + done, from + done + n))
                        done += n
                    }
                }
            }
            bootEntry != null -> {
                val pushed = pushEntry(s, img, zip, bootEntry)
                note("[解析] 包内 boot.img: $pushed bytes")
            }
            else -> throw ParseAbort("这个 zip 里既没有 payload.bin 也没有 boot.img，不是可用的全量包。")
        }
    }

    private fun checkPackageMatch(pkgDev: String, pkgVer: String) {
        val devId = getSystemProperty("ro.product.device")
        val model = getSystemProperty("ro.product.model")
        val buildId = getSystemProperty("ro.build.display.id")
        val deviceStr = listOf(devId, model, buildId).filter { it.isNotBlank() }.joinToString(" ")
        val version = Regex("""\d+\.\d+\.\d+\.\d+""").find(pkgVer)?.value
        val mismatch = when {
            pkgDev.isNotEmpty() && !deviceStr.contains(pkgDev, ignoreCase = true) ->
                "设备不匹配：这个包是 $pkgDev，当前设备是 $deviceStr"
            version != null && !buildId.contains(version) ->
                "版本不匹配：这个包是 $pkgVer，当前系统是 $buildId"
            else -> null
        }
        if (mismatch != null) {
            throw ParseAbort(
                "$mismatch\n\n已拒绝生成偏移：用错版本的地址跑写原语会让写操作落到" +
                    "错误的内核地址。请改用当前设备和版本对应的固件包 / boot.img。"
            )
        }
        addLog("[解析] 设备/版本校验通过: $deviceStr")
    }

    private fun pushChannel(s: IIcService, dst: String, ch: FileChannel, start: Long, length: Long): Long {
        var off = 0L
        while (off < length) {
            val want = minOf(CHUNK.toLong(), length - off).toInt()
            val buf = readAt(ch, start + off, want)
            if (buf.isEmpty()) break
            s.put(dst, off.toInt(), buf)
            off += buf.size
        }
        return off
    }

    private fun pushEntry(s: IIcService, dst: String, zip: ZipReader, e: ZipEntryInfo): Long {
        zip.open(e).use { input ->
            var off = 0L
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                s.put(dst, off.toInt(), if (n == buf.size) buf else buf.copyOf(n))
                off += n
            }
            return off
        }
    }

    private fun cleanup() {
        cleanupDialog = requireService("清理") ?: return
    }

    /** Deletes this tool's /data/local/tmp leftovers; works before any root. */
    private fun doTempCleanup(s: IIcService) {
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    s.runCmd(
                        "rm -f /data/local/tmp/ic-timer-a* /data/local/tmp/ic-lpe-a* " +
                            "/data/local/tmp/ic-app-a* /data/local/tmp/ksu-h* " +
                            "/data/local/tmp/ic-safe-h* /data/local/tmp/ic-syms-a* " +
                            "/data/local/tmp/ic-boot-a* /data/local/tmp/ic-clean-a* " +
                            "/data/local/tmp/ksu_stage.sh /data/local/tmp/ic-payload* 2>&1; " +
                            "echo ---; ls -l /data/local/tmp/ 2>/dev/null | " +
                            "grep -E 'ic-|ksu|payload' | head -20"
                    )
                }.getOrElse { "清理失败: $it" }
            }
            addLog("[清理] 本工具临时文件\n$out")
            Toast.makeText(
                this@MainActivity,
                "已清理本工具临时文件",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private var cleanupDialog by mutableStateOf<IIcService?>(null)

    @Composable
    private fun CleanupDialog() {
        val s = cleanupDialog
        OverlayDialog(
            show = s != null,
            title = "清理",
            onDismissRequest = { cleanupDialog = null },
            content = {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                ) {
                    Text(
                        "「清理本工具临时文件」删除 /data/local/tmp 里本工具运行时留下的" +
                            "进程/脚本/日志副本，提权前就可以做，不影响已解析的 " +
                            "ic-syms.txt。\n\n" +
                            "「清理 KSU」删除 /data/adb/ksu、/data/adb/modules、" +
                            "/data/adb/modules_update 等 KSU 数据。已加载的内核模块要重启后" +
                            "才消失；清理后请重启再提权。管理器 APK 默认保留。",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "清理本工具临时文件",
                        onClick = {
                            cleanupDialog = null
                            s?.let { doTempCleanup(it) }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "清理 KSU（保留管理器）",
                        onClick = {
                            cleanupDialog = null
                            s?.let { doCleanup(it, false) }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "连管理器一起卸载",
                        onClick = {
                            cleanupDialog = null
                            s?.let { doCleanup(it, true) }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "取消",
                        onClick = { cleanupDialog = null }
                    )
                }
            }
        )
    }

    @Composable
    private fun NoticeDialog() {
        val n = notice
        OverlayDialog(
            show = n != null,
            title = n?.title ?: "",
            onDismissRequest = { notice = null },
            content = {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                ) {
                    SelectionContainer {
                        Text(n?.message ?: "", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "知道了",
                        onClick = { notice = null },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }

    @Composable
    private fun OffsetPromptDialog() {
        val msg = offsetPrompt
        OverlayDialog(
            show = msg != null,
            title = "缺少偏移",
            onDismissRequest = { offsetPrompt = null },
            content = {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                ) {
                    Text(msg ?: "", fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "解析偏移",
                        onClick = {
                            offsetPrompt = null
                            parseOffsets()
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "取消",
                        onClick = { offsetPrompt = null }
                    )
                }
            }
        )
    }

    private fun postUi(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun doCleanup(s: IIcService, apk: Boolean) {
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                try {
                    val stamp = (System.currentTimeMillis() / 1000).toString()
                    val script = "$TMP/ic-clean-a$stamp.sh"
                    s.truncate(script)
                    push(s, "cleanup.sh", script)
                    s.makeExec(script)
                    s.runCmd("sh $script --logs" + (if (apk) " --apk" else "") + " 2>&1")
                } catch (t: Throwable) {
                    "清理失败: $t"
                }
            }
            addLog("[清理]\n$out")
        }
    }

    private fun push(s: IIcService, asset: String, dst: String) {
        assets.open(asset).use { input ->
            val buf = ByteArray(CHUNK)
            var off = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val chunk = if (n == buf.size) buf else buf.copyOf(n)
                s.put(dst, off, chunk)
                off += n
            }
        }
    }

    private fun addLog(s: String) {
        addLogOnMain(s)
    }

    private fun addLogOnMain(s: String) {
        runOnUiThread { raw(s + "\n") }
    }

    private fun raw(s: String) {
        partialLine.append(s)
        val cut = partialLine.lastIndexOf('\n')
        if (cut < 0) return
        val complete = partialLine.substring(0, cut)
        partialLine.delete(0, cut + 1)
        complete.split('\n').forEach { line ->
            if (line.isNotEmpty()) {
                logs.add(line)
                updateStage(line)
            }
        }
        while (logs.size > MAX_LOG_LINES) logs.removeAt(0)
    }

    /**
     * Maps the core's log lines onto RUN_STAGES so the main button can show
     * the live phase.  Only forward moves count: most stages retry, and the
     * "plant" lines of a later stage repeat earlier text.
     */
    private fun updateStage(line: String) {
        if (line.contains("[core] exit=")) {
            val rc = line.substringAfter("[core] exit=").trim()
            runStage = if (rc == "0") "完成" else "失败（exit=$rc）"
            busy = false
            // Keep the outcome on the button for a moment, then go back idle.
            lifecycleScope.launch {
                delay(4000)
                if (!busy) runStage = ""
            }
            return
        }
        if (!busy) return
        RUN_STAGES.forEachIndexed { idx, stage ->
            if (idx > runStageIndex && stage.second.any { line.contains(it) }) {
                runStageIndex = idx
                runStage = "${idx + 1}/${RUN_STAGES.size} ${stage.first}"
            }
        }
    }

    private val partialLine = StringBuilder()

    private enum class AppTab(val label: String, val icon: ImageVector) {
        HOME("首页", Icons.Filled.Home),
        LOGS("日志", COUIIcons.Notes),
        ABOUT("关于", Icons.Filled.Info)
    }

    private companion object {
        private const val REQ_SHIZUKU = 1001
        private const val TMP = "/data/local/tmp"
        private const val CHUNK = 128 * 1024
        private const val ATTEMPTS = 16
        private const val MAX_LOG_LINES = 20_000
        private const val DEFAULT_CMD =
            "id; cat /proc/self/attr/current; getenforce; echo LPE-PROOF"
        private const val SCHEME_ASSET = "run_core.sh"

        /** Ordered phases of a run; the button shows "<n>/<total> <label>". */
        private val RUN_STAGES: List<Pair<String, List<String>>> = listOf(
            "启动" to listOf("[core] start"),
            "环境检查" to listOf("[anchors]", "[syms]"),
            "校准内存" to listOf("[ids] trace", "[base] made"),
            "泄漏 cred" to listOf("[leak] child exec"),
            "定位 cred" to listOf("[cred] attempt="),
            "设置 SELinux" to listOf("stage selinux", "set SELinux permissive"),
            "零写 vr.ko" to listOf("vr: nulling", "stage vr_sys_exit_funcs"),
            "伪造 cred" to listOf("stage fake_cred"),
            "提权 uid" to listOf("stage fake_uid"),
            "提权 euid" to listOf("stage fake_euid"),
            "执行 payload" to listOf("exec payload", "[fullshell]", "plant verified"),
            "完成" to listOf("handoff OK", "ROOT_SHELL_OK"),
        )

        private val KSU_NAMES = listOf(
            "自动检测", "KernelSU", "KernelSU-Next", "SukiSU Ultra",
            "BakaSU", "ReSukiSU (旧包名)", "SakiSU", "FinaSU", "7kimisu"
        )
        private val KSU_PKGS = listOf(
            "",
            "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra",
            "org.bakasu.bakasu", "com.resukisu.resukisu",
            "com.sakisu.sakisu", "me.yuki.fina", "com.sevenk.core"
        )

        private val licenseItems = listOf(
            LicenseItem(
                name = "IC Timer LPE（本项目）",
                author = "kzktor/ic-timer-lpe，作者酷安@散花神木",
                website = PROJECT_URL,
                licenses = listOf(LicenseText("GPL-3.0", asset = "licenses/GPL-3.0.txt"))
            ),
            LicenseItem(
                name = "molanp/CVE-2026-98260-vivo-s60-poc",
                author = "S60 PoC，MIT 许可的参考实现",
                website = "https://github.com/molanp/CVE-2026-98260-vivo-s60-poc",
                licenses = listOf(LicenseText("MIT", asset = "licenses/MIT.txt"))
            ),
            LicenseItem(
                name = "woshigenm/CVE-2026-98260",
                author = "MIT 许可的参考实现",
                website = "https://github.com/woshigenm/CVE-2026-98260",
                licenses = listOf(LicenseText("MIT", asset = "licenses/MIT.txt"))
            ),
            LicenseItem(
                name = "whooslizi/infinitycore-x200pro",
                author = "GPL-3.0 许可的参考实现",
                website = "https://github.com/whooslizi/infinitycore-x200pro",
                licenses = listOf(LicenseText("GPL-3.0", asset = "licenses/GPL-3.0.txt"))
            ),
            LicenseItem(
                name = "nisu123f/dada-cve-AN00",
                author = "参考实现",
                website = "https://github.com/nisu123f/dada-cve-AN00"
            ),
            LicenseItem(
                name = "kpgc10kai/TimerRoot",
                author = "参考实现",
                website = "https://github.com/kpgc10kai/TimerRoot"
            ),
            LicenseItem(
                name = "Yunnijian/Shark",
                author = "界面移植来源，GPL-3.0",
                website = "https://github.com/Yunnijian/Shark",
                licenses = listOf(LicenseText("GPL-3.0", asset = "licenses/GPL-3.0.txt"))
            )
        )
    }
}
