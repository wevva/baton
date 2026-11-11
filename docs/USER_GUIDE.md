# 바통 사용 가이드 (처음 쓰는 분용)

> 저장소 공개용 사본입니다. 경로는 개발 PC 기준 예시입니다 (`C:\devaton` = 이 저장소).


---

## 1. 바통이 하는 일 (1분 요약)

1. **운행 중** 고장 경고등(타이어 공기압, ABS, 12V 배터리 등)이 켜질 때마다 바통이 셉니다. 경고가 꺼져도 기록은 남습니다.
2. 운전자가 **주차(P단)하고 내리면**, 그 기록과 함께 바꿔 둔 설정(ESC 꺼짐, i-Pedal 등)·소모품 상태를 저장합니다.
3. **다음 사람이 운전석에 앉으면**, "출발 전에 알아 두실 것"을 화면에 띄우고 음성으로 한 번 알려 줍니다. ("지난 운행 중 타이어 공기압 경고등이 2번 켜졌어요")
4. ESC를 다시 켜는 등 **해결되는 순간** 차 신호로 확인하고 스스로 지웁니다. ("ESC 꺼짐 해제 확인")
5. 문 잠금·창문·실내등은 다루지 않습니다(차량 순정 경고·블루링크 영역). 과속·안전벨트 같은 운전 습관 신호는 기록하지 않습니다.
6. 앱을 열어 두지 않아도 됩니다. 차가 켜지면 바통이 알아서 시작됩니다.

---

## 2. 무엇이 어디에 있나요

| 무엇 | 위치 |
|---|---|
| 앱 소스 코드 (git으로 관리) | `C:\dev\baton` |
| 제출용 APK | `C:\dev\baton\apk\바통랩_바통_release.apk` |
| 서명 키 (**백업 필수, 공유 금지**) | `C:\dev\keys` |
| 빌드 도구 (Java 17, Gradle) | `C:\dev\tools` |
| 보안 설계 / 테스트 목록 | `C:\dev\baton\docs\SECURITY.md`, `docs\TEST_CASES.md` |

---

## 3. 시연 시작하기 (5단계, 약 3분)

아래 명령은 **Git Bash**(시작 메뉴에서 "Git Bash" 검색)에 붙여 넣습니다.

### ① 에뮬레이터(가상 차량 화면) 켜기
```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Pleos_Connect_v2.1.0 &
```
- 차량 화면 창이 뜨고 **약 40초** 뒤 홈 화면이 나옵니다.
- Android Studio에서 켜도 됩니다: 오른쪽 **Device Manager** → `Pleos_Connect_v2.1.0` ▶ 버튼.
- PC 메모리를 4GB 정도 씁니다. 크롬 탭이 너무 많으면 꺼질 수 있어요.

### ② 가상 차량 조작판(Virtual Console) 연결
```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" forward tcp:8088 tcp:8088
```
- 이제 크롬에서 **http://127.0.0.1:8088/** 을 열면 기어·창문·좌석·잠금 등을 손으로 바꿀 수 있습니다.
- 에뮬레이터를 껐다 켤 때마다 ②를 다시 해야 합니다.

### ③ 바통 설치 (이미 깔려 있으면 건너뛰기)
```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r "C:/dev/baton/apk/바통랩_바통_release.apk"
```
- `Success`가 나오면 끝.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 나오면 다른 서명의 바통이 깔려 있는 것입니다. 먼저 지우고 다시 설치하세요:
  `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" uninstall com.baton.app`

### ④ 바통 열기
- 차량 화면 아래쪽 **앱 목록(네모 4개 아이콘)** → **바통** 아이콘(비스듬한 바통 + 초록 화살표).
- 처음 열면 "주행 중" 또는 "주차됨"이 보입니다.

### ⑤ 시나리오 자동 재생 (시연용)
```bash
bash /c/dev/baton/tools/demo.sh
```
약 1분 동안 자동으로 진행됩니다:
1. 주행 중 타이어 공기압 경고가 두 번 켜졌다 꺼짐
2. 운전자가 ESC를 끈 채 주차하고 하차
3. 다음 운전자 탑승 → **화면 + 음성 안내** ("지난 운행 중 타이어 공기압 경고등이 2번 켜졌어요", "ESC 꺼짐")
4. ESC 다시 켜기 → 초록 체크로 바뀌며 "ESC 꺼짐 해제 확인" 음성
5. (화면에서 '받았어요' 누르기) → 출발

천천히 보여 주고 싶으면 숫자를 붙이세요(초 단위 간격): `bash /c/dev/baton/tools/demo.sh 6`

---

## 4. 손으로 시연하기 (심사위원이 직접 조작해 보게 할 때)

크롬에서 **http://127.0.0.1:8088/** → 각 항목을 바꿉니다.

| 하고 싶은 것 | 조작판에서 |
|---|---|
| 주차 / 출발 | Motion › **Gear Selection**: **P** / **D** |
| 운전자 내리기 / 타기 | Seat › **Seat Occupancy** → Driver: **Empty** / **Occupied** |
| 경고등 켜기 / 끄기 | Safety › **Telltales** → 종류(TPMS, ABS, ESC Off, Low Volt Battery …) **On** / **Off** |
| 배터리 | EV Battery › **Battery SOC** 슬라이더 |

