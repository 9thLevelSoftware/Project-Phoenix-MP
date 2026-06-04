Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if (-not $repoRoot) {
    throw "Not inside a git repository."
}

$trackedFiles = & git -C $repoRoot ls-files
$violations = New-Object System.Collections.Generic.List[string]

foreach ($rawPath in $trackedFiles) {
    $path = $rawPath -replace '\\', '/'
    $lower = $path.ToLowerInvariant()

    $isTemplate = $lower.EndsWith(".example") -or
        $lower.EndsWith(".sample") -or
        $lower.EndsWith(".template") -or
        $lower.Contains("/templates/")

    if ($path -match '(^|/)Supabase\.xcconfig$') {
        $violations.Add("$path (real iOS Supabase config must stay local or be generated from CI secrets)")
        continue
    }

    if ($path -match '(^|/).*\.local\.properties$' -or $path -match '(^|/)local\.properties$') {
        $violations.Add("$path (local Gradle/SDK or secret properties must not be tracked)")
        continue
    }

    if (-not $isTemplate -and ($path -match '(^|/)google-services\.json$' -or $path -match '(^|/)GoogleService-Info\.plist$')) {
        $violations.Add("$path (service config must be supplied outside git unless it is a template)")
        continue
    }

    if (-not $isTemplate -and $path -match '\.(jks|keystore|p12|mobileprovision)$') {
        $violations.Add("$path (signing material must be supplied from a local machine or CI secret)")
        continue
    }

    if (-not $isTemplate -and $path -match '(^|/)\.env(\..*)?$') {
        $violations.Add("$path (environment files must not be tracked)")
        continue
    }
}

if ($violations.Count -gt 0) {
    Write-Error ("Forbidden tracked secret-bearing files found:`n - " + ($violations -join "`n - "))
    exit 1
}

Write-Host "No forbidden tracked secret-bearing files found."
