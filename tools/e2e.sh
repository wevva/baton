#!/usr/bin/env bash
# 바통 E2E 테스트 — 에뮬레이터 + Virtual Console 로 실제 신호를 넣고, 앱 로그로 합격/불합격을 판정한다.
# 전제: 디버그 APK 설치(로그가 나와야 함), adb forward tcp:8088 tcp:8088, 앱 언어 한국어.
# 사용: bash tools/e2e.sh            (전체)
#       bash tools/e2e.sh E2E-03     (하나만)
# 케이스 ID는 docs/TEST_CASES.md 와 같다.
export MSYS_NO_PATHCONV=1
DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG=com.baton.app
ONLY="$1"
PASS=0; FAIL=0; FAILED=()

vc()   { bash "$DIR/vc.sh" "$@" >/dev/null; }
logs() { "$ADB" logcat -d -s "Baton:*" "BatonStore:*" | sed -E 's/^.{31}//'; }
clear_logs() { "$ADB" logcat -c; }
ui_text() { "$ADB" shell uiautomator dump /sdcard/u.xml >/dev/null; "$ADB" shell cat /sdcard/u.xml | grep -oE 'text="[^"]*"' | tr '\n' ' '; }

# 앱을 깨끗한 상태(저장 인계 없음)로 시작. 차량은 '주행 중, 운전자 착석, 모두 정상'
fresh_start() {
  "$ADB" shell am force-stop $PKG
  "$ADB" shell "run-as $PKG --user 10 rm -f files/handover.bin" 2>/dev/null
  vc gear Drive; vc seat driver true; vc seat passenger false; vc lock true; vc window_rl 0; vc cabin false; vc soc 62; vc door_fl false
  clear_logs
  "$ADB" shell am start -n $PKG/.MainActivity >/dev/null
  sleep 6
}

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

run() { # $1=ID $2=설명 $3=함수
  [ -n "$ONLY" ] && [ "$ONLY" != "$1" ] && return
  printf '▶ %s %s\n' "$1" "$2"
  if "$3"; then PASS=$((PASS+1)); echo "   ✓ 통과"; else FAIL=$((FAIL+1)); FAILED+=("$1"); echo "   ✗ 실패"; fi
}

# ── 케이스 ─────────────────────────────────────────────────────

t01() { # 기본 흐름
  fresh_start
  vc gear Park; sleep 1; vc lock false; vc window_rl 40; vc cabin true; sleep 1
  vc seat driver false; sleep 4
  vc seat driver true; sleep 4
  vc window_rl 0; sleep 2; vc cabin false; sleep 2; vc lock true; sleep 2
  vc gear Drive; sleep 2
  expect_log "하차 시 3건 기록" "상태: Parked → Away \(남은 일=3\)" &&
  expect_log "탑승 시 인계" "상태: Away → Boarded \(남은 일=3\)" &&
  expect_log "탑승 음성 요약" "안내: 바통입니다. 이전 운전자가 남긴 일이 3가지" &&
  expect_log "창문 해결 음성" "안내: 뒷좌석 왼쪽 창문 닫힘 확인" &&
  expect_log "실내등 해결 음성" "안내: 실내등 꺼짐 확인" &&
  expect_log "잠금 해결 음성" "안내: 문 잠금 확인" &&
  expect_log "출발" "상태: Boarded → Driving" &&
  expect_no_log "안전 항목 해결 후 출발 경고 없음" "안전 항목이"
}

t02() { # 깨끗한 주차
  fresh_start
  vc gear Park; sleep 1; vc seat driver false; sleep 4; vc seat driver true; sleep 4
  expect_log "빈 인계" "상태: Parked → Away \(남은 일=0\)" &&
  expect_log "깨끗하다는 음성" "안내: 바통입니다. 깨끗한 상태로 받으셨어요"
}

t03() { # 동승자가 남은 채 운전 교대
  fresh_start
  vc seat passenger true; vc gear Park; sleep 1; vc cabin true; sleep 1
  vc seat driver false; sleep 4
  expect_log "동승자 있어도 하차 판정" "상태: Parked → Away \(남은 일=1\)"
}

