"""A pipe a blast destroys (batch 2026-11-15-pollution-system, WP6a; owner 2026-11-15: "need a damaged pipe SFX as well"):
a metal rupture, then what escapes. Synthesised by the models-and-sfx skill's recipes (damped modes for the strike,
band noise for the jet); authoring only, nothing here runs in the mod.

Physics, for a 50 to 150 mm steel line:
  rupture   a pressure thump (the released gas's first pulse, 70 to 110 Hz) and the wall tearing: three to five cracks
            30 to 60 ms apart, each a damped ring of the pipe shell (1.3, 2.1 and 3.4 kHz, 4 to 9 ms) -> rupture()
  gas       a choked jet: its noise peaks near f = St U / D = 0.2 x 300 m/s / 0.05 m, 1.2 kHz, broadband 0.9 to 7 kHz,
            falling as the line blows down (time constant 0.7 s), with a faint 3 kHz edge tone      -> hiss()
  liquid    a gush: a 150 to 1500 Hz rush falling over 0.9 s, the splatter of drops on the ground (damped 450 to
            1400 Hz rings, 25 a second) and a 60 Hz surge                                          -> gush()
  empty     the rupture alone, its shell ringing out over 0.3 s                                    -> ring()

Usage, from the worktree root (the authoring Python of README.md):
  python -I tools/process-sounds/pipe_burst_synth.py <output folder>
Writes pipe_burst_gas, pipe_burst_liquid and pipe_burst_empty (the installed sounds) and, for the owner's pick of the
rupture's character, pipe_burst_gas_A_clean (as installed), _B_heavy (modes 0.7 times lower and longer, a large line) and
_C_bright (a thin small-bore line). Mono 48 kHz Ogg Vorbis, peak 0.7 (as pipe_knock), a 5 ms fade in and an 80 ms
squared fade out, silent first and last samples. The broadband floor of the rupture stays 13 dB under its modes.
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))

import numpy as np
import soundfile as sf

RATE = 48000
PEAK = .7


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


def place(out, sound, at, level):
    a = int(at * RATE)
    b = min(len(out), a + len(sound))
    if b > a:
        out[a:b] += db(level) * sound[:b - a] / max(1e-12, np.max(np.abs(sound)))


def window(n, attack, release):
    w = np.ones(n)
    a, r = int(attack * RATE), int(release * RATE)
    if a:
        w[:a] = np.linspace(0, 1, a)
    if r:
        w[-r:] *= np.linspace(1, 0, r) ** 2
    return w


def rupture(out, rng, scale, longer):
    """The thump and the tearing cracks: clean damped modes, a short noise burst 13 dB under each."""
    n = int(.35 * RATE)
    thump = mode(n, 85 * scale, .22 * longer) + .5 * mode(n, 140 * scale, .12 * longer, 1.3)
    place(out, thump, .01, -2)
    cracks = ((.012, 0), (.045, -3), (.083, -6), (.118, -9), (.16, -13))
    m = int(.08 * RATE)
    for k, (at, level) in enumerate(cracks):
        jitter = 1 + rng.uniform(-.04, .04)
        ring = (mode(m, 1300 * scale * jitter, .009 * longer, rng.uniform(0, 6.28))
                + .6 * mode(m, 2100 * scale * jitter, .006 * longer, rng.uniform(0, 6.28))
                + .35 * mode(m, 3400 * scale * jitter, .004 * longer, rng.uniform(0, 6.28)))
        burst = band_noise(rng, m, 800, 9000) * np.exp(-np.arange(m) / (.0015 * RATE))
        place(out, ring / np.max(np.abs(ring)) + db(-13) * burst / np.max(np.abs(burst)), at, level - 1)


def ring(out, rng, scale, longer):
    """The torn shell ringing out after the rupture (an empty line)."""
    n = int(.45 * RATE)
    shell = mode(n, 1420 * scale, .3 * longer) + .5 * mode(n, 2350 * scale, .2 * longer, .7) + .25 * mode(n, 3900 * scale, .12 * longer, 2.1)
    place(out, shell * window(n, .004, .05), .17, -14)


def hiss(out, rng, length):
    """A choked jet blowing the line down: 0.9 to 7 kHz, its level falling with a 0.7 s constant; a faint edge tone."""
    start = .02
    n = int((length - start) * RATE)
    t = np.arange(n) / RATE
    jet = band_noise(rng, n, 900, 7000) + .6 * band_noise(rng, n, 1000, 1600)
    flutter = 1 + .12 * band_noise(rng, n, 2, 9) / 3
    edge = .06 * np.sin(2 * np.pi * (3000 - 400 * t / t[-1]) * t)
    sound = (jet * flutter + edge) * np.exp(-t / .7) * window(n, .006, .25)
    place(out, sound, start, -6)


def gush(out, rng, length):
    """A liquid gush: a 150 to 1500 Hz rush falling over 0.9 s, splatter rings, and a 60 Hz surge."""
    start = .025
    n = int((length - start) * RATE)
    t = np.arange(n) / RATE
    rush = band_noise(rng, n, 150, 1500) * (1 + .25 * band_noise(rng, n, 3, 12) / 3)
    surge = .5 * np.sin(2 * np.pi * 60 * t) * np.exp(-t / .5)
    body = (rush + surge) * np.exp(-t / .9) * window(n, .01, .3)
    place(out, body, start, -7)
    m = int(.04 * RATE)
    for at in np.arange(.08, length - .3, 1 / 25):
        at += rng.uniform(-.015, .015)
        f = rng.uniform(450, 1400)
        drop = mode(m, f, .02, rng.uniform(0, 6.28)) + .4 * mode(m, f * 1.7, .012)
        place(out, drop, at, -19 - 9 * at / length + rng.uniform(-3, 2))


def build(seed, kind, scale=1.0, longer=1.0):
    length = {"gas": 2.2, "liquid": 2.0, "empty": .9}[kind]
    rng = np.random.default_rng(seed)
    out = np.zeros(int(length * RATE))
    rupture(out, rng, scale, longer)
    if kind == "gas":
        hiss(out, rng, length)
    elif kind == "liquid":
        gush(out, rng, length)
    else:
        ring(out, rng, scale, longer)
    out *= window(len(out), .005, .08)
    out[0] = out[-1] = 0
    return out * (PEAK / np.max(np.abs(out))), length


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    sets = (("pipe_burst_gas", build(61, "gas")), ("pipe_burst_liquid", build(62, "liquid")), ("pipe_burst_empty", build(63, "empty")),
            ("pipe_burst_gas_A_clean", build(61, "gas")), ("pipe_burst_gas_B_heavy", build(64, "gas", scale=.7, longer=1.5)),
            ("pipe_burst_gas_C_bright", build(65, "gas", scale=1.35, longer=.7)))
    for name, (sound, length) in sets:
        path = output / (name + ".ogg")
        sf.write(str(path), sound, RATE, format="OGG", subtype="VORBIS")
        rms = float(np.sqrt(np.mean(sound ** 2)))
        print(f"{name}: {path.stat().st_size} bytes, {length:.1f} s, RMS {rms:.4f}, peak {float(np.max(np.abs(sound))):.3f}, first {sound[0]:.1f}, last {sound[-1]:.1f}")


if __name__ == "__main__":
    main()
