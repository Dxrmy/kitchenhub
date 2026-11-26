param(
    [ValidateSet("avd", "echo")]
    [string]$Device = "avd",
    [string]$EchoTarget = "192.168.1.94:33887"
)
$ProgressPreference = 'SilentlyContinue'
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.10002-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Resolve-Target {
    if ($Device -eq "echo") {
        adb connect $EchoTarget | Out-Null
        return $EchoTarget
    }
    $emu = (adb devices | Select-String "emulator-\d+\s+device" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
    return $emu
}

function Deploy-App {
    $serial = Resolve-Target
    if (-not $serial) {
        Write-Host "[dev-watch] Waiting for $Device..." -ForegroundColor Yellow
        return
    }
    if ($Device -eq "avd") {
        adb -s $serial emu avd hostmicon 2>$null | Out-Null
    }
    $env:ANDROID_SERIAL = $serial
    .\gradlew installDebug --console=plain -q
    if ($LASTEXITCODE -eq 0) {
        adb -s $serial shell am start -n com.kyle.kitchenhub/.MainActivity | Out-Null
        Write-Host "[dev-watch] Deployed & launched on $serial ($(Get-Date -Format HH:mm:ss))" -ForegroundColor Green
    } else {
        Write-Host "[dev-watch] Build failed!" -ForegroundColor Red
    }
}

Deploy-App
$watchPath = Join-Path $root "app\src\main"
$fsw = New-Object System.IO.FileSystemWatcher $watchPath, "*.*"
$fsw.IncludeSubdirectories = $true
$fsw.EnableRaisingEvents = $true
Write-Host "[dev-watch] Watching $watchPath (Ctrl+C to stop)..." -ForegroundColor Cyan

$lastRun = [datetime]::MinValue
while ($true) {
    $res = $fsw.WaitForChanged([System.IO.WatcherChangeTypes]::All, 1000)
    if (-not $res.TimedOut -and (([datetime]::Now - $lastRun).TotalSeconds -ge 2)) {
        Start-Sleep -Milliseconds 350
        $lastRun = [datetime]::Now
        Deploy-App
    }
}
