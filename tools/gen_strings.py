# 경고등·설정 이름과 인계 문장 틀을 4개 언어로 생성한다 (values, values-ko, values-ja, values-zh-rCN).
# 그리고 코드 → 리소스 매핑(TelltaleNames.kt)을 만든다. 이름을 고칠 땐 이 표만 고치고 다시 실행:
#   python tools/gen_strings.py
import re, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
DIRS = {'en': 'values', 'ko': 'values-ko', 'ja': 'values-ja', 'zh': 'values-zh-rCN'}
L = ['en', 'ko', 'ja', 'zh']

# 경고등·설정 이름 (en, ko, ja, zh) — 코드는 Pleos TelltaleType 이름
NAMES = {
    'ABS': ('ABS', 'ABS', 'ABS', 'ABS'),
    'RBS': ('Regenerative braking', '회생 제동 시스템', '回生ブレーキ', '再生制动系统'),
    'ESC': ('Stability control (ESC)', '차체 자세 제어(ESC)', '横滑り防止装置(ESC)', '车身稳定控制(ESC)'),
    'ELECTRONIC_PARKING_BRAKE': ('Electronic parking brake', '전자식 주차 브레이크', '電動パーキングブレーキ', '电子驻车制动'),
    'MDPS': ('Power steering (MDPS)', '전동식 조향(MDPS)', '電動パワーステアリング', '电动助力转向'),
    'LED_HEADLAMP': ('LED headlamp', 'LED 헤드램프', 'LEDヘッドランプ', 'LED大灯'),
    'AFS': ('Adaptive headlamp (AFS)', '적응형 헤드램프(AFS)', 'アダプティブヘッドランプ', '自适应大灯'),
    'TPMS': ('Tire pressure', '타이어 공기압', 'タイヤ空気圧', '胎压'),
    'AIRBAG': ('Airbag', '에어백', 'エアバッグ', '安全气囊'),
    'EMERGENCY_CALL': ('Emergency call', '긴급 호출', '緊急通報', '紧急呼叫'),
    'MASTER_WARNING': ('Master warning', '종합 경고', 'マスターウォーニング', '主警告'),
    'ENGINE': ('Check engine', '엔진 점검', 'エンジン警告', '发动机故障'),
    'ENGINE_OIL_PRESSURE': ('Engine oil pressure', '엔진오일 압력', '油圧', '机油压力'),
    'ENGINE_TEMPERATURE': ('Engine temperature', '냉각수 온도', '水温', '冷却液温度'),
    'EV_SYSTEM': ('EV system', '전기차 시스템', 'EVシステム', '电动车系统'),
    'HEV_SYSTEM': ('Hybrid system', '하이브리드 시스템', 'ハイブリッドシステム', '混合动力系统'),
    'FCEV_SYSTEM': ('Fuel cell system', '수소전기차 시스템', '燃料電池システム', '燃料电池系统'),
    'EREV_SYSTEM': ('Range extender system', '주행거리 연장 시스템', 'レンジエクステンダー', '增程系统'),
    'LOW_VOLT_BATTERY': ('12V battery', '12V 배터리', '12Vバッテリー', '12V蓄电池'),
    'POWER_DOWN': ('Reduced power', '출력 제한', '出力制限', '动力限制'),
    'H2_LEAK': ('Hydrogen leak', '수소 누출', '水素漏れ', '氢气泄漏'),
    'H2_SENSOR': ('Hydrogen sensor', '수소 센서', '水素センサー', '氢气传感器'),
    'PARTICLE_FILTER': ('Particulate filter', '매연 필터', '粒子フィルター', '颗粒过滤器'),
    'SCR': ('Exhaust treatment (SCR)', '배기 정화(SCR)', '排気浄化(SCR)', '尾气处理(SCR)'),
    'WATER_SEPARATOR': ('Fuel water separator', '연료 수분 분리기', '燃料水分離器', '燃油水分离器'),
    'IMMOBILIZER': ('Immobilizer', '이모빌라이저', 'イモビライザー', '防盗锁止'),
    'ESC_OFF': ('Stability control (ESC) off', '차체 자세 제어(ESC) 꺼짐', '横滑り防止装置(ESC)オフ', '车身稳定控制(ESC)关闭'),
    # PASSENGER_AIRBAG_OFF: 넘기지 않음 (승객 구분 시스템이 자동 제어 — Telltales.kt 주석 참고)
    'CHILD_PRESENCE_DETECTION_OFF': ('Rear occupant detection off', '뒷좌석 승객 감지 꺼짐', '後席乗員検知オフ', '后排乘员检测关闭'),
    'I_PEDAL': ('i-Pedal (one-pedal driving) on', 'i-Pedal(원페달 주행) 켜짐', 'i-Pedal(ワンペダル)オン', 'i-Pedal单踏板驾驶开启'),
    'CREEP_OFF': ('Creep off', '크리프 꺼짐', 'クリープオフ', '蠕行关闭'),
    'TOW_MODE': ('Tow mode', '견인 모드', 'トーイングモード', '拖车模式'),
    'ESC_SPORT': ('ESC sport mode', 'ESC 스포츠 모드', 'ESCスポーツモード', 'ESC运动模式'),
    'DRIVE_MODE_SPORT': ('Sport drive mode', '스포츠 주행 모드', 'スポーツモード', '运动驾驶模式'),
    'DRIFT_MODE': ('Drift mode', '드리프트 모드', 'ドリフトモード', '漂移模式'),
    'TRACK_MODE': ('Track mode', '트랙 모드', 'トラックモード', '赛道模式'),
    'LAUNCH_MODE': ('Launch mode', '런치 모드', 'ローンチモード', '弹射起步模式'),
    'FOUR_WHEEL_DRIVE_MODE_4L': ('4WD low (4L)', '4륜 저속(4L)', '4WDロー(4L)', '四驱低速(4L)'),
}

