<#
.SYNOPSIS
Runs destructive persisted-file upgrade scenarios on one disposable Android emulator.

.DESCRIPTION
The legacy APK must be a debuggable build from the released source so the harness can
seed and inspect its sandbox. A production-signed candidate must be validated separately.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Fresh', 'Upgrade', 'InterruptedStaging', 'CorruptSource', 'DualDatabase', 'LowStorage')]
    [string] $Scenario,

    [Parameter()]
    [string] $LegacyApk,

    [Parameter(Mandatory = $true)]
    [string] $CandidateApk,

    [Parameter()]
    [string] $PackageName = 'com.devil.phoenixproject.debug',

    [Parameter()]
    [string] $Serial,

    [Parameter()]
    [string] $EvidenceDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'phoenix-persisted-file-upgrade'),

    [Parameter()]
    [int] $ExpectedSchemaVersion = 47
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Adb = $null
$script:Aapt = $null
$script:ApkSigner = $null
$script:Sqlite = $null
$script:AdbPrefix = @()
$script:EvidenceRoot = $null
$script:LowStorageFiller = '/data/local/tmp/phoenix-persisted-file-low-storage.bin'

function Resolve-AndroidTool {
    param([Parameter(Mandatory = $true)][string] $Name)

    $sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $env:ANDROID_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $env:ANDROID_SDK_ROOT
    } else {
        Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }

    if ($Name -eq 'adb') {
        $path = Join-Path $sdkRoot 'platform-tools\adb.exe'
    } elseif ($Name -eq 'sqlite3') {
        $path = Join-Path $sdkRoot 'platform-tools\sqlite3.exe'
    } else {
        $buildToolsRoot = Join-Path $sdkRoot 'build-tools'
        $latest = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
            Select-Object -First 1
        if ($null -eq $latest) { throw "No Android build-tools installation found under $buildToolsRoot" }
        $path = Join-Path $latest.FullName "$Name.bat"
        if (-not (Test-Path -LiteralPath $path)) {
            $path = Join-Path $latest.FullName "$Name.exe"
        }
    }

    if (-not (Test-Path -LiteralPath $path)) { throw "Android tool not found: $path" }
    return (Resolve-Path -LiteralPath $path).Path
}

