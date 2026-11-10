"""Original process loops (flare roar and pilot, still burner, firebox, boiling pot); authoring only, never part of the
mod runtime.

The tools/machine-sounds route: periodic tones whose cycles fit the loop exactly, plus noise shaped in the frequency
domain over the whole loop (so it is periodic too), and short bursts that wrap round the loop's end. Mono 24 kHz
Vorbis, 4 s. No recordings, samples or external audio. Usage: make_loops.py <output folder>.
"""
from pathlib import Path
import json
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))
import numpy as np
import soundfile as sf

output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
rate, seconds = 24000, 4
count = rate * seconds
t = np.arange(count) / rate
frequency = np.fft.rfftfreq(count, 1 / rate)


def noise(seed, envelope):
    """Noise with the given amplitude spectrum, periodic over the loop, unit RMS."""
    rng = np.random.default_rng(seed)
    n = np.fft.irfft(envelope * np.exp(2j * np.pi * rng.random(len(frequency))), n=count)
    return n / np.sqrt(np.mean(n ** 2))


def tones(parts):
    """Sum of (frequency Hz, amplitude, phase); every frequency a whole number of cycles in the loop."""
    for f, _, _ in parts:
        assert abs(f * seconds - round(f * seconds)) < 1e-9, f
    return sum(a * np.sin(2 * np.pi * f * t + p) for f, a, p in parts)


def band(centre, width):
    """A smooth band of noise around centre, Hz (log-Gaussian, width in octaves)."""
    with np.errstate(divide="ignore"):
        octaves = np.log2(np.maximum(frequency, 1) / centre)
    return np.exp(-.5 * (octaves / width) ** 2)


def between(low, high):
    """A flat band from low to high Hz with half-octave smooth skirts."""
    with np.errstate(divide="ignore"):
        f = np.maximum(frequency, 1e-3)
        below = np.exp(-.5 * (np.log2(low / f) / .5) ** 2)
        above = np.exp(-.5 * (np.log2(f / high) / .5) ** 2)
    return np.where(f < low, below, np.where(f > high, above, 1.0))


def flutter(seed, low, high, depth):
    """A smooth random level envelope, periodic over the loop, swelling low to high times a second."""
    slow = noise(seed, np.where((frequency >= low) & (frequency <= high), 1.0, 0.0))
    return np.exp(depth * slow)


def bursts(seed, number, lengths, shape, source=None, pitches=None, strength_cap=3.0):
    """number short bursts at random starts, wrapped round the loop's end so the train is periodic. Each burst is an
    exponentially decaying piece of source noise, or a decaying sine at a random pitch (Hz) when pitches is given.
    Strengths are heavy-tailed (Pareto) and capped."""
    rng = np.random.default_rng(seed)
    out = np.zeros(count)
    for start in rng.integers(0, count, number):
        length = int(rng.integers(*lengths))
        strength = min(strength_cap, rng.pareto(shape) + .3)
        k = np.arange(length)
        span = (start + k) % count
        decay = np.exp(-k / (length / 5)) * (1 - np.exp(-k / max(1, length / 60)))
        if pitches is None:
            out[span] += strength * decay * source[span]
        else:
            f0 = rng.uniform(*pitches)
            chirp = f0 * (1 + .25 * k / length)  # a bubble's resonance rises as it shrinks at the surface
            phase = 2 * np.pi * np.cumsum(chirp) / rate
            out[span] += strength * decay * np.sin(phase + rng.uniform(0, 2 * np.pi))
    return out


def soft_limit(sound, knee):
    """tanh at knee times the RMS: the loudest bursts round off instead of clipping."""
    return np.tanh(sound / (knee * np.sqrt(np.mean(sound ** 2))))


