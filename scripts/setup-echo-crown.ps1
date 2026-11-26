param(
    [string]$Target = "192.168.1.94:33887"
)
$ProgressPreference = 'SilentlyContinue'
$pkg = "com.kyle.kitchenhub"

adb connect $Target
adb -s $Target wait-for-device

Write-Host "=== Granting Permissions & AppOps ==="
adb -s $Target shell pm grant $pkg android.permission.RECORD_AUDIO
adb -s $Target shell appops set $pkg SYSTEM_ALERT_WINDOW allow
adb -s $Target shell appops set $pkg WRITE_SETTINGS allow
adb -s $Target shell cmd notification allow_listener "$pkg/$pkg.service.media.KitchenNotificationListener"
adb -s $Target shell dumpsys deviceidle whitelist "+$pkg"

Write-Host "=== Display & Lockscreen ==="
adb -s $Target shell settings put system screen_off_timeout 2147483647
adb -s $Target shell "su -c 'locksettings set-disabled true 2>/dev/null || settings put secure lockscreen.disabled 1'"

Write-Host "=== Storage & Far-Field Mic Check ==="
adb -s $Target shell sm list-disks
adb -s $Target shell "getprop ro.hardware.audio.primary; ls -l /vendor/etc/audio-algorithms/AFE.cfg"
Write-Host "Provisioning complete." -ForegroundColor Green
