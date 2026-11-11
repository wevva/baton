"""숏폼(세로 1080x1920, 약 30초) 합성: record_demo.sh 결과에서 장면 몇 개를 잘라 세로로 다시 배치한다.

사용: python make_short.py <raw 폴더> <출력.mp4> [--ffmpeg 경로] [--latency 0.5]
- 장면 시각은 scenes.tsv(장면 이름 앞부분으로 찾음), 음성은 make_video.py와 같은 방식으로 맞춘다.
- 화면은 에뮬레이터 녹화(1920x1080)에서 바통 앱 영역만 잘라 가운데에 둔다.
- 자막 글꼴: Windows 기본 '맑은 고딕'.
"""
import argparse, math, os, subprocess, sys, wave

import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("raw"); ap.add_argument("out")
ap.add_argument("--ffmpeg", default=r"C:\dev\tools\ffmpeg\ffmpeg.exe")
ap.add_argument("--latency", type=float, default=0.5)
ap.add_argument("--voice", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "test_audio", "receive_ko.wav"))
a = ap.parse_args()
R, F = a.raw, a.ffmpeg
work = os.path.join(R, "short"); os.makedirs(work, exist_ok=True)

a0 = float(open(os.path.join(R, "audio_start")).read())
v0 = float(open(os.path.join(R, "video_start")).read()) + a.latency
scenes = []
for line in open(os.path.join(R, "scenes.tsv"), encoding="utf-8"):
    t, name = line.rstrip("\n").split("\t")
    scenes.append((float(t) - v0, name))


def at(prefix):
    for t, n in scenes:
        if n.startswith(prefix):
            return t
    sys.exit(f"장면 없음: {prefix}")


VOICE = at("VOICE")
# (시작, 길이, 위 큰 문구, 아래 설명) — 영상 시각 기준
CUTS = [
    (at("운행 중"), 4.5, "어젯밤 켜졌다 꺼진 경고", "운행 중 타이어 공기압 경고가 켜졌다 꺼집니다"),
    (at("운전자가 ESC"), 3.0, "ESC도 끈 채로 주차", "차체 자세 제어를 끄고 내립니다"),
    (at("다음 운전자가"), 6.0, "아침에 타는 사람은 모릅니다", "그래서 바통이 탑승하자마자 알려 줍니다"),
    (VOICE - 1.5, 5.5, "\"받았어요\" 한마디면 끝", "손은 운전대에 — 수령 시각이 기록됩니다"),
    (at("ESC를 다시"), 4.5, "고치면 스스로 지웁니다", "차량 신호로 해결을 확인"),
]
HOOK, OUTRO = 3.0, 3.5


def run(args):
    r = subprocess.run([F, "-hide_banner", "-loglevel", "error", "-y"] + args)
    if r.returncode:
        sys.exit(r.returncode)


def esc(p):
    return p.replace("\\", "/").replace(":", "\\:")


def ts(sec):
    return f"0:{int(sec // 60):02d}:{sec % 60:05.2f}"


