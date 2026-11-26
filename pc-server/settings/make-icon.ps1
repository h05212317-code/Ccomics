# 앱 아이콘(app/src/main/res: icon_ground 배경 + icon_leaf 그림)을 그대로 그려 설정 프로그램용 app.ico를 만든다.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$out = Join-Path $PSScriptRoot 'app.ico'

function New-Bezier([System.Drawing.Drawing2D.GraphicsPath]$p, [float[]]$a, [float[]]$q, [float[]]$b) {
  # 2차 곡선(Q)을 3차 곡선으로 바꿔 넣는다.
  $c1 = New-Object System.Drawing.PointF(($a[0] + 2 * ($q[0] - $a[0]) / 3), ($a[1] + 2 * ($q[1] - $a[1]) / 3))
  $c2 = New-Object System.Drawing.PointF(($b[0] + 2 * ($q[0] - $b[0]) / 3), ($b[1] + 2 * ($q[1] - $b[1]) / 3))
  $p.AddBezier((New-Object System.Drawing.PointF($a[0], $a[1])), $c1, $c2, (New-Object System.Drawing.PointF($b[0], $b[1])))
}

function New-Icon([int]$size) {
  $bmp = New-Object System.Drawing.Bitmap($size, $size)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'
  $g.Clear([System.Drawing.Color]::Transparent)
  # 둥근 사각형 배경(#164E40)
  $r = [float]($size * 0.22)
  $bg = New-Object System.Drawing.Drawing2D.GraphicsPath
  $bg.AddArc(0, 0, 2 * $r, 2 * $r, 180, 90); $bg.AddArc($size - 2 * $r - 1, 0, 2 * $r, 2 * $r, 270, 90)
  $bg.AddArc($size - 2 * $r - 1, $size - 2 * $r - 1, 2 * $r, 2 * $r, 0, 90); $bg.AddArc(0, $size - 2 * $r - 1, 2 * $r, 2 * $r, 90, 90)
  $bg.CloseFigure()
  $g.FillPath((New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#164E40'))), $bg)
  # 적응형 아이콘의 가운데 72/108 영역을 꽉 채우고, 그림 그룹 변환(0.8배, 10.8 이동)을 적용한다.
  $s = $size / 72.0
  $g.TranslateTransform([float](-18 * $s), [float](-18 * $s))
  $g.ScaleTransform([float]$s, [float]$s)
  $g.TranslateTransform(10.8, 10.8)
  $g.ScaleTransform(0.8, 0.8)

  $p1 = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p1.AddPolygon([System.Drawing.PointF[]]@((New-Object System.Drawing.PointF(23,39)),(New-Object System.Drawing.PointF(55,47)),(New-Object System.Drawing.PointF(85,30)),(New-Object System.Drawing.PointF(85,70)),(New-Object System.Drawing.PointF(55,87)),(New-Object System.Drawing.PointF(23,79))))
  $g.FillPath((New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#94BA9D'))), $p1)

  $p2 = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p2.StartFigure(); New-Bezier $p2 @(22,29) @(39,28) @(54,38); $p2.AddLine(54,38,54,75); New-Bezier $p2 @(54,75) @(39,65) @(22,65); $p2.CloseFigure()
  $p2.StartFigure(); New-Bezier $p2 @(58,38) @(68,26) @(84,22); $p2.AddLine(84,22,84,58); New-Bezier $p2 @(84,58) @(68,62) @(58,75); $p2.CloseFigure()
  $g.FillPath((New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#FEF9E6'))), $p2)

  $p3 = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p3.AddPolygon([System.Drawing.PointF[]]@((New-Object System.Drawing.PointF(70,29)),(New-Object System.Drawing.PointF(76,26)),(New-Object System.Drawing.PointF(76,43)),(New-Object System.Drawing.PointF(73,42)),(New-Object System.Drawing.PointF(70,47))))
  $g.FillPath((New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#D4AC68'))), $p3)
  $g.Dispose()
  $ms = New-Object System.IO.MemoryStream
  $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  return ,$ms.ToArray()
}

# PNG를 담은 ICO 파일(16·24·32·48·64·256)
$sizes = 16, 24, 32, 48, 64, 256
$images = foreach ($z in $sizes) { ,(New-Icon $z) }
$fs = [System.IO.File]::Create($out)
$w = New-Object System.IO.BinaryWriter($fs)
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
  $z = $sizes[$i]; $len = $images[$i].Length
  $w.Write([byte]($z % 256)); $w.Write([byte]($z % 256)); $w.Write([byte]0); $w.Write([byte]0)
  $w.Write([uint16]1); $w.Write([uint16]32); $w.Write([uint32]$len); $w.Write([uint32]$offset)
  $offset += $len
}
foreach ($img in $images) { $w.Write($img) }
$w.Close()
# 확인용 미리보기
[System.IO.File]::WriteAllBytes((Join-Path $PSScriptRoot 'app-preview.png'), $images[-1])
"icon: $out"
