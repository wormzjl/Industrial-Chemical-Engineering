"""The fixed pollution monitor's gas alarm (batch 2026-11-15-pollution-system, WP10; plan section 11): three 2 s one-shot
candidates, A, B and C. The monitor plays its pick at the block every 2 s while its lamp is red (index at or above 1).
Authoring only; nothing here runs in the mod.

Physics: a plant gas alarm is an electronic sounder (EN 54-3 / ISO 7731 tones, about 100 dB at 1 m). Its driver is a
square-ish wave, so you hear the fundamental with weaker odd harmonics (3rd at -10 dB, 5th at -16 dB, 7th at -22 dB),
rounded by the horn's throat (a gentle low-pass near 4 kHz). The tones are the standard ones: a two-tone warble
(800 / 970 Hz, 4 Hz), a pulsed 1 kHz tone (0.25 s on, 0.25 s off) and a slow whoop (600 to 1200 Hz in 0.9 s). Clean, as
the owner likes: no noise floor, only the tones, with 5 ms ramps at every step so nothing clicks.

Usage, from the worktree root (the authoring Python of README.md):
  python -I tools/process-sounds/gas_alarm_synth.py <output folder>
Writes gas_alarm_a_twotone.ogg, gas_alarm_b_pulsed.ogg and gas_alarm_c_whoop.ogg: mono 48 kHz Ogg Vorbis, 2.0 s, peak
0.7, silent first and last samples (a 5 ms fade in, an 80 ms squared fade out).
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))

import numpy as np
import soundfile as sf

RATE = 48000
LENGTH = 2.0
PEAK = .7
HARMONICS = ((1, 0.0), (3, -10.0), (5, -16.0), (7, -22.0))  # order, dB
THROAT = 4000.0  # Hz, the horn's gentle low-pass
RAMP = .005  # s, every step's ramp


def db(level):
    return 10 ** (level / 20)


def tone(frequency):
    """The sounder's wave for an instantaneous frequency track (Hz per sample): odd harmonics, phase continuous."""
    phase = 2 * np.pi * np.cumsum(frequency) / RATE
    out = np.zeros_like(frequency)
    for order, level in HARMONICS:
        f = frequency * order
        # The throat: a first-order roll-off above THROAT, per harmonic.
        gain = db(level) / np.sqrt(1 + (f / THROAT) ** 2)
        out += gain * np.sin(order * phase)
    return out


def gate(n, segments):
    """An on/off envelope from (start s, end s) pairs, with RAMP ramps."""
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for a, b in segments:
        rise = np.clip((t - a) / RAMP, 0, 1)
        fall = np.clip((b - t) / RAMP, 0, 1)
        out = np.maximum(out, np.minimum(rise, fall))
    return out


def two_tone(n):
    """A: 800 / 970 Hz, a quarter second each; the step is smoothed over the ramp so the pitch glides, never clicks."""
    t = np.arange(n) / RATE
    high = (np.floor(t / .25) % 2).astype(float)
    k = int(RAMP * RATE)
    high = np.convolve(high, np.ones(k) / k, mode="same")
    return tone(800 + 170 * high)


def pulsed(n):
    """B: 1 kHz, 0.25 s on, 0.25 s off, four pulses."""
    return tone(np.full(n, 1000.0)) * gate(n, [(.5 * i, .5 * i + .25) for i in range(4)])


def whoop(n):
    """C: a rising sweep 600 to 1200 Hz in 0.9 s, a 0.1 s gap, twice."""
    t = np.arange(n) / RATE
    within = t % 1.0
    frequency = 600 + 600 * np.clip(within / .9, 0, 1)
    return tone(frequency) * gate(n, [(0, .9), (1.0, 1.9)])


def finish(sound):
    n = len(sound)
    sound = sound.copy()
    rise = int(.005 * RATE)
    sound[:rise] *= np.linspace(0, 1, rise)
    fade = int(.08 * RATE)
    sound[-fade:] *= np.linspace(1, 0, fade) ** 2
    sound[0] = sound[-1] = 0
    return sound * (PEAK / max(1e-12, np.max(np.abs(sound))))


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    n = int(LENGTH * RATE)
    for name, build in (("gas_alarm_a_twotone", two_tone), ("gas_alarm_b_pulsed", pulsed), ("gas_alarm_c_whoop", whoop)):
        sound = finish(build(n))
        path = output / (name + ".ogg")
        sf.write(str(path), sound, RATE, format="OGG", subtype="VORBIS")
        rms = float(np.sqrt(np.mean(sound ** 2)))
        print(f"{name}: {path.stat().st_size} bytes, {n} frames, {LENGTH:.1f} s, RMS {rms:.4f}, "
              f"peak {np.max(np.abs(sound)):.2f}, first {sound[0]:.4f}, last {sound[-1]:.4f}")


if __name__ == "__main__":
    main()
