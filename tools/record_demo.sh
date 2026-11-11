#!/usr/bin/env bash
# 시연 영상 녹화: 에뮬레이터 화면(adb screenrecord) + PC 스피커 음성(루프백) + 장면 자막 시각 기록
# 사용: bash tools/record_demo.sh <출력폴더>   (Pleos 에뮬레이터 + Virtual Console 필요, ffmpeg로 합성은 tools/make_video.sh)
set -u
OUT="${1:?출력 폴더}"; mkdir -p "$OUT"
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
HERE="$(cd "$(dirname "$0")" && pwd)"
q() { bash "$HERE/vc.sh" "$@" >/dev/null; }
now() { date +%s.%N; }
tap() {
  timeout 60 "$ADB" shell uiautomator dump /sdcard/u.xml >/dev/null
  local b; b=$(timeout 30 "$ADB" shell cat /sdcard/u.xml | grep -o "text=\"[^\"]*$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b; timeout 30 "$ADB" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
SCENES="$OUT/scenes.tsv"; : > "$SCENES"
scene() { echo -e "$(now)\t$1" >> "$SCENES"; }

# 깨끗한 상태에서 시작
q lamps_off; q gear Drive; q seat driver true; q seat passenger false; q soc 62
timeout 30 "$ADB" shell am force-stop com.baton.app
timeout 30 "$ADB" shell pm clear --user 10 com.baton.app >/dev/null
sleep 6
timeout 30 "$ADB" shell am start --user 10 -n com.baton.app/.MainActivity >/dev/null
sleep 15

# 녹음 먼저, 녹화 다음 (두 시작 시각을 기록해 합성 때 맞춤)
python "$(cygpath -w "$HERE/rec_audio.py")" "$OUT/audio.wav" 175 > "$OUT/audio.log" 2>&1 &
AP=$!
echo "$(now)" > "$OUT/audio_start"
sleep 0.5
timeout 190 "$ADB" shell screenrecord --time-limit 170 --size 1920x1080 --bit-rate 10000000 /sdcard/baton_final.mp4 &
VP=$!
echo "$(now)" > "$OUT/video_start"
sleep 3

scene "운행 중 — 타이어 공기압 경고가 켜졌다 꺼집니다"
q lamp TPMS On; sleep 3; q lamp TPMS Off; sleep 3
scene "한 번 더 — 경고는 꺼졌지만 바통은 횟수를 기억합니다"
q lamp TPMS On; sleep 3; q lamp TPMS Off; sleep 3
scene "눈길에서 ESC(차체 자세 제어)를 끈 채 휴게소에 주차합니다"
q lamp ESCOff On; sleep 3; q gear Park; sleep 2
scene "내리기 전 화면: 다음 사람에게 넘어갈 것을 미리 보여 줍니다"
sleep 7
scene "시동을 켠 채 운전 교대 — 운전석이 비면 인계를 저장합니다"
q seat driver false; sleep 4; q soc 58; sleep 4
# 음성 수령 장면: 운전자 목소리 대신 "받았어요" 녹음을 음성 인식에 넣는다(디버그 빌드 전용 주입). 영상에는 같은 소리를 합성한다
PCM="$HERE/../build/test_audio/receive_ko.pcm"
[ -f "$PCM" ] || powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$HERE/make_test_audio.ps1")" >/dev/null
timeout 30 "$ADB" push "$(cygpath -w "$PCM")" /data/local/tmp/receive_ko.pcm >/dev/null
timeout 30 "$ADB" shell "run-as com.baton.app --user 10 cp /data/local/tmp/receive_ko.pcm files/stt_inject.pcm"
timeout 30 "$ADB" logcat -c
scene "다음 운전자가 탑니다 — 화면과 음성으로 한 번 안내합니다"
q seat driver true
for _ in $(seq 1 40); do timeout 10 "$ADB" logcat -d -s BatonListen:* | grep -q "듣기 시작" && break; sleep 0.5; done
echo -e "$(now)	VOICE" >> "$SCENES"
scene "안내가 끝나면 \"받았어요\" 한마디로 수령 기록 — 손은 운전대에 (출발 전 검토·서명, DVIR 원칙 · 운전자 목소리는 재현)"
sleep 8
scene "ESC를 다시 켜면 차량 신호로 해결을 확인하고 스스로 지웁니다"
q lamp ESCOff Off; sleep 9
scene "받는 사람의 언어로 — 일본어 (한국어·영어·일본어·중국어)"
tap "JA"; sleep 6; tap "KO"; sleep 3
scene "안전 항목을 남긴 채 출발하면 한 번만 음성으로 알립니다"
q gear Drive; sleep 14
scene "주행 중에는 Pleos가 앱 화면을 막습니다 — 바통은 출발할 때 음성으로 한 번만 알립니다"
sleep 6
echo -e "$(now)\tEND" >> "$SCENES"

wait $VP 2>/dev/null
timeout 120 "$ADB" pull /sdcard/baton_final.mp4 "$OUT/screen.mp4" | tail -1
wait $AP 2>/dev/null; cat "$OUT/audio.log"
q gear Park
echo "녹화 완료: $OUT"
