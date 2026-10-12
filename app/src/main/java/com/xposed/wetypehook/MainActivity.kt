package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.ViewCompat
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.xposed.wetypehook.wetype.gesture.GestureAction
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.WeTypeHyperMaterial
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.GlassOverrideField
import com.xposed.wetypehook.wetype.settings.GlassSliderParameter
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroup
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.WeTypeGestureSettings
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS = "com.xposed.wetypehook.extra.OPEN_WETYPE_EMBEDDED_SETTINGS"
const val EXTRA_OPEN_WETYPE_BACKUP_PAGE = "com.xposed.wetypehook.extra.OPEN_WETYPE_BACKUP_PAGE"
const val EXTRA_OPEN_WETYPE_LOGO_IMAGE_PAGE = "com.xposed.wetypehook.extra.OPEN_WETYPE_LOGO_IMAGE_PAGE"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeTypeSettingsApp(settingsContext = this)
        }
    }
}

@Composable
internal fun WeTypeSettingsApp(
    settingsContext: Context
) {
    var themeMode by remember(settingsContext) {
        mutableStateOf(WeTypeSettings.getAppThemeMode(settingsContext))
    }
    val systemDarkMode = isSystemInDarkTheme()
    val darkMode = when (themeMode) {
        WeTypeSettings.APP_THEME_MODE_LIGHT -> false
        WeTypeSettings.APP_THEME_MODE_DARK -> true
        else -> systemDarkMode
    }
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        WeTypeSettingsScreen(
            settingsContext = settingsContext,
            darkMode = darkMode,
            themeMode = themeMode,
            onThemeModeChange = {
                themeMode = it
                WeTypeSettings.setAppThemeMode(settingsContext, it)
            }
        )
    }
}

