# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Cr512-netizen
#
# Compares two presented frames: normalised luma MAE and mean horizontal luma gradient
# (a detail proxy). The candidate is usually an upscaled frame and the reference is a native
# 100% frame of the same frozen scene.
param(
    [Parameter(Mandatory = $true)][string]$Reference,
    [Parameter(Mandatory = $true)][string]$Candidate
)

Add-Type -AssemblyName System.Drawing

function Get-Luma([System.Drawing.Color]$color) {
    return 0.299 * $color.R + 0.587 * $color.G + 0.114 * $color.B
}

$referenceBitmap = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Reference))
$candidateBitmap = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Candidate))
try {
    if ($referenceBitmap.Width -ne $candidateBitmap.Width -or
        $referenceBitmap.Height -ne $candidateBitmap.Height) {
        throw "image size mismatch: $($referenceBitmap.Width)x$($referenceBitmap.Height) vs $($candidateBitmap.Width)x$($candidateBitmap.Height)"
    }

    $sum = 0.0
    $count = 0
    $detailSum = 0.0
    $detailCount = 0
    $referenceDetailSum = 0.0
    $referenceDetailCount = 0
    $bottomSum = 0.0
    $bottomCount = 0
    $bottomStart = [int]($candidateBitmap.Height * 0.35)
    for ($y = 0; $y -lt $candidateBitmap.Height; $y += 2) {
        $previous = Get-Luma $candidateBitmap.GetPixel(0, $y)
        $referencePrevious = Get-Luma $referenceBitmap.GetPixel(0, $y)
        for ($x = 2; $x -lt $candidateBitmap.Width; $x += 2) {
            $luma = Get-Luma $candidateBitmap.GetPixel($x, $y)
            $referenceLuma = Get-Luma $referenceBitmap.GetPixel($x, $y)
            $detailSum += [Math]::Abs($luma - $previous)
            $referenceDetailSum += [Math]::Abs($referenceLuma - $referencePrevious)
            $detailCount++
            $referenceDetailCount++
            $previous = $luma
            $referencePrevious = $referenceLuma
        }
        for ($x = 0; $x -lt $candidateBitmap.Width; $x += 2) {
            $difference = [Math]::Abs((Get-Luma $referenceBitmap.GetPixel($x, $y)) -
                    (Get-Luma $candidateBitmap.GetPixel($x, $y)))
            $sum += $difference
            $count++
            if ($y -ge $bottomStart) {
                $bottomSum += $difference
                $bottomCount++
            }
        }
    }

    $mae = if ($count -eq 0) { 0 } else { $sum / ($count * 255.0) }
    $maeBottom = if ($bottomCount -eq 0) { 0 } else { $bottomSum / ($bottomCount * 255.0) }
    $detail = if ($detailCount -eq 0) { 0 } else { $detailSum / ($detailCount * 255.0) }
    $referenceDetail = if ($referenceDetailCount -eq 0) { 0 } else {
        $referenceDetailSum / ($referenceDetailCount * 255.0)
    }
    [pscustomobject]@{
        reference = (Resolve-Path $Reference).Path
        candidate = (Resolve-Path $Candidate).Path
        width = $candidateBitmap.Width
        height = $candidateBitmap.Height
        mae = [Math]::Round($mae, 5)
        maeBelow35Percent = [Math]::Round($maeBottom, 5)
        referenceDetail = [Math]::Round($referenceDetail, 5)
        candidateDetail = [Math]::Round($detail, 5)
        detailRatio = if ($referenceDetail -eq 0) { 0 } else {
            [Math]::Round($detail / $referenceDetail, 3)
        }
    } | ConvertTo-Json
} finally {
    $referenceBitmap.Dispose()
    $candidateBitmap.Dispose()
}
