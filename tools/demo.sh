#!/usr/bin/env bash
# 바통 시연 시나리오 — 발표·영상 촬영용. 에뮬레이터 + Virtual Console(adb forward tcp:8088) 필요.
# 사용: bash tools/demo.sh [쉬는 시간(초), 기본 4]
#  1) 운행 중 타이어 공기압 경고가 두 번 켜졌다 꺼짐 (운전자는 대수롭지 않게 넘김)
#  2) 운전자가 ESC(차체 자세 제어)를 끈 채 주차하고 하차
#  3) 다음 운전자 탑승 → 바통이 '보이지 않는 것'을 화면+음성으로 안내 (이미 꺼진 경고라 계기판엔 안 보임)
#  4) 다음 운전자가 ESC를 다시 켬 → 차량 신호로 확인되는 순간 "해제 확인"
#  5) '받았어요'(수령증)는 화면에서 직접 누름 → 출발
set -e
P=${1:-4}
DIR="$(cd "$(dirname "$0")" && pwd)"
vc() { bash "$DIR/vc.sh" "$@" >/dev/null; }
say() { printf '\n▶ %s\n' "$1"; }

say "준비: 주행 중, 운전자 탑승, 경고등 모두 꺼짐, 배터리 62%"
vc lamps_off; vc gear Drive; vc seat driver true; vc seat passenger false; vc soc 62; sleep "$P"

say "1. 운행 중 타이어 공기압 경고가 켜졌다 꺼짐 (두 번)"
vc lamp TPMS On; sleep 2; vc lamp TPMS Off; sleep 2
vc lamp TPMS On; sleep 2; vc lamp TPMS Off; sleep "$P"

say "2. 운전자가 ESC를 끈 채 주차하고 하차"
vc lamp ESCOff On; sleep 2
vc gear Park; sleep "$P"
vc seat driver false; sleep "$P"

say "   (시간 경과, 배터리 62% → 58%)"
vc soc 58; sleep 2

say "3. 다음 운전자 탑승 → 바통이 계기판에 남지 않은 것까지 안내"
vc seat driver true; sleep $((P * 3))

say "4. 다음 운전자가 ESC를 다시 켬 → 신호로 확인되는 순간 '해제 확인'"
vc lamp ESCOff Off; sleep "$P"

say "5. (화면에서 '받았어요' 누르기) → 출발"
sleep "$P"
vc gear Drive; sleep "$P"
say "끝"