@Composable
internal fun SyncSystemBars(darkMode: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        ViewCompat.getWindowInsetsController(view)?.apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
        val window = view.context.findHostActivity()?.window
            ?.takeIf { it.decorView === view.rootView }
            ?: return@SideEffect
        // 顶栏玻璃延伸到状态栏底下，系统栏透明；图标深浅仍跟随主题。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@Composable
private fun WeTypeSettingsScreen(
    settingsContext: Context,
    darkMode: Boolean,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val preferencesContext = remember(settingsContext) { settingsContext }
    val isEmbeddedHost = remember(settingsContext) {
        (context.applicationContext ?: context).packageName != "com.xposed.wetypehook"
    }
    val snapshot = remember(preferencesContext) { WeTypeSettings.readSnapshot(preferencesContext) }
    var activationStatus by remember(preferencesContext) {
        mutableStateOf(ModuleActivationTracker.resolveStatusForUi(preferencesContext))
    }
    val systemDarkMode = isSystemInDarkTheme()
    val appearanceGroups = remember { WeTypeAppearanceColorGroups.groups }
    val appearanceSectionGroups = remember(appearanceGroups) {
        appearanceGroups.filterNot { it.isKeyColorGroup }
    }

    var lightColor by rememberSaveable { mutableIntStateOf(snapshot.lightColor) }
    var darkColor by rememberSaveable { mutableIntStateOf(snapshot.darkColor) }
    var blurRadius by rememberSaveable { mutableIntStateOf(snapshot.blurRadius) }
    var cornerRadius by rememberSaveable { mutableIntStateOf(snapshot.cornerRadius) }
    var keyCornerRadius by rememberSaveable { mutableIntStateOf(snapshot.keyCornerRadius) }
    var bubbleAlpha by rememberSaveable { mutableIntStateOf(snapshot.bubbleAlpha) }
    var edgeHighlightEnabled by rememberSaveable { mutableStateOf(snapshot.edgeHighlightEnabled) }
    var edgeHighlightIntensity by rememberSaveable { mutableIntStateOf(snapshot.edgeHighlightIntensity) }
    var candidateBackgroundAlpha by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundAlpha)
    }
    var candidateBackgroundCorner by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundCorner.roundToInt())
    }
    var candidateBackgroundLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidateBackgroundLeftMarginDp.toString())
    }
    var candidatePinyinInBar by rememberSaveable { mutableStateOf(snapshot.candidatePinyinInBar) }
    var candidatePinyinLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidatePinyinLeftMarginDp.toString())
    }
    var toolbarIconBgOpacity by rememberSaveable {
        mutableIntStateOf(snapshot.toolbarIconBgOpacity)
    }
    var disableHotUpdate by rememberSaveable {
        mutableStateOf(snapshot.disableHotUpdate)
    }
    var miuiClipboardGlass by rememberSaveable {
        mutableStateOf(snapshot.miuiClipboardGlass)
    }
    var showCrossDeviceClipboard by rememberSaveable {
        mutableStateOf(snapshot.showCrossDeviceClipboard)
    }
    var removeClipboardRetentionLimit by rememberSaveable {
        mutableStateOf(snapshot.removeClipboardRetentionLimit)
    }
    var removeClipboardTextLimit by rememberSaveable {
        mutableStateOf(snapshot.removeClipboardTextLimit)
    }
    var clipboardSearchEnabled by rememberSaveable {
        mutableStateOf(snapshot.clipboardSearchEnabled)
    }
    var clipboardSearchClearOnBack by rememberSaveable {
        mutableStateOf(snapshot.clipboardSearchClearOnBack)
    }
    var clipboardImageAdjustRatio by rememberSaveable {
        mutableStateOf(snapshot.clipboardImageAdjustRatio)
    }
    var clipboardImageCrop by rememberSaveable {
        mutableStateOf(snapshot.clipboardImageCrop)
    }
    var clipboardImageUniformRowHeight by rememberSaveable {
        mutableStateOf(snapshot.clipboardImageUniformRowHeight)
    }
    var clipboardImageMaxCount by rememberSaveable {
        mutableIntStateOf(snapshot.clipboardImageMaxCount)
    }
    var clipboardImageMaxSizeMb by rememberSaveable {
        mutableIntStateOf(snapshot.clipboardImageMaxSizeMb)
    }
    var qwertyGestureEnabled by rememberSaveable {
        mutableStateOf(snapshot.qwertyGestureEnabled)
    }
    var t9GestureEnabled by rememberSaveable {
        mutableStateOf(snapshot.t9GestureEnabled)
    }
    var gestureThreshold by rememberSaveable {
        mutableIntStateOf(snapshot.gestureThreshold)
    }
    var t9GestureThreshold by rememberSaveable {
        mutableIntStateOf(snapshot.t9GestureThreshold)
    }
    var gestureVibration by rememberSaveable {
        mutableStateOf(snapshot.gestureVibration)
    }
    var t9GestureVibration by rememberSaveable {
        mutableStateOf(snapshot.t9GestureVibration)
    }
    var gestureBindingsJson by rememberSaveable {
        mutableStateOf(snapshot.gestureBindingsJson)
    }
    var showGestureKeyLabels by rememberSaveable {
        mutableStateOf(snapshot.showGestureKeyLabels)
    }
    var gestureLabelTextSizeSp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelTextSizeSp)
    }
    var gestureLabelAlpha by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelAlpha)
    }
    var gestureLabelPosition by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelPosition)
    }
    var gestureLabelMarginTopDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginTopDp)
    }
    var gestureLabelMarginBottomDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginBottomDp)
    }
    var gestureLabelMarginLeftDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginLeftDp)
    }
    var gestureLabelMarginRightDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginRightDp)
    }
    var logoEnabled by rememberSaveable {
        mutableStateOf(snapshot.logoEnabled)
    }
    var logoShowEnabled by rememberSaveable {
        mutableStateOf(snapshot.logoShowEnabled)
    }
    var logoColorMode by rememberSaveable {
        mutableStateOf(WeTypeSettings.normalizeLogoColorMode(snapshot.logoColorMode))
    }
    var logoCustomColorInput by rememberSaveable {
        mutableStateOf(formatRgb(snapshot.logoCustomColor))
    }
    // 自定义图片 Logo 由二级页编辑；主页面只透传保存，用普通 remember 避免大字符串进 savedState。
    var logoImageEnabled by rememberSaveable { mutableStateOf(snapshot.logoImageEnabled) }
    var logoImageType by rememberSaveable {
        mutableStateOf(WeTypeSettings.normalizeLogoImageType(snapshot.logoImageType))
    }
    var logoSvgRecolorEnabled by rememberSaveable { mutableStateOf(snapshot.logoSvgRecolorEnabled) }
    var logoImagePngBase64 by remember { mutableStateOf(snapshot.logoImagePngBase64) }
    var logoImageSvgText by remember { mutableStateOf(snapshot.logoImageSvgText) }
    var logoImageName by rememberSaveable { mutableStateOf(snapshot.logoImageName) }
    var logoImageUpdatedAt by rememberSaveable { mutableStateOf(snapshot.logoImageUpdatedAt) }
    var logoImageSummary by rememberSaveable {
        mutableStateOf(WeTypeSettings.logoImageSummary(snapshot))
    }
    DisposableEffect(preferencesContext) {
        fun refreshFromLocal() {
            val fresh = WeTypeSettings.readLocalSnapshot(preferencesContext)
            logoImageEnabled = fresh.logoImageEnabled
            logoImageType = WeTypeSettings.normalizeLogoImageType(fresh.logoImageType)
            logoSvgRecolorEnabled = fresh.logoSvgRecolorEnabled
            logoImagePngBase64 = fresh.logoImagePngBase64
            logoImageSvgText = fresh.logoImageSvgText
            logoImageName = fresh.logoImageName
            logoImageUpdatedAt = fresh.logoImageUpdatedAt
            logoImageSummary = WeTypeSettings.logoImageSummary(fresh)
        }
        // 二级页在同一进程直接写本地偏好，回来时刷新摘要与透传值（主页面不编辑这些字段）。
        refreshFromLocal()
        val stopObserving = WeTypeSettings.observeLocalChanges(
            preferencesContext,
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshFromLocal() }
        )
        onDispose { stopObserving() }
    }
    var fontMode by rememberSaveable {
        mutableIntStateOf(snapshot.fontMode)
    }
    val glassInput = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *GlassOverrideField.entries.map { snapshot.glassOverrides.text(it) }.toTypedArray()
        )
    }
    val glassSupported = remember { WeTypeHyperMaterial.areGlassOverridesAvailable() }
    val parsedGlassOverrides = runCatching {
        GlassMaterialOverrides.parse(GlassOverrideField.entries.associateWith { glassInput[it.ordinal] })
    }.getOrNull()
    var previewGlassOverrides by remember { mutableStateOf(snapshot.glassOverrides) }
    LaunchedEffect(parsedGlassOverrides) {
        // Recreating a native material during every slider tick would flash its fallback tint.
        delay(120)
        parsedGlassOverrides?.let { previewGlassOverrides = it }
    }
    var hyperMaterialEnabled by rememberSaveable { mutableStateOf(snapshot.hyperMaterialEnabled) }
    var hyperMaterialAvailable by remember(preferencesContext) {
        mutableStateOf(WeTypeHyperMaterial.isAvailable(preferencesContext))
    }
    DisposableEffect(preferencesContext) {
        val stopObserving = WeTypeHyperMaterial.observeAvailability(preferencesContext) {
            hyperMaterialAvailable = WeTypeHyperMaterial.isAvailable(preferencesContext)
        }
        onDispose { stopObserving() }
    }
    val appearanceGroupColors = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *appearanceGroups.map { group ->
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            }.toTypedArray()
        )
    }
    var currentModeIsDark by rememberSaveable { mutableStateOf(systemDarkMode) }
    var bottomTab by rememberSaveable { mutableIntStateOf(0) }
    var detailRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var liquidGlass by remember(preferencesContext) {
        mutableStateOf(WeTypeSettings.isLiquidGlassBottomBar(preferencesContext))
    }
    var blurEnabled by remember(preferencesContext) {
        mutableStateOf(WeTypeSettings.isAppBlurEnabled(preferencesContext))
    }
    var floatingBar by remember(preferencesContext) {
        mutableStateOf(WeTypeSettings.isAppFloatingBar(preferencesContext))
    }
    var bottomBarHaptic by remember(preferencesContext) {
        mutableStateOf(WeTypeSettings.isBottomBarHaptic(preferencesContext))
    }
    var topBarProgressiveBlur by remember(preferencesContext) {
        mutableStateOf(WeTypeSettings.isTopBarProgressiveBlur(preferencesContext))
    }
    var colorInput by rememberSaveable {
        mutableStateOf(formatRgb(if (currentModeIsDark) darkColor else lightColor))
    }
    var alphaValue by rememberSaveable {
        mutableIntStateOf(Color.alpha(if (currentModeIsDark) darkColor else lightColor))
    }

    fun currentColor(): Int = if (currentModeIsDark) darkColor else lightColor

    fun syncEditorFromState() {
        alphaValue = Color.alpha(currentColor())
        colorInput = formatRgb(currentColor())
    }

    fun updateColorFromArgb(argb: Int) {
        if (currentModeIsDark) darkColor = argb else lightColor = argb
    }

    fun currentAppearanceColors(): Map<String, Int> = appearanceGroups.mapIndexed { index, group ->
        group.id to appearanceGroupColors[index]
    }.toMap()

    fun groupIndex(groupId: String): Int =
        appearanceGroups.indexOfFirst { it.id == groupId }

    fun keyColorGroup(isDark: Boolean) = appearanceGroups.first {
        it.id == if (isDark) {
            DARK_KEY_COLOR_GROUP_ID
        } else {
            LIGHT_KEY_COLOR_GROUP_ID
        }
    }

    fun keyColorValue(isDark: Boolean): Int {
        val group = keyColorGroup(isDark)
        return appearanceGroupColors[groupIndex(group.id)]
    }

    fun saveSettings(
        successMessage: Int = R.string.settings_saved,
        glassOverridesToSave: GlassMaterialOverrides? = parsedGlassOverrides,
        quietOnSuccess: Boolean = false,
        onPersistedResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (glassOverridesToSave == null) {
            Toast.makeText(context, R.string.settings_glass_invalid, Toast.LENGTH_SHORT).show()
            return false
        }
        return WeTypeSettings.save(
            context = preferencesContext,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            keyCornerRadius = keyCornerRadius,
            bubbleAlpha = bubbleAlpha,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner.toFloat(),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
            candidatePinyinInBar = candidatePinyinInBar,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            appearanceColors = currentAppearanceColors(),
            disableHotUpdate = disableHotUpdate,
            miuiClipboardGlass = miuiClipboardGlass,
            showCrossDeviceClipboard = showCrossDeviceClipboard,
            removeClipboardRetentionLimit = removeClipboardRetentionLimit,
            removeClipboardTextLimit = removeClipboardTextLimit,
            clipboardSearchEnabled = clipboardSearchEnabled,
            clipboardSearchClearOnBack = clipboardSearchClearOnBack,
            clipboardImageAdjustRatio = clipboardImageAdjustRatio,
            clipboardImageCrop = clipboardImageCrop,
            clipboardImageUniformRowHeight = clipboardImageUniformRowHeight,
            clipboardImageMaxCount = clipboardImageMaxCount,
            clipboardImageMaxSizeMb = clipboardImageMaxSizeMb,
            qwertyGestureEnabled = qwertyGestureEnabled,
            t9GestureEnabled = t9GestureEnabled,
            gestureThreshold = gestureThreshold,
            t9GestureThreshold = t9GestureThreshold,
            gestureVibration = gestureVibration,
            t9GestureVibration = t9GestureVibration,
            gestureBindingsJson = gestureBindingsJson,
            showGestureKeyLabels = showGestureKeyLabels,
            gestureLabelTextSizeSp = gestureLabelTextSizeSp,
            gestureLabelAlpha = gestureLabelAlpha,
            gestureLabelPosition = gestureLabelPosition,
            gestureLabelMarginTopDp = gestureLabelMarginTopDp,
            gestureLabelMarginBottomDp = gestureLabelMarginBottomDp,
            gestureLabelMarginLeftDp = gestureLabelMarginLeftDp,
            gestureLabelMarginRightDp = gestureLabelMarginRightDp,
            logoEnabled = logoEnabled,
            logoShowEnabled = logoShowEnabled,
            logoColorMode = logoColorMode,
            logoCustomColor = parseLogoCustomColor(logoCustomColorInput),
            logoImageEnabled = logoImageEnabled,
            logoImageType = logoImageType,
            logoSvgRecolorEnabled = logoSvgRecolorEnabled,
            logoImagePngBase64 = logoImagePngBase64,
            logoImageSvgText = logoImageSvgText,
            logoImageName = logoImageName,
            logoImageUpdatedAt = logoImageUpdatedAt,
            fontMode = fontMode,
            hyperMaterialEnabled = hyperMaterialEnabled,
            glassOverrides = glassOverridesToSave,
            onPersisted = { saved ->
                onPersistedResult?.invoke(saved)
                // 保存按钮走自动重启，成功时只留重启结果那句 Toast，这里不再重复提示。
                if (onPersistedResult == null && (!saved || !quietOnSuccess)) {
                    Toast.makeText(
                        context,
                        if (saved) successMessage else R.string.settings_save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    fun restoreDefaults() {
        lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
        darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
        blurRadius = WeTypeSettings.DEFAULT_BLUR_RADIUS
        cornerRadius = WeTypeSettings.DEFAULT_CORNER_RADIUS
        keyCornerRadius = WeTypeSettings.DEFAULT_KEY_CORNER_RADIUS
        bubbleAlpha = WeTypeSettings.DEFAULT_BUBBLE_ALPHA
        edgeHighlightEnabled = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_ENABLED
        edgeHighlightIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
        candidateBackgroundAlpha = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_ALPHA
        candidateBackgroundCorner = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_CORNER.roundToInt()
        candidateBackgroundLeftMarginDp =
            WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP.toString()
        candidatePinyinInBar = false
        candidatePinyinLeftMarginDp = WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP.toString()
        toolbarIconBgOpacity = WeTypeSettings.DEFAULT_TOOLBAR_ICON_BG_OPACITY
        disableHotUpdate = WeTypeSettings.DEFAULT_DISABLE_HOT_UPDATE
        miuiClipboardGlass = WeTypeSettings.DEFAULT_MIUI_CLIPBOARD_GLASS
        showCrossDeviceClipboard = WeTypeSettings.DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD
        removeClipboardRetentionLimit = WeTypeSettings.DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT
        removeClipboardTextLimit = WeTypeSettings.DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT
        clipboardSearchEnabled = WeTypeSettings.DEFAULT_CLIPBOARD_SEARCH_ENABLED
        clipboardSearchClearOnBack = WeTypeSettings.DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK
        clipboardImageAdjustRatio = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO
        clipboardImageCrop = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_CROP
        clipboardImageUniformRowHeight = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT
        clipboardImageMaxCount = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT
        clipboardImageMaxSizeMb = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB
        qwertyGestureEnabled = WeTypeSettings.DEFAULT_QWERTY_GESTURE_ENABLED
        t9GestureEnabled = WeTypeSettings.DEFAULT_T9_GESTURE_ENABLED
        gestureThreshold = WeTypeSettings.DEFAULT_GESTURE_THRESHOLD
        t9GestureThreshold = WeTypeSettings.DEFAULT_T9_GESTURE_THRESHOLD
        gestureVibration = WeTypeSettings.DEFAULT_GESTURE_VIBRATION
        t9GestureVibration = WeTypeSettings.DEFAULT_T9_GESTURE_VIBRATION
        gestureBindingsJson = WeTypeSettings.DEFAULT_GESTURE_BINDINGS_JSON
        showGestureKeyLabels = WeTypeSettings.DEFAULT_SHOW_GESTURE_KEY_LABELS
        gestureLabelTextSizeSp = WeTypeSettings.DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP
        gestureLabelAlpha = WeTypeSettings.DEFAULT_GESTURE_LABEL_ALPHA
        gestureLabelPosition = WeTypeSettings.DEFAULT_GESTURE_LABEL_POSITION
        gestureLabelMarginTopDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP
        gestureLabelMarginBottomDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP
        gestureLabelMarginLeftDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP
        gestureLabelMarginRightDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP
        logoEnabled = WeTypeSettings.DEFAULT_LOGO_ENABLED
        logoShowEnabled = WeTypeSettings.DEFAULT_LOGO_SHOW_ENABLED
        logoColorMode = WeTypeSettings.DEFAULT_LOGO_COLOR_MODE
        logoCustomColorInput = formatRgb(WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR)
        // 全局重置时图片开关回到关闭（用回矢量 Logo），已上传文件保留，可在二级页清除。
        logoImageEnabled = WeTypeSettings.DEFAULT_LOGO_IMAGE_ENABLED
        logoImageType = WeTypeSettings.DEFAULT_LOGO_IMAGE_TYPE
        logoSvgRecolorEnabled = WeTypeSettings.DEFAULT_LOGO_SVG_RECOLOR_ENABLED
        fontMode = WeTypeSettings.DEFAULT_FONT_MODE
        glassInput.indices.forEach { glassInput[it] = "" }
        previewGlassOverrides = GlassMaterialOverrides()
        hyperMaterialEnabled = WeTypeSettings.DEFAULT_HYPER_MATERIAL_ENABLED
        appearanceGroups.forEachIndexed { index, group ->
            appearanceGroupColors[index] = group.defaultColor
        }
        liquidGlass = WeTypeSettings.DEFAULT_LIQUID_GLASS_BOTTOM_BAR
        WeTypeSettings.setLiquidGlassBottomBar(
            preferencesContext,
            WeTypeSettings.DEFAULT_LIQUID_GLASS_BOTTOM_BAR
        )
        blurEnabled = WeTypeSettings.DEFAULT_APP_BLUR_ENABLED
        WeTypeSettings.setAppBlurEnabled(
            preferencesContext,
            WeTypeSettings.DEFAULT_APP_BLUR_ENABLED
        )
        floatingBar = WeTypeSettings.DEFAULT_APP_FLOATING_BAR
        WeTypeSettings.setAppFloatingBar(
            preferencesContext,
            WeTypeSettings.DEFAULT_APP_FLOATING_BAR
        )
        bottomBarHaptic = WeTypeSettings.DEFAULT_APP_BOTTOM_BAR_HAPTIC
        WeTypeSettings.setBottomBarHaptic(
            preferencesContext,
            WeTypeSettings.DEFAULT_APP_BOTTOM_BAR_HAPTIC
        )
        onThemeModeChange(WeTypeSettings.DEFAULT_APP_THEME_MODE)
        syncEditorFromState()
        saveSettings(
            successMessage = R.string.settings_reset_toast,
            glassOverridesToSave = GlassMaterialOverrides()
        )
    }

    // 二级路由共用：Feature 五段按集合过滤展示，避免三十余个参数重复三遍。
    fun LazyListScope.FeatureSections(sections: Set<String>) {
        FeatureTabContent(
            logoEnabled = logoEnabled,
            onLogoEnabledChange = { logoEnabled = it },
            logoShowEnabled = logoShowEnabled,
            onLogoShowEnabledChange = { logoShowEnabled = it },
            logoColorMode = logoColorMode,
            onLogoColorModeChange = { logoColorMode = it },
            logoCustomColorInput = logoCustomColorInput,
            onLogoCustomColorInputChange = { logoCustomColorInput = it },
            logoImageSummary = logoImageSummary,
            onOpenLogoImage = {
                WeTypeHostLauncher.launchLogoImagePage(settingsContext as? Activity)
            },
            onResetLogo = {
                logoEnabled = WeTypeSettings.DEFAULT_LOGO_ENABLED
                logoShowEnabled = WeTypeSettings.DEFAULT_LOGO_SHOW_ENABLED
                logoColorMode = WeTypeSettings.DEFAULT_LOGO_COLOR_MODE
                logoCustomColorInput = formatRgb(WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR)
                logoImageEnabled = WeTypeSettings.DEFAULT_LOGO_IMAGE_ENABLED
                logoImageType = WeTypeSettings.DEFAULT_LOGO_IMAGE_TYPE
                logoSvgRecolorEnabled = WeTypeSettings.DEFAULT_LOGO_SVG_RECOLOR_ENABLED
            },
            fontMode = fontMode,
            onFontModeChange = { fontMode = it },
            onResetFont = {
                fontMode = WeTypeSettings.DEFAULT_FONT_MODE
            },
            miuiClipboardGlass = miuiClipboardGlass,
            onMiuiClipboardGlassChange = { miuiClipboardGlass = it },
            showCrossDeviceClipboard = showCrossDeviceClipboard,
            onShowCrossDeviceClipboardChange = { showCrossDeviceClipboard = it },
            removeClipboardRetentionLimit = removeClipboardRetentionLimit,
            onRemoveClipboardRetentionLimitChange = { removeClipboardRetentionLimit = it },
            removeClipboardTextLimit = removeClipboardTextLimit,
            onRemoveClipboardTextLimitChange = { removeClipboardTextLimit = it },
            clipboardSearchEnabled = clipboardSearchEnabled,
            onClipboardSearchEnabledChange = { clipboardSearchEnabled = it },
            clipboardSearchClearOnBack = clipboardSearchClearOnBack,
            onClipboardSearchClearOnBackChange = { clipboardSearchClearOnBack = it },
            clipboardImageAdjustRatio = clipboardImageAdjustRatio,
            onClipboardImageAdjustRatioChange = { clipboardImageAdjustRatio = it },
            clipboardImageCrop = clipboardImageCrop,
            onClipboardImageCropChange = { clipboardImageCrop = it },
            clipboardImageUniformRowHeight = clipboardImageUniformRowHeight,
            onClipboardImageUniformRowHeightChange = { clipboardImageUniformRowHeight = it },
            clipboardImageMaxCount = clipboardImageMaxCount,
            onClipboardImageMaxCountChange = { clipboardImageMaxCount = it },
            clipboardImageMaxSizeMb = clipboardImageMaxSizeMb,
            onClipboardImageMaxSizeMbChange = { clipboardImageMaxSizeMb = it },
            onOpenClipboardBackup = {
                WeTypeHostLauncher.launchBackupPage(settingsContext as? Activity)
            },
            disableHotUpdate = disableHotUpdate,
            onDisableHotUpdateChange = { disableHotUpdate = it },
            onRestoreDefaults = ::restoreDefaults,
            visibleSections = sections
        )
    }

    if (isEmbeddedHost) {
        LaunchedEffect(preferencesContext) {
            ModuleActivationTracker.syncActivationFromUiContext(preferencesContext)
        }
    } else {
        DisposableEffect(preferencesContext) {
            val listener = ModuleActivationTracker.registerStatusListener(preferencesContext) {
                activationStatus = it
            }
            onDispose {
                ModuleActivationTracker.unregisterStatusListener(preferencesContext, listener)
            }
        }
    }

    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())
    val restartScope = rememberCoroutineScope()
    var applyingSettings by remember { mutableStateOf(false) }

    // 底栏四 tab + 二级路由：detailRoute 非空时展示详情并隐藏 hub。
    val contentKey = detailRoute ?: when (bottomTab) {
        0 -> "home"
        1 -> "function"
        2 -> "beautify"
        else -> "settings"
    }
    val bottomTabs = remember {
        listOf(
            BottomTab("首页", MiuixIcons.All),
            BottomTab("功能", MiuixIcons.Tune),
            BottomTab("美化", MiuixIcons.Theme),
            BottomTab("设置", MiuixIcons.Settings)
        )
    }
    val topTitle = when {
        detailRoute == "theme" -> "主题设置"
        detailRoute == "gesture" -> "按键手势"
        detailRoute == "clipboard" -> "剪贴板增强"
        detailRoute == "appearance" -> "界面美化"
        detailRoute == "font" -> "字体替换"
        detailRoute == "logo" -> "键盘 Logo"
        detailRoute == "configexport" -> "配置导出"
        bottomTab == 0 -> "首页"
        bottomTab == 1 -> "功能"
        bottomTab == 2 -> "美化"
        else -> "设置"
    }

    // 顶栏高度由玻璃框架实时回传（收起/展开都跟随），内容永远贴着栏底，无缝隙。
    GlassChromeScaffold(
        enableLiquidGlass = liquidGlass,
        blurEnabled = blurEnabled,
        floatingBar = floatingBar,
        darkMode = darkMode,
        showBottomBar = detailRoute == null,
        bottomBarHaptic = bottomBarHaptic,
        topBarProgressiveBlur = topBarProgressiveBlur,
        modifier = Modifier.fillMaxSize(),
        topBar = { topBarBackdrop ->
            TopAppBar(
                title = topTitle,
                color = ComposeColor.Transparent,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    AnimatedVisibility(
                        visible = detailRoute != null,
                        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + expandHorizontally(),
                        exit = fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) + shrinkHorizontally()
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(40.dp)
                                .miuiPressBounce()
                                .drawBackdrop(
                                    backdrop = topBarBackdrop,
                                    shape = { CircleShape },
                                    effects = {
                                        vibrancy()
                                        blur(12.dp.toPx())
                                    },
                                    highlight = { Highlight.Default },
                                    shadow = { null },
                                    innerShadow = { null },
                                    onDrawSurface = {
                                        drawRect(
                                            if (darkMode) ComposeColor.White.copy(alpha = 0.08f)
                                            else ComposeColor.Black.copy(alpha = 0.05f)
                                        )
                                    }
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (darkMode) ComposeColor.White.copy(alpha = 0.22f)
                                    else ComposeColor.White.copy(alpha = 0.65f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = {
                                    detailRoute = null
                                    scrollBehavior.state.heightOffset = 0f
                                }
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(40.dp)
                            .miuiPressBounce()
                            .drawBackdrop(
                                backdrop = topBarBackdrop,
                                shape = { CircleShape },
                                effects = {
                                    vibrancy()
                                    blur(12.dp.toPx())
                                },
                                highlight = { Highlight.Default },
                                shadow = { null },
                                innerShadow = { null },
                                onDrawSurface = {
                                    drawRect(
                                        if (darkMode) ComposeColor.White.copy(alpha = 0.08f)
                                        else ComposeColor.Black.copy(alpha = 0.05f)
                                    )
                                }
                            )
                            .border(
                                width = 0.5.dp,
                                color = if (darkMode) ComposeColor.White.copy(alpha = 0.22f)
                                else ComposeColor.White.copy(alpha = 0.65f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            enabled = !applyingSettings,
                            onClick = {
                                if (applyingSettings) return@IconButton
                                if (parsedGlassOverrides == null) {
                                    saveSettings(quietOnSuccess = true)
                                    return@IconButton
                                }
                                applyingSettings = true
                                restartScope.launch {
                                    try {
                                        val saved = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                                            val accepted = saveSettings(quietOnSuccess = true, onPersistedResult = {
                                                if (continuation.isActive) continuation.resume(it) {}
                                            })
                                            if (!accepted && continuation.isActive) continuation.resume(false) {}
                                        }
                                        if (!saved) {
                                            Toast.makeText(context, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        val hint = when (ImeRestarter.restartWeType(context)) {
                                            ImeRestarter.Result.Restarted ->
                                                context.getString(R.string.settings_restart_done)

                                            ImeRestarter.Result.Unconfirmed ->
                                                context.getString(R.string.settings_restart_inactive)

                                            ImeRestarter.Result.Failed ->
                                                context.getString(R.string.settings_restart_failed)
                                            ImeRestarter.Result.NotRunning ->
                                                context.getString(R.string.settings_restart_not_running)
                                        }
                                        Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
                                    } finally {
                                        applyingSettings = false
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = stringResource(R.string.settings_save_title)
                            )
                        }
                    }
                }
            )
        },
        tabs = bottomTabs,
        selectedIndex = bottomTab,
        onSelect = {
            bottomTab = it
            detailRoute = null
            scrollBehavior.state.heightOffset = 0f
        }
    ) { barContentModifier, topPadding ->
        BackHandler(enabled = detailRoute != null) {
            detailRoute = null
            scrollBehavior.state.heightOffset = 0f
        }
        val contentBottomPadding by animateDpAsState(
            targetValue = if (detailRoute != null) 48.dp else 150.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "contentBottomPadding"
        )
        Box(modifier = barContentModifier.fillMaxSize()) {
            AnimatedContent(
                targetState = contentKey,
                transitionSpec = {
                    MiuixMotion.pageTransition(initialState, targetState)
                },
                modifier = Modifier.fillMaxSize(),
                label = "MainContentTransition"
            ) { targetKey ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        // 实测顶栏高度之外再留 12dp，大标题和首卡之间透气。
                        top = topPadding + 12.dp,
                        bottom = contentBottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (targetKey) {
                "home" -> {
                    HomePageItems(
                        activationStatus = activationStatus,
                        darkMode = darkMode
                    )
                }
                "function" -> {
                    FunctionHubItems(onOpen = { detailRoute = it })
                }
                "beautify" -> {
                    BeautifyHubItems(onOpen = { detailRoute = it })
                }
                "appearance" -> {
                    AppearanceTabContent(
                        currentModeIsDark = currentModeIsDark,
                        onModeChange = { newDarkMode ->
                            if (newDarkMode != currentModeIsDark) {
                                currentModeIsDark = newDarkMode
                                syncEditorFromState()
                            }
                        },
                        currentColor = currentColor(),
                        alphaValue = alphaValue,
                        onAlphaChange = {
                            alphaValue = it
                            val rgb = currentColor() and 0xFFFFFF
                            updateColorFromArgb((alphaValue shl 24) or rgb)
                            colorInput = formatRgb(currentColor())
                        },
                        colorInput = colorInput,
                        onColorInputChange = { input ->
                            val trimmed = input.trim()
                            val hasPrefix = trimmed.startsWith("#")
                            val body = trimmed.removePrefix("#")
                            if (body.length <= 6 && body.matches(Regex("^[0-9a-fA-F]*$"))) {
                                colorInput = if (hasPrefix || body.isNotEmpty()) "#$body" else ""

                                if (body.length == 6) {
                                    runCatching {
                                        val opaque = Color.parseColor("#$body")
                                        val argb = Color.argb(
                                            alphaValue.coerceIn(0, 255),
                                            Color.red(opaque),
                                            Color.green(opaque),
                                            Color.blue(opaque)
                                        )
                                        updateColorFromArgb(argb)
                                    }
                                }
                            }
                        },
                        blurRadius = blurRadius,
                        onBlurRadiusChange = { blurRadius = it },
                        cornerRadius = cornerRadius,
                        onCornerRadiusChange = { cornerRadius = it },
                        keyCornerRadius = keyCornerRadius,
                        onKeyCornerRadiusChange = { keyCornerRadius = it },
                        bubbleAlpha = bubbleAlpha,
                        onBubbleAlphaChange = { bubbleAlpha = it },
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        onEdgeHighlightEnabledChange = { edgeHighlightEnabled = it },
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        onEdgeHighlightIntensityChange = { edgeHighlightIntensity = it },
                        hyperMaterialEnabled = hyperMaterialEnabled,
                        onHyperMaterialEnabledChange = { hyperMaterialEnabled = it },
                        hyperMaterialAvailable = hyperMaterialAvailable,
                        glassSupported = glassSupported,
                        glassInput = glassInput,
                        onGlassInputChange = { index, value -> glassInput[index] = value },
                        onGlassReset = {
                            val defaults = GlassMaterialOverrides().withGlassEnabled(true)
                            GlassOverrideField.entries.forEach { glassInput[it.ordinal] = defaults.text(it) }
                        },
                        toolbarIconBgOpacity = toolbarIconBgOpacity,
                        onToolbarIconBgOpacityChange = { toolbarIconBgOpacity = it },
                        appearanceSectionGroups = appearanceSectionGroups,
                        appearanceGroupColors = appearanceGroupColors,
                        groupIndex = ::groupIndex,
                        currentKeyGroup = keyColorGroup(currentModeIsDark),
                        currentKeyGroupIndex = groupIndex(keyColorGroup(currentModeIsDark).id),
                        onKeyColorChange = { appearanceGroupColors[groupIndex(keyColorGroup(currentModeIsDark).id)] = it },
                        lightKeyColor = keyColorValue(false),
                        darkKeyColor = keyColorValue(true),
                        candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
                        onCandidateBackgroundLeftMarginDpChange = { candidateBackgroundLeftMarginDp = it },
                        candidatePinyinInBar = candidatePinyinInBar,
                        onCandidatePinyinInBarChange = { candidatePinyinInBar = it },
                        candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
                        onCandidatePinyinLeftMarginDpChange = { candidatePinyinLeftMarginDp = it },
                        candidateBackgroundAlpha = candidateBackgroundAlpha,
                        onCandidateBackgroundAlphaChange = { candidateBackgroundAlpha = it },
                        candidateBackgroundCorner = candidateBackgroundCorner,
                        onCandidateBackgroundCornerChange = { candidateBackgroundCorner = it }
                    )
                }

                "gesture" -> {
                    GestureTabContent(
                        qwertyGestureEnabled = qwertyGestureEnabled,
                        onQwertyGestureEnabledChange = { qwertyGestureEnabled = it },
                        t9GestureEnabled = t9GestureEnabled,
                        onT9GestureEnabledChange = { t9GestureEnabled = it },
                        gestureVibration = gestureVibration,
                        onGestureVibrationChange = { gestureVibration = it },
                        t9GestureVibration = t9GestureVibration,
                        onT9GestureVibrationChange = { t9GestureVibration = it },
                        gestureThreshold = gestureThreshold,
                        onGestureThresholdChange = { gestureThreshold = it },
                        t9GestureThreshold = t9GestureThreshold,
                        onT9GestureThresholdChange = { t9GestureThreshold = it },
                        showGestureKeyLabels = showGestureKeyLabels,
                        onShowGestureKeyLabelsChange = { showGestureKeyLabels = it },
                        gestureLabelTextSizeSp = gestureLabelTextSizeSp,
                        onGestureLabelTextSizeSpChange = { gestureLabelTextSizeSp = it },
                        gestureLabelAlpha = gestureLabelAlpha,
                        onGestureLabelAlphaChange = { gestureLabelAlpha = it },
                        gestureLabelPosition = gestureLabelPosition,
                        onGestureLabelPositionChange = { gestureLabelPosition = it },
                        gestureLabelMarginTopDp = gestureLabelMarginTopDp,
                        onGestureLabelMarginTopDpChange = { gestureLabelMarginTopDp = it },
                        gestureLabelMarginBottomDp = gestureLabelMarginBottomDp,
                        onGestureLabelMarginBottomDpChange = { gestureLabelMarginBottomDp = it },
                        gestureLabelMarginLeftDp = gestureLabelMarginLeftDp,
                        onGestureLabelMarginLeftDpChange = { gestureLabelMarginLeftDp = it },
                        gestureLabelMarginRightDp = gestureLabelMarginRightDp,
                        onGestureLabelMarginRightDpChange = { gestureLabelMarginRightDp = it },
                        gestureBindingsJson = gestureBindingsJson,
                        onGestureBindingsJsonChange = { gestureBindingsJson = it }
                    )
                }

                "settings" -> {
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            insideMargin = PaddingValues(0.dp)
                        ) {
                            Column {
                                ArrowPreference(
                                    title = "主题设置",
                                    summary = "外观模式、悬浮底栏、顶栏渐进模糊与触觉反馈",
                                    onClick = { detailRoute = "theme" }
                                )
                                ArrowPreference(
                                    title = "配置导出",
                                    summary = "导出 / 导入全部模块设置",
                                    onClick = { detailRoute = "configexport" }
                                )
                            }
                        }
                    }
                    FeatureSections(setOf("protection", "about"))
                }
                "configexport" -> {
                    ConfigExportItems(preferencesContext)
                }
                "theme" -> {
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            insideMargin = PaddingValues(0.dp)
                        ) {
                            Column(modifier = Modifier.animateContentSize(MiuixMotion.springSpec())) {
                                MiuixDropdownPreference(
                                    title = "外观模式",
                                    items = listOf("跟随系统", "浅色模式", "深色模式"),
                                    selectedIndex = themeMode.coerceIn(
                                        WeTypeSettings.APP_THEME_MODE_FOLLOW_SYSTEM,
                                        WeTypeSettings.APP_THEME_MODE_DARK
                                    ),
                                    onSelectedIndexChange = { onThemeModeChange(it) }
                                )
                                MiuixSwitchWidget(
                                    title = "悬浮底栏",
                                    description = "使用 Apple 风格的悬浮底栏，关闭则用全宽 docked 栏",
                                    checked = floatingBar,
                                    onCheckedChange = {
                                        floatingBar = it
                                        WeTypeSettings.setAppFloatingBar(preferencesContext, it)
                                    }
                                )
                                AnimatedVisibility(
                                    visible = floatingBar,
                                    enter = expandVertically(MiuixMotion.springSpec()) + fadeIn(),
                                    exit = shrinkVertically(MiuixMotion.springSpec()) + fadeOut()
                                ) {
                                    MiuixSwitchWidget(
                                        title = "液态玻璃",
                                        description = "启用悬浮底栏的液态玻璃折射，关闭则为普通高斯模糊",
                                        checked = liquidGlass,
                                        onCheckedChange = {
                                            liquidGlass = it
                                            WeTypeSettings.setLiquidGlassBottomBar(preferencesContext, it)
                                        }
                                    )
                                }
                                MiuixSwitchWidget(
                                    title = "模糊",
                                    description = "非悬浮模式下顶栏和底栏的模糊效果",
                                    checked = blurEnabled,
                                    enabled = !floatingBar,
                                    onCheckedChange = {
                                        blurEnabled = it
                                        WeTypeSettings.setAppBlurEnabled(preferencesContext, it)
                                    }
                                )
                                MiuixSwitchWidget(
                                    title = "顶栏渐进模糊",
                                    description = "顶栏背景边缘平滑过渡淡出，呈现自然的渐变层次",
                                    checked = topBarProgressiveBlur,
                                    enabled = floatingBar || blurEnabled,
                                    onCheckedChange = {
                                        topBarProgressiveBlur = it
                                        WeTypeSettings.setTopBarProgressiveBlur(preferencesContext, it)
                                    }
                                )
                                MiuixSwitchWidget(
                                    title = "底栏震动",
                                    description = "切换底部导航栏时触发触觉反馈",
                                    checked = bottomBarHaptic,
                                    onCheckedChange = {
                                        bottomBarHaptic = it
                                        WeTypeSettings.setBottomBarHaptic(preferencesContext, it)
                                    }
                                )
                            }
                        }
                    }
                }
                "clipboard" -> {
                    FeatureSections(setOf("clipboard"))
                }
                "font" -> {
                    FeatureSections(setOf("font"))
                }
                "logo" -> {
                    FeatureSections(setOf("logo"))
                }
                }
            }
            }
        }
        }
}

private fun LazyListScope.AppearanceTabContent(
    currentModeIsDark: Boolean,
    onModeChange: (Boolean) -> Unit,
    currentColor: Int,
    alphaValue: Int,
    onAlphaChange: (Int) -> Unit,
    colorInput: String,
    onColorInputChange: (String) -> Unit,
    blurRadius: Int,
    onBlurRadiusChange: (Int) -> Unit,
    cornerRadius: Int,
    onCornerRadiusChange: (Int) -> Unit,
    keyCornerRadius: Int,
    onKeyCornerRadiusChange: (Int) -> Unit,
    bubbleAlpha: Int,
    onBubbleAlphaChange: (Int) -> Unit,
    edgeHighlightEnabled: Boolean,
    onEdgeHighlightEnabledChange: (Boolean) -> Unit,
    edgeHighlightIntensity: Int,
    onEdgeHighlightIntensityChange: (Int) -> Unit,
    hyperMaterialEnabled: Boolean,
    onHyperMaterialEnabledChange: (Boolean) -> Unit,
    hyperMaterialAvailable: Boolean,
    glassSupported: Boolean,
    glassInput: List<String>,
    onGlassInputChange: (Int, String) -> Unit,
    onGlassReset: () -> Unit,
    toolbarIconBgOpacity: Int,
    onToolbarIconBgOpacityChange: (Int) -> Unit,
    appearanceSectionGroups: List<WeTypeAppearanceColorGroup>,
    appearanceGroupColors: MutableList<Int>,
    groupIndex: (String) -> Int,
    currentKeyGroup: WeTypeAppearanceColorGroup,
    currentKeyGroupIndex: Int,
    onKeyColorChange: (Int) -> Unit,
    lightKeyColor: Int,
    darkKeyColor: Int,
    candidateBackgroundLeftMarginDp: String,
    onCandidateBackgroundLeftMarginDpChange: (String) -> Unit,
    candidatePinyinInBar: Boolean,
    onCandidatePinyinInBarChange: (Boolean) -> Unit,
    candidatePinyinLeftMarginDp: String,
    onCandidatePinyinLeftMarginDpChange: (String) -> Unit,
    candidateBackgroundAlpha: Int,
    onCandidateBackgroundAlphaChange: (Int) -> Unit,
    candidateBackgroundCorner: Int,
    onCandidateBackgroundCornerChange: (Int) -> Unit
) {
    // 1. 效果预览折叠卡片（默认收起，平滑展开）
    item {
        var isPreviewExpanded by rememberSaveable { mutableStateOf(false) }
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                insideMargin = PaddingValues(0.dp)
            ) {
                MiuixSwitchWidget(
                    title = "实时效果预览",
                    description = "展开查看键盘背景与毛玻璃渲染效果",
                    checked = isPreviewExpanded,
                    onCheckedChange = { isPreviewExpanded = it }
                )
            }

            AnimatedVisibility(
                visible = isPreviewExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    PreviewSection(
                        color = currentColor,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        keyCornerRadius = keyCornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        lightKeyColor = lightKeyColor,
                        darkKeyColor = darkKeyColor,
                        isDark = currentModeIsDark,
                        hyperMaterialEnabled = hyperMaterialEnabled
                    )
                }
            }
        }
    }

    // 2. 颜色与模式
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                // 模式切换 - 使用 TabRow
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_section_mode),
                        style = MiuixTheme.textStyles.main
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val tabs = listOf(
                        stringResource(R.string.settings_light_mode),
                        stringResource(R.string.settings_dark_mode)
                    )
                    TabRowWithContour(
                        tabs = tabs,
                        selectedTabIndex = if (currentModeIsDark) 1 else 0,
                        onTabSelected = { index ->
                            onModeChange(index == 1)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }


                // 自定义 HEX 颜色输入与取色器入口
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_custom_color),
                        style = MiuixTheme.textStyles.main
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_color_helper),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(ContinuousRoundedRectangle(999.dp))
                                .background(ComposeColor(currentColor))
                                .border(
                                    1.dp,
                                    MiuixTheme.colorScheme.outline,
                                    ContinuousRoundedRectangle(999.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextField(
                            value = colorInput,
                            onValueChange = onColorInputChange,
                            label = stringResource(R.string.settings_color_label),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }


                // 透明度滑块
                SliderPreferenceItem(
                    title = stringResource(R.string.settings_alpha_title),
                    value = alphaValue,
                    max = 255,
                    onValueChange = onAlphaChange
                )


                KeyColorEditor(
                    title = if (currentModeIsDark) {
                        stringResource(R.string.settings_dark_key_color_title)
                    } else {
                        stringResource(R.string.settings_light_key_color_title)
                    },
                    summary = stringResource(
                        R.string.settings_key_color_group_summary,
                        Color.alpha(appearanceGroupColors[currentKeyGroupIndex]),
                        currentKeyGroup.entryCount
                    ),
                    color = appearanceGroupColors[currentKeyGroupIndex],
                    onColorChange = onKeyColorChange
                )
            }
        }
    }

    // 3. 外观与特效
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column(modifier = Modifier.animateContentSize(MiuixMotion.springSpec())) {
                MiuixSwitchWidget(
                    title = stringResource(R.string.settings_hyper_material_title),
                    description = stringResource(
                        if (hyperMaterialAvailable) R.string.settings_hyper_material_desc
                        else R.string.settings_hyper_material_unavailable
                    ),
                    checked = hyperMaterialEnabled,
                    enabled = hyperMaterialAvailable,
                    onCheckedChange = onHyperMaterialEnabledChange
                )
                AnimatedVisibility(
                    visible = hyperMaterialEnabled,
                    enter = expandVertically(MiuixMotion.springSpec()) + fadeIn(),
                    exit = shrinkVertically(MiuixMotion.springSpec()) + fadeOut()
                ) {
                    Column {
                        HorizontalDivider()
                        GlassOverrideEditor(
                            values = glassInput,
                            enabled = hyperMaterialAvailable && glassSupported,
                            onValueChange = onGlassInputChange,
                            onReset = onGlassReset
                        )
                    }
                }
                HorizontalDivider()
                MiuixSwitchWidget(
                    title = stringResource(R.string.settings_edge_highlight_title),
                    description = stringResource(R.string.settings_edge_highlight_desc),
                    checked = edgeHighlightEnabled,
                    enabled = !hyperMaterialEnabled,
                    onCheckedChange = onEdgeHighlightEnabledChange
                )

                AnimatedVisibility(
                    visible = edgeHighlightEnabled,
                    enter = expandVertically(MiuixMotion.springSpec()) + fadeIn(),
                    exit = shrinkVertically(MiuixMotion.springSpec()) + fadeOut()
                ) {
                    SliderPreferenceItem(
                        title = stringResource(R.string.settings_edge_highlight_intensity_title),
                        value = edgeHighlightIntensity,
                        max = 200,
                        enabled = !hyperMaterialEnabled,
                        onValueChange = onEdgeHighlightIntensityChange
                    )
                }


                // 模糊滑块
                SliderPreferenceItem(
                    title = stringResource(R.string.settings_blur_title),
                    value = blurRadius,
                    max = 100,
                    enabled = !hyperMaterialEnabled,
                    onValueChange = onBlurRadiusChange
                )

                // 圆角滑块
                SliderPreferenceItem(
                    title = stringResource(R.string.settings_corner_title),
                    value = cornerRadius,
                    max = WeTypeSettings.MAX_CORNER_RADIUS,
                    onValueChange = onCornerRadiusChange
                )

                SliderPreferenceItem(
                    title = stringResource(R.string.settings_key_corner_title),
                    value = keyCornerRadius,
                    max = WeTypeSettings.MAX_KEY_CORNER_RADIUS,
                    onValueChange = onKeyCornerRadiusChange
                )

                SliderPreferenceItem(
                    title = stringResource(R.string.settings_bubble_opacity_title),
                    value = bubbleAlpha,
                    max = 255,
                    onValueChange = onBubbleAlphaChange
                )

                SliderPreferenceItem(
                    title = stringResource(R.string.settings_toolbar_icon_bg_opacity_title),
                    value = toolbarIconBgOpacity,
                    max = 255,
                    onValueChange = onToolbarIconBgOpacityChange
                )

                appearanceSectionGroups.forEach { group ->
                    val index = groupIndex(group.id)
                    AppearanceColorGroupEditor(
                        title = group.displayName,
                        summary = stringResource(
                            R.string.settings_appearance_color_group_summary,
                            group.entryCount,
                            formatArgb(group.defaultColor)
                        ),
                        color = appearanceGroupColors[index],
                        onColorChange = { appearanceGroupColors[index] = it }
                    )
                }

                NumericTextSettingItem(
                    title = stringResource(R.string.settings_candidate_background_left_margin_title),
                    summary = stringResource(R.string.settings_candidate_background_left_margin_desc),
                    value = candidateBackgroundLeftMarginDp,
                    onValueChange = { input ->
                        if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                            onCandidateBackgroundLeftMarginDpChange(input)
                        }
                    }
                )

                MiuixDropdownPreference(
                    title = "拼音显示方式",
                    items = listOf("原生直接上屏", "候选栏拼音"),
                    selectedIndex = if (candidatePinyinInBar) 1 else 0,
                    onSelectedIndexChange = { onCandidatePinyinInBarChange(it == 1) }
                )
                Text(
                    text = "候选栏模式将拼音显示在键盘中。保存后清空未确认输入并重新打开键盘。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (candidatePinyinInBar) {
                NumericTextSettingItem(
                    title = stringResource(R.string.settings_candidate_pinyin_margin_title),
                    summary = stringResource(R.string.settings_candidate_pinyin_margin_desc),
                    value = candidatePinyinLeftMarginDp,
                    onValueChange = { input ->
                        if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                            onCandidatePinyinLeftMarginDpChange(input)
                        }
                    }
                )

                }

                SliderPreferenceItem(
                    title = stringResource(R.string.settings_key_color_hook_alpha_title),
                    value = candidateBackgroundAlpha,
                    max = 255,
                    onValueChange = onCandidateBackgroundAlphaChange
                )

                SliderPreferenceItem(
                    title = stringResource(R.string.settings_candidate_corner_title),
                    value = candidateBackgroundCorner,
                    max = WeTypeSettings.MAX_CANDIDATE_BACKGROUND_CORNER,
                    onValueChange = onCandidateBackgroundCornerChange
                )
            }
        }
    }
}

