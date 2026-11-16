param([Parameter(Mandatory)][string]$Notes)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$repo = 'h05212317-code/Ccomics'
$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (!$gh) { $gh = (Get-ChildItem "$env:LOCALAPPDATA/CcomicsBuild/gh-cli" -Recurse -Filter gh.exe | Select-Object -First 1).FullName }
if (!$gh) { throw 'Install GitHub CLI and run gh auth login.' }
if ((git status --porcelain)) { throw 'Commit the source and version change before publishing.' }
$config = Get-Content -Raw app/build.gradle
if ($config -notmatch "versionName '([0-9]+\.[0-9]+\.[0-9]+)'") { throw 'Cannot read versionName.' }
$tag = 'v' + $Matches[1]
$commit = git rev-parse HEAD
if ($LASTEXITCODE) { throw 'No source commit found.' }
& ./build-apk.ps1
if ($LASTEXITCODE) { throw 'Build failed.' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA/CcomicsBuild/sdk" }
$cert = & "$sdk/build-tools/35.0.0/apksigner.bat" verify --print-certs Ccomics.apk
if ($LASTEXITCODE -or !($cert -match 'certificate SHA-256 digest: 5fcb92b91e22abf858bd39dec5a983d30da062843d6e4330ecded06809891257$')) { throw 'The APK must use the original Ccomics signing key.' }
git push -u origin HEAD:main
if ($LASTEXITCODE) { throw 'Git push failed.' }
$existing = & $gh release view $tag --repo $repo --json isDraft --jq .isDraft 2>$null
if ($LASTEXITCODE -eq 0 -and $existing -ne 'true') { throw 'This version has already been published. Increase versionCode and versionName.' }
$noteFile = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($noteFile, $Notes)
    if ($existing -eq 'true') {
        $draftTarget = & $gh release view $tag --repo $repo --json targetCommitish --jq .targetCommitish
        if ($draftTarget -ne $commit) { throw 'The existing draft points to another source commit.' }
        & $gh release upload $tag Ccomics.apk --repo $repo --clobber
    } else {
        & $gh release create $tag Ccomics.apk --repo $repo --target $commit --title "Ccomics $tag" --notes-file $noteFile --draft
    }
    if ($LASTEXITCODE) { throw 'Draft release upload failed.' }
    $data = & $gh release view $tag --repo $repo --json assets | ConvertFrom-Json
    $digest = ($data.assets | Where-Object name -eq 'Ccomics.apk').digest
    $localHash = 'sha256:' + (Get-FileHash Ccomics.apk).Hash.ToLowerInvariant()
    if ($LASTEXITCODE -or $digest -ne $localHash) { throw 'The uploaded APK checksum does not match.' }
    & $gh release edit $tag --repo $repo --draft=false --latest
    if ($LASTEXITCODE) { throw 'Release publication failed.' }
} finally { Remove-Item -LiteralPath $noteFile -ErrorAction SilentlyContinue }