function Invoke-Adb {
    # Do not start this switch name with "A": PowerShell would consume adb's
    # ubiquitous `-a <intent-action>` flag as an abbreviated function parameter.
    param([switch] $IgnoreExitCode)

    $adbArguments = @($args)
    $output = & $script:Adb @script:AdbPrefix @adbArguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $IgnoreExitCode) {
        throw "adb failed ($exitCode): $($adbArguments -join ' ')`n$($output -join "`n")"
    }
    return @($output)
}

function Get-ApkMetadata {
    param([Parameter(Mandatory = $true)][string] $Path)

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $badging = (& $script:Aapt dump badging $resolved 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect APK metadata: $resolved" }
    $match = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)'")
    if (-not $match.Success) { throw "Could not parse APK package metadata: $resolved" }

    [pscustomobject]@{
        Path = $resolved
        PackageName = $match.Groups[1].Value
        VersionCode = [long]$match.Groups[2].Value
        Debuggable = $badging.Contains('application-debuggable')
    }
}

function Get-ApkCertificateDigest {
    param([Parameter(Mandatory = $true)][string] $Path)

    $certificate = (& $script:ApkSigner verify --print-certs $Path 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $Path" }
    $match = [regex]::Match($certificate, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    if (-not $match.Success) { throw "Could not read APK signing certificate: $Path" }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Initialize-Device {
    $devices = & $script:Adb devices
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
    $online = @($devices | Select-String -Pattern '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })

    if ([string]::IsNullOrWhiteSpace($Serial)) {
        if ($online.Count -ne 1) { throw "Expected exactly one online device, found $($online.Count). Pass -Serial." }
        $script:AdbPrefix = @('-s', $online[0])
    } else {
        if ($Serial -notin $online) { throw "Requested device is not online: $Serial" }
        $script:AdbPrefix = @('-s', $Serial)
    }

    $isEmulator = (Invoke-Adb shell getprop ro.kernel.qemu) -join ''
    if ($isEmulator.Trim() -ne '1') {
        throw 'This harness uninstalls the exact test package and is restricted to a disposable emulator.'
    }

    # Keep deterministic QA data off every network while it is being seeded and migrated.
    Invoke-Adb shell svc wifi disable | Out-Null
    Invoke-Adb shell svc data disable -IgnoreExitCode | Out-Null
}

function Test-SandboxFile {
    param([Parameter(Mandatory = $true)][string] $RelativePath)

    & $script:Adb @script:AdbPrefix shell run-as $PackageName ls $RelativePath 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Wait-SandboxFile {
    param(
        [Parameter(Mandatory = $true)][string] $RelativePath,
        [int] $TimeoutSeconds = 60
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-SandboxFile $RelativePath) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for sandbox file: $RelativePath"
}

function Copy-SandboxFile {
    param(
        [Parameter(Mandatory = $true)][string] $RelativePath,
        [Parameter(Mandatory = $true)][string] $Destination,
        [switch] $Optional
    )

    if (-not (Test-SandboxFile $RelativePath)) {
        if ($Optional) { return $false }
        throw "Sandbox file does not exist: $RelativePath"
    }

    $arguments = @($script:AdbPrefix + @('exec-out', 'run-as', $PackageName, 'cat', $RelativePath))
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:Adb
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $arguments) { [void]$startInfo.ArgumentList.Add($argument) }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $stream = [IO.File]::Open($Destination, [IO.FileMode]::Create, [IO.FileAccess]::Write)
    try {
        $process.StandardOutput.BaseStream.CopyTo($stream)
    } finally {
        $stream.Dispose()
    }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Could not copy $RelativePath from sandbox: $errorText" }
    return $true
}

function Push-SandboxFile {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $RelativePath
    )

    $remoteTemporary = "/data/local/tmp/phoenix-migration-$([guid]::NewGuid().ToString('N'))"
    try {
        Invoke-Adb push $Source $remoteTemporary | Out-Null
        Invoke-Adb shell run-as $PackageName cp $remoteTemporary $RelativePath | Out-Null
        Invoke-Adb shell run-as $PackageName chmod 600 $RelativePath | Out-Null
    } finally {
        Invoke-Adb shell rm -f $remoteTemporary -IgnoreExitCode | Out-Null
    }
}

function Get-SandboxSha256 {
    param([Parameter(Mandatory = $true)][string] $RelativePath)

    $result = Invoke-Adb shell run-as $PackageName sha256sum $RelativePath
    $match = [regex]::Match(($result -join ' '), '^([0-9a-fA-F]{64})')
    if (-not $match.Success) { throw "Could not hash sandbox file: $RelativePath" }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Save-SandboxListing {
    param([Parameter(Mandatory = $true)][string] $Name)

    $databaseFiles = Invoke-Adb shell run-as $PackageName ls -la databases -IgnoreExitCode
    $preferenceFiles = Invoke-Adb shell run-as $PackageName ls -la shared_prefs -IgnoreExitCode
    @('DATABASES', $databaseFiles, 'PREFERENCES', $preferenceFiles) |
        Set-Content -LiteralPath (Join-Path $script:EvidenceRoot "$Name-files.txt")
}

function Install-Apk {
    param([Parameter(Mandatory = $true)][string] $Path, [switch] $Replace)

    # The upgrade path intentionally uses adb install -r and never clears app data.
    $arguments = if ($Replace) { @('install', '-r', $Path) } else { @('install', $Path) }
    $result = Invoke-Adb @arguments
    if (($result -join "`n") -notmatch 'Success') { throw "APK install did not report success: $($result -join ' ')" }
}

function Start-App {
    Invoke-Adb shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Null
}

