#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '0.0.1',
    [switch]$SkipBuild,
    [switch]$IncludeInstaller
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$originalPath = $env:PATH
Push-Location $projectRoot
try {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK with jpackage (Java 21 or newer).' }
    $jpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
    if (-not (Test-Path -LiteralPath $jpackage)) { throw "jpackage not found: $jpackage" }
    if ($IncludeInstaller -and $env:WIX) {
        $env:PATH = (Join-Path $env:WIX 'bin') + [IO.Path]::PathSeparator + $env:PATH
    }
    if ($IncludeInstaller -and -not (Get-Command candle.exe -ErrorAction SilentlyContinue) -and -not (Get-Command wix.exe -ErrorAction SilentlyContinue)) {
        throw 'Installer packaging requires WiX on PATH (WiX 3 for JDK 21). GitHub Windows runners include it. Omit -IncludeInstaller for a portable ZIP only.'
    }
    if (-not $SkipBuild) {
        & .\mvnw.cmd -B verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
        node --test src/test/javascript/*.test.cjs
        if ($LASTEXITCODE -ne 0) { throw 'JavaScript tests failed.' }
    }
    [xml]$pom = Get-Content pom.xml
    $jar = Join-Path $projectRoot "target/$($pom.project.artifactId)-$($pom.project.version).jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Build the application first: $jar" }
    # Isolate every packaging run; never include uploads, databases, or credentials.
    $stage = Join-Path $projectRoot ('target/package-work/' + [guid]::NewGuid().ToString())
    $inputDir = Join-Path $stage 'input'
    $imageDir = Join-Path $stage 'image'
    New-Item -ItemType Directory -Force $inputDir,$imageDir,'target/releases' | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $inputDir 'quick-drop.jar')
    & $jpackage --type app-image --name 'Quick Drop' --app-version $Version `
        --vendor 'Quick Drop' --description 'Share files across your local network' `
        --input $inputDir --main-jar quick-drop.jar `
        --main-class org.springframework.boot.loader.launch.JarLauncher `
        --dest $imageDir --win-console --add-modules ALL-MODULE-PATH `
        --java-options '-Dquickdrop.desktop=true' --java-options '-Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw 'Windows packaging failed.' }
    $appImage = Join-Path $imageDir 'Quick Drop'
    Copy-Item -LiteralPath 'scripts/WINDOWS-README.txt' -Destination (Join-Path $appImage 'START HERE.txt')
    & (Join-Path $PSScriptRoot 'test-windows-package.ps1') -AppDirectory $appImage
    $zip = Join-Path $projectRoot "target/releases/Quick-Drop-$Version-windows-x64.zip"
    Compress-Archive -LiteralPath $appImage -DestinationPath $zip -Force
    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($zip))" | Set-Content -Encoding ascii -LiteralPath "$zip.sha256"
    if ($IncludeInstaller) {
        $installerDir = Join-Path $stage 'installer'
        # Keep this upgrade identity stable across versions.
        & $jpackage --type exe --name 'Quick Drop' --app-version $Version `
            --vendor 'Quick Drop' --description 'Share files across your local network' `
            --app-image $appImage --dest $installerDir `
            --win-per-user-install --win-menu --win-shortcut --win-dir-chooser `
            --win-upgrade-uuid '4f047225-1a2c-4d49-9d20-e39c1f896378'
        if ($LASTEXITCODE -ne 0) { throw 'Windows installer packaging failed.' }
        $builtInstallers = @(Get-ChildItem -LiteralPath $installerDir -Filter '*.exe' -File)
        if ($builtInstallers.Count -ne 1) { throw 'Expected exactly one Windows setup executable.' }
        $installer = Join-Path $projectRoot "target/releases/Quick-Drop-$Version-windows-x64-setup.exe"
        Copy-Item -LiteralPath $builtInstallers[0].FullName -Destination $installer -Force
        $installerHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
        "$installerHash  $([IO.Path]::GetFileName($installer))" | Set-Content -Encoding ascii -LiteralPath "$installer.sha256"
        Write-Output "Installer: $installer"
    }
    Write-Output "Executable: $(Join-Path $appImage 'Quick Drop.exe')"
    Write-Output "Release: $zip"
} finally {
    $env:PATH = $originalPath
    Pop-Location
}
