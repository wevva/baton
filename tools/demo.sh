#!/usr/bin/env bash
# 바통 시연 시나리오 — 발표·영상 촬영용. 에뮬레이터 + Virtual Console(adb forward tcp:8088) 필요.
# 사용: bash tools/demo.sh [쉬는 시간(초), 기본 4]
#  1) 주행 → 주차   2) 운전자가 문 안 잠그고·창문 열고·실내등 켠 채 하차
#  3) 다음 운전자 탑승 → 인계 안내(화면+음성)   4) 하나씩 해결 → 자동 소거   5) 출발
set -e
P=${1:-4}
DIR="$(cd "$(dirname "$0")" && pwd)"
vc() { bash "$DIR/vc.sh" "$@" >/dev/null; }
say() { printf '\n▶ %s\n' "$1"; }

say "준비: 주행 중, 운전자 탑승, 모든 상태 정상, 배터리 62%"
vc gear Drive; vc seat driver true; vc lock true; vc window_rl 0; vc cabin false; vc soc 62; sleep "$P"

say "1. 주차 (P단)"
vc gear Park; sleep "$P"

say "2. 첫 운전자가 '아직 끝나지 않은 일'을 남기고 하차 — 뒷좌석 창문 40%, 실내등 켬, 문 안 잠금"
vc window_rl 40; vc cabin true; vc lock false; sleep "$P"
vc seat driver false; sleep "$P"

say "   (시간 경과, 배터리 자연 방전 62% → 58%)"
vc soc 58; sleep 2

say "3. 다음 운전자 탑승 → 바통이 이어받은 일을 화면과 음성으로 안내"
vc seat driver true; sleep $((P * 3))

say "4. 하나씩 해결 → 차량 신호로 확인되는 순간 스스로 지움"
vc window_rl 0; sleep "$P"
vc cabin false; sleep "$P"
vc lock true; sleep "$P"

say "5. 출발 → 인계는 이력으로 보관"
vc gear Drive; sleep "$P"
say "끝"