function Wait-AppProcess {
    param([int] $TimeoutSeconds = 30)

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $pidText = (Invoke-Adb shell pidof $PackageName -IgnoreExitCode) -join ''
        if (-not [string]::IsNullOrWhiteSpace($pidText)) {
            # Let Application.onCreate finish loading the debug receiver's Koin module.
            Start-Sleep -Seconds 2
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for app process: $PackageName"
}

function Stop-App {
    Invoke-Adb shell am force-stop $PackageName | Out-Null
}

function Wait-QaSeed {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Invoke-Adb logcat -c | Out-Null
        Invoke-Adb shell am broadcast -a com.devil.phoenixproject.QA_SEED_PROFILE -p $PackageName | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(90)
        do {
            $log = (Invoke-Adb logcat -d -s ProfileQaSeed:I '*:S') -join "`n"
            if ($log.Contains('PROFILE_QA_SEED_OK')) {
                $log | Set-Content -LiteralPath (Join-Path $script:EvidenceRoot 'legacy-seed-logcat.txt')
                return
            }
            if ($log.Contains('PROFILE_QA_SEED_FAILED:')) {
                if ($attempt -lt 3 -and $log.Contains('ProfileContextUnavailableException')) {
                    Start-Sleep -Seconds 3
                    break
                }
                throw "Legacy QA seed failed:`n$log"
            }
            Start-Sleep -Seconds 1
        } while ([DateTime]::UtcNow -lt $deadline)
    }
    throw 'Timed out waiting for PROFILE_QA_SEED_OK'
}

