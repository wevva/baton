# PC 스피커 출력(루프백)을 녹음해 WAV로 저장: python rec_audio.py out.wav 초
import sys, wave, numpy as np, soundcard as sc
out, sec = sys.argv[1], float(sys.argv[2]); sr = 48000
mic = sc.get_microphone(id=str(sc.default_speaker().name), include_loopback=True)
with mic.recorder(samplerate=sr, channels=2) as r:
    data = r.record(numframes=int(sr*sec))
pcm = (np.clip(data, -1, 1) * 32767).astype('<i2')
with wave.open(out, 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())
print('peak', float(np.abs(data).max()))
