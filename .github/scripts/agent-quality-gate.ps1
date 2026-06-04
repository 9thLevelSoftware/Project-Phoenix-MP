Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if (-not $repoRoot) {
    throw "Not inside a git repository."
}

function Test-AndroidSdkConfigured {
    if ($env:ANDROID_HOME -or $env:ANDROID_SDK_ROOT) {
        return $true
    }

    $localProperties = Join-Path $repoRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        return (Select-String -LiteralPath $localProperties -Pattern '^sdk\.dir=' -Quiet)
    }

    return $false
}

if (-not (Test-AndroidSdkConfigured)) {
    throw "Android SDK is not configured. Set ANDROID_HOME, ANDROID_SDK_ROOT, or local.properties sdk.dir before running the agent gate."
}

& (Join-Path $repoRoot ".github/scripts/forbid-tracked-secrets.ps1")

& (Join-Path $repoRoot "gradlew.bat") `
    "-Pskip.supabase.check=true" `
    "spotlessCheck" `
    "validateSchemaManifest" `
    ":shared:verifyCommonMainVitruvianDatabaseMigration" `
    ":shared:testAndroidHostTest" `
    "--console=plain" `
    "--no-daemon"

& git -C $repoRoot diff --check
