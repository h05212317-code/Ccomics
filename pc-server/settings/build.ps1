# Ccomics PC 서버 설정 프로그램을 Windows 기본 .NET Framework 컴파일러로 빌드해 Ccomics 폴더에 둡니다.
$ErrorActionPreference = 'Stop'
$csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$fw = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$out = Join-Path $root 'Ccomics PC 서버 설정.exe'
& $csc /nologo /target:winexe /codepage:65001 /optimize+ "/win32icon:$(Join-Path $PSScriptRoot 'app.ico')" "/out:$out" `
  "/r:$fw\System.Windows.Forms.dll" "/r:$fw\System.Drawing.dll" "/r:$fw\System.Management.dll" "/r:$fw\System.Web.Extensions.dll" `
  (Join-Path $PSScriptRoot 'ServerSettings.cs')
if ($LASTEXITCODE) { throw 'build failed' }
"built: $out"
