# Downloads the depth estimation model that "照片轉空間桌布" needs (not kept in git: 99 MB).
# Depth Anything V2 Small, ONNX export by onnx-community, Apache-2.0.
$ErrorActionPreference = 'Stop'
$url = 'https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx'
$dir = Join-Path $PSScriptRoot '..\app\src\main\assets\models'
$dest = Join-Path $dir 'depth_anything_v2_small.onnx'
$expected = 99060839

New-Item -ItemType Directory -Force $dir | Out-Null
if ((Test-Path $dest) -and (Get-Item $dest).Length -eq $expected) {
    "Model already present: $dest"
    exit 0
}
curl.exe -L --fail --silent --show-error -o $dest $url
$size = (Get-Item $dest).Length
if ($size -ne $expected) { throw "Unexpected model size $size (expected $expected)" }
"Downloaded $dest"