STR = {
    'item_warning_on': ('%1$s warning light is on', '%1$s 경고등이 켜져 있어요', '%1$s警告灯が点灯しています', '%1$s警告灯亮起'),
    'item_setting': ('Handed over with %1$s', '%1$s 상태로 넘겨받았어요', '%1$sの状態で引き継ぎました', '以%1$s状态交接'),
    'item_engine_oil_low': ('Engine oil is low — check before driving', '엔진오일이 부족해요 — 출발 전에 점검하세요', 'エンジンオイルが不足しています — 出発前に点検してください', '机油不足 — 请在出发前检查'),
    'item_transmission_oil_low': ('Transmission oil needs checking', '변속기 오일 점검이 필요해요', 'トランスミッションオイルの点検が必要です', '需要检查变速箱油'),
    'item_washer_low': ('Washer fluid is low', '워셔액이 부족해요', 'ウォッシャー液が不足しています', '玻璃水不足'),
    'done_warning': ('%1$s warning off', '%1$s 경고등 꺼짐 확인', '%1$s警告灯の消灯を確認', '%1$s警告灯已熄灭'),
    'done_warning_trip': ('%1$s noted', '%1$s 확인함', '%1$sを確認しました', '已确认%1$s'),
    'done_setting': ('%1$s cleared', '%1$s 해제 확인', '%1$sの解除を確認', '%1$s已解除'),
    'done_engine_oil': ('Engine oil OK', '엔진오일 정상 확인', 'エンジンオイル正常を確認', '机油已恢复正常'),
    'done_transmission_oil': ('Transmission oil OK', '변속기 오일 정상 확인', 'トランスミッションオイル正常を確認', '变速箱油已恢复正常'),
    'done_washer': ('Washer fluid refilled', '워셔액 보충 확인', 'ウォッシャー液の補充を確認', '玻璃水已补充'),
}

PLU = {
    'item_warning_on_trip': (('%1$s warning light is on (came on %2$d time last trip)', '%1$s warning light is on (came on %2$d times last trip)'),
                             '%1$s 경고등이 켜져 있어요 (지난 운행 중 %2$d번)', '%1$s警告灯が点灯しています(前回の走行中%2$d回)', '%1$s警告灯亮起(上次行驶中%2$d次)'),
    'item_warning_trip': (('%1$s warning came on %2$d time during the last trip — check before driving', '%1$s warning came on %2$d times during the last trip — check before driving'),
                          '지난 운행 중 %1$s 경고등이 %2$d번 켜졌어요 — 출발 전에 점검하세요', '前回の走行中に%1$s警告灯が%2$d回点灯しました — 出発前に点検してください', '上次行驶中%1$s警告灯亮起%2$d次 — 请在出发前检查'),
    'voice_more': (('and %1$d more.', 'and %1$d more.'), '외 %1$d건이 더 있어요.', 'ほか%1$d件あります。', '另有%1$d项。'),
}

# 새 방향에서 다음 운전자에게 넘기지 않는 항목(문·창문·실내등)의 문구 제거
REMOVE = ['item_doors_unlocked', 'item_door_open', 'item_trunk_open', 'item_window_open', 'item_cabin_light_on',
          'done_doors_unlocked', 'done_door_open', 'done_trunk_open', 'done_window_open', 'done_cabin_light_on']


def main():
    for i, lang in enumerate(L):
        p = os.path.join(RES, DIRS[lang], 'strings.xml')
        s = open(p, encoding='utf-8').read()
        for k in REMOVE + list(STR) + ['tt_' + c for c in NAMES]:
            s = re.sub(r'\s*<string name="%s">.*?</string>' % k, '', s)
        for k in PLU:
            s = re.sub(r'\s*<plurals name="%s">.*?</plurals>' % k, '', s, flags=re.S)
        add = []
        for k, v in STR.items():
            add.append('    <string name="%s">%s</string>' % (k, v[i]))
        for c, v in NAMES.items():
            add.append('    <string name="tt_%s">%s</string>' % (c, v[i]))
        for k, v in PLU.items():
            if lang == 'en':
                add.append('    <plurals name="%s">\n        <item quantity="one">%s</item>\n        <item quantity="other">%s</item>\n    </plurals>' % (k, v[0][0], v[0][1]))
            else:
                add.append('    <plurals name="%s">\n        <item quantity="other">%s</item>\n    </plurals>' % (k, v[i]))
        s = s.replace('</resources>', '\n' + '\n'.join(add) + '\n</resources>')
        open(p, 'w', encoding='utf-8').write(s)

    kt = ['package com.baton.app.i18n', '', 'import com.baton.app.R', '',
          '/** 경고등·설정 코드 → 이름 문자열 (tools/gen_strings.py로 생성, 직접 고치지 말 것). getIdentifier를 쓰지 않아 리소스 축소에도 안전 */',
          'object TelltaleNames {', '    fun res(code: String): Int? = when (code) {']
    for c in NAMES:
        kt.append('        "%s" -> R.string.tt_%s' % (c, c))
    kt += ['        else -> null', '    }', '',
           '    val CODES: Set<String> = setOf(' + ', '.join('"%s"' % c for c in NAMES) + ')', '}', '']
    out = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'baton', 'app', 'i18n', 'TelltaleNames.kt')
    open(out, 'w', encoding='utf-8').write('\n'.join(kt))
    print('generated', len(NAMES), 'names')


if __name__ == '__main__':
    main()
