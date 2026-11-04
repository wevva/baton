#!/usr/bin/env bash
# Pleos Virtual Console(http://127.0.0.1:8088) 명령줄 도우미 — 바통 시연 시나리오 자동화용.
# 콘솔은 브라우저 세션이 살아 있을 때만 값을 반영하므로(timeout 3s) 명령 전후로 세션을 갱신한다.
# 사용: tools/vc.sh gear Drive | soc 62 | seat driver true | lamp TPMS On [Yellow] | lamp ESCOff Off | lock true
#   lamp 이름은 콘솔 표기(TPMS, ABS, ESCOff, LowVoltBattery, EVSystem, MDPS, Airbag ...), 상태 On|Off|Blink, 색 Red|Yellow|Green|White
C=${VC_URL:-http://127.0.0.1:8088}
ping_session() { curl -s -X POST "$C/browser/session?client=baton-cli" >/dev/null; }
put() { ping_session; curl -s -X PUT "$C$1"; echo; }
case "$1" in
  gear)      put "/vehicle/gear?gear=$2" ;;
  soc)       put "/vehicle/ev-battery?field=soc&value=$2" ;;
  lock)      put "/vehicle/body?doors_locked=$2" ;;
  door_*)    put "/vehicle/body?$1=$2" ;;
  window_*)  put "/vehicle/body?$1=$2" ;;
  cabin)     put "/vehicle/body?cabin_light=$2" ;;
  tire)      put "/vehicle/tire-pressure?area=$2&pressure_kpa=$3" ;;
  seat)      put "/vehicle/seat?kind=occupied&area=$2&value=$3" ;;
  range)     put "/vehicle/odometer?remaining_range_m=$2" ;;
  lamp)      put "/vehicle/telltales/simulator?type=$2&state=$3&color=${4:-Yellow}" ;;
  lamps_off) for t in TPMS ABS ESC ESCOff LowVoltBattery EVSystem MDPS Airbag MasterWarning Engine EngineOilPressure EngineTemperature PowerDown; do put "/vehicle/telltales/simulator?type=$t&state=Off&color=Yellow" >/dev/null; done; echo ok ;;
  keepalive) while true; do ping_session; sleep 1; done ;;
  *) echo "usage: $0 {gear|soc|seat|lamp|lamps_off|lock|door_fl|window_fl|cabin|tire|range|keepalive} ..."; exit 1 ;;
esac
