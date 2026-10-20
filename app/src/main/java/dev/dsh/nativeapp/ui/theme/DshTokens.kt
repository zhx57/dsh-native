package dev.dsh.nativeapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DSH design tokens, transcribed from `@deepseek-ai/dsh-client-ui-theme`.
 *
 * Names mirror the CSS custom properties (`--dsw-static-*` / `--dsw-alias-*`)
 * so the native UI can be checked against the web build token by token.
 */
object Static {
    val amber100 = Color(0xFFFEF5E7)
    val amber400 = Color(0xFFF7AD31)
    val amber500 = Color(0xFFF59E0B)
    val amber600 = Color(0xFFDD8629)

    val blue100 = Color(0xFFDBEAFE)
    val blue300 = Color(0xFF93C5FD)
    val blue400 = Color(0xFF60A5FA)
    val blue450 = Color(0xFF4D93F8)
    val blue500 = Color(0xFF3B82F6)
    val blue50 = Color(0xFFEFF6FF)
    val blue75 = Color(0xFFE5F0FF)
    val blue600 = Color(0xFF2563EB)
    val blue800 = Color(0xFF1E40AF)
    val blue900 = Color(0xFF0E3074)

    val deepseek50 = Color(0xFFEDF3FE)
    val deepseek100 = Color(0xFFE4EDFD)
    val deepseek200 = Color(0xFFD3E2FF)
    val deepseek300 = Color(0xFFB7C8FE)
    val deepseek400 = Color(0xFF7AAAFF)
    val deepseek450 = Color(0xFF5686FE)
    val deepseek500 = Color(0xFF4176E6)
    val deepseek600 = Color(0xFF4868B2)
    val deepseek900 = Color(0xFF283142)

    val green100 = Color(0xFFE6FAED)
    val green400 = Color(0xFF4ED17E)
    val green500 = Color(0xFF22C55E)

    val red100 = Color(0xFFFEE2E2)
    val red400 = Color(0xFFF25A5A)
    val red500 = Color(0xFFEF4444)
    val red50 = Color(0xFFFEF2F2)
    val red600 = Color(0xFFEC1313)

    val neutral00 = Color(0xFFFFFFFF)
    val neutral1000 = Color(0xFF000000)
    val neutral50 = Color(0xFFFAFAFA)
    val neutral100 = Color(0xFFF5F5F5)
    val neutral150 = Color(0xFFEDEDED)
    val neutral200 = Color(0xFFE5E5E5)
    val neutral300 = Color(0xFFD4D4D4)
    val neutral400 = Color(0xFFA2A4A6)
    val neutral500 = Color(0xFF7F8287)
    val neutral600 = Color(0xFF545557)
    val neutral700 = Color(0xFF3C3C3D)
    val neutral800 = Color(0xFF292929)
    val neutral850 = Color(0xFF212123)
    val neutral900 = Color(0xFF0F0F0F)

    val bluish00 = Color(0xFFFFFFFF)
    val bluish50 = Color(0xFFF9FAFB)
    val bluish60 = Color(0xFFF5F6F7)
    val bluish75 = Color(0xFFF1F3F5)
    val bluish100 = Color(0xFFEBEEF2)
    val bluish150 = Color(0xFFE9ECF2)
    val bluish200 = Color(0xFFE1E5EE)
    val bluish300 = Color(0xFFCFD3D6)
    val bluish400 = Color(0xFFADB2B8)
    val bluish500 = Color(0xFF979DA6)
    val bluish600 = Color(0xFF81858C)
    val bluish700 = Color(0xFF61666B)
    val bluish750 = Color(0xFF43454A)
    val bluish800 = Color(0xFF353638)
    val bluish850 = Color(0xFF2C2C2E)
    val bluish875 = Color(0xFF232324)
    val bluish900 = Color(0xFF1B1B1C)
    val bluish950 = Color(0xFF151517)
    val bluish1000 = Color(0xFF0F1115)
}

/** Resolved alias tokens for one appearance. */
data class DshColors(
    val isDark: Boolean,
    val bgBase: Color,
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgOverlay: Color,
    val bgMask: Color,
    val bgSkeleton: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    val brandPrimary: Color,
    val brandText: Color,
    val link: Color,
    val interactiveHover: Color,
    val interactiveActive: Color,
    val interactiveHoverSolid: Color,
    val sidebarFill: Color,
    val sidebarNavItemActive: Color,
    val sidebarNavItemHover: Color,
    val sidebarNavItemActiveAccent: Color,
    val bubble: Color,
    val bubbleHighlight: Color,
    val inputMajor: Color,
    val selector: Color,
    val menu: Color,
    val tip: Color,
    val codeBlock: Color,
    val codeBlockBanner: Color,
    val inlineCode: Color,
    val markdownTag: Color,
    val markdownPlaceholder: Color,
    val stateError: Color,
    val stateSuccess: Color,
    val stateWarn: Color,
    val buttonPrimaryFill: Color,
    val buttonPrimaryForeground: Color,
    val buttonInfoFill: Color,
    val scrollbar: Color,
    val diffAdded: Color,
    val diffDeleted: Color,
)