function Add-LegacySeedPrerequisites {
    # v0.9.6's debug QA receiver creates representative application rows through
    # repositories, but it expects a catalogue exercise to exist already. Keep the
    # released source build immutable and add only the receiver's minimal prerequisite
    # while the process is stopped. The next app write recreates a real device WAL.
    $localDatabase = Capture-Database 'vitruvian.db' 'legacy-seed-prerequisite.db'
    $sql = @'
PRAGMA wal_checkpoint(TRUNCATE);
INSERT OR IGNORE INTO Exercise (
  id, name, displayName, description, created, muscleGroup, muscleGroups,
  equipment, popularity, archived, isFavorite, isCustom, timesPerformed,
  defaultCableConfig, isBodyweight
) VALUES (
  'qa-bench-press', 'Bench Press', 'Bench Press',
  'Persisted-file upgrade harness prerequisite', 0, 'Chest', 'Chest',
  'BAR', 0, 0, 0, 0, 0, 'DUAL', 0
);
INSERT OR IGNORE INTO Routine (
  id, name, description, createdAt, useCount, profile_id
) VALUES (
  'qa-persisted-routine', '[QA] Persisted Routine',
  'Representative filename-migration fixture', 0, 0, 'default'
);
PRAGMA wal_checkpoint(TRUNCATE);
PRAGMA quick_check;
'@
    $result = (& $script:Sqlite $localDatabase $sql 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or @($result -split "`r?`n")[-1].Trim() -ne 'ok') {
        throw "Could not prepare integrity-checked legacy seed prerequisites:`n$result"
    }

    Push-SandboxFile $localDatabase 'databases/vitruvian.db'
    Invoke-Adb shell run-as $PackageName rm -f `
        databases/vitruvian.db-wal `
        databases/vitruvian.db-shm `
        databases/vitruvian.db-journal -IgnoreExitCode | Out-Null
}

function Add-CommittedLegacyWalFixture {
    $database = Capture-Database 'vitruvian.db' 'legacy-before-wal.db'
    $walSnapshot = Join-Path $script:EvidenceRoot 'legacy-committed.db-wal'
    $mainSnapshot = Join-Path $script:EvidenceRoot 'legacy-committed.db'

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:Sqlite
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    [void]$startInfo.ArgumentList.Add($database)

    $sqliteProcess = [Diagnostics.Process]::new()
    $sqliteProcess.StartInfo = $startInfo
    [void]$sqliteProcess.Start()
    try {
        $sqliteProcess.StandardInput.WriteLine('PRAGMA journal_mode=WAL;')
        $sqliteProcess.StandardInput.WriteLine('PRAGMA synchronous=FULL;')
        $sqliteProcess.StandardInput.WriteLine('PRAGMA wal_autocheckpoint=0;')
        $sqliteProcess.StandardInput.WriteLine("UPDATE Routine SET description = '$($script:WalMarker)' WHERE id = 'qa-persisted-routine';")
        $sqliteProcess.StandardInput.Flush()

        $walPath = "$database-wal"
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            if ((Test-Path -LiteralPath $walPath) -and (Get-Item -LiteralPath $walPath).Length -gt 32) { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not (Test-Path -LiteralPath $walPath) -or (Get-Item -LiteralPath $walPath).Length -le 32) {
            throw 'Host SQLite did not create a committed WAL fixture.'
        }

        # Snapshot while the writer remains open: SQLite normally checkpoints and
        # removes its final WAL when the last connection closes.
        [IO.File]::Copy($database, $mainSnapshot, $true)
        [IO.File]::Copy($walPath, $walSnapshot, $true)
    } finally {
        if (-not $sqliteProcess.HasExited) {
            $sqliteProcess.Kill()
            $sqliteProcess.WaitForExit()
        }
        $sqliteProcess.Dispose()
    }

    Push-SandboxFile $mainSnapshot 'databases/vitruvian.db'
    Push-SandboxFile $walSnapshot 'databases/vitruvian.db-wal'
    Invoke-Adb shell run-as $PackageName rm -f `
        databases/vitruvian.db-shm `
        databases/vitruvian.db-journal -IgnoreExitCode | Out-Null
}

function Expand-LegacyDatabaseForLowStorage {
    $database = Capture-Database 'vitruvian.db' 'legacy-before-low-storage.db'
    $sql = @'
PRAGMA wal_checkpoint(TRUNCATE);
CREATE TABLE IF NOT EXISTS QaLowStoragePadding (payload BLOB NOT NULL);
DELETE FROM QaLowStoragePadding;
INSERT INTO QaLowStoragePadding(payload) VALUES (zeroblob(67108864));
UPDATE Routine SET description = 'before-low-storage-wal'
WHERE id = 'qa-persisted-routine';
PRAGMA wal_checkpoint(TRUNCATE);
PRAGMA quick_check;
'@
    $result = (& $script:Sqlite $database $sql 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or @($result -split "`r?`n")[-1].Trim() -ne 'ok') {
        throw "Could not create the integrity-checked low-storage database fixture:`n$result"
    }

    Push-SandboxFile $database 'databases/vitruvian.db'
    Invoke-Adb shell run-as $PackageName rm -f `
        databases/vitruvian.db-wal `
        databases/vitruvian.db-shm `
        databases/vitruvian.db-journal -IgnoreExitCode | Out-Null
    Add-CommittedLegacyWalFixture
}

function Add-PlaintextPreferenceSentinel {
    $localXml = Join-Path $script:EvidenceRoot 'legacy-plaintext-before.xml'
    Copy-SandboxFile 'shared_prefs/vitruvian_preferences.xml' $localXml | Out-Null
    [xml]$document = Get-Content -Raw -LiteralPath $localXml
    $existing = @($document.map.string | Where-Object { $_.name -eq 'persisted_file_upgrade_sentinel' })
    foreach ($entry in $existing) { [void]$document.map.RemoveChild($entry) }
    $sentinel = $document.CreateElement('string')
    [void]$sentinel.SetAttribute('name', 'persisted_file_upgrade_sentinel')
    $sentinel.InnerText = 'legacy-plain'
    [void]$document.map.AppendChild($sentinel)
    $document.Save($localXml)
    Push-SandboxFile $localXml 'shared_prefs/vitruvian_preferences.xml'
}

function Capture-Database {
    param(
        [Parameter(Mandatory = $true)][string] $SandboxName,
        [Parameter(Mandatory = $true)][string] $LocalName
    )

    $main = Join-Path $script:EvidenceRoot $LocalName
    foreach ($localArtifact in @($main, "$main-wal", "$main-shm")) {
        if (Test-Path -LiteralPath $localArtifact) {
            Remove-Item -LiteralPath $localArtifact -Force
        }
    }
    Copy-SandboxFile "databases/$SandboxName" $main | Out-Null
    Copy-SandboxFile "databases/$SandboxName-wal" "$main-wal" -Optional | Out-Null
    Copy-SandboxFile "databases/$SandboxName-shm" "$main-shm" -Optional | Out-Null
    return $main
}

function Get-DatabaseCounts {
    param([Parameter(Mandatory = $true)][string] $DatabasePath)

    $sql = @'
SELECT
  (SELECT COUNT(*) FROM UserProfile),
  (SELECT COUNT(*) FROM WorkoutSession),
  (SELECT COUNT(*) FROM Routine),
  PRAGMA_USER_VERSION.user_version,
  (SELECT description FROM Routine WHERE id = 'qa-persisted-routine')
FROM pragma_user_version AS PRAGMA_USER_VERSION;
'@
    $line = (& $script:Sqlite -readonly -noheader -separator '|' $DatabasePath $sql 2>&1) -join ''
    if ($LASTEXITCODE -ne 0) { throw "SQLite evidence query failed: $line" }
    $values = $line.Trim().Split('|')
    if ($values.Count -ne 5) { throw "Unexpected SQLite evidence row: $line" }
    return [pscustomobject]@{
        Profiles = [int]$values[0]
        Workouts = [int]$values[1]
        Routines = [int]$values[2]
        SchemaVersion = [int]$values[3]
        WalMarker = $values[4]
    }
}

function Assert-PreservedCounts {
    param($Before, $After)

    if ($Before.Profiles -lt 3) { throw "Legacy seed did not create representative profiles: $($Before.Profiles)" }
    if ($Before.Workouts -lt 10) { throw "Legacy seed did not create representative workouts: $($Before.Workouts)" }
    if ($Before.Routines -lt 1) { throw 'Legacy install did not contain a representative routine.' }
    if ($After.Profiles -ne $Before.Profiles) { throw 'Profile count changed during filename migration.' }
    if ($After.Workouts -ne $Before.Workouts) { throw 'Workout count changed during filename migration.' }
    if ($After.Routines -ne $Before.Routines) { throw 'Routine count changed during filename migration.' }
    if ($Before.WalMarker -ne $script:WalMarker -or $After.WalMarker -ne $script:WalMarker) {
        throw 'The committed legacy WAL payload was not preserved.'
    }
    if ($After.SchemaVersion -ne $ExpectedSchemaVersion) {
        throw "Unexpected target schema version: $($After.SchemaVersion); expected $ExpectedSchemaVersion"
    }
}

function Assert-LegacyLogicalState {
    param($Before, $After)

    foreach ($property in 'Profiles', 'Workouts', 'Routines', 'SchemaVersion', 'WalMarker') {
        if ($After.$property -ne $Before.$property) {
            throw "Low-storage failure changed legacy logical state: $property"
        }
    }
}

function Wait-ForDiagnosticCode {
    param([Parameter(Mandatory = $true)][string[]] $AllowedCodes)

    $remote = '/sdcard/window_dump.xml'
    $local = Join-Path $script:EvidenceRoot 'startup-diagnostic.xml'
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        Invoke-Adb shell uiautomator dump $remote -IgnoreExitCode | Out-Null
        Invoke-Adb pull $remote $local -IgnoreExitCode | Out-Null
        if (Test-Path -LiteralPath $local) {
            $text = Get-Content -Raw -LiteralPath $local
            if ($AllowedCodes | Where-Object { $text.Contains($_) }) { return }
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Expected startup diagnostic was not rendered: $($AllowedCodes -join ', ')"
}

function Invoke-RetryAction {
    $remote = '/sdcard/window_dump.xml'
    $local = Join-Path $script:EvidenceRoot 'startup-retry.xml'
    Invoke-Adb shell uiautomator dump $remote | Out-Null
    Invoke-Adb pull $remote $local | Out-Null
    [xml]$document = Get-Content -Raw -LiteralPath $local
    $retryNode = @($document.SelectNodes('//node[@text="Retry"]')) | Select-Object -First 1
    if ($null -eq $retryNode) { throw 'The retry action was not present on the startup failure screen.' }
    $bounds = [regex]::Match($retryNode.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $bounds.Success) { throw "Could not parse Retry bounds: $($retryNode.bounds)" }
    $x = ([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2
    $y = ([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2
    Invoke-Adb shell input tap ([int]$x) ([int]$y) | Out-Null
}

function Get-DataAvailableKilobytes {
    $line = @((Invoke-Adb shell df -k /data) | Where-Object { $_ -match '^/dev/' })[-1]
    $columns = @($line.Trim() -split '\s+')
    if ($columns.Count -lt 4) { throw "Could not parse device free space: $line" }
    return [long]$columns[3]
}

function Enter-LowStorageState {
    Invoke-Adb shell rm -f $script:LowStorageFiller -IgnoreExitCode | Out-Null
    $availableKilobytes = Get-DataAvailableKilobytes
    $leaveKilobytes = 384L
    if ($availableKilobytes -le ($leaveKilobytes + 1024L)) {
        throw "Not enough free space to create a controlled low-storage fixture: ${availableKilobytes}KB"
    }
    $fillBytes = ($availableKilobytes - $leaveKilobytes) * 1024L
    Invoke-Adb shell fallocate -l $fillBytes $script:LowStorageFiller | Out-Null
    $remainingKilobytes = Get-DataAvailableKilobytes
    "before_kb=$availableKilobytes`nafter_kb=$remainingKilobytes" |
        Set-Content -LiteralPath (Join-Path $script:EvidenceRoot 'low-storage-space.txt')
    if ($remainingKilobytes -ge (48L * 1024L)) {
        throw "Low-storage fixture left too much free space: ${remainingKilobytes}KB"
    }
}