명령어로도 바꿀 수 있습니다 (조작판이 열려 있지 않아도 됨):
```bash
bash /c/dev/baton/tools/vc.sh gear Park             # 기어 P (Park/Reverse/Neutral/Drive)
bash /c/dev/baton/tools/vc.sh seat driver false     # 운전자 내림 (true면 탐)
bash /c/dev/baton/tools/vc.sh lamp TPMS On          # 타이어 공기압 경고등 켜기 (Off로 끄기)
bash /c/dev/baton/tools/vc.sh lamp ESCOff On        # ESC 꺼짐 표시 켜기
bash /c/dev/baton/tools/vc.sh lamp LowVoltBattery On Red   # 12V 배터리 경고 (색: Red|Yellow)
bash /c/dev/baton/tools/vc.sh lamps_off             # 주요 경고등 모두 끄기
bash /c/dev/baton/tools/vc.sh soc 18                # 배터리 18%
```
> 운전자가 내린 뒤 **2초가 지나야** "하차"로 인식합니다 (몸을 들썩이는 것을 하차로 착각하지 않도록).

---

## 5. 화면 설명

| 화면 | 언제 | 보이는 것 |
|---|---|---|
| **주행 중** | 기어가 P가 아닐 때 | 한 줄만 (운전 중 시선 분산 방지) |
| **주차됨** | P단, 운전자 착석 | "지금 내리면 다음 운행에 이렇게 전달돼요" 미리보기 (설정은 "내리기 전에 되돌릴 수 있어요") |
| **다음 운행에 넘길 내용 저장됨** | 운전자가 내린 뒤 | 기록된 항목 |
| **출발 전에 알아 둘 것** | 다음 운전자가 앉았을 때 | 항목 카드, 배터리 변화, **받았어요** 버튼 |
| **인계 이력** | 주차 중 상단 **이력** 버튼 | 지난 인계 기록 (날짜·남긴 일·해결·수령·배터리) |

**카드 색과 기호**
- 빨강 `!` **안전** (지금 켜진 타이어·ABS·12V 배터리 경고, ESC 꺼짐, 브레이크액 부족 등) → 지금 켜져 있으면 누를 수 없음. 실제로 해결해야 사라짐
- 노랑 `•` **할 일** (i-Pedal 켜짐, 크리프 꺼짐, 배터리 부족 등) / 파랑 **참고** (스포츠 모드, 워셔액)
- "지난 운행 중 ○○ 경고 N번"은 이미 꺼진 경고의 기록이라, 확인했으면 눌러서 내릴 수 있음 (8초 안에 **되돌리기**)
- 초록 `✓` **해결됨**

**버튼**
- **KO / EN / JA / ZH**: 화면 언어 (음성은 한국어·영어만)
- **받았어요**: 수령증. 누르면 "오전 9:02 수령 확인". 탑승 안내가 끝난 뒤 8초 동안 "받았어요"라고 말해도 됩니다(화면에 "🎤 듣는 중"이 보일 때)
- **이력**: 주차 중에만 보임

---

## 6. 테스트 돌려 보기 (선택)

### 단위 테스트 (에뮬레이터 필요 없음, 약 30초)
```bash
cd /c/dev/baton
export JAVA_HOME=/c/dev/tools/jdk-17.0.21+9
./gradlew.bat testDebugUnitTest
```
`BUILD SUCCESSFUL`이면 전부 통과입니다.

### 실제 동작 테스트 E2E (에뮬레이터 + ② 연결 필요, 약 8분)
E2E는 로그를 읽어야 해서 **디버그 버전**을 씁니다:
```bash
cd /c/dev/baton
export JAVA_HOME=/c/dev/tools/jdk-17.0.21+9
./gradlew.bat assembleDebug
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" uninstall com.baton.app
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install app/build/outputs/apk/debug/app-debug.apk
bash tools/e2e.sh
```
마지막 줄에 `결과: 통과 17 / 실패 0`이 나오면 성공입니다. 시연 전에는 다시 ③으로 release 버전을 설치하세요.

---

## 7. 코드를 고친 뒤 제출 파일 다시 만들기
```bash
cd /c/dev/baton
export JAVA_HOME=/c/dev/tools/jdk-17.0.21+9
./gradlew.bat assembleRelease
```
→ `C:\dev\baton\apk\바통랩_바통_release.apk`가 새로 만들어집니다 (서명 포함).

---

## 8. 자주 겪는 문제

| 증상 | 해결 |
|---|---|
| 에뮬레이터가 바로 꺼짐 / "hardware acceleration" 오류 | Windows 기능에서 "Windows 하이퍼바이저 플랫폼" 켜고 재부팅 (이미 해 둠) |
| 에뮬레이터가 갑자기 꺼짐 | PC 메모리 부족. 크롬 탭·다른 프로그램을 닫고 다시 켜기 |
| 조작판 명령이 `{"error":"emulator_inactive"}` | `tools/vc.sh`를 쓰면 자동 해결. 크롬 조작판은 페이지를 새로고침 |
| 조작판(8088)이 안 열림 | ② 명령을 다시 실행 |
| 바통이 아무 반응이 없음 | 바통을 강제 종료 후 다시 열기: `adb shell am force-stop com.baton.app` |
| 소리가 안 남 | PC 볼륨 확인. 화면 언어가 JA/ZH면 음성 없음(화면만) |
| 설치 시 `UPDATE_INCOMPATIBLE` | ③의 uninstall 후 재설치 |
| 언어가 안 바뀜 | 버튼을 누르고 1초 기다리기 (시스템이 적용하는 시간) |

---

## 9. 꼭 지켜야 할 것
- **서명 키 `C:\dev\keys` 폴더**: USB나 본인 전용 클라우드에 백업. 다른 사람·GitHub에 올리지 않기.
