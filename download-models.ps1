# Downloads the free local speech models into .\models (several hundred MB total).
# Run from the debate-checker folder:   powershell -ExecutionPolicy Bypass -File .\download-models.ps1
# Uses curl.exe and tar.exe, both built into Windows 10/11.

$ErrorActionPreference = "Stop"
$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
New-Item -ItemType Directory -Force -Path models | Out-Null
Set-Location models

function Get-File($url, $name) {
    if (Test-Path $name) { Write-Host "already have $name"; return }
    Write-Host "downloading $name ..."
    curl.exe -L --fail -o $name $url
}

# Voice activity detection (~2 MB)
Get-File "$base/asr-models/silero_vad.onnx" "silero_vad.onnx"

# Speaker fingerprints: NVIDIA TitaNet-small (~39 MB). Chosen after testing 7 free models;
# it separated different voices far better than the CAM++ model used at first.
Get-File "$base/speaker-recongition-models/nemo_en_titanet_small.onnx" "nemo_en_titanet_small.onnx"

# Speech-to-text: NVIDIA Parakeet TDT 0.6B v2, English, int8 (several hundred MB)
$asr = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
if (-not (Test-Path $asr)) {
    Get-File "$base/asr-models/$asr.tar.bz2" "$asr.tar.bz2"
    Write-Host "extracting $asr ..."
    tar.exe -xjf "$asr.tar.bz2"
    Remove-Item "$asr.tar.bz2"
} else {
    Write-Host "already have $asr"
}

Set-Location ..
Write-Host ""
Write-Host "Done. Models are in $(Resolve-Path models)"
Get-ChildItem models -Recurse -Include *.onnx, tokens.txt | Select-Object FullName, @{n="MB";e={[math]::Round($_.Length/1MB,1)}}
