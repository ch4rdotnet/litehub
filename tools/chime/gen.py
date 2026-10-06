#!/usr/bin/env python3
# writes app/src/main/res/raw/chime.wav, a soft two note chime for notification banners.
# generated rather than downloaded, so there's no sound file licence to carry.
# usage: gen.py
import math
import struct
import wave
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent.parent / "app/src/main/res/raw/chime.wav"
RATE = 22050
# e6 then c6, a falling third reads as "something happened" without sounding like an alarm
NOTES = [(1318.5, 0.0), (1046.5, 0.16)]
LENGTH = 0.9
DECAY = 5.0
VOLUME = 0.35


def sample(t):
    v = 0.0
    for freq, start in NOTES:
        if t < start:
            continue
        local = t - start
        env = math.exp(-DECAY * local) * min(1.0, local / 0.005)
        # a quiet octave above gives it a bell-ish edge
        v += env * (math.sin(2 * math.pi * freq * local) + 0.3 * math.sin(4 * math.pi * freq * local))
    return v * VOLUME / len(NOTES)


def main():
    frames = b"".join(struct.pack("<h", int(max(-1.0, min(1.0, sample(i / RATE))) * 32767)) for i in range(int(LENGTH * RATE)))
    with wave.open(str(OUT), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)
    print(f"{OUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