t04() { # 센서 흔들림 (1초 미만 비움)
  fresh_start
  vc gear Park; sleep 1; vc seat driver false; vc seat driver true; sleep 4
  expect_no_log "흔들림은 하차 아님" "상태: Parked → Away"
}

t05() { # 다음 운전자가 잠금 해제하고 탑승 → '문 안 잠김' 오탐 금지
  fresh_start
  vc gear Park; sleep 1; vc seat driver false; sleep 4
  vc lock false; vc door_fl true; sleep 1; vc seat driver true; sleep 3; vc door_fl false; sleep 2
  expect_log "빈 인계로 탑승" "상태: Away → Boarded \(남은 일=0\)" &&
  expect_no_log "잠금 해제가 인계로 둔갑하지 않음" "안내: .*문이 잠기지 않았어요"
}

t06() { # 앱 강제 종료 후 복원
  fresh_start
  vc gear Park; sleep 1; vc cabin true; sleep 1; vc seat driver false; sleep 4
  "$ADB" shell am force-stop $PKG; sleep 1; clear_logs
  "$ADB" shell am start -n $PKG/.MainActivity >/dev/null; sleep 6
  vc seat driver true; sleep 4
  expect_log "복원" "복원: Away" &&
  expect_log "복원 후 탑승 인계" "상태: Away → Boarded \(남은 일=1\)"
}

t07() { # 주차 중 배터리 하락 → 새 항목
  fresh_start
  vc soc 30; vc gear Park; sleep 1; vc seat driver false; sleep 4
  vc soc 15; sleep 3; vc seat driver true; sleep 5
  expect_log "배터리 부족 포함 인계" "상태: Away → Boarded \(남은 일=1\)" &&
  expect_log "배터리 음성" "배터리가 부족해요 \(15%\)"
}

t08() { # 안전 항목 남긴 채 출발 → 1회 경고
  fresh_start
  vc gear Park; sleep 1; vc lock false; sleep 1; vc seat driver false; sleep 4; vc seat driver true; sleep 4
  vc gear Drive; sleep 3
  expect_log "출발 경고" "안내: 안전 항목이 1건 남아 있어요" &&
  [ "$(logs | grep -c '안전 항목이')" = "1" ] || { echo "      ✗ 경고가 1회가 아님"; return 1; }
}

t09() { # 탔다가 다시 내림 → 같은 인계 유지
  fresh_start
  vc gear Park; sleep 1; vc cabin true; sleep 1; vc seat driver false; sleep 4
  vc seat driver true; sleep 3; vc seat driver false; sleep 4
  expect_log "다시 대기" "상태: Boarded → Away \(남은 일=1\)"
}

t10() { # 저장 파일 변조 → 크래시 없이 무시
  fresh_start
  vc gear Park; sleep 1; vc cabin true; sleep 1; vc seat driver false; sleep 4
  "$ADB" shell "run-as $PKG --user 10 sh -c 'printf Z | dd of=files/handover.bin bs=1 seek=40 conv=notrunc 2>/dev/null'"
  "$ADB" shell am force-stop $PKG; "$ADB" logcat -c -b crash; clear_logs
  "$ADB" shell am start -n $PKG/.MainActivity >/dev/null; sleep 6
  expect_no_log "변조된 인계를 복원하지 않음" "복원: Away" &&
  [ "$("$ADB" logcat -d -b crash | grep -c $PKG)" = "0" ] || { echo "      ✗ 크래시 발생"; return 1; }
}

t11() { # 주행 중 화면은 한 줄
  fresh_start
  expect_ui "주행 중 표시" "주행 중" &&
  ! ui_text | grep -qF "이력" || { echo "      ✗ 주행 중에 이력 버튼이 보임"; return 1; }
}

t12() { # 창문 반쯤 닫기 → 해결 아님
  fresh_start
  vc gear Park; sleep 1; vc window_rl 60; sleep 1; vc seat driver false; sleep 4; vc seat driver true; sleep 3
  vc window_rl 30; sleep 3
  expect_no_log "반쯤 닫힘은 해결 아님" "창문 닫힘 확인" &&
  expect_ui "갱신된 값" "30% 열려"
}