HEAD = """[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Big,Malgun Gothic,84,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,5,60,60,0,1
Style: Mid,Malgun Gothic,50,&H00C8D2DC,&H00C8D2DC,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,60,60,0,1
Style: Brand,Malgun Gothic,40,&H0080C8A0,&H0080C8A0,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,5,60,60,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def ass(path, lines, dur):
    with open(path, "w", encoding="utf-8-sig") as f:
        f.write(HEAD)
        for style, y, text in lines:
            f.write(f"Dialogue: 0,{ts(0)},{ts(dur)},{style},,0,0,0,,{{\\pos(540,{y})}}{text}\n")


# 1) 소리 정렬: 차량 음성(루프백 녹음)을 영상 시각에 맞추고, VOICE 0.5초 뒤 운전자 목소리(재현)를 얹은 전체 트랙
with wave.open(os.path.join(R, "audio.wav")) as w:
    pcm = np.frombuffer(w.readframes(w.getnframes()), "<i2")
peak = max(int(np.abs(pcm).max()), 1) / 32767
gain_db = min(40.0, -3.0 - 20 * math.log10(peak))
full = os.path.join(work, "full_audio.wav")
ms = int((VOICE + 0.5) * 1000)
run(["-ss", f"{v0 - a0:.3f}", "-i", os.path.join(R, "audio.wav"), "-i", a.voice,
     "-filter_complex",
     f"[0:a]volume={gain_db:.1f}dB,aresample=48000,aformat=channel_layouts=stereo[car];"
     f"[1:a]aresample=48000,aformat=channel_layouts=stereo,volume=6dB,adelay={ms}|{ms}[drv];"
     f"[car][drv]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.9[a]",
     "-map", "[a]", full])

V = ["-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30"]
A = ["-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2"]
BG = "0x101418"
# 바통 앱 영역 (녹화 1920x1080 기준): x 640~1900, y 70~970 → 가로 1080으로 축소(높이 771)
CROP = "crop=1260:900:640:70,scale=1080:771"
parts = []

# 2) 첫 카드
ass(os.path.join(work, "hook.ass"), [
    ("Big", 820, "어젯밤 꺼진 경고,"), ("Big", 940, "오늘 아침 운전자는"), ("Big", 1060, "어떻게 알까?"),
    ("Brand", 1700, "바통 (Baton)")], HOOK)
p = os.path.join(work, "p0.mp4"); parts.append(p)
run(["-f", "lavfi", "-i", f"color=c={BG}:s=1080x1920:d={HOOK}:r=30", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
     "-t", f"{HOOK}", "-vf", f"subtitles='{esc(os.path.join(work, 'hook.ass'))}'", *V, *A, p])

# 3) 장면들
for i, (start, dur, big, mid) in enumerate(CUTS, 1):
    sub = os.path.join(work, f"c{i}.ass")
    ass(sub, [("Big", 330, big), ("Mid", 1560, mid), ("Brand", 1780, "바통 · Pleos Connect 실제 동작")], dur)
    p = os.path.join(work, f"p{i}.mp4"); parts.append(p)
    run(["-ss", f"{start:.3f}", "-t", f"{dur:.3f}", "-i", os.path.join(R, "screen.mp4"),
         "-ss", f"{start:.3f}", "-t", f"{dur:.3f}", "-i", full,
         "-f", "lavfi", "-i", f"color=c={BG}:s=1080x1920:r=30",
         "-filter_complex",
         f"[0:v]{CROP}[app];[2:v][app]overlay=0:575:shortest=1,subtitles='{esc(sub)}'[v]",
         "-map", "[v]", "-map", "1:a", "-t", f"{dur:.3f}", *V, *A, p])

# 4) 끝 카드
ass(os.path.join(work, "outro.ass"), [
    ("Big", 780, "바통"), ("Mid", 900, "다음 운전자에게 보이지 않는 것만"),
    ("Mid", 980, "차 안에서 넘겨 줍니다"), ("Brand", 1150, "차량 조작 기능 없음 · 인터넷 없음 · 차 안 암호화 저장"),
    ("Brand", 1700, "바통랩")], OUTRO)
p = os.path.join(work, "p9.mp4"); parts.append(p)
run(["-f", "lavfi", "-i", f"color=c={BG}:s=1080x1920:d={OUTRO}:r=30", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
     "-t", f"{OUTRO}", "-vf", f"subtitles='{esc(os.path.join(work, 'outro.ass'))}'", *V, *A, p])

lst = os.path.join(work, "list.txt")
with open(lst, "w", encoding="utf-8") as f:
    for p in parts:
        f.write(f"file '{p.replace(chr(92), '/')}'\n")
run(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", a.out])
total = HOOK + OUTRO + sum(c[1] for c in CUTS)
print("완료:", a.out, f"({total:.1f}초)")
