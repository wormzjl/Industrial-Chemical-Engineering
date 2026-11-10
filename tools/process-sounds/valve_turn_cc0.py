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


def stft(signal, n=1024, hop=256):
    w = np.hanning(n)
    frames = 1 + max(0, (len(signal) - n) // hop)
    return np.array([np.fft.rfft(w * signal[i * hop:i * hop + n]) for i in range(frames)]), w, hop


def istft(spectra, w, hop, length):
    n = len(w)
    out, norm = np.zeros(length + n), np.zeros(length + n)
    for i, s in enumerate(spectra):
        out[i * hop:i * hop + n] += w * np.fft.irfft(s, n)
        norm[i * hop:i * hop + n] += w ** 2
    return out[:length] / np.maximum(norm[:length], 1e-9)


def db_to_gain(level):
    return 10 ** (level / 20)


def band_limit(signal, rate, low, high):
    """Keeps low..high Hz, with 20 % cosine skirts."""
    spectrum = np.fft.rfft(signal)
    f = np.fft.rfftfreq(len(signal), 1 / rate)
    g = np.ones_like(f)
    g[f < low * .8] = 0
    rise = (f >= low * .8) & (f < low)
    g[rise] = .5 - .5 * np.cos(np.pi * (f[rise] - low * .8) / (low * .2))
    fall = (f > high) & (f <= high * 1.2)
    g[fall] = .5 + .5 * np.cos(np.pi * (f[fall] - high) / (high * .2))
    g[f > high * 1.2] = 0
    return np.fft.irfft(spectrum * g, len(signal))


def denoise(signal, profile, strength, floor):
    """Spectral subtraction: the profile's mean power per bin, times strength, taken off each frame's power; the gain is
    smoothed over 3 bins and 3 frames and kept at least floor (no musical noise from bins switching on and off)."""
    noise = np.mean(np.abs(stft(profile)[0]) ** 2, axis=0)
    spectra, w, hop = stft(signal)
    power = np.abs(spectra) ** 2
    gain = np.sqrt(np.clip(1 - strength * noise / np.maximum(power, 1e-20), 0, 1))
    kernel = np.ones((3, 3)) / 9
    padded = np.pad(gain, 1, mode="edge")
    gain = sum(padded[i:i + gain.shape[0], j:j + gain.shape[1]] * kernel[i, j] for i in range(3) for j in range(3))
    return istft(spectra * np.maximum(gain, floor), w, hop, len(signal))


def main():
    mode, folder = sys.argv[1], Path(sys.argv[2])
    if mode == "clean":
        # R3 cleaned (owner: "simply clean the original r3"): the original's first 1.2 s, before the handle moves, is its
        # background (the shower and the recorder's hiss); taken off the 1.24 s + 1.0 s cut at three strengths.
        output = Path(sys.argv[3])
        output.mkdir(parents=True, exist_ok=True)
        signal, rate = mono(folder / FILES["R3"])
        piece = signal[int(1.24 * rate):int(2.24 * rate)]
        # The background before the turn is 50 dB under the stop and changes nothing; the dirt is the water's wash, which
        # starts as the handle opens the valve. Its profile: the cut's 0.75-0.95 s, after the stop's rebounds.
        profile = piece[int(.75 * rate):int(.95 * rate)]
        for name, strength, floor, gate, band in (("wash_off", 2, .05, 0, False), ("wash_off_gated", 3, .03, 18, False),
                                                  ("wash_off_gated_band", 3, .03, 18, True)):
            clean = denoise(piece, profile, strength, floor)
            if gate:
                # A downward expander: below the 30 ms peak envelope less gate dB, the level falls 2 dB per dB.
                n = int(.005 * rate)
                env = np.sqrt(np.convolve(clean ** 2, np.ones(n) / n, mode="same") + 1e-20)
                held = np.maximum.accumulate(env[::-1])[::-1] * 0 + np.array([env[max(0, i - 6 * n):i + n].max() for i in range(0, len(env))])
                threshold = held * db_to_gain(-gate)
                over = np.minimum(1, env / threshold)
                clean *= np.convolve(over ** 2, np.ones(n) / n, mode="same")
            if band:
                clean = band_limit(clean, rate, 70, 10000)
            fade_in, fade_out = int(.005 * rate), int(.08 * rate)
            clean[:fade_in] *= np.linspace(0, 1, fade_in)
            clean[-fade_out:] *= np.linspace(1, 0, fade_out) ** 2
            clean[0] = clean[-1] = 0
            clean *= PEAK / max(1e-9, np.max(np.abs(clean)))
            path = output / f"valve_turn_R3_clean_{name}.ogg"
            sf.write(str(path), clean, rate, format="OGG", subtype="VORBIS")
            quiet = clean[int(.9 * rate):]
            print(f"{name}: strength {strength}, floor {floor} -> {path.name}, {path.stat().st_size} bytes, "
                  f"RMS {np.sqrt(np.mean(clean ** 2)):.4f}, last 0.1 s RMS {20 * np.log10(np.sqrt(np.mean(quiet ** 2)) / PEAK + 1e-12):.1f} dB re peak")
        return
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
