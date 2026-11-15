# LADA Trip Engineering v0.2 - offline/local Windows build helper
# Requires installed Android Studio (bundled JDK), Android SDK Platform 35 + Build Tools 35.0.0.
# Downloads only the official Gradle 8.7 distribution (unless already cached).
# Does not use GitHub, does not upload sources, secrets or GPS information.
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root 'android'
Write-Host '=== LADA Trip Engineering v0.2 / local APK build ===' -ForegroundColor Cyan
if (!(Test-Path (Join-Path $android 'app\build.gradle'))) {
    throw 'Project not found. Unzip the full archive and run build-lada-trip.cmd from the lada-trip folder.'
}
# Use Android Studio's bundled JBR rather than the system Java, which may be too new.
$studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path (Join-Path $studioJbr 'bin\java.exe')) {
    $env:JAVA_HOME = $studioJbr
    $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
} elseif ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or !(Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'Java JDK not found. Install Android Studio from https://developer.android.com/studio (bundled JBR), or configure JAVA_HOME for JDK 17/21.'
}
$ErrorActionPreference = 'Continue'
$javaOut = & (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Out-String
$ErrorActionPreference = 'Stop'
Write-Host ('JAVA_HOME: ' + $env:JAVA_HOME)
Write-Host $javaOut
if ($javaOut -match 'version "(\d+)') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 17 -or $javaMajor -gt 21) {throw 'This build requires Java 17 or 21. Set JAVA_HOME or install Android Studio.'}
}
$possibleSdks = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
$sdk = $null
foreach($candidate in $possibleSdks) {
    if (![string]::IsNullOrWhiteSpace($candidate) -and (Test-Path (Join-Path $candidate 'platforms\android-35\android.jar'))) { $sdk=$candidate; break }
}
if ($null -eq $sdk) {
    Write-Host 'Android SDK Platform 35 is missing.' -ForegroundColor Yellow
    Write-Host 'Android Studio > More Actions > SDK Manager (or Settings > Languages & Frameworks > Android SDK).'
    Write-Host 'Install Android SDK Platform 35 and SDK Build-Tools 35.0.0, accept licenses, then rerun.'
    throw 'SDK 35 not found.'
}
if (!(Test-Path (Join-Path $sdk 'build-tools\35.0.0\aapt2.exe'))) {
    Write-Host 'Install Android SDK Build-Tools 35.0.0 via Android Studio > SDK Manager > SDK Tools > Show Package Details.' -ForegroundColor Yellow
    throw 'Android SDK Build-Tools 35.0.0 missing.'
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$sdkUnix = $sdk.Replace('\','/')
Set-Content -LiteralPath (Join-Path $android 'local.properties') -Value ('sdk.dir='+$sdkUnix) -Encoding ASCII
Write-Host ('Android SDK: ' + $sdk) -ForegroundColor Green

# Cache official Gradle distribution per user, not inside the source repository.
$toolsDir = Join-Path $env:LOCALAPPDATA 'LADATripBuild'
$gradleDir = Join-Path $toolsDir 'gradle-8.7'
$gradleBat = Join-Path $gradleDir 'bin\gradle.bat'
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
if (!(Test-Path $gradleBat)) {
    $distZip = Join-Path $toolsDir 'gradle-8.7-bin.zip'
    $hashFile = Join-Path $toolsDir 'gradle-8.7-bin.zip.sha256'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $url = 'https://services.gradle.org/distributions/gradle-8.7-bin.zip'
    if (!(Test-Path $distZip)) {
        Write-Host 'Downloading official Gradle 8.7 (one-time)...' -ForegroundColor Cyan
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $distZip
    }
    try {
        Invoke-WebRequest -UseBasicParsing -Uri ($url+'.sha256') -OutFile $hashFile
        $expected = ((Get-Content $hashFile -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
        $actual = (Get-FileHash -LiteralPath $distZip -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($expected -ne $actual) {
            Remove-Item -LiteralPath $distZip -Force
            throw 'Gradle SHA256 mismatch! Re-run to download a new distribution.'
        }
    } catch {
        throw ('Gradle checksum verification failed: ' + $_.Exception.Message)
    }
    Write-Host 'Extracting Gradle...' -ForegroundColor Cyan
    Expand-Archive -LiteralPath $distZip -DestinationPath $toolsDir -Force
    if (!(Test-Path $gradleBat)) {throw 'Gradle extraction failed.'}
}
$logsDir = Join-Path $root 'build-logs'
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$logPath = Join-Path $logsDir 'last-build.txt'
Write-Host 'Building APK (this may download Android plugin dependencies)...' -ForegroundColor Cyan
Push-Location $android
try {
    # Android SDK and Gradle may produce useful stderr messages during a successful build.
    $ErrorActionPreference = 'Continue'
    & $gradleBat --no-daemon --max-workers=2 --console=plain assembleDebug 2>&1 | Tee-Object -FilePath $logPath
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
} finally { Pop-Location }
if ($code -ne 0) {
    Write-Host ('BUILD FAILED. Full log: ' + $logPath) -ForegroundColor Red
    throw ('Gradle exit code: ' + $code)
}
$apk = Join-Path $android 'app\build\outputs\apk\debug\app-debug.apk'
if (!(Test-Path $apk)) {throw ('Build reported success, but APK not found: ' + $apk)}
$outDir = Join-Path $root 'APK'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir 'LADA-Trip-v0.2-Engineering-debug.apk'
Copy-Item -LiteralPath $apk -Destination $out -Force
Write-Host 'SUCCESS! APK:' -ForegroundColor Green
Write-Host $out -ForegroundColor Green
Write-Host ('SHA256: ' + (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash)
Write-Host ('Build log: ' + $logPath)
Write-Host 'Note: debug APK is test-only. Do not uninstall any previous installation without exporting trips first.' -ForegroundColor Yellow
