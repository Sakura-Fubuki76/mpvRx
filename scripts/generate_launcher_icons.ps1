# Rebuild density-specific Android assets from the transparent master artwork.
# Run from any directory with Windows PowerShell or PowerShell on Windows.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$repo = Split-Path -Parent $PSScriptRoot
$resources = Join-Path $repo 'app/src/main/res'
$source = [System.Drawing.Bitmap]::new((Join-Path $repo 'artwork/ic_launcher_character.png'))
$background = [System.Drawing.ColorTranslator]::FromHtml('#D9E4DB')

function New-Canvas([int]$size) {
    return [System.Drawing.Bitmap]::new($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

function New-Graphics($bitmap) {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    return $graphics
}

function Save-Png($bitmap, [string]$path) {
    $directory = Split-Path -Parent $path
    New-Item -ItemType Directory -Force $directory | Out-Null
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

$foreground = New-Canvas 432
$graphics = New-Graphics $foreground
# 108 dp layer: 72 dp portrait, centered. Face and hand remain in the 66 dp safe zone.
$height = 288
$width = [int]($source.Width * $height / $source.Height)
$graphics.DrawImage($source, [int]((432 - $width) / 2), 72, $width, $height)
$graphics.Dispose()

# Legacy icons represent the visible 72 dp adaptive viewport, not the padded 108 dp layer.
$square = New-Canvas 512
$graphics = New-Graphics $square
$graphics.Clear($background)
$graphics.DrawImage($source, 0, 0, 512, 512)
$graphics.Dispose()

try {
    foreach ($density in @(@('mdpi', 1), @('hdpi', 1.5), @('xhdpi', 2), @('xxhdpi', 3), @('xxxhdpi', 4))) {
        $directory = Join-Path $resources ('mipmap-' + $density[0])
        foreach ($asset in @(@('ic_launcher_foreground', $foreground, 108), @('ic_launcher', $square, 48), @('ic_launcher_round', $square, 48))) {
            $size = [int]($asset[2] * $density[1])
            $bitmap = New-Canvas $size
            $graphics = New-Graphics $bitmap
            $graphics.DrawImage($asset[1], 0, 0, $size, $size)
            $graphics.Dispose()
            Save-Png $bitmap (Join-Path $directory ($asset[0] + '.png'))
            $bitmap.Dispose()
            $oldAsset = Join-Path $directory ($asset[0] + '.webp')
            if (Test-Path -LiteralPath $oldAsset) { Remove-Item -LiteralPath $oldAsset }
        }
    }
    # Android's no-background splash uses a 288 dp canvas with a central 192 dp mask.
    # A transparent 128 dp portrait fits entirely inside it and matches the Compose splash.
    $splash = New-Canvas 1152
    $graphics = New-Graphics $splash
    $graphics.DrawImage($source, 320, 320, 512, 512)
    $graphics.Dispose()
    Save-Png $splash (Join-Path $resources 'drawable-xxxhdpi/ic_splash.png')
    $splash.Dispose()
    $splashPortrait = New-Canvas 512
    $graphics = New-Graphics $splashPortrait
    $graphics.DrawImage($source, 0, 0, 512, 512)
    $graphics.Dispose()
    Save-Png $splashPortrait (Join-Path $resources 'drawable-xxxhdpi/ic_splash_portrait.png')
    $splashPortrait.Dispose()

    # Keep themed-launcher artwork separate from the notification's small play symbol.
    $monochrome = New-Canvas 432
    for ($y = 0; $y -lt 432; $y++) {
        for ($x = 0; $x -lt 432; $x++) {
            $monochrome.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($foreground.GetPixel($x, $y).A, 255, 255, 255))
        }
    }
    Save-Png $monochrome (Join-Path $resources 'drawable-xxxhdpi/ic_launcher_themed.png')
    $monochrome.Dispose()
    Save-Png $square (Join-Path $repo 'fastlane/metadata/android/en-US/images/icon.png')
    Save-Png $square (Join-Path $repo 'website/public/images/icon.png')
} finally {
    $foreground.Dispose()
    $square.Dispose()
    $source.Dispose()
}
