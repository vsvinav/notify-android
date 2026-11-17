"""Generate Notify's original, soft two-note notification cue (no sampled audio)."""
import math
from pathlib import Path
import struct
import wave

rate = 44100
duration = 0.82
output = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/notify_attention.wav"
output.parent.mkdir(parents=True, exist_ok=True)
samples = []
for index in range(round(rate * duration)):
    time = index / rate
    value = 0.0
    for start, frequency, volume in [(0.0, 659.255, 0.20), (0.17, 880.0, 0.16)]:
        age = time - start
        if age >= 0:
            envelope = min(1.0, age / 0.012) * math.exp(-age * 7.5)
            envelope *= min(1.0, (duration - time) / 0.06)
            value += volume * envelope * (math.sin(2 * math.pi * frequency * age)
                                         + 0.12 * math.sin(4 * math.pi * frequency * age))
    samples.append(struct.pack("<h", round(value * 32767)))
with wave.open(str(output), "wb") as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(rate)
    audio.writeframes(b"".join(samples))