function Exit-LowStorageState {
    Invoke-Adb shell rm -f $script:LowStorageFiller -IgnoreExitCode | Out-Null
}

function Assert-NoLegacyArtifacts {
    $forbidden = @(
        'databases/vitruvian.db',
        'databases/vitruvian.db-wal',
        'databases/vitruvian.db-shm',
        'databases/vitruvian.db-journal',
        'shared_prefs/vitruvian_preferences.xml',
        'shared_prefs/vitruvian_preferences.xml.bak',
        'shared_prefs/vitruvian_secure_preferences.xml',
        'shared_prefs/vitruvian_secure_preferences.xml.bak'
    )
    $remaining = @($forbidden | Where-Object { Test-SandboxFile $_ })
    if ($remaining.Count -gt 0) { throw "Legacy artifacts remain: $($remaining -join ', ')" }
}

function Assert-SecondLaunchCleanup {
    Start-App
    Start-Sleep -Seconds 5
    Stop-App

    $recoveryArtifacts = @(
        'databases/phoenix-recovery.db',
        'databases/phoenix-recovery.db-wal',
        'databases/phoenix-recovery.db-shm',
        'databases/phoenix.db.migrating',
        'shared_prefs/phoenix_preferences_recovery.xml',
        'shared_prefs/phoenix_preferences_recovery.xml.bak',
        'shared_prefs/phoenix_secure_preferences_recovery.xml',
        'shared_prefs/phoenix_secure_preferences_recovery.xml.bak'
    )
    $remaining = @($recoveryArtifacts | Where-Object { Test-SandboxFile $_ })
    if ($remaining.Count -gt 0) { throw "Recovery artifacts remain after the clean restart: $($remaining -join ', ')" }
    Assert-NoLegacyArtifacts
    Save-SandboxListing 'second-launch'
}

