$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$toolchain = Join-Path $env:LOCALAPPDATA 'CcomicsBuild'
if (-not $env:JAVA_HOME -and (Test-Path "$toolchain/jdk")) {
    $env:JAVA_HOME = (Get-ChildItem "$toolchain/jdk" -Directory | Select-Object -First 1).FullName
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $toolchain 'sdk' }
$signer = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/apksigner.bat'
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
& "$PSScriptRoot/gradlew.bat" --max-workers=4 --console=plain :app:testDebugUnitTest
if ($LASTEXITCODE) { throw 'Android tests failed.' }
& "$PSScriptRoot/gradlew.bat" --max-workers=4 --console=plain :app:assembleRelease
if ($LASTEXITCODE) { throw 'Android build failed.' }
$private = Join-Path $env:LOCALAPPDATA 'CcomicsSigning'
New-Item -ItemType Directory -Force $private | Out-Null
$keyfile = Join-Path $private 'ccomics.p12'
$protected = Join-Path $private 'password.xml'
if (!(Test-Path $protected)) {
    if (Test-Path $keyfile) { throw 'The signing key exists but its password file is missing.' }
    $random = [byte[]]::new(36)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($random)
    $rng.Dispose()
    $password = ConvertTo-SecureString ([Convert]::ToBase64String($random)) -AsPlainText -Force
    $password | Export-Clixml -LiteralPath $protected
}
$secret = Import-Clixml -LiteralPath $protected
$env:CCOMICS_KEY_PASSWORD = [Net.NetworkCredential]::new('', $secret).Password
try {
    if (!(Test-Path $keyfile)) {
        & $keytool -genkeypair -storetype PKCS12 -keystore $keyfile -storepass:env CCOMICS_KEY_PASSWORD -keypass:env CCOMICS_KEY_PASSWORD -alias ccomics -keyalg RSA -keysize 3072 -validity 12000 -dname 'CN=Ccomics'
        if ($LASTEXITCODE) { throw 'Signing key generation failed.' }
    }
    $unsigned = "$PSScriptRoot/app/build/outputs/apk/release/app-release-unsigned.apk"
    & $signer sign --ks $keyfile --ks-key-alias ccomics --ks-pass env:CCOMICS_KEY_PASSWORD --v4-signing-enabled false --out "$PSScriptRoot/Ccomics.apk" $unsigned
    if ($LASTEXITCODE) { throw 'APK signing failed.' }
    & $signer verify --verbose "$PSScriptRoot/Ccomics.apk"
    if ($LASTEXITCODE) { throw 'APK signature verification failed.' }
} finally { Remove-Item Env:CCOMICS_KEY_PASSWORD -ErrorAction SilentlyContinue }
