# 인계 문구를 중립적으로 다듬는다 (같은 사람이 다시 타도 맞는 말, 비난하지 않는 말). 4개 언어.
import re, os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
DIRS = {'en': 'values', 'ko': 'values-ko', 'ja': 'values-ja', 'zh': 'values-zh-rCN'}
L = ['en', 'ko', 'ja', 'zh']
STR = {
    'state_boarded': ('Before you drive', '출발 전에 알아 둘 것', '出発前の確認事項', '出发前须知'),
    'state_away': ('Saved for the next drive', '다음 운행에 넘길 내용 저장됨', '次の走行への引き継ぎを保存しました', '已保存给下次行驶的交接'),
    'empty_handover': ('Nothing to pass on — no issues on the last drive.', '넘겨받을 것이 없어요. 지난 운행에서 이상이 없었어요.', '引き継ぎ事項はありません。前回の走行で異常はありませんでした。', '没有需要交接的事项，上次行驶无异常。'),
    'voice_none': ('Baton here. Nothing to pass on from the last drive.', '바통입니다. 지난 운행에서 넘겨받을 것이 없어요.', 'バトンです。前回の走行から引き継ぐことはありません。', '这里是接力。上次行驶没有需要交接的事项。'),
    'hint_tap_to_ack': ('Tap an item you have checked to clear it (warnings that are still on stay)', '확인한 항목은 눌러서 내릴 수 있어요 (지금 켜진 안전 경고는 제외)', '確認した項目はタップで消せます(点灯中の安全警告を除く)', '已确认的事项可点击移除(仍亮起的安全警告除外)'),
    'parked_hint': ('If you leave now, the next driver will be told:', '지금 내리면 다음 운행에 이렇게 전달돼요:', '今降りると次の走行にこう伝わります:', '现在下车将向下次行驶转达:'),
    'preview_setting': ('%1$s — you can switch it back before leaving', '%1$s — 내리기 전에 되돌릴 수 있어요', '%1$s — 降りる前に戻せます', '%1$s — 下车前可恢复'),
}
PLU = {
    'voice_intro': (('Baton here. There is %1$d thing to know before you drive.', 'Baton here. There are %1$d things to know before you drive.'),
                    '바통입니다. 출발 전에 알아 두실 것이 %1$d가지 있어요.', 'バトンです。出発前に確認していただきたいことが%1$d件あります。', '这里是接力。出发前有%1$d项需要了解。'),
}

for i, lang in enumerate(L):
    p = os.path.join(RES, DIRS[lang], 'strings.xml')
    s = open(p, encoding='utf-8').read()
    for k, v in STR.items():
        s = re.sub(r'\s*<string name="%s">.*?</string>' % k, '', s)
    for k in PLU:
        s = re.sub(r'\s*<plurals name="%s">.*?</plurals>' % k, '', s, flags=re.S)
    add = ['    <string name="%s">%s</string>' % (k, v[i]) for k, v in STR.items()]
    for k, v in PLU.items():
        if lang == 'en':
            add.append('    <plurals name="%s">\n        <item quantity="one">%s</item>\n        <item quantity="other">%s</item>\n    </plurals>' % (k, v[0][0], v[0][1]))
        else:
            add.append('    <plurals name="%s">\n        <item quantity="other">%s</item>\n    </plurals>' % (k, v[i]))
    s = s.replace('</resources>', '\n' + '\n'.join(add) + '\n</resources>')
    open(p, 'w', encoding='utf-8').write(s)
print('ok')
