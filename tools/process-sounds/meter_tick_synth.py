"""The handheld pollution meter's warning clicks (batch 2026-11-15-pollution-system, WP8; plan section 11): three
one-second one-shots with 1, 3 and 8 clicks, played to the holder once a second by the worst of the four indices
(LOW: slow, MEDIUM: medium, HIGH and above: fast). Authoring only; nothing here runs in the mod.

Physics: a detector's alarm click is its piezo sounder driven by a pulse of about 100 us. What you hear is the disc's
own ring, not the pulse: a main mode at about 3.4 kHz, its second mode at about 6.8 kHz and the case's body near
1.25 kHz, each dying within a few milliseconds. Built as damped modes (the owner's "cleaner": no noise bursts); a faint
2 to 9 kHz snap 15 dB under the modes gives the edge. The clicks fall at irregular times, as a Geiger counter's, with
small click-to-click jitter in level, pitch and decay.

Usage, from the worktree root (the authoring Python of README.md):
  python -I tools/process-sounds/meter_tick_synth.py <output folder>
Writes meter_tick_slow.ogg, meter_tick_medium.ogg and meter_tick_fast.ogg: mono 48 kHz Ogg Vorbis, 1.0 s, peak 0.7,
silent first and last samples, no click in the last 0.1 s (so the clips run back to back without a pile-up).
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))

import numpy as np
import soundfile as sf

RATE = 48000
LENGTH = 1.0
PEAK = .7
MODES = ((3400, .004, 1.0), (6800, .0025, .35), (1250, .006, .45))  # Hz, T60 s, gain
SNAP = -15  # dB under the modes


def db(level):
    return 10 ** (level / 20)


def band_noise(rng, n, low, high):
    """White noise kept to low..high Hz in the frequency domain, unit RMS."""
    spectrum = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    spectrum[(f < low) | (f > high)] = 0
    out = np.fft.irfft(spectrum, n)
    return out / max(1e-12, np.sqrt(np.mean(out ** 2)))


def mode(n, frequency, decay, phase=0.0):
    """A damped mode: sin at frequency Hz falling 60 dB in decay s."""
    t = np.arange(n) / RATE
    return np.sin(2 * np.pi * frequency * t + phase) * np.exp(-6.91 * t / decay)


def click(rng):
    """One click: the three modes with a few % jitter in pitch and decay, and the faint snap."""
    n = int(.03 * RATE)
    pitch = 1 + rng.uniform(-.02, .02)
    out = sum(g * mode(n, f * pitch, d * rng.uniform(.9, 1.1), rng.uniform(0, 6.28)) for f, d, g in MODES)
    out = out / max(1e-12, np.max(np.abs(out)))
    snap = band_noise(rng, n, 2000, 9000) * np.exp(-np.arange(n) / (.0006 * RATE))
    out += db(SNAP) * snap / max(1e-12, np.max(np.abs(snap)))
    # A 0.2 ms rise, so the click starts without a step.
    rise = int(.0002 * RATE)
    out[:rise] *= np.linspace(0, 1, rise)
    return out


def times(rng, count):
    """Irregular click times in 0.03..0.88 s, at least 60 ms apart (a Geiger-like scatter, seeded)."""
    if count == 1:
        return [.05]
    while True:
        at = np.sort(rng.uniform(.03, .88, count))
        if np.all(np.diff(at) >= .06):
            return list(at)


def build(seed, count):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(LENGTH * RATE))
    for at in times(rng, count):
        sound = click(rng) * rng.uniform(.88, 1.0)
        a = int(at * RATE)
        b = min(len(out), a + len(sound))
        out[a:b] += sound[:b - a]
    fade = int(.08 * RATE)
    out[-fade:] *= np.linspace(1, 0, fade) ** 2
    out[0] = out[-1] = 0
    return out * (PEAK / np.max(np.abs(out)))


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    for name, seed, count in (("meter_tick_slow", 81, 1), ("meter_tick_medium", 83, 3), ("meter_tick_fast", 88, 8)):
        sound = build(seed, count)
        path = output / (name + ".ogg")
        sf.write(str(path), sound, RATE, format="OGG", subtype="VORBIS")
        rms = float(np.sqrt(np.mean(sound ** 2)))
        print(f"{name}: {count} clicks, {path.stat().st_size} bytes, {len(sound)} frames, {LENGTH:.1f} s, RMS {rms:.4f}, "
              f"peak {np.max(np.abs(sound)):.2f}, first {sound[0]:.4f}, last {sound[-1]:.4f}")


if __name__ == "__main__":
    main()
