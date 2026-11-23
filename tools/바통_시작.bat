@echo off
title 바통 시작 (가짜 차 + 리모컨)
setlocal
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
set "BASH=C:\Program Files\Git\bin\bash.exe"
set "VC=C:/dev/baton/tools/vc.sh"
set "AVD=Pleos_Connect_v2.1.0"

echo.
echo  ================================================
echo    바통 시작하기
echo    1) 가짜 차(에뮬레이터)를 켭니다
echo    2) 리모컨(Virtual Console)을 연결합니다
echo    3) 바통 앱을 띄웁니다
echo  ================================================
echo.

if not exist "%ADB%" ( echo [오류] adb를 찾지 못했습니다: %ADB% & pause & exit /b 1 )
if not exist "%EMU%" ( echo [오류] 에뮬레이터를 찾지 못했습니다: %EMU% & pause & exit /b 1 )
if not exist "%BASH%" ( echo [오류] Git Bash를 찾지 못했습니다: %BASH% & pause & exit /b 1 )

"%ADB%" start-server >nul 2>&1 <nul
"%ADB%" devices <nul | findstr /c:"emulator-" >nul
if errorlevel 1 (
  echo [1/3] 가짜 차를 켜는 중입니다. 새 창이 뜨고 30초~1분 걸립니다...
  start "" /min "%EMU%" -avd %AVD%
) else (
  echo [1/3] 가짜 차가 이미 켜져 있습니다.
)

"%ADB%" wait-for-device <nul
:waitboot
rem adb 출력 끝에 줄바꿈 문자가 붙어 있어 문자열 비교 대신 findstr로 "1"을 찾는다
"%ADB%" shell getprop sys.boot_completed 2>nul <nul | findstr /b "1" >nul
if errorlevel 1 (
  ping -n 3 127.0.0.1 >nul
  goto waitboot
)
echo       부팅 완료.

echo [2/3] 리모컨 연결 중...
"%ADB%" forward tcp:8088 tcp:8088 >nul <nul
start "" http://127.0.0.1:8088/
echo       브라우저에 리모컨 화면을 열었습니다. (닫지 마세요. 열려 있어야 값이 반영됩니다)

echo [3/3] 바통 앱을 띄웁니다...
ping -n 4 127.0.0.1 >nul
"%ADB%" shell am start -n com.baton.app/.MainActivity >nul 2>&1 <nul
echo       완료.

:menu
echo.
echo  ------------------ 리모컨 ------------------
echo   1  기어 D  (출발 / 주행)
echo   2  기어 P  (주차)
echo   3  운전석 비우기   (내리기)
echo   4  운전석 앉기     (타기)
echo   5  타이어 경고등 켜기
echo   6  타이어 경고등 끄기
echo   7  ESC 끔 표시등 켜기  (앞사람이 ESC를 꺼 둔 상황)
echo   8  ESC 끔 표시등 끄기  (ESC를 다시 켬)
echo   9  경고등 모두 끄기
echo   R  처음부터 다시 (앱 기록 지우기 + 주차, 운전석 앉음, 경고등 끔)
echo   0  끝내기 (가짜 차는 켜 둔 채로)
echo  --------------------------------------------
set "K="
set /p "K=번호를 누르고 Enter: "
if "%K%"=="1" call :vc gear Drive
if "%K%"=="2" call :vc gear Park
if "%K%"=="3" call :vc seat driver false
if "%K%"=="4" call :vc seat driver true
if "%K%"=="5" call :vc lamp TPMS On
if "%K%"=="6" call :vc lamp TPMS Off
if "%K%"=="7" call :vc lamp ESCOff On
if "%K%"=="8" call :vc lamp ESCOff Off
if "%K%"=="9" call :vc lamps_off
if /i "%K%"=="R" call :reset
if "%K%"=="0" goto :eof
goto menu

:vc
"%BASH%" "%VC%" %* >nul 2>&1 <nul
echo   → 보냈습니다: %*
exit /b

:reset
echo   처음 상태로 돌리는 중... (10초쯤)
"%ADB%" shell am force-stop com.baton.app <nul
"%ADB%" shell "run-as com.baton.app --user 10 rm -f files/handover.bin" >nul 2>&1 <nul
"%BASH%" "%VC%" lamps_off >nul 2>&1 <nul
"%BASH%" "%VC%" seat driver true >nul 2>&1 <nul
"%BASH%" "%VC%" gear Park >nul 2>&1 <nul
ping -n 5 127.0.0.1 >nul
"%ADB%" shell am start -n com.baton.app/.MainActivity >nul 2>&1 <nul
echo   → 처음 상태입니다. 운전석에 앉아 있고 기어는 P입니다.
exit /b
