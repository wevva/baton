"""시연 영상 합성: record_demo.sh 결과(화면·음성·장면 시각) → 자막·시작/끝 카드가 들어간 mp4

사용: python make_video.py <raw 폴더> <출력.mp4> [--ffmpeg 경로] [--latency 0.5]
- 화면은 adb screenrecord, 음성은 PC 스피커 루프백 녹음. 두 시작 시각 차이 + screenrecord 시작 지연(latency)으로 맞춘다.
- 자막 글꼴: Windows 기본 '맑은 고딕'.
"""
import argparse, math, os, subprocess, sys, wave

import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("raw"); ap.add_argument("out")
ap.add_argument("--ffmpeg", default=r"C:\dev\tools\ffmpeg\ffmpeg.exe")
ap.add_argument("--latency", type=float, default=0.5, help="screenrecord가 실제로 첫 프레임을 찍기까지 걸린 시간(초)")
ap.add_argument("--voice", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "test_audio", "receive_ko.wav"),
                help="VOICE 표시 시각에 얹을 운전자 목소리(재현). 음성 인식에는 같은 소리를 직접 넣었다")
a = ap.parse_args()
R = a.raw
F = a.ffmpeg

a0 = float(open(os.path.join(R, "audio_start")).read())
v0 = float(open(os.path.join(R, "video_start")).read()) + a.latency
scenes = []
for line in open(os.path.join(R, "scenes.tsv"), encoding="utf-8"):
    t, name = line.rstrip("\n").split("\t")
    scenes.append((float(t) - v0, name))
end = [t for t, n in scenes if n == "END"][0] + 1.5
voice_at = [t for t, n in scenes if n == "VOICE"]
scenes = [(t, n) for t, n in scenes if n not in ("END", "VOICE")]

TITLE, OUTRO = 5.0, 7.0


def ts(sec):
    sec = max(0.0, sec)
    h, m = int(sec // 3600), int(sec % 3600 // 60)
    return f"{h}:{m:02d}:{sec % 60:05.2f}"


HEAD = """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Cap,Malgun Gothic,44,&H00FFFFFF,&H00FFFFFF,&H30705A1E,&H30705A1E,1,0,0,0,100,100,0,0,3,20,0,2,680,60,150,1
Style: Title,Malgun Gothic,96,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,5,80,80,0,1
Style: Sub,Malgun Gothic,44,&H00C8D2DC,&H00C8D2DC,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,80,80,0,1
Style: Small,Malgun Gothic,34,&H0080C8A0,&H0080C8A0,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,80,80,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def write_ass(path, events):
    with open(path, "w", encoding="utf-8-sig") as f:
        f.write(HEAD)
        for s, e, style, text in events:
            f.write(f"Dialogue: 0,{ts(s)},{ts(e)},{style},,0,0,0,,{text}\n")


work = os.path.join(R, "build"); os.makedirs(work, exist_ok=True)
cap = []
for i, (t, n) in enumerate(scenes):
    nxt = scenes[i + 1][0] if i + 1 < len(scenes) else end
    cap.append((t + 0.2, nxt - 0.1, "Cap", n))
write_ass(os.path.join(work, "cap.ass"), cap)
write_ass(os.path.join(work, "title.ass"), [
    (0, TITLE, "Title", "{\\pos(960,420)}바통 (Baton)"),
    (0, TITLE, "Sub", "{\\pos(960,560)}계기판에 남지 않는 지난 운행의 경고와 바뀐 설정을\\N차를 넘겨받는 다음 운전자에게 차 안에서 알려 줍니다"),
    (0, TITLE, "Small", "{\\pos(960,760)}Pleos Connect 에뮬레이터 실제 동작 · 차량 음성 안내 포함"),
])
write_ass(os.path.join(work, "outro.ass"), [
    (0, OUTRO, "Title", "{\\pos(960,330)}바통"),
    (0, OUTRO, "Sub", "{\\pos(960,500)}지난 운행 중 켜졌다 꺼진 경고 · 바뀐 안전 설정 · 소모품 · 에너지"),
    (0, OUTRO, "Sub", "{\\pos(960,590)}차량 신호로 해결을 확인하면 스스로 지웁니다"),
    (0, OUTRO, "Small", "{\\pos(960,740)}차량 조작 기능 없음 · 인터넷 권한 없음 · 차량 안 암호화 저장"),
    (0, OUTRO, "Small", "{\\pos(960,810)}바통랩"),
])


def run(args):
    print("+", " ".join(args[:6]), "...")
    r = subprocess.run([F, "-hide_banner", "-loglevel", "error", "-y"] + args)
    if r.returncode:
        sys.exit(r.returncode)


def esc(p):  # ffmpeg 필터 안 경로 (Windows 드라이브 콜론 이스케이프)
    return p.replace("\\", "/").replace(":", "\\:")


# 녹음 대부분이 무음이라 평균 기준 정규화(loudnorm)로는 거의 안 커진다 → 최대 음량을 -3 dBFS로 맞추는 고정 이득
with wave.open(os.path.join(R, "audio.wav")) as w:
    pcm = np.frombuffer(w.readframes(w.getnframes()), "<i2")
peak = max(int(np.abs(pcm).max()), 1) / 32767
gain_db = min(40.0, -3.0 - 20 * math.log10(peak))
print(f"음성 최대 {20 * math.log10(peak):.1f} dBFS → +{gain_db:.1f} dB")

V = ["-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30"]
A = ["-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2"]
# 1) 본편: 화면 0~end, 음성은 (v0 - a0)만큼 앞을 잘라 맞춤, 음량 정규화, 자막
#    VOICE 시각이 있으면 운전자 목소리(재현)를 0.5초 뒤에 얹는다 (인식기에 넣은 소리와 같은 파일, 앞 무음 0.5초)
inputs = ["-i", os.path.join(R, "screen.mp4"), "-ss", f"{v0 - a0:.3f}", "-i", os.path.join(R, "audio.wav")]
af = f"[1:a]volume={gain_db:.1f}dB,aresample=48000,aformat=channel_layouts=stereo[car]"
if voice_at and os.path.exists(a.voice):
    inputs += ["-i", a.voice]
    ms = int((voice_at[0] + 0.5) * 1000)
    af += f";[2:a]aresample=48000,aformat=channel_layouts=stereo,volume=6dB,adelay={ms}|{ms}[drv];[car][drv]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.9[a]"
else:
    af += ";[car]alimiter=limit=0.9[a]"
run(inputs + ["-t", f"{end:.3f}", "-map", "0:v", "-map", "[a]",
     "-vf", f"scale=1920:1080,subtitles='{esc(os.path.join(work, 'cap.ass'))}'",
     "-filter_complex", af, *V, *A, os.path.join(work, "main.mp4")])
# 2) 시작·끝 카드
for name, dur in (("title", TITLE), ("outro", OUTRO)):
    run(["-f", "lavfi", "-i", f"color=c=0x101418:s=1920x1080:d={dur}:r=30",
         "-f", "lavfi", "-i", f"anullsrc=r=48000:cl=stereo", "-t", f"{dur}",
         "-vf", f"subtitles='{esc(os.path.join(work, name + '.ass'))}'", *V, *A, os.path.join(work, name + ".mp4")])
# 3) 이어 붙이기
lst = os.path.join(work, "list.txt")
with open(lst, "w", encoding="utf-8") as f:
    for name in ("title", "main", "outro"):
        f.write(f"file '{os.path.join(work, name + '.mp4').replace(chr(92), '/')}'\n")
run(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", a.out])
print("완료:", a.out, f"(본편 {end:.0f}초 + 카드 {TITLE + OUTRO:.0f}초)")
