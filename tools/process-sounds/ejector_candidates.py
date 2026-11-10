"""Candidate steam ejector loops for the owner to choose from (batch 2026-11-10-steam-ejector-flow, EJECTOR_FLOW_PLAN.md
section 3.2). Authoring only; writes .ogg files to the given folder and touches nothing in the mod. The valve_candidates.py
route: synthesised, periodic over the loop, no recordings, 48 kHz mono, 4 s.

Physics: the motive steam leaves its nozzle at about 800 m/s (Mach 2.4 against the ambient), a supersonic jet whose mixing
noise peaks at a Strouhal number near 0.2 on the jet's width, a few kHz for a nozzle of millimetres to centimetres, and
is steady (the jet runs at its choked flow). Its shock cells add broadband shock noise a little above the mixing peak,
and at times a screech tone. The mixed stream decelerates in the diffuser and the discharge line, whose turbulence and
the casing's give a low rumble (100 to 400 Hz). The casing passes the high band less well than the rumble.

Usage: ejector_candidates.py <output folder>
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
frequency = np.fft.rfftfreq(count, 1 / rate)
f = np.maximum(frequency, 1e-3)


def noise(seed, envelope):
    """Noise with the given amplitude spectrum, periodic over the loop, unit RMS."""
    rng = np.random.default_rng(seed)
    n = np.fft.irfft(envelope * np.exp(2j * np.pi * rng.random(len(frequency))), n=count)
    return n / np.sqrt(np.mean(n ** 2))


def band(centre, width):
    """Log-Gaussian band around centre, Hz, width in octaves."""
    return np.exp(-.5 * (np.log2(f / centre) / width) ** 2)


def hiss(peak, below_db_oct, above_db_oct, low, high):
    """A broad jet-noise spectrum: rising below_db_oct dB per octave up to peak, falling above_db_oct above it, with soft
    skirts at low and high (Hz). Amplitude spectrum."""
    octaves = np.log2(f / peak)
    db = np.where(octaves < 0, below_db_oct * octaves, -above_db_oct * octaves)
    skirt = np.exp(-.5 * (np.log2(np.maximum(low / f, 1)) / .4) ** 2) * np.exp(-.5 * (np.log2(np.maximum(f / high, 1)) / .3) ** 2)
    return 10 ** (db / 20) * skirt


def swell(seed, low, high, depth):
    """A smooth random level envelope, periodic over the loop, low to high Hz."""
    return np.exp(depth * noise(seed, np.where((frequency >= low) & (frequency <= high), 1.0, 0.0)))


RUMBLE = .25  # 12 dB under the hiss
candidates = {
    # J1: the steady supersonic hiss, 2 to 8 kHz (peak 4 kHz, +4 dB/oct below, -4 dB/oct above), with a faint fast
    # turbulence jitter, over the diffuser's 100 to 400 Hz rumble 12 dB under it, swelling a little.
    "ejector_J1_hiss_rumble": noise(2101, hiss(4000, 4, 4, 1500, 12000)) * swell(2102, 8, 30, .04)
                              + RUMBLE * noise(2103, hiss(200, 2, 6, 80, 500)) * swell(2104, .3, 2, .25),
    # J2: brighter: the hiss peaks near 6 kHz, and a faint screech tone near 3 kHz (a 1.5 % wide band, so it wanders as
    # a real screech does) at a sixth of the RMS.
    "ejector_J2_bright_screech": noise(2201, hiss(6000, 4, 4, 2000, 14000)) * swell(2202, 8, 30, .04)
                                 + RUMBLE * noise(2203, hiss(200, 2, 6, 80, 500)) * swell(2204, .3, 2, .25)
                                 + .17 * noise(2205, band(3000, .015)) * swell(2206, .2, 1, .30),
    # J3: rumble-forward: the diffuser rumble as strong as the hiss, the hiss a little lower (3.5 kHz), the whole
    # swelling slowly (about 0.3 Hz) as the discharge line breathes.
    "ejector_J3_rumble_swell": (noise(2301, hiss(3500, 4, 5, 1200, 10000)) * swell(2302, 8, 30, .04)
                                + noise(2303, hiss(180, 2, 6, 60, 500)) * swell(2304, .5, 3, .25)) * swell(2305, .25, .5, .15),
}

RMS = .0926  # the other loops' level (tools/machine-sounds)
for name, sound in candidates.items():
    sound = sound - np.mean(sound)
    sound *= RMS / np.sqrt(np.mean(sound ** 2))
    path = output / (name + ".ogg")
    sf.write(path, sound, rate, format="OGG", subtype="VORBIS")
    decoded, decoded_rate = sf.read(path)
    print(json.dumps(dict(asset=name, bytes=path.stat().st_size, frames=len(decoded), rate=decoded_rate,
                          rms=round(float(np.sqrt(np.mean(decoded ** 2))), 4), peak=round(float(np.max(np.abs(decoded))), 3),
                          seam_step=round(float(abs(decoded[0] - decoded[-1])), 4),
                          typical_step=round(float(np.mean(np.abs(np.diff(decoded)))), 4))))