@Composable
private fun PreviewSection(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean,
    hyperMaterialEnabled: Boolean = false
) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp),
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = ComposeColor.Transparent
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Image(
                painter = painterResource(R.drawable.natural_texture_004),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Column {
                PreviewCard(
                    color = color,
                    blurRadius = blurRadius,
                    cornerRadius = cornerRadius,
                    keyCornerRadius = keyCornerRadius,
                    edgeHighlightEnabled = edgeHighlightEnabled,
                    edgeHighlightIntensity = edgeHighlightIntensity,
                    lightKeyColor = lightKeyColor,
                    darkKeyColor = darkKeyColor,
                    isDark = isDark,
                    hyperMaterialEnabled = hyperMaterialEnabled
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean,
    hyperMaterialEnabled: Boolean = false
) {
    val context = LocalContext.current
    // 系统材质开时预览显示 fallback 底色（真机材质只在 HyperOS 上生效）。
    val displayColor = if (hyperMaterialEnabled) WeTypeHyperMaterial.fallbackColor(isDark) else color
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val previewCornerValue = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS)
    val previewCorner = previewCornerValue.dp
    val previewMinHeight = maxOf(88.dp, (previewCornerValue * 2).dp)
    val previewShape = ContinuousRoundedRectangle(
        topStart = CornerSize(previewCorner),
        topEnd = CornerSize(previewCorner),
        bottomEnd = CornerSize(0.dp),
        bottomStart = CornerSize(0.dp)
    )
    val previewKeyColor = if (isDark) darkKeyColor else lightKeyColor
    val previewKeyShape = ContinuousRoundedRectangle(keyCornerRadius.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 20.dp, end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = previewMinHeight)
                    .weTypePreviewBloom(
                        color = displayColor,
                        cornerRadius = previewCorner,
                        edgeHighlightEnabled = edgeHighlightEnabled && !hyperMaterialEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        isDark = isDark
                    )
                    .clip(previewShape)
            ) {
                if (hyperMaterialEnabled) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(ComposeColor(displayColor))
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.natural_texture_004),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur((blurRadius / 3f).coerceAtLeast(0f).dp)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(ComposeColor(color))
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isDark) stringResource(R.string.settings_preview_mode_dark) else stringResource(R.string.settings_preview_mode_light),
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(previewKeyShape)
                                    .background(ComposeColor(previewKeyColor))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (hyperMaterialEnabled) stringResource(R.string.settings_hyper_material_preview) else formatArgb(color),
                                    color = previewTextColor(displayColor),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                            }

                            for (i in 'A'..'C') {
                                Box(
                                    modifier = Modifier
                                        .clip(previewKeyShape)
                                        .background(ComposeColor(previewKeyColor))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                Text(
                                    text = i.toString(),
                                    color = previewTextColor(displayColor),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (hyperMaterialEnabled) {
                                "${stringResource(R.string.settings_corner_label)} $cornerRadius"
                            } else {
                                "${stringResource(R.string.settings_blur_label)} $blurRadius · ${stringResource(R.string.settings_corner_label)} $cornerRadius"
                            },
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun Modifier.weTypePreviewBloom(
    color: Int,
    cornerRadius: androidx.compose.ui.unit.Dp,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    isDark: Boolean
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val previewContext = remember(context, isDark) {
        createPreviewContext(context, isDark)
    }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    return this.drawWithCache {
        val previewCornerRadii = WeTypeCornerRadii(
            topLeft = cornerRadiusPx,
            topRight = cornerRadiusPx,
            bottomRight = 0f,
            bottomLeft = 0f
        )
        val widthPx = size.width.roundToInt()
        val heightPx = size.height.roundToInt()
        // The bloom overlay relies on clipPath + BlurMaskFilter + Path.op, which are not reliably
        // supported on Compose's hardware-accelerated recording canvas and crash the preview. Render
        // it once into an offscreen software bitmap (which supports every operation) and blit the
        // result, keeping the preview pixel-accurate.
        val overlayBitmap = if (edgeHighlightEnabled && widthPx > 0 && heightPx > 0) {
            runCatching {
                val bloomDrawable = WeTypeBloomStrokeDrawable(
                    context = previewContext,
                    cornerRadii = previewCornerRadii,
                    surfaceColor = color,
                    intensityScale = edgeHighlightIntensity / 100f
                )
                bloomDrawable.setBounds(0, 0, widthPx, heightPx)
                val clipPath = createWeTypeContinuousRoundedPath(
                    width = widthPx.toFloat(),
                    height = heightPx.toFloat(),
                    cornerRadii = previewCornerRadii
                )
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val bitmapCanvas = Canvas(bitmap)
                    bitmapCanvas.clipPath(clipPath)
                    bloomDrawable.draw(bitmapCanvas)
                }
            }.getOrNull()
        } else {
            null
        }

        onDrawWithContent {
            drawContent()
            val bitmap = overlayBitmap ?: return@onDrawWithContent
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }
}

private fun createPreviewContext(baseContext: Context, isDark: Boolean): Context {
    val configuration = Configuration(baseContext.resources.configuration).apply {
        uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return baseContext.createConfigurationContext(configuration)
}

@Composable
private fun AppearanceColorGroupEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatArgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatArgb(color)
        if (!input.equals(formatted, ignoreCase = true) && parseHexColor(input) != color) {
            input = formatted
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(32.dp)
                    .clip(ContinuousRoundedRectangle(999.dp))
                    .background(ComposeColor(color))
                    .border(1.dp, MiuixTheme.colorScheme.outline, ContinuousRoundedRectangle(999.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.main
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = input,
            onValueChange = { raw ->
                val sanitized = sanitizeHexColorInput(raw) ?: return@TextField
                input = sanitized
                parseHexColor(sanitized)?.let(onColorChange)
            },
            label = stringResource(R.string.settings_appearance_color_input_label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun KeyColorEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatRgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatRgb(color)
        val normalizedColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        if (!input.equals(formatted, ignoreCase = true) && parseRgbColor(input) != normalizedColor) {
            input = formatted
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
        }
        // Per-mode key opacity, baked directly into the key color's alpha channel (same logic as
        // the background opacity slider above).
        SliderPreferenceItem(
            title = stringResource(R.string.settings_key_opacity_title),
            value = Color.alpha(color),
            max = 255,
            onValueChange = { alpha ->
                onColorChange((alpha shl 24) or (color and 0xFFFFFF))
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { raw ->
                    val sanitized = sanitizeRgbColorInput(raw) ?: return@TextField
                    input = sanitized
                    parseRgbColor(sanitized)?.let { rgb ->
                        onColorChange((color and 0xFF000000.toInt()) or (rgb and 0xFFFFFF))
                    }
                },
                label = stringResource(R.string.settings_key_color_input_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NumericTextSettingItem(
    title: String,
    summary: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = "DP"
        )
    }
}

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Int,
    max: Int,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit
) = SliderPreferenceItem(
    title = title,
    value = value.toFloat(),
    range = 0f..max.toFloat(),
    step = 1f,
    enabled = enabled,
    format = { it.roundToInt().toString() },
    onValueChange = { onValueChange(it.roundToInt()) }
)

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean = true,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = format(value),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            enabled = enabled,
            value = value.coerceIn(range),
            onValueChange = { onValueChange(((it / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
        )
    }
}

@Composable
private fun GlassOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit,
    onReset: () -> Unit
) {
    fun read(field: GlassOverrideField) = runCatching {
        GlassMaterialOverrides.parse(mapOf(field to values[field.ordinal]))
    }.getOrNull()
    val glass = read(GlassOverrideField.GLASS)?.glass
    val radii = read(GlassOverrideField.BLUR_RADII)?.blurRadii ?: GlassSliderParameter.startingBlurRadii()
    val bloom = read(GlassOverrideField.BLOOM)?.bloom ?: GlassSliderParameter.startingBloom()
    val valid = GlassOverrideField.entries.all { read(it) != null }
    MiuixSwitchWidget(
        title = stringResource(R.string.settings_glass_custom),
        description = stringResource(
            if (enabled) R.string.settings_glass_custom_desc else R.string.settings_glass_unavailable
        ),
        checked = glass != null,
        enabled = enabled && valid,
        onCheckedChange = { checked ->
            val current = GlassMaterialOverrides(glass, radii, bloom)
            val updated = current.withGlassEnabled(checked)
            GlassOverrideField.entries.forEach { onValueChange(it.ordinal, updated.text(it)) }
        }
    )
    if (glass == null && valid) return
    HorizontalDivider()
    if (glass != null) {
        val labels = mapOf(
            GlassSliderParameter.LIGHT_ANGLE to R.string.settings_glass_light_angle,
            GlassSliderParameter.LIGHT_INTENSITY to R.string.settings_glass_light_intensity,
            GlassSliderParameter.REFRACTION to R.string.settings_glass_refraction,
            GlassSliderParameter.DEPTH to R.string.settings_glass_depth,
            GlassSliderParameter.SPLAY to R.string.settings_glass_splay,
            GlassSliderParameter.THICKNESS to R.string.settings_glass_thickness,
            GlassSliderParameter.EDGE_CURVE to R.string.settings_glass_edge_curve
        )
        @Composable
        fun ParameterSlider(parameter: GlassSliderParameter, bloomMode: Boolean = false) {
            val label = stringResource(
                if (!bloomMode && parameter == GlassSliderParameter.LIGHT_INTENSITY) R.string.settings_glass_edge_lighten
                else if (!bloomMode && parameter == GlassSliderParameter.SPLAY) R.string.settings_glass_lighten_angle
                else labels.getValue(parameter)
            )
            SliderPreferenceItem(
                title = if (bloomMode) stringResource(R.string.settings_glass_highlight_parameter, label) else label,
                value = if (bloomMode) parameter.readBloom(bloom) else parameter.read(glass),
                range = parameter.range,
                step = parameter.step,
                enabled = enabled && valid,
                format = { value ->
                    when (parameter) {
                        GlassSliderParameter.LIGHT_ANGLE, GlassSliderParameter.SPLAY -> "${value.roundToInt()}°"
                        GlassSliderParameter.LIGHT_INTENSITY -> "${value.roundToInt()}%"
                        GlassSliderParameter.DEPTH, GlassSliderParameter.THICKNESS -> "${value.roundToInt()} px"
                        else -> String.format(java.util.Locale.ROOT, "%.2f", value)
                    }
                },
                onValueChange = {
                    val field = if (bloomMode) GlassOverrideField.BLOOM else GlassOverrideField.GLASS
                    val updated = if (bloomMode) parameter.writeBloom(bloom, it) else parameter.write(glass, it)
                    onValueChange(field.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.forEach { ParameterSlider(it) }
        val unsupported = stringResource(R.string.settings_glass_unsupported)
        SliderPreferenceItem(
            title = stringResource(R.string.settings_glass_dispersion),
            value = 0f, range = 0f..100f, step = 1f,
            enabled = false, format = { unsupported }, onValueChange = {}
        )
        listOf(R.string.settings_glass_frost_small, R.string.settings_glass_frost_large).forEachIndexed { index, title ->
            SliderPreferenceItem(
                title = stringResource(title), value = radii[index].toFloat(),
                range = 0f..400f, step = 1f, enabled = enabled && valid,
                format = { "${it.roundToInt()} px" },
                onValueChange = { value ->
                    val updated = radii.toMutableList().apply { this[index] = value.roundToInt() }
                    onValueChange(GlassOverrideField.BLUR_RADII.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.filter { it.bloomIndex != null }.forEach {
            ParameterSlider(it, bloomMode = true)
        }
    }
    HorizontalDivider()
    var rawExpanded by rememberSaveable { mutableStateOf(false) }
    val showRaw = rawExpanded || !valid
    val arrowRotation by animateFloatAsState(
        targetValue = if (showRaw) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "GlassRawArrowRotation"
    )
    BasicComponent(
        title = stringResource(R.string.settings_glass_raw),
        summary = stringResource(if (showRaw) R.string.settings_glass_collapse else R.string.settings_glass_expand),
        onClick = { rawExpanded = !rawExpanded },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(width = 10.dp, height = 16.dp).rotate(arrowRotation)
            )
        }
    )
    if (showRaw) GlassRawOverrideEditor(values, enabled, onValueChange)
    ArrowPreference(
        title = stringResource(R.string.settings_glass_reset),
        summary = stringResource(R.string.settings_glass_reset_desc),
        onClick = onReset
    )
}

@Composable
private fun GlassRawOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit
) {
    val titles = listOf(
        R.string.settings_glass_params, R.string.settings_glass_blur,
        R.string.settings_glass_bloom, R.string.settings_glass_type
    )
    val descriptions = listOf(
        R.string.settings_glass_params_desc, R.string.settings_glass_blur_desc,
        R.string.settings_glass_bloom_desc, R.string.settings_glass_type_desc
    )
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.settings_glass_raw_summary),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        GlassOverrideField.entries.filter { it != GlassOverrideField.MATERIAL_TYPE }.forEach { field ->
            val index = field.ordinal
            val valid = GlassMaterialOverrides.isValid(field, values[index])
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).alpha(if (enabled) 1f else 0.38f)) {
                Text(stringResource(titles[index]), style = MiuixTheme.textStyles.main)
                Text(
                    stringResource(descriptions[index]), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = values[index],
                    enabled = enabled,
                    onValueChange = { if (it.length <= 4096) onValueChange(index, it) },
                    label = stringResource(R.string.settings_glass_default),
                    singleLine = field == GlassOverrideField.MATERIAL_TYPE || field == GlassOverrideField.BLUR_RADII,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!valid) {
                    Text(
                        stringResource(R.string.settings_glass_invalid),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixSwitchWidget(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val toggleAction = {
        if (enabled) onCheckedChange(!checked)
    }

    BasicComponent(
        title = title,
        modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
        enabled = enabled,
        summary = description,
        onClick = toggleAction,
        endActions = {
            Switch(
                enabled = enabled,
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

private fun previewTextColor(color: Int): ComposeColor =
    if (isLightColor(color)) ComposeColor.Black else ComposeColor.White

private fun parseLogoCustomColor(input: String): Int {
    return parseRgbColor(input) ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR
}

@Composable
private fun MiuixDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueMaxWidth: Dp = 132.dp
) {
    var expanded by remember { mutableStateOf(false) }
    var holdDown by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val entry = remember(items, selectedIndex) {
        DropdownEntry(
            items = items.mapIndexed { index, text ->
                DropdownItem(
                    text = text,
                    selected = index == selectedIndex,
                    onClick = { onSelectedIndexChange(index) }
                )
            }
        )
    }
    val actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    val selectedText = entry.items.firstOrNull { it.selected }?.text.orEmpty()

    BasicComponent(
        modifier = modifier,
        endActions = {
            Text(
                text = selectedText,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .widthIn(max = valueMaxWidth)
                    .align(Alignment.CenterVertically)
                    .basicMarquee(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = actionColor,
                textAlign = TextAlign.End
            )
            DropdownArrowEndAction(actionColor = actionColor)
            OverlayDropdownPopup(
                entry = entry,
                show = expanded,
                onDismiss = { expanded = false },
                onDismissFinished = { holdDown = false },
                maxHeight = null,
                dropdownColors = DropdownDefaults.dropdownColors(),
                renderInRootScaffold = true,
                collapseOnSelection = true
            )
        },
        onClick = {
            expanded = !expanded
            if (expanded) {
                holdDown = true
                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        },
        role = Role.DropdownList,
        holdDownState = holdDown
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatRgb(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

private fun formatArgb(color: Int): String = String.format("#%08X", color)

private fun sanitizeHexColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 8 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun sanitizeRgbColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun parseHexColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    return when (body.length) {
        6, 8 -> runCatching { Color.parseColor("#$body") }.getOrNull()
        else -> null
    }
}

private fun parseRgbColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    if (body.length != 6) return null
    return runCatching { Color.parseColor("#$body") }.getOrNull()
}

private fun sanitizeIntegerInput(input: String, maxLength: Int): String? {
    val trimmed = input.trim()
    if (trimmed.length > maxLength) return null
    if (trimmed.isNotEmpty() && !trimmed.matches(Regex("^\\d*$"))) return null
    return trimmed
}

private fun isLightColor(color: Int): Boolean {
    val luminance =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
    return luminance > 0.5
}

private fun LazyListScope.GestureTabContent(
    qwertyGestureEnabled: Boolean,
    onQwertyGestureEnabledChange: (Boolean) -> Unit,
    t9GestureEnabled: Boolean,
    onT9GestureEnabledChange: (Boolean) -> Unit,
    gestureVibration: Boolean,
    onGestureVibrationChange: (Boolean) -> Unit,
    t9GestureVibration: Boolean,
    onT9GestureVibrationChange: (Boolean) -> Unit,
    gestureThreshold: Int,
    onGestureThresholdChange: (Int) -> Unit,
    t9GestureThreshold: Int,
    onT9GestureThresholdChange: (Int) -> Unit,
    showGestureKeyLabels: Boolean,
    onShowGestureKeyLabelsChange: (Boolean) -> Unit,
    gestureLabelTextSizeSp: Int,
    onGestureLabelTextSizeSpChange: (Int) -> Unit,
    gestureLabelAlpha: Int,
    onGestureLabelAlphaChange: (Int) -> Unit,
    gestureLabelPosition: Int,
    onGestureLabelPositionChange: (Int) -> Unit,
    gestureLabelMarginTopDp: Int,
    onGestureLabelMarginTopDpChange: (Int) -> Unit,
    gestureLabelMarginBottomDp: Int,
    onGestureLabelMarginBottomDpChange: (Int) -> Unit,
    gestureLabelMarginLeftDp: Int,
    onGestureLabelMarginLeftDpChange: (Int) -> Unit,
    gestureLabelMarginRightDp: Int,
    onGestureLabelMarginRightDpChange: (Int) -> Unit,
    gestureBindingsJson: String,
    onGestureBindingsJsonChange: (String) -> Unit
) {
    // 1. 手势总控卡片
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                MiuixSwitchWidget(
                    title = "启用 26 键 QWERTY 下滑手势",
                    description = "全键盘按键向下滑动触发绑定动作 (默认 Z/X/C/V)",
                    checked = qwertyGestureEnabled,
                    onCheckedChange = onQwertyGestureEnabledChange
                )
                MiuixSwitchWidget(
                    title = "启用九宫格 T9 下滑手势",
                    description = "支持 1~9 号键位向下滑动触发绑定动作",
                    checked = t9GestureEnabled,
                    onCheckedChange = onT9GestureEnabledChange
                )
                MiuixSwitchWidget(
                    title = "QWERTY 手势触觉反馈",
                    description = "26 键手势触发时调用键盘触觉振动",
                    checked = gestureVibration,
                    onCheckedChange = onGestureVibrationChange
                )
                MiuixSwitchWidget(
                    title = "T9 九宫格手势触觉反馈",
                    description = "九宫格手势触发时调用键盘触觉振动",
                    checked = t9GestureVibration,
                    onCheckedChange = onT9GestureVibrationChange
                )
            }
        }
    }

    // 2. 灵敏度阈值卡片
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SliderPreferenceItem(
                    title = "QWERTY 触发滑动阈值: ${gestureThreshold} dp",
                    value = gestureThreshold,
                    max = 48,
                    onValueChange = { onGestureThresholdChange(it.coerceIn(10, 48)) }
                )
                SliderPreferenceItem(
                    title = "T9 触发滑动阈值: ${t9GestureThreshold} dp",
                    value = t9GestureThreshold,
                    max = 48,
                    onValueChange = { onT9GestureThresholdChange(it.coerceIn(10, 48)) }
                )
            }
        }
    }

    // 3. 标签样式卡片
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                MiuixSwitchWidget(
                    title = "显示按键手势标签",
                    description = "在已绑定手势的按键上显示动作名称角标",
                    checked = showGestureKeyLabels,
                    onCheckedChange = onShowGestureKeyLabelsChange
                )
                SliderPreferenceItem(
                    title = "标签文字大小: ${gestureLabelTextSizeSp} sp",
                    value = gestureLabelTextSizeSp,
                    max = 16,
                    onValueChange = { onGestureLabelTextSizeSpChange(it.coerceIn(6, 16)) }
                )
                SliderPreferenceItem(
                    title = "标签不透明度: ${gestureLabelAlpha}",
                    value = gestureLabelAlpha,
                    max = 255,
                    onValueChange = { onGestureLabelAlphaChange(it.coerceIn(0, 255)) }
                )
                MiuixDropdownPreference(
                    title = "标签位置",
                    items = listOf("底部", "顶部"),
                    selectedIndex = if (gestureLabelPosition == WeTypeSettings.GESTURE_LABEL_POSITION_TOP) 1 else 0,
                    onSelectedIndexChange = { index ->
                        onGestureLabelPositionChange(
                            if (index == 1) {
                                WeTypeSettings.GESTURE_LABEL_POSITION_TOP
                            } else {
                                WeTypeSettings.GESTURE_LABEL_POSITION_BOTTOM
                            }
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签上边距: ${gestureLabelMarginTopDp} dp",
                    value = gestureLabelMarginTopDp,
                    max = 24,
                    onValueChange = { onGestureLabelMarginTopDpChange(it.coerceIn(0, 24)) }
                )
                SliderPreferenceItem(
                    title = "标签下边距: ${gestureLabelMarginBottomDp} dp",
                    value = gestureLabelMarginBottomDp,
                    max = 24,
                    onValueChange = { onGestureLabelMarginBottomDpChange(it.coerceIn(0, 24)) }
                )
                val horizontalOffsetDp = gestureLabelMarginLeftDp - gestureLabelMarginRightDp
                SliderPreferenceItem(
                    title = "标签水平偏移",
                    value = horizontalOffsetDp.toFloat(),
                    range = -24f..24f,
                    step = 1f,
                    format = { "${it.roundToInt()} dp" },
                    onValueChange = { rawOffset ->
                        val offset = rawOffset.roundToInt().coerceIn(-24, 24)
                        // Keep the persisted left/right keys for backward compatibility while
                        // exposing the single signed offset used by the reference implementation.
                        onGestureLabelMarginLeftDpChange(offset.coerceAtLeast(0))
                        onGestureLabelMarginRightDpChange((-offset).coerceAtLeast(0))
                    }
                )
            }
        }
    }

    // 4. 可视化键位动作映射编辑器卡片
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            GestureKeyBindingEditor(
                bindingsJson = gestureBindingsJson,
                onBindingsChange = onGestureBindingsJsonChange
            )
        }
    }
}

@Composable
private fun GestureKeyBindingEditor(
    bindingsJson: String,
    onBindingsChange: (String) -> Unit
) {
    var selectedKeyboardTab by rememberSaveable { mutableIntStateOf(0) }
    var editingKey by remember { mutableStateOf<Char?>(null) }
    val bindings = remember(bindingsJson) { WeTypeGestureSettings.parseBindings(bindingsJson) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Tab 切换：26键 (QWERTY) 与 九宫格 (T9)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedKeyboardTab == 0) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { selectedKeyboardTab = 0 }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "QWERTY",
                    style = MiuixTheme.textStyles.main,
                    color = if (selectedKeyboardTab == 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (selectedKeyboardTab == 0) FontWeight.Bold else FontWeight.Normal
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedKeyboardTab == 1) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { selectedKeyboardTab = 1 }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "T9",
                    style = MiuixTheme.textStyles.main,
                    color = if (selectedKeyboardTab == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (selectedKeyboardTab == 1) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedKeyboardTab == 0) {
            val row1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
            val row2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
            val row3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

            // Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row1.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row2.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 3
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row3.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 4: Space
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    GestureKeyButton(
                        keyLabel = "空格 (Space)",
                        action = bindings[' '] ?: GestureAction.None,
                        onClick = { editingKey = ' ' }
                    )
                }
            }
        } else {
            val t9Rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9')
            )
            t9Rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { char ->
                        Box(modifier = Modifier.weight(1f)) {
                            GestureKeyButton(
                                keyLabel = char.toString(),
                                action = bindings[char] ?: GestureAction.None,
                                onClick = { editingKey = char }
                            )
                        }
                    }
                }
                if (index < t9Rows.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 快捷操作按钮：恢复默认 / 清空全部
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        onBindingsChange(WeTypeSettings.DEFAULT_GESTURE_BINDINGS_JSON)
                        Toast.makeText(context, "已恢复默认预设 (Z=全选 / X=剪切 / C=复制 / V=粘贴)", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "恢复默认预设",
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        onBindingsChange(WeTypeGestureSettings.serializeBindings(emptyMap()))
                        Toast.makeText(context, "已清空所有按键手势绑定", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "清空全部按键",
                    style = MiuixTheme.textStyles.main,
                    color = ComposeColor(0xFFE53935),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // 动作选择对话框
    editingKey?.let { targetChar ->
        val currentAction = bindings[targetChar] ?: GestureAction.None
        val keyName = if (targetChar == ' ') "空格 (Space)" else targetChar.uppercaseChar().toString()
        val scrollState = rememberScrollState()

        Dialog(onDismissRequest = { editingKey = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                insideMargin = PaddingValues(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "设置按键 [$keyName] 下滑动作",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "选择下滑此按键时触发的操作 (共 24 种动作)",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(scrollState)
                    ) {
                        GestureAction.entries.forEach { action ->
                            val isSelected = action == currentAction
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else ComposeColor.Transparent
                                    )
                                    .clickable {
                                        val newMap = bindings.toMutableMap()
                                        if (action == GestureAction.None) {
                                            newMap.remove(targetChar)
                                        } else {
                                            newMap[targetChar] = action
                                        }
                                        onBindingsChange(WeTypeGestureSettings.serializeBindings(newMap))
                                        editingKey = null
                                        Toast.makeText(context, "[$keyName] 已绑定: ${action.title}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${action.id}. ${action.title}",
                                        style = MiuixTheme.textStyles.main,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                                    )
                                    if (action.shortTitle.isNotEmpty() && action.shortTitle != "\\") {
                                        Text(
                                            text = "按键标签: ${action.shortTitle}",
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        style = MiuixTheme.textStyles.title4,
                                        color = MiuixTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { editingKey = null }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "取消",
                                style = MiuixTheme.textStyles.main,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureKeyButton(
    keyLabel: String,
    action: GestureAction,
    onClick: () -> Unit
) {
    val isBound = action != GestureAction.None && action != GestureAction.Disable
    val isDisable = action == GestureAction.Disable

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isBound) 1.5.dp else 0.5.dp,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                when {
                    isBound -> MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    isDisable -> ComposeColor(0xFFE53935).copy(alpha = 0.08f)
                    else -> MiuixTheme.colorScheme.surfaceContainerHigh
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = keyLabel,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Bold,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
            Text(
                text = when {
                    isBound -> action.shortTitle
                    isDisable -> "禁用"
                    else -> "-"
                },
                fontSize = 9.sp,
                fontWeight = if (isBound) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                },
                maxLines = 1
            )
        }
    }
}

private fun LazyListScope.FeatureTabContent(
    logoEnabled: Boolean,
    onLogoEnabledChange: (Boolean) -> Unit,
    logoShowEnabled: Boolean,
    onLogoShowEnabledChange: (Boolean) -> Unit,
    logoColorMode: String,
    onLogoColorModeChange: (String) -> Unit,
    logoCustomColorInput: String,
    onLogoCustomColorInputChange: (String) -> Unit,
    logoImageSummary: String,
    onOpenLogoImage: () -> Unit,
    onResetLogo: () -> Unit,
    fontMode: Int,
    onFontModeChange: (Int) -> Unit,
    onResetFont: () -> Unit,
    miuiClipboardGlass: Boolean,
    onMiuiClipboardGlassChange: (Boolean) -> Unit,
    showCrossDeviceClipboard: Boolean,
    onShowCrossDeviceClipboardChange: (Boolean) -> Unit,
    removeClipboardRetentionLimit: Boolean,
    onRemoveClipboardRetentionLimitChange: (Boolean) -> Unit,
    removeClipboardTextLimit: Boolean,
    onRemoveClipboardTextLimitChange: (Boolean) -> Unit,
    clipboardSearchEnabled: Boolean,
    onClipboardSearchEnabledChange: (Boolean) -> Unit,
    clipboardSearchClearOnBack: Boolean,
    onClipboardSearchClearOnBackChange: (Boolean) -> Unit,
    clipboardImageAdjustRatio: Boolean,
    onClipboardImageAdjustRatioChange: (Boolean) -> Unit,
    clipboardImageCrop: Boolean,
    onClipboardImageCropChange: (Boolean) -> Unit,
    clipboardImageUniformRowHeight: Boolean,
    onClipboardImageUniformRowHeightChange: (Boolean) -> Unit,
    clipboardImageMaxCount: Int,
    onClipboardImageMaxCountChange: (Int) -> Unit,
    clipboardImageMaxSizeMb: Int,
    onClipboardImageMaxSizeMbChange: (Int) -> Unit,
    onOpenClipboardBackup: () -> Unit,
    disableHotUpdate: Boolean,
    onDisableHotUpdateChange: (Boolean) -> Unit,
    onRestoreDefaults: () -> Unit,
    visibleSections: Set<String>? = null
) {
    // 1. 键盘 Logo 卡片
    if (visibleSections == null || "logo" in visibleSections) item {
        val context = LocalContext.current
        val logoColorModeOptions = listOf(
            WeTypeSettings.LOGO_COLOR_MODE_BRAND,
            WeTypeSettings.LOGO_COLOR_MODE_SYSTEM,
            WeTypeSettings.LOGO_COLOR_MODE_CUSTOM
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                MiuixSwitchWidget(
                    title = "启用 Logo 替换",
                    description = "关闭则显示输入法原生 Logo",
                    checked = logoEnabled,
                    onCheckedChange = onLogoEnabledChange
                )
                MiuixSwitchWidget(
                    title = "显示 Logo",
                    description = "关闭则隐藏键盘上的 Logo",
                    checked = logoShowEnabled,
                    onCheckedChange = onLogoShowEnabledChange
                )
                MiuixDropdownPreference(
                    title = "Logo 主体颜色",
                    items = listOf(
                        "跟随品牌色 (官方彩色)",
                        "跟随系统 (自适应黑白)",
                        "自定义颜色"
                    ),
                    selectedIndex = logoColorModeOptions.indexOf(logoColorMode).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onLogoColorModeChange(logoColorModeOptions[index])
                    }
                )
                if (logoColorMode == WeTypeSettings.LOGO_COLOR_MODE_CUSTOM) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "自定义颜色",
                            style = MiuixTheme.textStyles.main
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "输入 #RRGGBB，例如 #23C891",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = logoCustomColorInput,
                            onValueChange = { input ->
                                val trimmed = input.trim()
                                val hasPrefix = trimmed.startsWith("#")
                                val body = trimmed.removePrefix("#")
                                if (body.length <= 6 && body.matches(Regex("^[0-9a-fA-F]*$"))) {
                                    onLogoCustomColorInputChange(if (hasPrefix || body.isNotEmpty()) "#$body" else "")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = "#RRGGBB"
                        )
                    }
                }
                BasicComponent(
                    title = "自定义图片 Logo",
                    summary = logoImageSummary,
                    onClick = onOpenLogoImage
                )
                ArrowPreference(
                    title = "重置 Logo 设置",
                    summary = "恢复 Logo 默认开启、品牌色状态",
                    onClick = {
                        onResetLogo()
                        Toast.makeText(context, "Logo 设置已重置", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    // 2. 字体替换卡片
    if (visibleSections == null || "font" in visibleSections) item {
        val context = LocalContext.current
        val fontModeOptions = listOf(
            WeTypeSettings.FONT_MODE_OFFICIAL,
            WeTypeSettings.FONT_MODE_MODULE,
            WeTypeSettings.FONT_MODE_SYSTEM
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                MiuixDropdownPreference(
                    title = "字体来源",
                    items = listOf(
                        "微信官方 (放行宿主原生字体)",
                        "模块内置 (WE-Regular 优化字体)",
                        "跟随系统 (系统默认字体 Typeface.DEFAULT)"
                    ),
                    selectedIndex = fontModeOptions.indexOf(fontMode).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onFontModeChange(fontModeOptions[index])
                    }
                )
                ArrowPreference(
                    title = "重置字体设置",
                    summary = "恢复跟随系统默认字体",
                    onClick = {
                        onResetFont()
                        Toast.makeText(context, "字体设置已重置", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    // 3. 剪贴板增强卡片
    if (visibleSections == null || "clipboard" in visibleSections) item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                MiuixSwitchWidget(
                    title = "小米剪贴板与常用语液态玻璃",
                    description = "为小米底栏唤起的原生剪切板及常用语面板应用液态玻璃材质与高斯模糊",
                    checked = miuiClipboardGlass,
                    onCheckedChange = onMiuiClipboardGlassChange
                )
                MiuixSwitchWidget(
                    title = "跨设备条目可见化持久保存",
                    description = "自动将多端同步的剪贴板远程条目转换为本地可见条目保存",
                    checked = showCrossDeviceClipboard,
                    onCheckedChange = onShowCrossDeviceClipboardChange
                )
                MiuixSwitchWidget(
                    title = "解除保留上限与时长限制",
                    description = "剪贴板保存条数上限提升至 100,000 条，留存时长永久",
                    checked = removeClipboardRetentionLimit,
                    onCheckedChange = onRemoveClipboardRetentionLimitChange
                )
                MiuixSwitchWidget(
                    title = "解除单条文本长度限制",
                    description = "剪贴板文本长度上限提升至 1 亿字符，抑制超限提示",
                    checked = removeClipboardTextLimit,
                    onCheckedChange = onRemoveClipboardTextLimitChange
                )
                MiuixSwitchWidget(
                    title = "剪贴板搜索",
                    description = "在剪贴板页面显示搜索框，按文本拼音分词过滤",
                    checked = clipboardSearchEnabled,
                    onCheckedChange = onClipboardSearchEnabledChange
                )
                MiuixSwitchWidget(
                    title = "返回时清理搜索关键词",
                    description = "在剪贴板页面点击返回键时清理搜索关键词，恢复完整列表；关闭则保留上次搜索结果",
                    checked = clipboardSearchClearOnBack,
                    onCheckedChange = onClipboardSearchClearOnBackChange
                )
                MiuixSwitchWidget(
                    title = "图片缩略图保持原比例",
                    description = "宽度按原图比例缩放，最长不超过行宽；关闭后缩略图统一为正方形",
                    checked = clipboardImageAdjustRatio,
                    onCheckedChange = onClipboardImageAdjustRatioChange
                )
                MiuixSwitchWidget(
                    title = "图片缩略图裁剪填满",
                    description = "在缩略图框内居中裁剪填满，可能裁掉图片边缘；关闭则完整显示",
                    checked = clipboardImageCrop,
                    onCheckedChange = onClipboardImageCropChange
                )
                MiuixSwitchWidget(
                    title = "图片缩略图统一行高",
                    description = "所有图片条目统一为两行高度；关闭后图片按单行高度显示",
                    checked = clipboardImageUniformRowHeight,
                    onCheckedChange = onClipboardImageUniformRowHeightChange
                )
                val imageCountOptions = listOf(0, 20, 50, 100, 200, 500)
                val imageSizeOptions = listOf(0, 128, 256, 512, 1024, 2048)
                MiuixDropdownPreference(
                    title = "图片数量上限",
                    items = imageCountOptions.map { if (it == 0) "无上限" else "$it 张" },
                    selectedIndex = imageCountOptions.indexOf(clipboardImageMaxCount).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onClipboardImageMaxCountChange(imageCountOptions[index])
                    }
                )
                MiuixDropdownPreference(
                    title = "图片容量上限",
                    items = imageSizeOptions.map {
                        when {
                            it == 0 -> "无上限"
                            it >= 1024 -> "${it / 1024} GB"
                            else -> "$it MB"
                        }
                    },
                    selectedIndex = imageSizeOptions.indexOf(clipboardImageMaxSizeMb).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onClipboardImageMaxSizeMbChange(imageSizeOptions[index])
                    }
                )
                BasicComponent(
                    title = "剪贴板备份与恢复",
                    summary = "导出 zip / WebDAV 备份 / 导入还原",
                    onClick = onOpenClipboardBackup
                )
            }
        }
    }

    // 4. 进阶系统防护卡片
    if (visibleSections == null || "protection" in visibleSections) item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                MiuixSwitchWidget(
                    title = stringResource(R.string.settings_disable_hot_update_title),
                    description = stringResource(R.string.settings_disable_hot_update_desc),
                    checked = disableHotUpdate,
                    onCheckedChange = onDisableHotUpdateChange
                )
            }
        }
    }

    // 5. 关于与重置卡片
    if (visibleSections == null || "about" in visibleSections) item {
        val context = LocalContext.current
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                BasicComponent(
                    title = stringResource(R.string.settings_visit_github_title),
                    summary = "https://github.com/xu-qian123/WeType-3t",
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary
                    ),
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/xu-qian123/WeType-3t")
                        )
                        context.startActivity(intent)
                    }
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_reset_title),
                    summary = stringResource(R.string.settings_reset_desc),
                    onClick = onRestoreDefaults
                )
            }
        }
    }
}

// ---------- 底栏四 tab：首页 / 功能 hub / 美化 hub ----------

private fun LazyListScope.HomePageItems(
    activationStatus: ModuleActivationTracker.ActivationStatus,
    darkMode: Boolean
) {
    // 1. 模块工作状态卡（整卡按状态变色，不再用小标签）
    item {
        val active = activationStatus.isActive
        val cardBg = when {
            active && !darkMode -> ComposeColor(0xFF4F9A71)
            active && darkMode -> ComposeColor(0xFF2E6B48)
            !active && !darkMode -> ComposeColor(0xFFC86F67)
            else -> ComposeColor(0xFF8A4A43)
        }
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            colors = CardColors(
                color = cardBg,
                contentColor = ComposeColor.White
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (active) "工作中" else "模块未启用",
                    color = ComposeColor.White,
                    fontSize = 20.sp,
                    style = MiuixTheme.textStyles.main
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (active) {
                        "LSPosed 心跳正常，Hook 已加载"
                    } else {
                        "请在 LSPosed 中启用本模块并勾选微信输入法作用域后重启输入法"
                    },
                    color = ComposeColor.White.copy(alpha = 0.85f),
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }

    // 2. 模块信息卡
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                BasicComponent(
                    title = "模块版本",
                    summary = "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})"
                )
                BasicComponent(
                    title = "构建时间",
                    summary = BuildConfig.BUILD_TIME
                )
                BasicComponent(
                    title = "作者",
                    summary = "xu-qian123"
                )
            }
        }
    }

    // 3. 设备与构建卡
    item {
        val context = LocalContext.current
        val wetypeVersion = runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    "com.tencent.wetype",
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo("com.tencent.wetype", 0)
            }
            info.versionName ?: "未知"
        }.getOrDefault("未安装")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                BasicComponent(
                    title = "微信输入法版本",
                    summary = wetypeVersion
                )
                BasicComponent(
                    title = "手机型号",
                    summary = "${Build.MANUFACTURER} ${Build.MODEL}"
                )
                BasicComponent(
                    title = "安卓版本",
                    summary = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                )
            }
        }
    }
}

private fun LazyListScope.FunctionHubItems(onOpen: (String) -> Unit) {
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                ArrowPreference(
                    title = "按键手势",
                    summary = "全键盘与九宫格下滑手势、动作绑定与触觉反馈",
                    onClick = { onOpen("gesture") }
                )
                ArrowPreference(
                    title = "剪贴板增强",
                    summary = "上限解除、搜索、图片与备份恢复",
                    onClick = { onOpen("clipboard") }
                )
            }
        }
    }
}

private fun LazyListScope.BeautifyHubItems(onOpen: (String) -> Unit) {
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                ArrowPreference(
                    title = "界面美化",
                    summary = "背景、按键与候选词的颜色、模糊与圆角",
                    onClick = { onOpen("appearance") }
                )
                ArrowPreference(
                    title = "字体替换",
                    summary = "官方、内置与系统字体切换",
                    onClick = { onOpen("font") }
                )
                ArrowPreference(
                    title = "键盘 Logo",
                    summary = "颜色模式与自定义图片 Logo",
                    onClick = { onOpen("logo") }
                )
            }
        }
    }
}
