#Requires -Version 5.1
<#
.SYNOPSIS
  Build the VehicleEdge Assistant debug APK and install it on a connected device.

.DESCRIPTION
  Runs Gradle assembleDebug, then installs the APK onto a running adb device
  with replace (-r) and runtime permission grant (-g). Optionally launches the app.

  Gradle 8.4 cannot run on JDK 25+. This script auto-selects a compatible JDK
  (17-21), preferring Android Studio's bundled JBR.

.PARAMETER Serial
  Target a specific device when more than one is connected (adb -s).

.PARAMETER Clean
  Run a clean build (gradlew clean assembleDebug).

.PARAMETER Launch
  Force-stop and start LocalLLMActivity after install.

.PARAMETER SkipBuild
  Skip Gradle and install an existing APK at the default output path.

.PARAMETER JavaHome
  Explicit JDK home used to run Gradle (must be 17-21 for this project).

.EXAMPLE
  .\buildDeploy.ps1

.EXAMPLE
  .\buildDeploy.ps1 -Clean -Launch

.EXAMPLE
  .\buildDeploy.ps1 -Serial emulator-5554 -Launch
#>
[CmdletBinding()]
param(
    [string] $Serial,
    [switch] $Clean,
    [switch] $Launch,
    [switch] $SkipBuild,
    [string] $JavaHome
)

$ErrorActionPreference = "Stop"

$Package = "com.tcs.vehicleassistant"
$Activity = ".LocalLLMActivity"
$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
$Gradlew = Join-Path $PSScriptRoot "gradlew.bat"

function Write-Step {
    param([string] $Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Get-JavaMajorVersion {
    param([string] $JdkHome)
    $javaExe = Join-Path $JdkHome "bin\java.exe"
    if (-not (Test-Path $javaExe)) {
        return $null
    }
    # java -version writes to stderr; do not treat that as a terminating error
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $javaExe -version 2>&1 | Out-String
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($output -match 'version "(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Resolve-GradleJavaHome {
    param([string] $Preferred)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Preferred) { [void]$candidates.Add($Preferred) }

    foreach ($path in @(
        "C:\Program Files\Android\Android Studio1\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android\Android Studio\jbr",
        $env:JAVA_HOME
    )) {
        if ($path) { [void]$candidates.Add($path) }
    }

    # Common JDK install roots (prefer 21, then 17)
    foreach ($root in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft", "C:\Program Files\BellSoft")) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '21|17|jdk-21|jdk-17|jbr' } |
                Sort-Object { if ($_.Name -match '21') { 0 } else { 1 } } |
                ForEach-Object { [void]$candidates.Add($_.FullName) }
        }
    }

    foreach ($jdkPath in ($candidates | Select-Object -Unique)) {
        $major = Get-JavaMajorVersion -JdkHome $jdkPath
        if ($null -eq $major) { continue }
        if ($major -ge 17 -and $major -le 21) {
            return @{ JdkHome = $jdkPath; Major = $major }
        }
    }

    return $null
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $AdbArgs)
    if ($Serial) {
        & adb -s $Serial @AdbArgs
    } else {
        & adb @AdbArgs
    }
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($AdbArgs -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Get-ReadyDevices {
    $lines = & adb devices 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed. Is Android SDK platform-tools on PATH?"
    }

    $ready = New-Object System.Collections.Generic.List[string]
    foreach ($line in $lines) {
        $text = "$line"
        if ($text -match '^\s*(\S+)\s+device\s*$') {
            [void]$ready.Add($Matches[1])
        }
    }
    return ,$ready.ToArray()
}

Push-Location $PSScriptRoot
try {
    Write-Host "======================================================"
    Write-Host "  VehicleEdge Assistant - Build and Deploy"
    Write-Host "======================================================"

    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw "adb not found in PATH. Install Android SDK platform-tools and retry."
    }

    Write-Step "Checking for a running device..."
    $devices = @(Get-ReadyDevices)
    if ($devices.Count -eq 0) {
        throw "No device in 'device' state. Connect a device/emulator and enable USB debugging."
    }

    if ($Serial) {
        if ($devices -notcontains $Serial) {
            throw "Device '$Serial' not found. Connected: $($devices -join ', ')"
        }
        Write-Host "Using device: $Serial"
    } elseif ($devices.Count -gt 1) {
        throw "Multiple devices connected ($($devices -join ', ')). Re-run with -Serial SERIAL."
    } else {
        $Serial = [string]$devices[0]
        Write-Host "Using device: $Serial"
    }

    if (-not $SkipBuild) {
        if (-not (Test-Path $Gradlew)) {
            throw "gradlew.bat not found at $Gradlew"
        }

        $localProps = Join-Path $PSScriptRoot "local.properties"
        if (-not (Test-Path $localProps)) {
            $sdk = $env:ANDROID_HOME
            if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
            if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
            if (-not (Test-Path $sdk)) {
                throw "Android SDK not found. Set ANDROID_HOME or create local.properties with sdk.dir=..."
            }
            $sdkUnix = $sdk -replace '\\', '/'
            "sdk.dir=$sdkUnix" | Set-Content -Path $localProps -Encoding ASCII
            Write-Host "Created local.properties -> sdk.dir=$sdkUnix"
        }

        Write-Step "Selecting JDK for Gradle..."
        $jdk = Resolve-GradleJavaHome -Preferred $JavaHome
        if (-not $jdk) {
            throw @"
No compatible JDK (17-21) found for Gradle 8.4.
Current default Java is likely JDK 25+, which Gradle 8.4 cannot use.
Install Android Studio (bundled JBR) or JDK 17/21, then re-run.
Optional: .\buildDeploy.ps1 -JavaHome 'C:\Path\To\JDK21'
"@
        }
        $env:JAVA_HOME = $jdk.JdkHome
        $env:PATH = "$(Join-Path $jdk.JdkHome 'bin');$env:PATH"
        Write-Host "Using JDK $($jdk.Major): $($jdk.JdkHome)"

        # Stop any daemon started with an incompatible JDK.
        & $Gradlew --stop 2>$null | Out-Null

        if ($Clean) {
            Write-Step "Cleaning and building debug APK..."
            & $Gradlew clean assembleDebug --stacktrace
        } else {
            Write-Step "Building debug APK..."
            & $Gradlew assembleDebug --stacktrace
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    } else {
        Write-Step "Skipping build (-SkipBuild)"
    }

    if (-not (Test-Path $ApkPath)) {
        throw "APK not found: $ApkPath"
    }

    Write-Step "Installing APK..."
    Write-Host "  $ApkPath"
    Invoke-Adb install -r -g $ApkPath

    if ($Launch) {
        Write-Step "Launching $Package/$Activity..."
        Invoke-Adb shell am force-stop $Package
        Invoke-Adb shell am start -n "$Package/$Activity"
    }

    Write-Host ""
    Write-Host "Done." -ForegroundColor Green
    Write-Host "  Package : $Package"
    Write-Host "  Device  : $Serial"
    Write-Host "  APK     : $ApkPath"
    if (-not $Launch) {
        Write-Host "  Tip     : re-run with -Launch to start LocalLLMActivity"
    }
}
finally {
    Pop-Location
}
