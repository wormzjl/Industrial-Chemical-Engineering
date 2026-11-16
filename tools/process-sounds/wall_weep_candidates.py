"""Candidate weeping-wall loops for the owner to choose from (batch 2026-11-16-fouling-corrosion, WP4, plan section 16,
sound set A). Authoring only; writes .ogg files to the given folder and touches nothing in the mod. The
ejector_candidates.py route: synthesised, periodic over the loop, no recordings, 48 kHz mono, 4 s.

Physics: a wall at 90 to 100 % of its corrosion allowance weeps: a pinhole that does not yet pass a measurable flow. Gas
through a sub-millimetre hole leaves near sonic speed as a tiny jet, whose mixing noise peaks at a Strouhal number near 0.2
on the hole's width: tens of kHz for a pinhole, so what is heard is the low skirt of that hiss, 3 to 10 kHz, thin and
steady with a slight flutter. Liquid seeps and gathers into drops at the shell's lowest point; a drop falling on steel or
into a puddle rings as a small bubble or plate mode: a short tone of 1 to 3 kHz that rises a little in pitch as it decays
in 10 to 40 ms (Minnaert-like), irregularly spaced, a drop every 0.5 to 1 s.

Usage: wall_weep_candidates.py <output folder>
"""
from pathlib import Path
import json
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))
import numpy as np
import soundfile as sf

output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
rate, seconds = 48000, 4
count = rate * seconds
t = np.arange(count) / rate
frequency = np.fft.rfftfreq(count, 1 / rate)
f = np.maximum(frequency, 1e-3)


def noise(seed, envelope):
    """Noise with the given amplitude spectrum, periodic over the loop, unit RMS."""
    rng = np.random.default_rng(seed)
    n = np.fft.irfft(envelope * np.exp(2j * np.pi * rng.random(len(frequency))), n=count)
    return n / np.sqrt(np.mean(n ** 2))


def hiss(peak, below_db_oct, above_db_oct, low, high):
    """A jet-noise skirt: rising below_db_oct dB per octave up to peak, falling above_db_oct above it, soft skirts at low and high (Hz)."""
    octaves = np.log2(f / peak)
    db = np.where(octaves < 0, below_db_oct * octaves, -above_db_oct * octaves)
    skirt = np.exp(-.5 * (np.log2(np.maximum(low / f, 1)) / .4) ** 2) * np.exp(-.5 * (np.log2(np.maximum(f / high, 1)) / .3) ** 2)
    return 10 ** (db / 20) * skirt


def swell(seed, low, high, depth):
    """A smooth random level envelope, periodic over the loop, low to high Hz."""
    return np.exp(depth * noise(seed, np.where((frequency >= low) & (frequency <= high), 1.0, 0.0)))


def drips(seed, times, pitch, spread, decay, rise, level, splash=0.0):
    """Drops at the given times (s, wrapped over the loop): each a damped tone starting near pitch (Hz, +- spread share),
    rising by rise (share) over its decay (T60, s), its own level (+- 30 %), and an optional short splash of band noise."""
    rng = np.random.default_rng(seed)
    out = np.zeros(count)
    length = int(decay * rate * 1.2)
    tau = np.arange(length) / rate
    for start in times:
        f0 = pitch * (1 + spread * (2 * rng.random() - 1))
        t60 = decay * (.7 + .6 * rng.random())
        inst = f0 * (1 + rise * np.minimum(tau / t60, 1))
        phase = 2 * np.pi * np.cumsum(inst) / rate
        drop = np.sin(phase) * np.exp(-6.91 * tau / t60) * (1 - np.exp(-tau / .0005))
        drop += .35 * np.sin(2.7 * phase + 1) * np.exp(-6.91 * tau / (.5 * t60))
        if splash > 0:
            burst = rng.standard_normal(length) * np.exp(-tau / .003)
            spectrum = np.fft.rfft(burst)
            band = np.fft.rfftfreq(length, 1 / rate)
            spectrum *= np.exp(-.5 * (np.log2(np.maximum(band, 1) / 4000) / .8) ** 2)
            drop += splash * np.fft.irfft(spectrum, n=length) / max(1e-9, np.max(np.abs(np.fft.irfft(spectrum, n=length))))
        index = (int(start * rate) + np.arange(length)) % count
        np.add.at(out, index, level * (.7 + .6 * rng.random()) * drop)
    return out


def scaled(sound, rms):
    return sound / np.sqrt(np.mean(sound ** 2)) * rms


# Irregular drop times over the 4 s loop (a drop every 0.5 to 1 s), one set per candidate.
TIMES_1 = [.21, .93, 1.48, 2.36, 2.97, 3.62]
TIMES_2 = [.12, .71, 1.19, 1.87, 2.30, 2.91, 3.44]
TIMES_3 = [.55, 1.95, 3.20]
candidates = {
    # W1: the thin pinhole hiss (peak 6 kHz, 3 to 12 kHz) with a slight 2 to 6 Hz flutter, and clear drips of 1.6 kHz
    # rising 15 % over 25 ms about 6 dB over the hiss's RMS.
    "wall_weep_W1_hiss_drips": scaled(noise(3101, hiss(6000, 6, 5, 2500, 12000)) * swell(3102, 2, 6, .12), 1)
                               + scaled(drips(3103, TIMES_1, 1600, .25, .025, .15, 1), 2),
    # W2: drips forward: the hiss 18 dB under them, lower and duller drops (1.1 kHz, 40 ms) on a puddle, each with a faint
    # splash; reads as water rather than gas.
    "wall_weep_W2_drips_puddle": scaled(noise(3201, hiss(5000, 6, 6, 2500, 10000)) * swell(3202, 1, 4, .10), .125)
                                 + scaled(drips(3203, TIMES_2, 1100, .3, .04, .25, 1, splash=.3), 1),
    # W3: hiss forward: a gas seep, brighter (8 kHz) with a slow 0.5 to 1.5 Hz breathing, and three faint high drops
    # (2.4 kHz, 15 ms) 9 dB under it.
    "wall_weep_W3_seep_hiss": scaled(noise(3301, hiss(8000, 5, 4, 3000, 14000)) * swell(3302, .5, 1.5, .25) * swell(3304, 3, 8, .06), 1)
                              + scaled(drips(3303, TIMES_3, 2400, .2, .015, .1, 1), .35),
}

RMS = .0926  # the other loops' level (tools/machine-sounds)
for name, sound in candidates.items():
    sound = sound - np.mean(sound)
    sound *= RMS / np.sqrt(np.mean(sound ** 2))
    sound = 3 * RMS * np.tanh(sound / (3 * RMS))  # soft limit for the drops' peaks
    sound *= RMS / np.sqrt(np.mean(sound ** 2))
    path = output / (name + ".ogg")
    sf.write(path, sound, rate, format="OGG", subtype="VORBIS")
    decoded, decoded_rate = sf.read(path)
    print(json.dumps(dict(asset=name, bytes=path.stat().st_size, frames=len(decoded), rate=decoded_rate,
                          rms=round(float(np.sqrt(np.mean(decoded ** 2))), 4), peak=round(float(np.max(np.abs(decoded))), 3),
                          seam_step=round(float(abs(decoded[0] - decoded[-1])), 4),
                          typical_step=round(float(np.mean(np.abs(np.diff(decoded)))), 4))))
