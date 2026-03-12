param(
    [Parameter(Mandatory=$true)][string]$Vendor,
    [Parameter(Mandatory=$true)][string]$Package,
    [string]$Out,
    [string]$Registry
)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$TemplateDir = Join-Path $Root 'template/starter'

if (-not (Test-Path $TemplateDir)) {
    throw "Starter template not found at $TemplateDir"
}

$VendorLower = $Vendor.ToLowerInvariant()
$TargetDir = if ($Out) { $Out } else { Join-Path $Root "template/generated/$VendorLower" }
New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
Copy-Item -Path (Join-Path $TemplateDir '*') -Destination $TargetDir -Recurse -Force

Get-ChildItem -Path $TargetDir -Filter '*.kt' | ForEach-Object {
    $content = Get-Content -Raw -Path $_.FullName
    $content = $content -replace 'package com\.phoenix\.vendor\.template', "package $Package"
    $content = $content -replace 'interface VendorPlugin', "interface ${Vendor}Plugin"
    $content = $content -replace 'object PluginRegistry', "object ${Vendor}PluginRegistry"
    Set-Content -Path $_.FullName -Value $content
}

$vendorPlugin = Join-Path $TargetDir 'VendorPlugin.kt'
if (Test-Path $vendorPlugin) {
    Move-Item -Path $vendorPlugin -Destination (Join-Path $TargetDir "$Vendor`Plugin.kt") -Force
}

$pluginRegistry = Join-Path $TargetDir 'PluginRegistry.kt'
if (Test-Path $pluginRegistry) {
    Move-Item -Path $pluginRegistry -Destination (Join-Path $TargetDir "$Vendor`PluginRegistry.kt") -Force
}

if ($Registry -and (Test-Path $Registry)) {
    $entry = "register($Vendor`Plugin())"
    $existing = Get-Content -Raw -Path $Registry
    if (-not ($existing -match [regex]::Escape($entry))) {
        Add-Content -Path $Registry -Value "`n    // Added by new_vendor_template.ps1`n    $entry"
    }
}

Write-Output "Created vendor scaffold for $Vendor at $TargetDir"