function Initialize-LegacyState {
    param([Parameter(Mandatory = $true)] $LegacyMetadata)

    Install-Apk $LegacyMetadata.Path
    Start-App
    Wait-SandboxFile 'databases/vitruvian.db'
    Stop-App
    Add-LegacySeedPrerequisites
    # A force-stopped package does not receive the package-scoped QA broadcast.
    Start-App
    Wait-AppProcess
    Wait-QaSeed
    Stop-App
    Add-CommittedLegacyWalFixture
    Add-PlaintextPreferenceSentinel

    if (-not (Test-SandboxFile 'databases/vitruvian.db-wal')) {
        throw 'The seeded legacy process did not leave a WAL artifact; WAL recovery was not exercised.'
    }
    Save-SandboxListing 'legacy-stopped'
    $database = Capture-Database 'vitruvian.db' 'legacy.db'
    $counts = Get-DatabaseCounts $database
    $counts | Format-List | Out-File -LiteralPath (Join-Path $script:EvidenceRoot 'legacy-counts.txt')
    return $counts
}

function Assert-SuccessfulUpgrade {
    param($BeforeCounts)

    Assert-NoLegacyArtifacts
    if (-not (Test-SandboxFile 'databases/phoenix-recovery.db')) {
        throw 'Neutral database recovery was not retained after the migrated launch.'
    }
    $targetPreferences = Join-Path $script:EvidenceRoot 'phoenix-preferences-first-launch.xml'
    Copy-SandboxFile 'shared_prefs/phoenix_preferences.xml' $targetPreferences | Out-Null
    if (-not (Get-Content -Raw -LiteralPath $targetPreferences).Contains('persisted_file_upgrade_sentinel')) {
        throw 'Plaintext preference sentinel was not migrated.'
    }

    $database = Capture-Database 'phoenix.db' 'target-first-launch.db'
    $afterCounts = Get-DatabaseCounts $database
    Assert-PreservedCounts $BeforeCounts $afterCounts
    $afterCounts | Format-List | Out-File -LiteralPath (Join-Path $script:EvidenceRoot 'target-counts.txt')
    Save-SandboxListing 'first-launch'
    Assert-SecondLaunchCleanup
}

