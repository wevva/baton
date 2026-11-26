#!/usr/bin/env bash
# 바통 E2E 테스트 — 에뮬레이터 + Virtual Console 로 실제 신호(기어·착석·경고등·배터리)를 넣고, 앱 로그로 합격/불합격을 판정한다.
# 전제: 디버그 APK 설치(로그가 나와야 함), adb forward tcp:8088 tcp:8088, 앱 언어 한국어.
# 사용: bash tools/e2e.sh            (전체)
#       bash tools/e2e.sh E2E-03     (하나만)
# 케이스 ID는 docs/TEST_CASES.md 와 같다.
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
ADB_BIN="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
adb_t() { timeout 60 "$ADB_BIN" "$@"; }
ADB=adb_t
PKG=com.baton.app
ONLY="$1"
PASS=0; FAIL=0; FAILED=()

vc()   { bash "$DIR/vc.sh" "$@" >/dev/null; }
logs() { $ADB logcat -d -s "Baton:*" "BatonStore:*" | sed -E 's/^.{31}//'; }
clear_logs() { $ADB logcat -c; }
ui_text() { $ADB shell uiautomator dump /sdcard/u.xml >/dev/null; $ADB shell cat /sdcard/u.xml | grep -oE 'text="[^"]*"' | tr '\n' ' '; }

# 앱을 깨끗한 상태(저장 인계 없음)로 시작. 차량은 '주행 중, 운전자 착석, 경고등 모두 꺼짐'
fresh_start() {
  $ADB shell am force-stop $PKG
  $ADB shell "run-as $PKG --user 10 rm -f files/handover.bin" 2>/dev/null
  vc lamps_off; vc gear Drive; vc seat driver true; vc seat passenger false; vc soc 62
  # 콘솔이 '꺼짐'을 차량에 반영하는 데 몇 초 걸린다 — 앞 테스트의 경고등이 이번 운행으로 섞이지 않게 기다린다
  sleep 6
  clear_logs
  $ADB shell am start -n $PKG/.MainActivity >/dev/null
  wait_ready
}
# 앱이 차량 구성 인식을 마칠 때까지 기다림 (최대 30초) — 준비 전에 신호를 넣으면 놓칠 수 있다
wait_ready() {
  for _ in $(seq 1 30); do
    # 신호 구독 완료 후 시작 보호 구간(3초: 점등 점검·오래된 값 무시)이 지나야 경고등 변화를 센다
    $ADB logcat -d -s "BatonSignals:*" | grep -q "신호 구독 완료" && { sleep 4; return 0; }
    sleep 1
  done
  echo "      (경고: 준비 로그를 30초 안에 못 봄)"
}
park_and_exit() { vc gear Park; sleep 2; vc seat driver false; sleep 4; }
board() { vc seat driver true; sleep 4; }

