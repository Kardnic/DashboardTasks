$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $PSScriptRoot
$keystorePath = Join-Path $projectDir "DashboardTasks-release.jks"
$secretsPath = Join-Path $projectDir "signing-secrets.txt"

if (Test-Path $keystorePath) {
    throw "Keystore existiert bereits: $keystorePath"
}

$keytool = Get-Command keytool -ErrorAction Stop

function New-HexSecret([int]$bytes = 24) {
    $buffer = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
    } finally {
        $rng.Dispose()
    }
    return ($buffer | ForEach-Object { $_.ToString("x2") }) -join ""
}

$storePassword = New-HexSecret 24
$keyPassword = New-HexSecret 24
$keyAlias = "dashboardtasks"

& $keytool.Source -genkeypair -keystore $keystorePath -alias $keyAlias -keyalg RSA -keysize 4096 -validity 10000 -storepass $storePassword -keypass $keyPassword -dname "CN=DashboardTasks, O=Private, C=DE"

if ($LASTEXITCODE -ne 0) {
    throw "keytool konnte den Signaturschlüssel nicht erzeugen."
}

$keystoreBytes = [System.IO.File]::ReadAllBytes($keystorePath)
$keystoreBase64 = [Convert]::ToBase64String($keystoreBytes)

$secretLines = @(
    "ANDROID_KEYSTORE_BASE64=$keystoreBase64",
    "ANDROID_KEYSTORE_PASSWORD=$storePassword",
    "ANDROID_KEY_ALIAS=$keyAlias",
    "ANDROID_KEY_PASSWORD=$keyPassword"
)
$secretLines | Set-Content -Path $secretsPath -Encoding UTF8

Write-Host ""
Write-Host "Signaturschlüssel wurde erstellt:" -ForegroundColor Green
Write-Host "  $keystorePath"
Write-Host ""
Write-Host "GitHub-Secret-Werte stehen in:" -ForegroundColor Yellow
Write-Host "  $secretsPath"
Write-Host ""
Write-Host "WICHTIG:"
Write-Host "- Keystore und signing-secrets.txt sicher sichern."
Write-Host "- Beide Dateien niemals in Git committen."
Write-Host "- Ohne diesen Schlüssel können spätere App-Updates nicht signiert werden."