function Invoke-SuccessfulUpgrade {
    param($CandidateMetadata, $BeforeCounts)

    Install-Apk $CandidateMetadata.Path -Replace
    Start-App
    Wait-SandboxFile 'databases/phoenix.db'
    Start-Sleep -Seconds 5
    Stop-App
    Assert-SuccessfulUpgrade $BeforeCounts
}

$script:Adb = Resolve-AndroidTool 'adb'
$script:Aapt = Resolve-AndroidTool 'aapt'
$script:ApkSigner = Resolve-AndroidTool 'apksigner'
$script:Sqlite = Resolve-AndroidTool 'sqlite3'
$script:EvidenceRoot = [IO.Path]::GetFullPath($EvidenceDirectory)
$script:WalMarker = 'committed-wal-survived'
[void](New-Item -ItemType Directory -Force -Path $script:EvidenceRoot)

$candidate = Get-ApkMetadata $CandidateApk
if ($candidate.PackageName -ne $PackageName) {
    throw "Candidate package '$($candidate.PackageName)' does not match '$PackageName'."
}
if (-not $candidate.Debuggable) {
    throw 'Automated sandbox validation requires a debuggable candidate APK.'
}

$legacy = $null
if ($Scenario -ne 'Fresh') {
    if ([string]::IsNullOrWhiteSpace($LegacyApk)) { throw '-LegacyApk is required for this scenario.' }
    $legacy = Get-ApkMetadata $LegacyApk
    if ($legacy.PackageName -ne $PackageName) { throw 'Legacy and requested package names differ.' }
    if (-not $legacy.Debuggable) { throw 'Automated seed/inspection requires a debuggable legacy APK.' }
    if ($candidate.VersionCode -lt $legacy.VersionCode) { throw 'Candidate version code is lower than legacy.' }
    $legacyCertificate = Get-ApkCertificateDigest $legacy.Path
    $candidateCertificate = Get-ApkCertificateDigest $candidate.Path
    if ($legacyCertificate -ne $candidateCertificate) { throw 'Legacy and candidate signing certificates differ.' }
}

Initialize-Device
Invoke-Adb uninstall $PackageName -IgnoreExitCode | Out-Null

Get-FileHash -Algorithm SHA256 -LiteralPath $candidate.Path |
    Format-List | Out-File -LiteralPath (Join-Path $script:EvidenceRoot 'candidate-apk-sha256.txt')
if ($null -ne $legacy) {
    Get-FileHash -Algorithm SHA256 -LiteralPath $legacy.Path |
        Format-List | Out-File -LiteralPath (Join-Path $script:EvidenceRoot 'legacy-apk-sha256.txt')
}

