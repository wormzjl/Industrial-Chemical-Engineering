"""The hand valve's turning sound, synthesised after the owner's pick of a recording (owner 2026-11-10: "R3 shower
handle", then "make synthetic sound based on this"). No recording is used: the structure, frequencies and decays
below were measured from SpliceSound's CC0 shower-handle turn (freesound 369869, the 1 s cut R3 of valve_turn_cc0.py)
and are rebuilt from noise and damped modes. Authoring only; nothing here runs in the mod.

R3, measured (5 ms envelope, 40 ms spectra), and the stage that rebuilds it:
  0.03-0.15 s  grab and rub: broadband, 2.8-3.3 kHz with a 100-125 Hz knock, -20 to -30 dB   -> grab()
  0.15-0.43 s  the turn: a fluctuating rub, 70-90 % below 300 Hz (125 Hz), parts at 650 and 1100 Hz, -9 to -19 dB -> turn()
  0.44-0.50 s  the detent: clicks ringing at 3.68 kHz with 7.36, 11.0 and 14.8 kHz, -8 to -5 dB, 250 ms to -20 dB  -> detent()
  0.54-0.66 s  the stop: the loudest, 150-175 Hz with 375-450 and 625 Hz, rebounds at +40, +75, +100 ms
               (-4, -10, -11 dB), each about 100-140 ms to -20 dB                                         -> stop()
  0.66-1.0 s   the tail: 125 Hz with weak 350 and 1175 Hz, from -17 to -40 dB over 0.3 s                  -> tail()

Usage, from the worktree root (the authoring Python of README.md):
  python -I tools/process-sounds/valve_turn_synth.py <output folder>
Writes S1 (as measured), S2 (dry: a ball valve in a pipe, no water rumble under the turn and in the tail) and S3
(heavy: the modes 0.8 times lower and longer, a larger valve); after the owner's "too dirty", C1 (dry, a quarter of
the noise), C2 (no noise: the turn's slips ring clean modes) and C3 (no noise, no turn: the grab's knock, the detent
and the stop). Mono 48 kHz Ogg Vorbis, peak 0.7 (as pipe_knock),
silent first and last samples.
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))

import numpy as np
import soundfile as sf

RATE = 48000
LENGTH = 1.0
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


def grab(out, rng, scale, grit):
    n = int(.12 * RATE)
    rub = band_noise(rng, n, 2500, 3600) * window(n, .01, .05) * (1 + .5 * band_noise(rng, n, 5, 30))
    if grit > 0:
        place(out, grit * rub, .03, -25)
    knock = mode(int(.15 * RATE), 110 * scale, .12) + .3 * grit * band_noise(rng, int(.15 * RATE), 80, 400) * np.exp(-np.arange(int(.15 * RATE)) / (.02 * RATE))
    place(out, knock, .04, -20)


def turn(out, rng, scale, water, grit, creak):
    n = int(.28 * RATE)
    if creak:
        # Clean stick-slip: each slip rings the stem's modes (650, 1100, 1700 Hz) briefly, 33 slips a second, no noise.
        sound = np.zeros(n)
        ring = int(.03 * RATE)
        for k, at in enumerate(np.arange(0, .27, 1 / 33)):
            level = .6 + .4 * np.sin(np.pi * at / .27)
            slip = (mode(ring, 650 * scale, .025, k) + .6 * mode(ring, 1100 * scale, .02, 2 * k) + .35 * mode(ring, 1700 * scale, .015, 3 * k))
            a = int(at * RATE)
            sound[a:a + ring] += level * slip[:max(0, min(ring, n - a))]
        sound += .5 * mode(n, 125 * scale, 2.0) * (1 if water else .3)
        place(out, sound * window(n, .015, .04), .155, -13)
        return
    # Stick-slip: the rub's level jumps 25 to 40 times a second, by about 6 dB.
    slips = np.repeat(rng.uniform(.5, 1, int(.28 * 33) + 1), int(RATE / 33))[:n]
    slips = np.convolve(slips, np.ones(96) / 96, mode="same")
    low = band_noise(rng, n, 70 * scale, 300 * scale) * (1.0 if water else .25)
    # R3's turn brightens as it goes: its 1-3 kHz share rises from 0.06 to 0.43.
    rise = np.linspace(.3, 1.2, n)
    mids = (band_noise(rng, n, 560 * scale, 760 * scale) * .9 + band_noise(rng, n, 1000 * scale, 1250 * scale) * .7
            + band_noise(rng, n, 1250 * scale, 2400 * scale) * .6 * rise)
    hum = mode(n, 125 * scale, 2.0) * (.8 if water else .2)
    sound = (grit * (low + mids) + hum) * slips * window(n, .015, .04)
    place(out, sound, .155, -11)


def detent(out, rng, scale, grit):
    n = int(.12 * RATE)
    for at, level in ((.445, -4), (.47, -3.5), (.5, -5)):
        click = sum(mode(n, f * scale, d, rng.uniform(0, 6.28)) * g for f, d, g in ((3680, .09, 1), (7360, .05, .5), (11040, .035, .3), (14720, .025, .2)))
        click += .4 * grit * band_noise(rng, n, 2000, 12000) * np.exp(-np.arange(n) / (.002 * RATE))
        place(out, click, at, level)


def stop(out, rng, scale, longer, grit):
    n = int(.25 * RATE)
    for at, level in ((.54, 0), (.58, -4), (.615, -10), (.64, -11)):
        body = (mode(n, 165 * scale, .11 * longer) + .55 * mode(n, 410 * scale, .07 * longer, 1.1) + .35 * mode(n, 620 * scale, .05 * longer, 2.3)
                + .2 * grit * band_noise(rng, n, 100, 2500) * np.exp(-np.arange(n) / (.004 * RATE)))
        place(out, body, at, level)


def tail(out, rng, scale, water, grit):
    n = int(.34 * RATE)
    t = np.arange(n) / RATE
    sound = grit * band_noise(rng, n, 90 * scale, 180 * scale) * (1.0 if water else .3) + .35 * mode(n, 125 * scale, .6) + .12 * mode(n, 350 * scale, .4) + .06 * mode(n, 1175 * scale, .3)
    sound *= np.exp(-t / .1) * window(n, .02, .06)
    place(out, sound, .66, -17)


def build(seed, scale=1.0, water=True, longer=1.0, grit=1.0, creak=False, rub=True):
    """grit scales every noise part (1 as measured, 0 none); creak rings the turn's slips as clean modes; rub False drops the turn."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(LENGTH * RATE))
    grab(out, rng, scale, grit)
    if rub:
        turn(out, rng, scale, water, grit, creak)
    detent(out, rng, scale, grit)
    stop(out, rng, scale, longer, grit)
    tail(out, rng, scale, water, grit)
    out *= window(len(out), .002, .06)
    out[0] = out[-1] = 0
    return out * (PEAK / np.max(np.abs(out)))


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    sets = (("valve_turn_S1_as_measured", build(31)), ("valve_turn_S2_dry", build(32, water=False)),
            ("valve_turn_S3_heavy", build(33, scale=.8, longer=1.4)),
            # Owner on S1-S3: "too dirty". Cleaner: less or no noise, the parts carried by clean modes.
            ("valve_turn_C1_soft", build(41, water=False, grit=.25)),
            ("valve_turn_C2_clean_creak", build(42, water=False, grit=0, creak=True)),
            ("valve_turn_C3_click_clunk", build(43, water=False, grit=0, rub=False)))
    for name, sound in sets:
        path = output / (name + ".ogg")
        sf.write(str(path), sound, RATE, format="OGG", subtype="VORBIS")
        rms = float(np.sqrt(np.mean(sound ** 2)))
        print(f"{name}: {path.stat().st_size} bytes, {LENGTH:.1f} s, RMS {rms:.4f}, peak {PEAK}")


if __name__ == "__main__":
    main()
