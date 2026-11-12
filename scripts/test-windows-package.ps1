#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$AppDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$executable = Join-Path (Resolve-Path -LiteralPath $AppDirectory) 'Quick Drop.exe'
$testHome = Join-Path $projectRoot ('target/desktop-smoke/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force $testHome | Out-Null
$previousDataHome = $env:QUICKDROP_DATA_HOME
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$process = $null
function Start-TestApp([string]$RunName) {
    $output = Join-Path $testHome "$RunName.stdout.log"
    $errors = Join-Path $testHome "$RunName.stderr.log"
    $script:process = Start-Process -FilePath $executable -ArgumentList '--server.port=0','--server.address=127.0.0.1','--quickdrop.open-browser=false' `
        -WorkingDirectory $testHome -WindowStyle Hidden -RedirectStandardOutput $output -RedirectStandardError $errors -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($script:process.HasExited) { throw "Executable exited unexpectedly. See $output and $errors" }
        $log = Get-Content -LiteralPath $output -Raw -ErrorAction SilentlyContinue
        if ($log -match 'Quick Drop is ready: (http://localhost:\d+)') { return $Matches[1] }
        Start-Sleep -Milliseconds 250
    }
    throw "Executable did not become ready. See $output"
}
try {
    $env:QUICKDROP_DATA_HOME = $testHome
    $env:JAVA_HOME = ''
    $env:PATH = Join-Path $env:SystemRoot 'System32'
    $address = Start-TestApp 'first'
    $page = Invoke-WebRequest "$address/" -SessionVariable session
    if ($page.Content -notmatch 'Create owner PIN') { throw 'Expected first-launch PIN setup.' }
    $csrf = [regex]::Match($page.Content, '<meta id="csrf-token"[^>]*content="([^"]+)"').Groups[1].Value
    Invoke-WebRequest "$address/owner/setup" -WebSession $session -Method Post -Body @{_csrf=$csrf;pin='1357';confirmPin='1357'} | Out-Null
    $page = Invoke-WebRequest "$address/" -WebSession $session
    $csrf = [regex]::Match($page.Content, '<meta id="csrf-token"[^>]*content="([^"]+)"').Groups[1].Value
    $fixture = Join-Path $testHome 'release-test.txt'
    'Packaged runtime persistence check.' | Set-Content -LiteralPath $fixture
    Invoke-RestMethod "$address/upload" -WebSession $session -Method Post -Headers @{'X-CSRF-Token'=$csrf} `
        -Form @{file=Get-Item -LiteralPath $fixture;expiration='never'} | Out-Null
    if (-not (Test-Path (Join-Path $testHome 'data/owner-auth.properties'))) { throw 'PIN was not stored in the desktop data folder.' }
    Stop-Process -Id $process.Id
    $process.WaitForExit()
    $process = $null
    $address = Start-TestApp 'second'
    $page = Invoke-WebRequest "$address/"
    if ($page.Content -notmatch 'Your files') { throw 'Owner PIN did not survive restart.' }
    $snapshot = Invoke-RestMethod "$address/api/files"
    if ($snapshot.files.Count -ne 1 -or $snapshot.files[0].filename -ne 'release-test.txt') { throw 'Uploaded file did not survive restart.' }
    $download = Invoke-WebRequest "$address/share/$($snapshot.files[0].shareToken)"
    $content = if ($download.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($download.Content) } else { $download.Content }
    if ($content.Trim() -ne 'Packaged runtime persistence check.') { throw 'Downloaded bytes do not match the fixture.' }
    Write-Output 'PASS: packaged EXE runs without system Java, opens first-run setup, and retains PIN and uploaded bytes across restart.'
} finally {
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id; $process.WaitForExit() }
    $env:QUICKDROP_DATA_HOME = $previousDataHome
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
