"""Candidate valve loops for the owner to choose from (2026-11-10, owner: "Valve sound is not good, find a better
example"). Authoring only; writes .ogg files to the given folder and touches nothing in the mod. Same route as
make_loops.py (synthesised, periodic over the loop, no recordings), but at 48 kHz so the hiss can reach 10 kHz.

Physics (IEC 60534-8-3 in short): a throttled gas jet's noise peaks inside the pipe at f_p ~ 0.2 U_vc / D_j, a few
kHz for a control valve; the pipe wall passes it best near and above its ring frequency, so outside one hears a broad
hiss rising to a peak of 2 to 6 kHz and falling slowly above it, a low rush from the downstream pipe's turbulence
(50 to 400 Hz) that swells irregularly, and at high drops often a weak whistle from a cavity or cage port. A liquid
valve that does not cavitate is a rush of flowing water (100 Hz to 2 kHz) with a slow gurgle; one that flashes or
cavitates adds a sparse gravel crackle.

Usage: valve_candidates.py <output folder>
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


def band(centre, width):
    """Log-Gaussian band around centre, Hz, width in octaves."""
    return np.exp(-.5 * (np.log2(f / centre) / width) ** 2)


def hiss(peak, below_db_oct, above_db_oct, low, high):
    """A broad jet-noise spectrum: rising below_db_oct dB per octave up to peak, falling above_db_oct above it,
    with soft skirts at low and high (Hz). Amplitude spectrum."""
    octaves = np.log2(f / peak)
    db = np.where(octaves < 0, below_db_oct * octaves, -above_db_oct * octaves)
    skirt = np.exp(-.5 * (np.log2(np.maximum(low / f, 1)) / .4) ** 2) * np.exp(-.5 * (np.log2(np.maximum(f / high, 1)) / .3) ** 2)
    return 10 ** (db / 20) * skirt


def swell(seed, low, high, depth):
    """A smooth random level envelope, periodic over the loop, low to high Hz."""
    return np.exp(depth * noise(seed, np.where((frequency >= low) & (frequency <= high), 1.0, 0.0)))


def crackle(seed, per_second, source):
    """Sparse short bursts of source noise (2 to 8 ms), heavy-tailed in strength, wrapped round the loop's end."""
    rng = np.random.default_rng(seed)
    out = np.zeros(count)
    for start in rng.integers(0, count, per_second * seconds):
        length = int(rng.integers(rate // 500, rate // 125))
        k = np.arange(length)
        decay = np.exp(-k / (length / 5)) * (1 - np.exp(-k / max(1, length / 40)))
        out[(start + k) % count] += min(3.0, rng.pareto(1.8) + .3) * decay * source[(start + k) % count]
    return out


candidates = {
    # A: what one hears beside a throttling steam or gas control valve: a broad hiss peaking near 4 kHz (+3 dB/oct
    # below, -5 dB/oct above, up to 10 kHz), a downstream rush 50 to 400 Hz at a quarter of the RMS swelling 0.3 to
    # 2 times a second, and a fast faint turbulence jitter on the hiss.
    "valve_gas_A_hiss": noise(1101, hiss(4000, 3, 5, 300, 10000)) * swell(1102, 8, 30, .05)
                        + .25 * noise(1103, hiss(120, 0, 6, 50, 400)) * swell(1104, .3, 2, .35),
    # B: the same valve heard through a thick or lagged pipe: the peak lower (2 kHz), less above 5 kHz, and the
    # downstream pipe rush much stronger (a roar under the hiss).
    "valve_gas_B_roar": noise(1201, hiss(2000, 3, 7, 200, 8000)) * swell(1202, 6, 25, .06)
                        + .55 * noise(1203, hiss(150, 0, 6, 40, 600)) * swell(1204, .3, 2, .40),
    # C: A at a high drop, with a weak whistle from a cage port: a narrow band near 3.3 kHz (a 2 % wide band, so it
    # wanders like a real whistle) at a fifth of the RMS, swelling slowly.
    "valve_gas_C_whistle": noise(1301, hiss(4500, 3, 5, 300, 10000)) * swell(1302, 8, 30, .05)
                           + .25 * noise(1303, hiss(120, 0, 6, 50, 400)) * swell(1304, .3, 2, .35)
                           + .20 * noise(1305, band(3300, .02)) * swell(1306, .2, 1, .30),
    # L1: a liquid valve that does not cavitate: rushing water, 100 Hz to 2 kHz (peak near 400 Hz), with a slow
    # gurgle (level swelling 1 to 4 times a second) and a faint 2 to 5 kHz splash hiss.
    "valve_liquid_L1_rush": noise(1401, hiss(400, 3, 6, 100, 3000)) * swell(1402, 1, 4, .25)
                            + .12 * noise(1403, hiss(3000, 0, 6, 2000, 6000)),
    # L2: the same valve flashing or cavitating: the rush plus about 60 gravel crackles a second (bursts of 1 to
    # 6 kHz noise), far sparser than the pump's cavitation loop.
    "valve_liquid_L2_cavitating": noise(1501, hiss(400, 3, 6, 100, 3000)) * swell(1502, 1, 4, .25)
                                  + 1.2 * crackle(1503, 60, noise(1504, hiss(2500, 3, 4, 1000, 8000))),
}


def rattle(seed, per_second, rings, body, cluster):
    """A control valve's plug rattling in its guide: the flashing flow shakes the plug-stem near its natural frequency
    (2 to 7 kHz), so the plug strikes the guide in irregular clusters. Each strike is a click with decaying metallic
    rings (frequency Hz, amplitude, decay s) and a weaker body knock near body Hz; strikes come per_second on average,
    bunched by a slow cluster envelope (cluster Hz), wrapped round the loop's end."""
    rng = np.random.default_rng(seed)
    out = np.zeros(count)
    gate = swell(seed + 1, cluster / 2, cluster * 2, .9)
    length = rate // 25
    k = np.arange(length) / rate
    for start in rng.integers(0, count, per_second * seconds):
        if rng.random() > min(1, gate[start] / 2):
            continue
        strength = min(2.5, rng.pareto(2.0) + .4)
        jitter = rng.uniform(.97, 1.03)
        strike = sum(a * np.exp(-k / tau) * np.sin(2 * np.pi * fr * jitter * k + rng.uniform(0, 2 * np.pi)) for fr, a, tau in rings)
        strike = strike + .5 * np.exp(-k / .006) * np.sin(2 * np.pi * body * k) + .6 * np.exp(-k / .0008) * rng.standard_normal(length)
        out[(start + np.arange(length)) % count] += strength * strike
    return out


METAL = ((2600, .8, .012), (4300, .5, .008), (6100, .3, .005))
# F1 (owner 2026-11-10: a flashing liquid valve, "control valve rattling" for reference): the flashing jet's steady
# two-phase hiss (2 to 8 kHz, peak near 4 kHz) over a low two-phase rush (100 Hz to 1 kHz) swelling irregularly, and
# the plug rattling in its guide: about 40 strikes a second, bunched in clusters 2 to 3 times a second.
candidates["valve_liquid_F1_flashing_rattle"] = (noise(1601, hiss(4000, 4, 5, 1500, 10000)) * swell(1602, 5, 20, .10)
                                                 + .45 * noise(1603, hiss(300, 2, 6, 100, 1200)) * swell(1604, .5, 3, .35)
                                                 + .9 * rattle(1605, 40, METAL, 600, 2.5))
# F2: the same valve rattling hard: about 80 strikes a second in tighter clusters, the hiss a little lower.
candidates["valve_liquid_F2_hard_rattle"] = (.8 * noise(1701, hiss(4000, 4, 5, 1500, 10000)) * swell(1702, 5, 20, .10)
                                             + .45 * noise(1703, hiss(300, 2, 6, 100, 1200)) * swell(1704, .5, 3, .35)
                                             + 1.4 * rattle(1705, 80, METAL, 600, 4))

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
