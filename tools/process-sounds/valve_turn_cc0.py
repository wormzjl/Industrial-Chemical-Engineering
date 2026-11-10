"""Cuts the hand valve's turning sound from CC0 recordings (owner 2026-11-10: the synthesised candidates were "Not good";
"Recorded CC0 sample"). Authoring only; nothing here runs in the mod.

Sources (freesound.org, Creative Commons 0; the HQ previews, 128 kbps MP3, which download without an account):
  R1 gas_off_395880.mp3     "Turning gas off.wav" by jokallset, https://freesound.org/people/jokallset/sounds/395880/
  R2 leaky_pipe_340643.mp3  "Leaky_Pipe_Valve_Turn.wav" by mickyman5000, https://freesound.org/people/mickyman5000/sounds/340643/
  R3 shower_369869.mp3      "Shower handle turn on and off quickly" by SpliceSound, https://freesound.org/people/SpliceSound/sounds/369869/

Usage, from the worktree root (the authoring Python of README.md):
  python -I tools/process-sounds/valve_turn_cc0.py envelope <download folder>
  python -I tools/process-sounds/valve_turn_cc0.py cut <download folder> <output folder> [R1=start,length ...]
envelope prints each file's 25 ms RMS envelope in dB; cut writes each cut mono, a 5 ms fade in and an 80 ms fade out,
peak 0.7 (as pipe_knock), Ogg Vorbis at the source rate. Without a window, a cut starts 40 ms before the loudest 25 ms
frame's onset (the first frame within 20 dB of it) and lasts 1.0 s.
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))

import numpy as np
import soundfile as sf

FILES = {"R1": "gas_off_395880.mp3", "R2": "leaky_pipe_340643.mp3", "R3": "shower_369869.mp3"}
FRAME = .025
PEAK = .7


def mono(path):
    data, rate = sf.read(str(path), always_2d=True)
    return data.mean(axis=1), rate


def envelope(signal, rate):
    n = int(FRAME * rate)
    frames = len(signal) // n
    rms = np.sqrt(np.mean(signal[:frames * n].reshape(frames, n) ** 2, axis=1) + 1e-20)
    return 20 * np.log10(rms)


def auto_window(signal, rate):
    db = envelope(signal, rate)
    loudest = int(np.argmax(db))
    onset = loudest
    while onset > 0 and db[onset - 1] > db[loudest] - 20:
        onset -= 1
    return max(0.0, onset * FRAME - .04), 1.0


def cut(signal, rate, start, length):
    a = int(start * rate)
    piece = signal[a:a + int(length * rate)].copy()
    fade_in, fade_out = int(.005 * rate), int(.08 * rate)
    piece[:fade_in] *= np.linspace(0, 1, fade_in)
    piece[-fade_out:] *= np.linspace(1, 0, fade_out) ** 2
    piece[0] = piece[-1] = 0
    return piece * (PEAK / max(1e-9, np.max(np.abs(piece))))


def main():
    mode, folder = sys.argv[1], Path(sys.argv[2])
    if mode == "envelope":
        for key, name in FILES.items():
            signal, rate = mono(folder / name)
            db = envelope(signal, rate)
            print(f"{key} {name}: {len(signal) / rate:.2f} s at {rate} Hz, peak {np.max(np.abs(signal)):.3f}")
            print("  " + " ".join(f"{d:.0f}" for d in db))
        return
    output = Path(sys.argv[3])
    output.mkdir(parents=True, exist_ok=True)
    windows = dict(arg.split("=") for arg in sys.argv[4:])
    for key, name in FILES.items():
        signal, rate = mono(folder / name)
        start, length = map(float, windows[key].split(",")) if key in windows else auto_window(signal, rate)
        piece = cut(signal, rate, start, length)
        path = output / f"valve_turn_{key}.ogg"
        sf.write(str(path), piece, rate, format="OGG", subtype="VORBIS")
        rms = float(np.sqrt(np.mean(piece ** 2)))
        print(f"{key}: {start:.3f} s + {length:.3f} s -> {path.name}, {path.stat().st_size} bytes, RMS {rms:.4f}, peak {PEAK}")


if __name__ == "__main__":
    main()