expect_log() { # $1=설명 $2=정규식
  if logs | grep -qE "$2"; then return 0; fi
  echo "      ✗ 기대한 로그 없음: $1  (/$2/)"; return 1
}
expect_no_log() {
  if logs | grep -qE "$2"; then echo "      ✗ 나오면 안 되는 로그: $1  (/$2/)"; return 1; fi
  return 0
}
expect_ui() {
  if ui_text | grep -qF "$2"; then return 0; fi
  echo "      ✗ 화면에 없음: $1  ('$2')"; return 1
}
tap_text() {
  $ADB shell uiautomator dump /sdcard/u.xml >/dev/null
  # 부분 일치 (예: "…켜져 있어요" 뒤에 "(지난 운행 중 1번)"이 붙어도 찾음)
  local b; b=$($ADB shell cat /sdcard/u.xml | grep -o "text=\"[^\"]*$1[^\"]*\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [ -z "$b" ] && return 1
  set -- $b; $ADB shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}

run() { # $1=ID $2=설명 $3=함수
  [ -n "$ONLY" ] && [ "$ONLY" != "$1" ] && return
  printf '▶ %s %s\n' "$1" "$2"
  if "$3"; then PASS=$((PASS+1)); echo "   ✓ 통과"; else
    FAIL=$((FAIL+1)); FAILED+=("$1"); echo "   ✗ 실패 — 앱 로그:"
    $ADB logcat -d -s "Baton:*" "BatonSignals:*" | sed -E 's/^.{19}//' | grep -v beginning | tail -14 | sed 's/^/        /'
  fi
}

# ── 케이스 ─────────────────────────────────────────────────────

t01() { # 기본 흐름: 주행 중 타이어 경고 2번 + ESC 끈 채 주차 → 다음 운전자 안내 → ESC 다시 켜면 해제 확인 → 출발
  fresh_start
  vc lamp TPMS On; sleep 1; vc lamp TPMS Off; sleep 1; vc lamp TPMS On; sleep 1; vc lamp TPMS Off; sleep 1
  vc lamp ESCOff On; sleep 1
  park_and_exit; board
  vc lamp ESCOff Off; sleep 3
  vc gear Drive; sleep 2
  expect_log "하차 시 2건 기록" "상태: Parked → Away \(남은 일=2\)" &&
  expect_log "탑승 안내" "안내: 바통입니다. 출발 전에 알아 두실 것이 2가지" &&
  expect_log "지난 운행 타이어 경고 2번" "지난 운행 중 타이어 공기압 경고등이 2번" &&
  expect_log "ESC 해제 확인" "안내: 차체 자세 제어\(ESC\) 꺼짐 해제 확인" &&
  expect_log "출발" "상태: Boarded → Driving"
}

t02() { # 아무 문제 없는 운행
  fresh_start
  park_and_exit; board
  expect_log "빈 인계" "상태: Parked → Away \(남은 일=0\)" &&
  expect_log "넘겨받을 것 없음 안내" "안내: 바통입니다. 지난 운행에서 넘겨받을 것이 없어요"
}

t03() { # 동승자가 남은 채 운전 교대
  fresh_start
  vc seat passenger true; vc lamp TPMS On; sleep 1; vc lamp TPMS Off; sleep 1
  park_and_exit
  expect_log "동승자 있어도 하차 판정" "상태: Parked → Away \(남은 일=1\)"
}

t04() { # 센서 흔들림
  fresh_start
  vc gear Park; sleep 2; vc seat driver false; vc seat driver true; sleep 4
  expect_no_log "흔들림은 하차 아님" "상태: Parked → Away"
}

t05() { # 다음 운전자가 탄 뒤 켜진 경고는 인계에 넣지 않음
  fresh_start
  park_and_exit; board
  vc lamp ABS On; sleep 3
  expect_log "빈 인계로 탑승" "상태: Away → Boarded \(남은 일=0\)" &&
  ! ui_text | grep -qF "ABS" || { echo "      ✗ 탑승 후 켜진 경고가 인계에 들어감"; return 1; }
}

t06() { # 앱 강제 종료 후 복원
  fresh_start
  vc lamp ESCOff On; sleep 1
  park_and_exit
  $ADB shell am force-stop $PKG; sleep 1; clear_logs
  $ADB shell am start -n $PKG/.MainActivity >/dev/null; wait_ready
  board
  expect_log "복원" "복원: Away" &&
  expect_log "복원 후 탑승 인계" "상태: Away → Boarded \(남은 일=1\)"
}

t07() { # 차를 떠나 있는 동안 12V 배터리 경고 발생 → 추가
  fresh_start
  park_and_exit
  vc lamp LowVoltBattery On Red; sleep 3
  board
  expect_log "12V 경고 포함 인계" "상태: Away → Boarded \(남은 일=1\)" &&
  expect_log "12V 음성" "12V 배터리 경고등이 켜져 있어요"
}

t08() { # 지금 켜진 안전 경고를 남긴 채 출발 → 1회 경고
  fresh_start
  vc lamp TPMS On; sleep 1
  park_and_exit; board
  vc gear Drive; sleep 3
  expect_log "출발 경고" "안내: 안전 항목이 1건 남아 있어요" &&
  [ "$(logs | grep -c '안전 항목이')" = "1" ] || { echo "      ✗ 경고가 1회가 아님"; return 1; }
}

t09() { # 탔다가 다시 내림 → 같은 인계 유지
  fresh_start
  vc lamp ESCOff On; sleep 1
  park_and_exit; board
  vc seat driver false; sleep 4
  expect_log "다시 대기" "상태: Boarded → Away \(남은 일=1\)"
}

t10() { # 저장 파일 변조 → 크래시 없이 무시
  fresh_start
  vc lamp ESCOff On; sleep 1
  park_and_exit
  $ADB shell "run-as $PKG --user 10 sh -c 'printf Z | dd of=files/handover.bin bs=1 seek=40 conv=notrunc 2>/dev/null'"
  $ADB shell am force-stop $PKG; $ADB logcat -c -b crash; clear_logs
  $ADB shell am start -n $PKG/.MainActivity >/dev/null; wait_ready
  expect_no_log "변조된 인계를 복원하지 않음" "복원: Away" &&
  [ "$($ADB logcat -d -b crash | grep -c $PKG)" = "0" ] || { echo "      ✗ 크래시 발생"; return 1; }
}

t11() { # 주행 중 화면은 한 줄
  fresh_start
  expect_ui "주행 중 표시" "주행 중" &&
  ! ui_text | grep -qF "이력" || { echo "      ✗ 주행 중에 이력 버튼이 보임"; return 1; }
}

t12() { # 운전 습관 신호(안전벨트 경고)는 기록하지 않음
  fresh_start
  vc lamp SeatBelt On Red; sleep 1; vc lamp SeatBelt Off; sleep 1
  park_and_exit
  expect_log "습관 신호 미기록" "상태: Parked → Away \(남은 일=0\)"
}

t13() { # 탔다 내렸다 다시 타도 요약 반복 없음
  fresh_start
  vc lamp ESCOff On; sleep 1
  park_and_exit; board
  vc seat driver false; sleep 4; board
  [ "$(logs | grep -c '안내: 바통입니다')" = "1" ] || { echo "      ✗ 요약 안내 횟수: $(logs | grep -c '안내: 바통입니다')"; return 1; }
}

t14() { # 지금 켜진 안전 경고는 눌러도 숨겨지지 않음
  fresh_start
  vc lamp TPMS On; sleep 1
  park_and_exit; board
  tap_text "타이어 공기압 경고등이 켜져 있어요" || { echo "      ✗ 안전 경고 카드를 찾지 못함"; return 1; }
  sleep 1
  expect_ui "안전 경고가 그대로 보임" "타이어 공기압 경고등이 켜져 있어요" &&
  ! ui_text | grep -qF "되돌리기" || { echo "      ✗ 안전 경고가 확인 처리됨"; return 1; }
}

t15() { # 홈으로 나가도 감지 계속 (앱을 열지 않아도 동작)
  fresh_start
  $ADB shell input keyevent KEYCODE_HOME; sleep 2
  vc lamp ABS On; sleep 1; vc lamp ABS Off; sleep 1
  park_and_exit; board
  expect_log "백그라운드 하차 기록" "상태: Parked → Away \(남은 일=1\)" &&
  expect_log "백그라운드 탑승 안내" "안내: 바통입니다. 출발 전에 알아 두실 것이 1가지"
}

t16() { # 보안: 외부(셸)에서 서비스·수신기 강제 호출 불가
  local out1 out2
  out1=$($ADB shell am start-foreground-service -n $PKG/.HandoverService 2>&1)
  out2=$($ADB shell am broadcast -n $PKG/.BootReceiver -a android.intent.action.BOOT_COMPLETED 2>&1)
  echo "$out1" | grep -qiE "security|not exported|denied|requires" || { echo "      ✗ 서비스가 외부에서 시작됨: $out1"; return 1; }
  echo "$out2" | grep -qiE "security|not exported|denied|protected|requires|result=0" || { echo "      ✗ 수신기 응답: $out2"; return 1; }
}

t17() { # 지난 운행 기록(꺼진 경고)은 확인 처리로 내릴 수 있다
  fresh_start
  vc lamp ABS On; sleep 1; vc lamp ABS Off; sleep 1
  park_and_exit; board
  tap_text "지난 운행 중 ABS 경고등이 1번 켜졌어요 — 출발 전에 점검하세요" || { echo "      ✗ 카드를 찾지 못함"; return 1; }
  sleep 1
  expect_ui "되돌리기 표시" "되돌리기"
}

t18() { # 음성 수령: 탑승 안내가 끝나면 8초만 듣고 "받았어요"로 수령 기록 (마이크 대신 녹음 파일 주입 — 디버그 빌드 전용)
  local pcm="$DIR/../build/test_audio/receive_ko.pcm"
  [ -f "$pcm" ] || powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$DIR/make_test_audio.ps1")" >/dev/null
  fresh_start
  vc lamp TPMS On; sleep 1; vc lamp TPMS Off; sleep 1
  park_and_exit
  $ADB push "$(cygpath -w "$pcm")" /data/local/tmp/receive_ko.pcm >/dev/null
  $ADB shell "run-as $PKG --user 10 cp /data/local/tmp/receive_ko.pcm files/stt_inject.pcm"
  board
  # 탑승 안내(약 12초) → 듣기 → 인식까지 걸리는 시간이 매번 달라 최대 40초까지 기다린다
  for _ in $(seq 1 40); do logs | grep -q "음성으로 수령" && break; sleep 1; done
  sleep 2
  expect_log "안내 끝에 음성 수령 권유" "받으셨으면 \"받았어요\"라고 말씀해 주세요" &&
  expect_log "음성으로 수령 기록" "음성으로 수령" &&
  expect_ui "수령 시각 표시" "수령 확인"
}

t19() { # 아무 말도 없으면 8초 뒤 듣기를 끝내고 버튼은 그대로 (수령으로 치지 않음)
  fresh_start
  $ADB shell "run-as $PKG --user 10 rm -f files/stt_inject.pcm"
  vc lamp TPMS On; sleep 1; vc lamp TPMS Off; sleep 1
  park_and_exit; board; sleep 26
  expect_no_log "말이 없으면 수령 아님" "음성으로 수령" &&
  expect_ui "받았어요 버튼 유지" "받았어요"
}

run E2E-01 "기본: 지난 운행 경고 2번 + ESC 꺼짐 인계 → 안내 → ESC 해제 확인 → 출발" t01
run E2E-02 "문제 없는 운행은 '넘겨받을 것 없음'" t02
run E2E-03 "동승자가 남은 채 운전 교대해도 인계" t03
run E2E-04 "운전석 센서 흔들림은 하차로 보지 않음" t04
run E2E-05 "다음 운전자 탑승 후 켜진 경고는 이번 인계에 넣지 않음" t05
run E2E-06 "앱 강제 종료 후에도 인계 복원" t06
run E2E-07 "차를 떠나 있는 동안 생긴 12V 배터리 경고 추가" t07
run E2E-08 "지금 켜진 안전 경고를 남긴 채 출발 시 1회만 경고" t08
run E2E-09 "탔다가 다시 내리면 같은 인계 유지" t09
run E2E-10 "저장 파일 변조 시 크래시 없이 무시" t10
run E2E-11 "주행 중에는 한 줄만, 이력 버튼 숨김" t11
run E2E-12 "운전 습관 신호(안전벨트 경고)는 기록하지 않음" t12
run E2E-13 "탔다 내렸다 다시 타도 요약 안내 반복 없음" t13
run E2E-14 "지금 켜진 안전 경고는 눌러도 숨겨지지 않음" t14
run E2E-15 "홈 화면으로 나가도 감지·안내 계속 (앱 안 열어도 동작)" t15
run E2E-16 "보안: 외부에서 서비스·수신기 강제 호출 불가" t16
run E2E-17 "지난 운행 기록은 확인 처리 가능 (되돌리기 표시)" t17
run E2E-18 "음성 수령: 안내 뒤 \"받았어요\"라고 말하면 수령 기록" t18
run E2E-19 "말이 없으면 8초 뒤 듣기 종료, 수령으로 치지 않음" t19

echo
echo "결과: 통과 $PASS / 실패 $FAIL ${FAILED[*]}"
[ "$FAIL" = "0" ]
