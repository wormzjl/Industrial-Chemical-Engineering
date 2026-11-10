"""Candidate one-shots for a hand valve turning (owner 2026-11-10: "add turning sound and animation for hand valve",
"Synthesised candidates"). Authoring only; writes .ogg files to the given folder and touches nothing in the mod. Same
route as make_loops.py's pipe knock: synthesised, no recordings, 48 kHz mono Vorbis, peak 0.7, silent first and last
samples.

Physics in short: a quarter-turn ball valve's stem turns in its packing (PTFE or graphite rings). Packing friction is
stick-slip: the stem sticks, the lever winds up, the stem slips, some tens of times a second, and each slip rings the
lever and stem near a bending resonance of a few hundred hertz to about 1.5 kHz. One hears a squeak, a rough tone with
harmonics, its level pulsing at the stick-slip rate, its pitch rising a little as the turn speeds up. At the end of the
quarter turn the lever's stop lug strikes the stop plate: a sharp click, the lever's metallic rings (1 to 5 kHz,
tens of milliseconds) and a short body thud from the casting (100 to 250 Hz). The ball seating adds nothing one hears.

Usage: valve_turn_candidates.py <output folder>
"""
from pathlib import Path
import json
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))
import numpy as np
import soundfile as sf

output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
rate = 48000


def band_noise(rng, count, low, high):
    """White noise band-passed to low..high Hz in the frequency domain, unit RMS."""
    spectrum = np.fft.rfft(rng.standard_normal(count))
    f = np.fft.rfftfreq(count, 1 / rate)
    spectrum *= (f >= low) & (f <= high)
    n = np.fft.irfft(spectrum, n=count)
    return n / np.sqrt(np.mean(n ** 2))


def window(t, start, end, rise, fall):
    """1 between start and end, raised-cosine ramps of rise and fall seconds, 0 outside."""
    up = np.clip((t - start) / rise, 0, 1)
    down = np.clip((end - t) / fall, 0, 1)
    return (.5 - .5 * np.cos(np.pi * up)) * (.5 - .5 * np.cos(np.pi * down))


def squeak(rng, t, start, end, f0, f1, slip, sharpness, grit):
    """Stick-slip squeak: a tone gliding f0 -> f1 Hz with harmonics 1/n, pulsed at about slip Hz (jittered), plus a
    little 2 to 6 kHz grit noise under the same pulses."""
    s = np.clip((t - start) / (end - start), 0, 1)
    freq = f0 + (f1 - f0) * s * s * (3 - 2 * s)
    phase = 2 * np.pi * np.cumsum(freq) / rate
    tone = sum(np.sin(n * phase + rng.uniform(0, 2 * np.pi)) / n for n in (1, 2, 3, 4))
    jitter = np.cumsum(1 + .25 * band_noise(rng, len(t), .5, 12)) / rate
    pulses = (.5 + .5 * np.cos(2 * np.pi * slip * jitter)) ** sharpness
    noise = band_noise(rng, len(t), 2000, 6000)
    return window(t, start, end, .04, .06) * pulses * (tone + grit * noise)


def strike(rng, t, at, rings, body, body_tau, click):
    """The stop lug on the stop plate: a short click, decaying rings (Hz, amplitude, decay s), a body thud."""
    s = np.maximum(t - at, 0)
    on = t >= at
    out = sum(a * np.exp(-s / tau) * np.sin(2 * np.pi * fr * s + rng.uniform(0, 2 * np.pi)) for fr, a, tau in rings)
    out = out + np.exp(-s / body_tau) * np.sin(2 * np.pi * body * s)
    out = out + click * np.exp(-s / .0025) * rng.standard_normal(len(t))
    return on * out * (1 - np.exp(-s / .0004))


def candidate(seconds, seed, parts):
    count = int(seconds * rate)
    t = np.arange(count) / rate
    rng = np.random.default_rng(seed)
    sound = parts(rng, t)
    sound *= window(t, 0, seconds, .002, .04)
    return sound * (.7 / np.max(np.abs(sound)))


candidates = {
    # A: a dry packing's squeak through the quarter turn (0.05 to 0.65 s, 620 to 760 Hz, 28 slips a second), then the
    # stop's clunk at 0.68 s (rings near 1.25, 2.9 and 4.7 kHz, a 180 Hz body). 0.9 s.
    "valve_turn_A_squeak_clunk": candidate(.9, 2101, lambda rng, t:
        .55 * squeak(rng, t, .05, .66, 620, 760, 28, 3, .15)
        + strike(rng, t, .68, ((1250, .55, .045), (2900, .35, .022), (4700, .2, .010)), 180, .05, .6)),
    # B: a stiffer, gritty stem: a grinding scrape (more noise, 45 slips a second, a weaker 1.1 kHz tone) through
    # 0.05 to 0.80 s, and a heavier thunk at 0.82 s (a 95 Hz body, lower rings at 600 Hz and 1.7 kHz). 1.1 s.
    "valve_turn_B_grind_thunk": candidate(1.1, 2201, lambda rng, t:
        .45 * squeak(rng, t, .05, .80, 1050, 1150, 45, 5, .9)
        + 1.2 * strike(rng, t, .82, ((600, .5, .06), (1700, .35, .03), (3600, .15, .012)), 95, .08, .5)),
    # C: a small lever turned briskly: a short bright chirp (0.03 to 0.38 s, 950 to 1250 Hz, 35 slips a second) and a
    # light clink at 0.41 s (rings near 2.2, 3.8 and 6 kHz, a faint 220 Hz body). 0.65 s.
    "valve_turn_C_chirp_clink": candidate(.65, 2301, lambda rng, t:
        .5 * squeak(rng, t, .03, .38, 950, 1250, 35, 3, .1)
        + strike(rng, t, .41, ((2200, .6, .035), (3800, .4, .018), (6000, .25, .008)), 220, .025, .45)),
}

for name, sound in candidates.items():
    path = output / (name + ".ogg")
    sf.write(path, sound, rate, format="OGG", subtype="VORBIS")
    decoded, decoded_rate = sf.read(path)
    print(json.dumps(dict(asset=name, bytes=path.stat().st_size, frames=len(decoded), seconds=round(len(decoded) / decoded_rate, 3),
                          rate=decoded_rate, rms=round(float(np.sqrt(np.mean(decoded ** 2))), 4),
                          peak=round(float(np.max(np.abs(decoded))), 3), first=round(float(abs(decoded[0])), 4),
                          last=round(float(abs(decoded[-1])), 4))))
