# Ccomics PC 서버: 지정한 폴더들을 읽기 전용 WebDAV로 127.0.0.1에만 엽니다.
# 외부 접속은 Tailscale Funnel의 /ccomics 경로가 이 포트로 넘겨 줍니다.
# 설정: %APPDATA%\CcomicsServer\config.json  (folders, user, pass, port)
# rclone 설정 파일(구글 드라이브 등)은 읽지도 바꾸지도 않습니다(--config 미사용, 환경 변수로만 원격 정의).
param([switch]$Init, [string]$LogLevel = 'NOTICE')

$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:APPDATA 'CcomicsServer'
$configPath = Join-Path $dir 'config.json'
$rclone = 'C:\Programs\rclone-v1.76.0-windows-amd64\rclone.exe'
New-Item -ItemType Directory -Force $dir | Out-Null

if (-not (Test-Path $configPath)) {
  $bytes = New-Object byte[] 24
  [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $pass = ([Convert]::ToBase64String($bytes)).Replace('+', 'A').Replace('/', 'B').Replace('=', '')
  $cfg = [ordered]@{ port = 17820; user = 'ccomics'; pass = $pass; folders = @('X:\COMICS\테스트') }
  $cfg | ConvertTo-Json | Out-File -Encoding utf8 $configPath
}
if ($Init) { return }

$cfg = Get-Content -Raw -Encoding UTF8 $configPath | ConvertFrom-Json
$names = @{}
$upstreams = foreach ($folder in @($cfg.folders)) {
  $name = Split-Path $folder -Leaf
  $base = $name; $n = 2
  while ($names.ContainsKey($name)) { $name = "$base$n"; $n++ }
  $names[$name] = $true
  '"' + $name + '=' + $folder + '"'
}

# 설정 파일 대신 환경 변수로만 'ccomicslib' 원격을 정의합니다.
$env:RCLONE_CONFIG_CCOMICSLIB_TYPE = 'combine'
$env:RCLONE_CONFIG_CCOMICSLIB_UPSTREAMS = ($upstreams -join ' ')
$env:RCLONE_USER = $cfg.user
$env:RCLONE_PASS = $cfg.pass

& $rclone serve webdav 'ccomicslib:' `
  --config /notfound `
  --addr "127.0.0.1:$($cfg.port)" `
  --baseurl /ccomics `
  --read-only `
  --dir-cache-time 30s `
  --poll-interval 0 `
  --log-file (Join-Path $dir 'server.log') `
  --log-level $LogLevel