val LightColors = DshColors(
    isDark = false,
    bgBase = Static.bluish00,
    bgLayer1 = Static.bluish00,
    bgLayer2 = Static.bluish00,
    bgLayer3 = Static.bluish00,
    bgOverlay = Static.bluish150,
    bgMask = Color(0x3D000000),
    bgSkeleton = Color(0x0A000000),
    borderL1 = Color(0x0A000000),
    borderL2 = Color(0x1A000000),
    borderL3 = Color(0x1F000000),
    borderL4 = Color(0x29000000),
    labelPrimary = Static.bluish1000,
    labelSecondary = Static.bluish700,
    labelTertiary = Static.bluish600,
    labelCaption = Static.bluish400,
    labelDimmed = Static.bluish200,
    brandPrimary = Static.bluish1000,
    brandText = Static.bluish1000,
    link = Static.deepseek500,
    interactiveHover = Color(0x0F263148),
    interactiveActive = Color(0x1A263148),
    interactiveHoverSolid = Static.bluish75,
    sidebarFill = Static.bluish50,
    sidebarNavItemActive = Static.bluish100,
    sidebarNavItemHover = Static.bluish75,
    sidebarNavItemActiveAccent = Static.deepseek100,
    bubble = Static.deepseek50,
    bubbleHighlight = Static.deepseek200,
    inputMajor = Static.bluish00,
    selector = Static.bluish60,
    menu = Color(0xF8F8F9FA),
    tip = Static.bluish60,
    codeBlock = Static.bluish50,
    codeBlockBanner = Static.bluish50,
    inlineCode = Static.neutral50,
    markdownTag = Static.bluish75,
    markdownPlaceholder = Static.bluish60,
    stateError = Static.red600,
    stateSuccess = Static.green500,
    stateWarn = Static.amber500,
    buttonPrimaryFill = Static.bluish1000,
    buttonPrimaryForeground = Static.bluish00,
    buttonInfoFill = Static.deepseek500,
    scrollbar = Static.neutral200,
    diffAdded = Color(0x1422C55E),
    diffDeleted = Color(0x14EC1313),
)

val DarkColors = DshColors(
    isDark = true,
    bgBase = Static.bluish950,
    bgLayer1 = Static.bluish875,
    bgLayer2 = Static.bluish850,
    bgLayer3 = Static.bluish800,
    bgOverlay = Static.bluish700,
    bgMask = Color(0x80000000),
    bgSkeleton = Color(0x14FFFFFF),
    borderL1 = Color(0x0FFFFFFF),
    borderL2 = Color(0x1FFFFFFF),
    borderL3 = Color(0x29FFFFFF),
    borderL4 = Color(0x33FFFFFF),
    labelPrimary = Static.bluish50,
    labelSecondary = Static.bluish300,
    labelTertiary = Static.bluish400,
    labelCaption = Static.bluish600,
    labelDimmed = Static.bluish750,
    brandPrimary = Static.bluish50,
    brandText = Static.bluish50,
    link = Static.deepseek400,
    interactiveHover = Color(0x14FFFFFF),
    interactiveActive = Color(0x24FFFFFF),
    interactiveHoverSolid = Static.bluish800,
    sidebarFill = Static.bluish900,
    sidebarNavItemActive = Static.bluish750,
    sidebarNavItemHover = Static.bluish850,
    sidebarNavItemActiveAccent = Static.bluish800,
    bubble = Static.bluish850,
    bubbleHighlight = Static.bluish750,
    inputMajor = Static.bluish850,
    selector = Static.bluish800,
    menu = Color(0xF0303136),
    tip = Static.bluish800,
    codeBlock = Static.bluish900,
    codeBlockBanner = Static.bluish850,
    inlineCode = Static.neutral800,
    markdownTag = Static.bluish850,
    markdownPlaceholder = Static.bluish850,
    stateError = Static.red400,
    stateSuccess = Static.green400,
    stateWarn = Static.amber400,
    buttonPrimaryFill = Static.bluish50,
    buttonPrimaryForeground = Static.bluish1000,
    buttonInfoFill = Static.deepseek400,
    scrollbar = Static.neutral200,
    diffAdded = Color(0x1422C55E),
    diffDeleted = Color(0x1FF25A5A),
)

/** Radius tokens (`--dsw-radius-*`). */
object DshRadius {
    val xs = 4
    val sm = 6
    val md = 8
    val lg = 12
    val xl = 16
    val xxl = 20
    val full = 999
}

/** Spacing scale used by the web layout. */
object DshSpacing {
    val xxs = 2
    val xs = 4
    val sm = 8
    val md = 12
    val lg = 16
    val xl = 20
    val xxl = 24
}