# An elevated flare's combustion roar: turbulent diffusion-flame noise, broadband with most of its energy from 40 to
# 400 Hz (peak near 100 Hz), a slow irregular flutter in level (0.5 to 2 a second) as the flame breathes in the wind,
# and a faint high jet hiss from the tip's exit on top.
flare_roar = (noise(8101, between(40, 400) * (1 + .6 * band(110, .6))) * flutter(8102, .5, 2, .32)
              + .10 * noise(8103, band(3500, .8)) * flutter(8104, .5, 2, .15))

# The pilots' gas hiss: small premixed jets, a steady 2 to 6 kHz band, almost no flutter. A third of the others' RMS.
flare_pilot = noise(8201, between(2000, 6000)) * (1 + .05 * np.cos(2 * np.pi * 1 * t))

# A pressure-jet oil or gas burner: the combustion roar (80 to 600 Hz), the combustion-air fan's blade pass
# (2,900 rpm x 12 blades = 580 Hz) with its second harmonic and a faint broadband fan rush, and the 2-pole 50 Hz fan
# motor's faint 100 Hz hum.
burner_roar = (noise(8301, between(80, 600) * (1 + .4 * band(250, .7))) * flutter(8302, 1, 4, .10)
               + .30 * noise(8303, band(1800, .8))
               + tones([(580, .32, 0), (1160, .10, .9), (100, .12, 0), (200, .05, 1.3)]))

# A solid-fuel firebox: the draught's low roar (30 to 150 Hz) through the grate, with random crackles (short bright
# bursts of 3 to 15 ms, about 25 a second) and fewer, longer pops (resin pockets and steam in the fuel, about 2 a second), heavy-
# tailed in strength, wrapped round the loop's end, rounded by a soft limit so they do not clip.
draught = noise(8401, between(30, 150)) * flutter(8402, .3, 1.5, .25)
bright = noise(8403, band(3000, 1.0) + .6 * band(900, .8))
crackles = (bursts(8404, 25 * seconds, (rate // 330, rate // 70), 1.8, bright)
            + bursts(8405, 2 * seconds, (rate // 200, rate // 60), 1.5, noise(8406, band(1200, 1.0) + band(300, .8)),
                     strength_cap=4.0))
fire_draught = soft_limit(.8 * draught + 1.2 * crackles, 3.2)

# A boiling copper pot: random bubble bursts at the surface (decaying, slightly rising resonances 150 to 800 Hz,
# about 25 a second) over a faint broadband simmer, and low thumps (40 to 80 Hz, about 2.5 a second) as larger
# vapour pockets collapse on the bottom and shake the pot.
bubbles = bursts(8501, 25 * seconds, (rate // 120, rate // 30), 2.2, pitches=(150, 800))
thumps = bursts(8502, 10, (rate // 12, rate // 6), 2.0, pitches=(40, 80), strength_cap=2.5)
pot_boil = soft_limit(bubbles + 1.1 * thumps + .12 * noise(8503, between(200, 2000)), 3.0)

# Each loop's RMS: the pump loop's level (tools/machine-sounds), the pilot hiss at a third of it.
PUMP_RMS = .0926
for name, sound, level in (("flare_roar", flare_roar, PUMP_RMS), ("flare_pilot", flare_pilot, PUMP_RMS / 3),
                           ("burner_roar", burner_roar, PUMP_RMS), ("fire_draught", fire_draught, PUMP_RMS),
                           ("pot_boil", pot_boil, PUMP_RMS)):
    sound = sound - np.mean(sound)
    sound *= level / np.sqrt(np.mean(sound ** 2))
    path = output / (name + ".ogg")
    sf.write(path, sound, rate, format="OGG", subtype="VORBIS")
    decoded, decoded_rate = sf.read(path)
    print(json.dumps(dict(asset=name, bytes=path.stat().st_size, frames=len(decoded), rate=decoded_rate,
                          rms=float(np.sqrt(np.mean(decoded ** 2))), peak=float(np.max(np.abs(decoded))),
                          seam_step=float(abs(decoded[0] - decoded[-1])),
                          typical_step=float(np.mean(np.abs(np.diff(decoded)))))))
