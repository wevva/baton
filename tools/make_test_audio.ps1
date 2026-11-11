# E2E용 "받았어요" 음성(16kHz·16bit·모노 PCM)을 Windows 내장 한국어 음성(Microsoft Heami)으로 만든다.
# 생성물은 저장소에 넣지 않는다(.gitignore). 사용: powershell -File tools/make_test_audio.ps1 <출력 폴더>
param([string]$Out = "$PSScriptRoot\..\build\test_audio")
New-Item -ItemType Directory -Force $Out | Out-Null
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice('Microsoft Heami Desktop')
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$wav = Join-Path $Out 'receive_ko.wav'
$s.SetOutputToWaveFile($wav, $fmt); $s.Speak('받았어요'); $s.SetOutputToNull(); $s.Dispose()
# WAV 머리(44바이트)를 떼고 앞 0.5초·뒤 1.5초 무음을 붙인다 (인식기 끝점 검출용)
$b = [IO.File]::ReadAllBytes($wav)
$pcm = New-Object byte[] (16000 + ($b.Length - 44) + 48000)
[Array]::Copy($b, 44, $pcm, 16000, $b.Length - 44)
[IO.File]::WriteAllBytes((Join-Path $Out 'receive_ko.pcm'), $pcm)
Write-Output (Join-Path $Out 'receive_ko.pcm')
