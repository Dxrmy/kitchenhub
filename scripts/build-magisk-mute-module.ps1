param(
    [string]$OutZip = "$PSScriptRoot\magisk-crown-mute-no-sleep.zip"
)
$ProgressPreference = 'SilentlyContinue'
$stage = Join-Path $env:TEMP "magisk-mute-mod"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path "$stage\META-INF\com\google\android", "$stage\system\usr\keylayout" -Force | Out-Null

@"
id=crown_mute_no_sleep
name=Crown Mic Mute Without Screen Sleep
version=v1.0
versionCode=1
author=KitchenHub
description=Comments out key 116 POWER in Generic.kl so top hardware mic mute does not sleep the screen.
"@ | Set-Content "$stage\module.prop" -Encoding ASCII

@"
#!/sbin/sh
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=true
LATESTARTSERVICE=false
ui_print "- Installing Crown Mic Mute Overlay"
"@ | Set-Content "$stage\customize.sh" -Encoding ASCII

@"
#!/system/bin/sh
MODDIR=`${0%/*}
SRC="/system/usr/keylayout/Generic.kl"
DST="`$MODDIR/system/usr/keylayout/Generic.kl"
if [ -f "`$SRC" ] && [ ! -f "`$DST" ]; then
  mkdir -p "`$(dirname "`$DST")"
  sed 's/^key 116   POWER/# key 116   POWER/' "`$SRC" > "`$DST"
fi
"@ | Set-Content "$stage\post-fs-data.sh" -Encoding ASCII

@"
#MAGISK
"@ | Set-Content "$stage\META-INF\com\google\android\updater-script" -Encoding ASCII

@"
#!/sbin/sh
OUTFD=`$2
ZIPFILE=`$3
ui_print() { echo -e "ui_print `$1\nui_print" >> /proc/self/fd/`$OUTFD; }
unzip -o "`$ZIPFILE" -d /data/adb/modules_update/crown_mute_no_sleep >&2
"@ | Set-Content "$stage\META-INF\com\google\android\update-binary" -Encoding ASCII

if (Test-Path $OutZip) { Remove-Item $OutZip -Force }
Compress-Archive -Path "$stage\*" -DestinationPath $OutZip -Force
Remove-Item $stage -Recurse -Force
Write-Host "Built Magisk module: $OutZip" -ForegroundColor Green