if ($Scenario -eq 'Fresh') {
    Install-Apk $candidate.Path
    Start-App
    Wait-SandboxFile 'databases/phoenix.db'
    Start-Sleep -Seconds 5
    Stop-App
    Assert-NoLegacyArtifacts
    if (Test-SandboxFile 'databases/phoenix-recovery.db') { throw 'Fresh install created an unexpected recovery database.' }
    Save-SandboxListing 'fresh-install'
    Write-Output "PASS Fresh evidence=$script:EvidenceRoot"
    exit 0
}

$before = Initialize-LegacyState $legacy

switch ($Scenario) {
    'InterruptedStaging' {
        Invoke-Adb shell run-as $PackageName cp databases/vitruvian.db databases/phoenix.db.migrating | Out-Null
    }
    'CorruptSource' {
        $corrupt = Join-Path $script:EvidenceRoot 'corrupt-source.db'
        [IO.File]::WriteAllBytes($corrupt, [Text.Encoding]::UTF8.GetBytes('not a sqlite database'))
        Push-SandboxFile $corrupt 'databases/vitruvian.db'
        Invoke-Adb shell run-as $PackageName rm -f databases/vitruvian.db-wal databases/vitruvian.db-shm -IgnoreExitCode | Out-Null
    }
    'DualDatabase' {
        Invoke-Adb shell run-as $PackageName cp databases/vitruvian.db databases/phoenix.db | Out-Null
    }
}

if ($Scenario -eq 'CorruptSource') {
    Install-Apk $candidate.Path -Replace
    Start-App
    Wait-ForDiagnosticCode @('DB_CHECKPOINT_FAILED', 'DB_INTEGRITY_CHECK_FAILED')
    Stop-App
    if (-not (Test-SandboxFile 'databases/vitruvian.db')) { throw 'Corrupt source was not preserved.' }
    if (Test-SandboxFile 'databases/phoenix.db') { throw 'Corrupt source created a Phoenix target.' }
    Save-SandboxListing 'corrupt-source-blocked'
    Write-Output "PASS CorruptSource evidence=$script:EvidenceRoot"
    exit 0
}

if ($Scenario -eq 'DualDatabase') {
    $legacyBefore = Get-SandboxSha256 'databases/vitruvian.db'
    $targetBefore = Get-SandboxSha256 'databases/phoenix.db'
    Install-Apk $candidate.Path -Replace
    Start-App
    Wait-ForDiagnosticCode @('DB_DUAL_DATABASES')
    Stop-App
    if ((Get-SandboxSha256 'databases/vitruvian.db') -ne $legacyBefore) { throw 'Dual-database legacy candidate changed.' }
    if ((Get-SandboxSha256 'databases/phoenix.db') -ne $targetBefore) { throw 'Dual-database Phoenix candidate changed.' }
    Save-SandboxListing 'dual-database-blocked'
    Write-Output "PASS DualDatabase evidence=$script:EvidenceRoot"
    exit 0
}

if ($Scenario -eq 'LowStorage') {
    Expand-LegacyDatabaseForLowStorage
    Install-Apk $candidate.Path -Replace
    try {
        Enter-LowStorageState
        Start-App
        Wait-ForDiagnosticCode @('DB_RECOVERY_COPY_FAILED')
        $afterFailureDatabase = Capture-Database 'vitruvian.db' 'low-storage-preserved-legacy.db'
        $afterFailureCounts = Get-DatabaseCounts $afterFailureDatabase
        Assert-LegacyLogicalState $before $afterFailureCounts
        if (Test-SandboxFile 'databases/phoenix.db') {
            throw 'Low-storage recovery-copy failure created a Phoenix target.'
        }
    } finally {
        Exit-LowStorageState
    }

    # Exercise the in-process UI retry after space is restored. The migration
    # singleton must be re-created, while its recovery remains until restart.
    Invoke-RetryAction
    Wait-SandboxFile 'databases/phoenix.db'
    Start-Sleep -Seconds 5
    Stop-App
    Assert-SuccessfulUpgrade $before
    Write-Output "PASS LowStorage evidence=$script:EvidenceRoot"
    exit 0
}

Invoke-SuccessfulUpgrade $candidate $before
Write-Output "PASS $Scenario evidence=$script:EvidenceRoot"
