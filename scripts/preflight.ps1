# Cheap pre-flight check: the two failure modes that kept breaking CI.
#
# 1. A Compose symbol is used but its import is missing (TextOverflow, clip,
#    TextAlign, spacedBy, ...). Kotlin only reports these at compile time, so a
#    grep catches them in a second instead of a four-minute CI round trip.
# 2. A public composable gained a parameter but a call site was not updated.
#
# This is not a compiler. It only covers the mistakes that actually happened.

$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "app/src/main/java"
$files = Get-ChildItem $src -Recurse -Filter *.kt -File
$problems = @()

# symbol -> required import
$required = @{
    'TextOverflow'          = 'androidx.compose.ui.text.style.TextOverflow'
    'TextAlign'             = 'androidx.compose.ui.text.style.TextAlign'
    'FontFamily'            = 'androidx.compose.ui.text.font.FontFamily'
    'clip'                  = 'androidx.compose.ui.draw.clip'
    'verticalScroll'        = 'androidx.compose.foundation.verticalScroll'
    'rememberScrollState'   = 'androidx.compose.foundation.rememberScrollState'
    'SelectionContainer'    = 'androidx.compose.foundation.text.selection.SelectionContainer'
    'height'                = 'androidx.compose.foundation.layout.height'
    'heightIn'              = 'androidx.compose.foundation.layout.heightIn'
    'widthIn'               = 'androidx.compose.foundation.layout.widthIn'
    'clickable'             = 'androidx.compose.foundation.clickable'
    'border'                = 'androidx.compose.foundation.border'
    'imePadding'            = 'androidx.compose.foundation.layout.imePadding'
    'navigationBarsPadding' = 'androidx.compose.foundation.layout.navigationBarsPadding'
    'statusBarsPadding'     = 'androidx.compose.foundation.layout.statusBarsPadding'
    'safeDrawingPadding'    = 'androidx.compose.foundation.layout.safeDrawingPadding'
    'combinedClickable'     = 'androidx.compose.foundation.combinedClickable'
    'weight'                = 'androidx.compose.foundation.layout.RowScope'
    'Color'                 = 'androidx.compose.ui.graphics.Color'
}

foreach ($file in $files) {
    $text = Get-Content $file.FullName -Raw
    if ($text -match '^\s*package dev\.dsh\.nativeapp\.ui\.theme') { continue }

    foreach ($symbol in $required.Keys) {
        $import = $required[$symbol]
        # Only flag real usages: `Symbol` or `Symbol(` or `.Symbol`. Comment lines
        # are skipped because a prose mention of a word like "height" is not a call.
        $usage = [regex]::IsMatch($text, "(?m)^(?!\s*(//|\*|/\*|import )).*?(?<![\w.])$symbol\s*[.(]")
        if (-not $usage) { continue }
        if ($text -match [regex]::Escape("import $import")) { continue }
        # Symbols resolvable through the wildcard-ish stdlib or same package are skipped.
        if ($text -match [regex]::Escape("import ${import.Split('.')[0..($import.Split('.').Length - 2)] -join '.'}.*")) { continue }
        $problems += "$($file.Name): uses $symbol without `"import $import`""
    }
}

# Every public composable in the app must have its call sites satisfied.
#
# Regex cannot delimit a Kotlin argument list reliably (named arguments contain
# parentheses), so this rule only compares parameter *names*: a composable that
# gained a parameter is flagged when no call site anywhere mentions that name.
# That is coarse on purpose — it catches the real defect (a new parameter nobody
# passes) without inventing false positives.
$signatures = @{}
foreach ($file in $files) {
    $text = Get-Content $file.FullName -Raw
    foreach ($m in [regex]::Matches($text, '(?s)fun\s+([A-Z]\w+)\s*\((.*?)\)\s*\{')) {
        $name = $m.Groups[1].Value
        $params = $m.Groups[2].Value
        $requiredParams = @()
        $depth = 0
        foreach ($rawLine in ($params -split "`n")) {
            $trimmed = $rawLine.Trim()
            if ($trimmed -eq '' -or $trimmed.StartsWith('//')) { continue }
            if ($trimmed -notmatch '^\w+:\s') { continue }
            if ($trimmed -match '=') { continue }
            $requiredParams += ($trimmed -split ':')[0].Trim()
        }
        if ($requiredParams.Count -gt 0) { $signatures[$name] = $requiredParams }
    }
}

$allText = ($files | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
foreach ($name in $signatures.Keys) {
    # Count call sites: `Name(` occurrences that are not the declaration itself.
    $callSites = [regex]::Matches($allText, "\b$name\s*\(").Count - 1
    if ($callSites -le 0) { continue }
    # A single positional parameter is passed without its name, so the check
    # below cannot see it; only multi-parameter composables are worth auditing.
    if ($signatures[$name].Count -lt 2) { continue }
    foreach ($param in $signatures[$name]) {
        # Positional passing (`UserTurn(item)`) leaves no `param =` text, so accept
        # either a named argument or the parameter name appearing in a call.
        if ($allText -notmatch "(?m)\b$param\s*=") {
            $problems += "no call site passes `"$param`" required by $name"
        }
    }
}

if ($problems.Count -eq 0) {
    Write-Host "preflight: no known issues"
    exit 0
}

$problems | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }
Write-Host "preflight: $($problems.Count) issue(s)"
exit 1
