$ErrorActionPreference = "Stop"
$outDir = Join-Path $PSScriptRoot "..\build\magisk-mute-module"
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$outDir\META-INF\com\google\android\updater-script" | Out-Null
New-Item -ItemType Directory -Force -Path "$outDir\system\usr\keylayout" | Out-Null

@"
id=kitchenhub_gating_mute
name=KitchenHub Echo Show 8 Mute Remap
version=v1.0
versionCode=1
author=KitchenHub
description=Remaps Echo Show 8 (crown) hardware 'gating' key 116 from POWER to MICROPHONE_MUTE without modifying Generic.kl.
"@ | Set-Content "$outDir\module.prop" -Encoding ASCII

@"
# KitchenHub safe device-specific keylayout for Echo Show 8 /dev/input/event0 ("gating")
key 116   MICROPHONE_MUTE
"@ | Set-Content "$outDir\system\usr\keylayout\gating.kl" -Encoding ASCII

$zipPath = Join-Path $PSScriptRoot "..\build\kitchenhub-gating-mute.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path "$outDir\*" -DestinationPath $zipPath -Force
Write-Host "Built Magisk module: $zipPath"