tap_text() { # 화면에서 해당 문구를 찾아 누른다
  "$ADB" shell uiautomator dump /sdcard/u.xml >/dev/null
  local b; b=$("$ADB" shell cat /sdcard/u.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [ -z "$b" ] && return 1
  set -- $b; "$ADB" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}

t13() { # 탔다 내렸다 다시 타도 요약 반복 없음
  fresh_start
  vc gear Park; sleep 1; vc cabin true; sleep 1; vc seat driver false; sleep 4
  vc seat driver true; sleep 3; vc seat driver false; sleep 4; vc seat driver true; sleep 3
  [ "$(logs | grep -c '안내: 바통입니다')" = "1" ] || { echo "      ✗ 요약 안내 횟수: $(logs | grep -c '안내: 바통입니다')"; return 1; }
}

t14() { # 안전 항목은 눌러도 숨겨지지 않음
  fresh_start
  vc gear Park; sleep 1; vc lock false; sleep 1; vc seat driver false; sleep 4; vc seat driver true; sleep 4
  tap_text "문이 잠기지 않았어요" || { echo "      ✗ 안전 항목 카드를 찾지 못함"; return 1; }
  sleep 1
  expect_ui "안전 항목이 그대로 보임" "문이 잠기지 않았어요" &&
  ! ui_text | grep -qF "되돌리기" || { echo "      ✗ 안전 항목이 확인 처리됨"; return 1; }
}

t15() { # 홈으로 나가도 감지 계속 (앱을 열지 않아도 동작)
  fresh_start
  "$ADB" shell input keyevent KEYCODE_HOME; sleep 2
  vc gear Park; sleep 1; vc cabin true; sleep 1; vc seat driver false; sleep 4; vc seat driver true; sleep 4
  expect_log "백그라운드 하차 기록" "상태: Parked → Away \(남은 일=1\)" &&
  expect_log "백그라운드 탑승 안내" "안내: 바통입니다. 이전 운전자가 남긴 일이 1가지"
}

t16() { # 보안: 외부(셸)에서 서비스·수신기 강제 호출 불가
  local out1 out2
  out1=$("$ADB" shell am start-foreground-service -n $PKG/.HandoverService 2>&1)
  out2=$("$ADB" shell am broadcast -n $PKG/.BootReceiver -a android.intent.action.BOOT_COMPLETED 2>&1)
  echo "$out1" | grep -qiE "security|not exported|denied|requires" || { echo "      ✗ 서비스가 외부에서 시작됨: $out1"; return 1; }
  echo "$out2" | grep -qiE "security|not exported|denied|protected|requires|result=0" || { echo "      ✗ 수신기 응답: $out2"; return 1; }
}

run E2E-01 "기본 흐름: 하차 기록 → 탑승 안내 → 하나씩 자동 소거 → 출발" t01
run E2E-02 "깨끗하게 주차하면 '깨끗한 상태' 안내" t02
run E2E-03 "동승자가 남은 채 운전 교대해도 인계" t03
run E2E-04 "운전석 센서 흔들림은 하차로 보지 않음" t04
run E2E-05 "다음 운전자의 잠금 해제를 인계로 오인하지 않음" t05
run E2E-06 "앱 강제 종료 후에도 인계 복원" t06
run E2E-07 "주차 중 배터리 하락은 새 항목으로 추가" t07
run E2E-08 "안전 항목 남긴 채 출발 시 1회만 경고" t08
run E2E-09 "탔다가 다시 내리면 같은 인계 유지" t09
run E2E-10 "저장 파일 변조 시 크래시 없이 무시" t10
run E2E-11 "주행 중에는 한 줄만, 이력 버튼 숨김" t11
run E2E-12 "창문 반쯤 닫기는 해결로 치지 않고 값만 갱신" t12
run E2E-13 "탔다 내렸다 다시 타도 요약 안내 반복 없음" t13
run E2E-14 "안전 항목은 눌러도 숨겨지지 않음" t14
run E2E-15 "홈 화면으로 나가도 감지·안내 계속 (앱 안 열어도 동작)" t15
run E2E-16 "보안: 외부에서 서비스·수신기 강제 호출 불가" t16

echo
echo "결과: 통과 $PASS / 실패 $FAIL ${FAILED[*]}"
[ "$FAIL" = "0" ]
