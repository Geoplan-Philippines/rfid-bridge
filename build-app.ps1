<#
  Builds the RFID Bridge desktop app and packages it into a self-contained
  Windows .exe (bundled Java runtime, no Java needed on the target PC).

  Output:  dist\RfidBridge\RfidBridge.exe   (+ runtime\ folder alongside it)

  Usage (PowerShell, from this folder):
      .\build-app.ps1
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $root

# --- locate the JDK (needs jpackage + jar, JDK 17+) -------------------------
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\jpackage.exe'))) {
    $candidates = @(
        'C:\Program Files\Java\jdk-26.0.1',
        'C:\Program Files\Java\latest'
    )
    $jdk = $candidates | Where-Object { Test-Path (Join-Path $_ 'bin\jpackage.exe') } | Select-Object -First 1
}
if (-not $jdk) { throw "Could not find a JDK with jpackage. Set JAVA_HOME to a JDK 17+ install." }

$javac    = Join-Path $jdk 'bin\javac.exe'
$java     = Join-Path $jdk 'bin\java.exe'
$jar      = Join-Path $jdk 'bin\jar.exe'
$jpackage = Join-Path $jdk 'bin\jpackage.exe'
Write-Host "Using JDK: $jdk" -ForegroundColor Cyan

# --- clean ------------------------------------------------------------------
foreach ($d in @('build','dist')) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path 'build\classes' | Out-Null
New-Item -ItemType Directory -Force -Path 'build\lib'     | Out-Null

# --- compile ----------------------------------------------------------------
Write-Host "Compiling..." -ForegroundColor Cyan
$sources = Get-ChildItem 'src\main\java\geoplanph\*.java' | ForEach-Object { $_.FullName }
& $javac -d 'build\classes' $sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

# --- bundle resources (web UI + default config template) --------------------
Copy-Item 'src\main\resources\*' 'build\classes\' -Recurse -Force

# --- generate the app icon --------------------------------------------------
Write-Host "Generating icon..." -ForegroundColor Cyan
& $java -cp 'build\classes' geoplanph.IconGen 'assets\app.ico'
if ($LASTEXITCODE -ne 0) { throw "Icon generation failed." }

# --- jar --------------------------------------------------------------------
Write-Host "Building jar..." -ForegroundColor Cyan
& $jar --create --file 'build\lib\rfid-bridge.jar' --main-class geoplanph.TrayApp -C 'build\classes' .
if ($LASTEXITCODE -ne 0) { throw "jar failed." }

# --- package into a self-contained .exe app-image ---------------------------
Write-Host "Packaging with jpackage (this bundles a Java runtime)..." -ForegroundColor Cyan
& $jpackage `
    --type app-image `
    --name 'RfidBridge' `
    --app-version '1.0.0' `
    --vendor 'Geoplan' `
    --description 'RFID reader to backend bridge' `
    --input 'build\lib' `
    --main-jar 'rfid-bridge.jar' `
    --main-class geoplanph.TrayApp `
    --icon 'assets\app.ico' `
    --dest 'dist'
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

$exe = Join-Path $root 'dist\RfidBridge\RfidBridge.exe'
Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  Executable: $exe" -ForegroundColor Green
Write-Host "  Launch it, then right-click the tray icon -> Open Dashboard." -ForegroundColor Green
