"""Candidate prime mover loops and their start and stop one-shots (batch 2026-11-10-prime-movers; owner: "engine sound
not good", "add working sound & animation to them"). Authoring only; writes .ogg files to the given folder and touches
nothing in the mod. Same route as make_loops.py (synthesised, periodic over the loop, no recordings), at 48 kHz.

Physics. A four-stroke engine fires cylinders x rpm / 120 times a second: a V16 at 1,500 rpm 200 Hz, a V18 at 750 rpm
112.5 Hz, a V20 at 500 rpm 83 Hz. Each firing is an exhaust blow-down pulse: a short broadband burst through the
manifold (its own decay, a few milliseconds) over a low thump from the exhaust's pipe resonance. Cylinders differ a
little in strength, so the sound throbs at the cycle rate (rpm / 120); cycle-to-cycle combustion jitters each pulse's
time and strength by a few per cent. The valve train ticks twice per cylinder per cycle; the turbocharger whines at its
blade pass (several kHz). The game pitches a loop by rpm over its authored rpm, clamped 0.5 to 2, so one loop cannot
cover 500 to 1,500 rpm: each candidate has a high-speed loop authored at 1,500 rpm (16 cylinders) and a medium-speed
loop authored at 600 rpm (18 cylinders: 500 rpm plays at 0.83, 750 rpm at 1.25).

A gas turbine: the compressor's blade passes (3,000 rpm x 30 blades = 1,500 Hz and its front stages near 5.5 kHz),
the combustion and exhaust roar (broadband, most below 2 kHz), the inlet's air hiss (broadband 2 to 10 kHz).

Usage: prime_candidates.py <output folder>
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
PUMP_RMS = .0926


def noise(seed, envelope, n=count):
    """Noise with the given amplitude spectrum (over rfftfreq(n)), periodic over n samples, unit RMS."""
    rng = np.random.default_rng(seed)
    x = np.fft.irfft(envelope * np.exp(2j * np.pi * rng.random(len(envelope))), n=n)
    return x / np.sqrt(np.mean(x ** 2))


def between(low, high, freqs=f):
    """A flat band from low to high Hz with half-octave skirts."""
    below = np.exp(-.5 * (np.log2(low / freqs) / .5) ** 2)
    above = np.exp(-.5 * (np.log2(freqs / high) / .5) ** 2)
    return np.where(freqs < low, below, np.where(freqs > high, above, 1.0))


def band(centre, width, freqs=f):
    return np.exp(-.5 * (np.log2(freqs / centre) / width) ** 2)


def swell(seed, low, high, depth):
    return np.exp(depth * noise(seed, np.where((frequency >= low) & (frequency <= high), 1.0, 0.0)))


def tones(parts):
    for fr, _, _ in parts:
        assert abs(fr * seconds - round(fr * seconds)) < 1e-9, fr
    return sum(a * np.sin(2 * np.pi * fr * t + p) for fr, a, p in parts)


def engine(seed, rpm, cylinders, style):
    """A running engine loop at rpm. style: 'drone' (smooth genset, turbo whine), 'beat' (slow heavy beat, valve-train
    clatter) or 'knock' (mostly mechanical knock and slap, little whistle)."""
    rng = np.random.default_rng(seed)
    firing, cycle = cylinders * rpm / 120, rpm / 120
    assert abs(firing * seconds - round(firing * seconds)) < 1e-9 and abs(cycle * seconds - round(cycle * seconds)) < 1e-9
    strength = 1 + {"drone": .08, "beat": .22, "knock": .15}[style] * rng.standard_normal(cylinders)  # each cylinder's own
    burst_band = {"drone": between(250, 1200), "beat": between(120, 700), "knock": between(600, 3000) * (1 + band(1400, .4))}[style]
    source = noise(seed + 1, burst_band)
    tau0 = {"drone": .0022, "beat": .0055, "knock": .0016}[style]
    thump_f = {"drone": 95, "beat": 55, "knock": 80}[style]
    jitter = {"drone": .015, "beat": .03, "knock": .04}[style]
    out = np.zeros(count)
    events = int(round(firing * seconds))
    for e in range(events):
        start = int(round((e / firing + jitter / firing * rng.standard_normal()) * rate)) % count
        a = strength[e % cylinders] * (1 + jitter * 3 * rng.standard_normal())
        tau = tau0 * (1 + .2 * rng.standard_normal())
        k = np.arange(int(rate * max(8 * tau, 7 * 2.5 / thump_f)))  # both decays to under 0.1 %
        s = k / rate
        span = (start + k) % count
        burst = np.exp(-s / max(tau, 4e-4)) * (1 - np.exp(-s / 2e-4)) * source[span]
        thump = .6 * np.exp(-s / (2.5 / thump_f)) * np.sin(2 * np.pi * thump_f * s)
        out[span] += a * (burst + thump)
    low = noise(seed + 2, between(30, 160)) * (1 + .3 * np.sin(2 * np.pi * cycle * t))  # the exhaust's low end
    sound = out / np.sqrt(np.mean(out ** 2)) + .45 * low
    if style == "drone":
        whine = 2 * np.pi * np.cumsum(4200 * (1 + .004 * np.sin(2 * np.pi * .25 * t))) / rate
        sound += .10 * np.sin(whine) + .05 * noise(seed + 3, band(4200, .15)) + tones([(cycle, .25, 0), (2 * cycle, .2, .5)])
    elif style == "beat":
        ticks = np.zeros(count)
        tick_src = noise(seed + 4, between(2000, 6000))
        for e in range(int(round(2 * firing * seconds))):
            start = int(round((e / (2 * firing) + .1 / firing * rng.random()) * rate)) % count
            k = np.arange(int(.004 * rate))
            span = (start + k) % count
            ticks[span] += (.6 + .4 * rng.random()) * np.exp(-k / (.0008 * rate)) * tick_src[span]
        sound += .55 * ticks / np.sqrt(np.mean(ticks ** 2)) + .04 * np.sin(2 * np.pi * 2500 * t) + tones([(cycle, .4, 0), (2 * cycle, .3, .9)])
    else:
        slap = np.zeros(count)
        for e in range(events):
            start = int(round((e / firing + .3 / firing) * rate)) % count
            k = np.arange(int(.045 * rate))
            s = k / rate
            span = (start + k) % count
            slap[span] += (.7 + .3 * rng.random()) * np.exp(-s / .006) * np.sin(2 * np.pi * (320 + 40 * rng.standard_normal()) * s)
        sound += .7 * slap / np.sqrt(np.mean(slap ** 2)) + .02 * np.sin(2 * np.pi * 3600 * t)
    return np.tanh(sound / (3.2 * np.sqrt(np.mean(sound ** 2))))


def turbine(seed, style):
    """A gas turbine loop. style: 'whine' (whine plus roar), 'roar' (roar forward) or 'inlet' (inlet hiss)."""
    roar = noise(seed, between(60, 1800) * (1 + .7 * band(250, .8))) * swell(seed + 1, .2, 1.0, .08)
    whine = tones([(1500, .30, 0), (3000, .09, .8), (1475, .05, .3), (1525, .05, 1.9), (5500, .08, 1.4), (50, .3, .2), (100, .1, 1.1)])
    hiss = noise(seed + 2, between(2500, 10000) * (1 + .5 * band(5000, .6))) * swell(seed + 3, .5, 2, .05)
    level = {"whine": (1.0, 1.6, .25), "roar": (1.6, .45, .2), "inlet": (.9, .8, 1.2)}[style]
    return level[0] * roar + level[1] * whine / .35 * .35 + level[2] * hiss


def write(name, sound, loop=True, peak=None):
    sound = sound - np.mean(sound)
    if peak is None:
        sound *= PUMP_RMS / np.sqrt(np.mean(sound ** 2))
    else:
        sound *= peak / np.max(np.abs(sound))
    path = output / (name + ".ogg")
    sf.write(path, sound, rate, format="OGG", subtype="VORBIS")
    decoded, decoded_rate = sf.read(path)
    info = dict(asset=name, bytes=path.stat().st_size, seconds=len(decoded) / decoded_rate, rms=round(float(np.sqrt(np.mean(decoded ** 2))), 4),
                peak=round(float(np.max(np.abs(decoded))), 3))
    if loop:
        info.update(seam_step=round(float(abs(decoded[0] - decoded[-1])), 4), typical_step=round(float(np.mean(np.abs(np.diff(decoded)))), 4))
    else:
        info.update(first=round(float(abs(decoded[0])), 4), last=round(float(abs(decoded[-1])), 4))
    print(json.dumps(info))


for letter, style, seed in (("A", "drone", 9300), ("B", "beat", 9400), ("C", "knock", 9500)):
    write(f"engine_{letter}_{style}_hs1500", engine(seed, 1500, 16, style))
    write(f"engine_{letter}_{style}_ms600", engine(seed + 50, 600, 18, style))
for letter, style, seed in (("A", "whine", 9600), ("B", "roar", 9700), ("C", "inlet", 9800)):
    write(f"gas_turbine_{letter}_{style}", turbine(seed, style))


# One-shots: not loops; silent first and last samples, peak 0.7 as the pipe knock.
def shot(duration):
    n = int(duration * rate)
    return n, np.arange(n) / rate


def shaped_noise(seed, n, envelope_fn):
    freqs = np.maximum(np.fft.rfftfreq(n, 1 / rate), 1e-3)
    return noise(seed, envelope_fn(freqs), n=n)


def pulses(times, n, source, tau, thump_f, seed):
    """Firing pulses at the given times (s): each a decaying burst of source noise over a low thump."""
    rng = np.random.default_rng(seed)
    out = np.zeros(n)
    for when, strength in times:
        start = int(when * rate)
        k = np.arange(min(n - start, int(rate * max(8 * tau, 7 * 2.5 / thump_f))))
        if len(k) <= 0:
            continue
        s = k / rate
        out[start + k] += strength * (1 + .1 * rng.standard_normal()) * (np.exp(-s / tau) * source[start + k] + .6 * np.exp(-s / (2.5 / thump_f)) * np.sin(2 * np.pi * thump_f * s))
    return out


def ramp_events(t0, t1, f0, f1):
    """Event times from t0 to t1 with the rate rising (or falling) linearly from f0 to f1 per second."""
    out, when = [], t0
    while when < t1:
        x = (when - t0) / (t1 - t0)
        out.append(when)
        when += 1 / (f0 + (f1 - f0) * x)
    return out


def fade(sound, n, attack=.01, release=.25):
    k = np.arange(n) / rate
    return sound * np.minimum(1, k / attack) * np.minimum(1, (n / rate - k) / release)


# A medium-speed set's air start: the starting-air valve's blast (a 1.5 to 8 kHz hiss, 0.15 s rise, held 1.2 s, then
# cut), the first compression chuffs as air turns it (2 to 6 a second), then it catches: firing pulses rising from
# 15 to 90 a second over 1.6 s (the 600 rpm loop's rate), the last second fading so the loop takes over.
n, k = shot(3.6)
air = shaped_noise(9901, n, lambda q: between(1500, 8000, q)) * np.clip(k / .15, 0, 1) * np.clip((1.5 - k) / .1, 0, 1)
src = shaped_noise(9902, n, lambda q: between(120, 700, q))
chuffs = pulses([(w, .8) for w in ramp_events(.25, 1.4, 2, 6)], n, src, .02, 50, 9903)
catch = pulses([(w, 1.0) for w in ramp_events(1.4, 3.6, 15, 90)], n, src, .006, 55, 9904)
write("engine_start_air", fade(.5 * air + chuffs + catch, n, release=.9), loop=False, peak=.7)

# A high-speed set's electric start: the starter motor's whine (gear mesh rising 300 to 900 Hz in 1 s with its
# second harmonic), compression beats at the cranking rate (4 to 8 a second), then it catches and runs up: firing
# pulses from 40 to 200 a second over 1.2 s, the starter cut.
n, k = shot(3.0)
mesh = 2 * np.pi * np.cumsum(300 + 600 * np.clip(k / 1.0, 0, 1)) / rate
starter = (np.sin(mesh) + .3 * np.sin(2 * mesh)) * np.clip(k / .05, 0, 1) * np.clip((1.3 - k) / .15, 0, 1)
src = shaped_noise(9911, n, lambda q: between(250, 1200, q))
beats = pulses([(w, .5) for w in ramp_events(.1, 1.1, 4, 8)], n, src, .015, 70, 9912)
run = pulses([(w, 1.0) for w in ramp_events(1.1, 3.0, 40, 200)], n, src, .0025, 95, 9913)
write("engine_start_electric", fade(.35 * starter + beats + run, n, release=.8), loop=False, peak=.7)

# An engine's stop: the fuel cut, the last weak firings (0.4 s), then the run-down: compression beats slowing from 12 to
# 1 a second as the flywheel coasts, a low rumble falling away, a last settle.
n, k = shot(3.5)
src = shaped_noise(9921, n, lambda q: between(150, 900, q))
last = pulses([(w, .7) for w in ramp_events(0, .4, 60, 30)], n, src, .003, 70, 9922)
coast = pulses([(w, .45 * (1 - w / 3.4)) for w in ramp_events(.4, 3.3, 12, 1)], n, src, .02, 45, 9923)
rumble = shaped_noise(9924, n, lambda q: between(30, 150, q)) * np.exp(-k / .9) * .3
write("engine_stop", fade(last + coast + rumble, n, release=.4), loop=False, peak=.7)

# A gas turbine's start: the starter turns it, the whine rising with speed (blade pass 0 to 1,500 Hz along a starter's
# S-curve over 7 s), light-off at 2 s (a soft whump and the roar rising), the inlet hiss growing with the flow.
n, k = shot(8.0)
speed = 1 / (1 + np.exp(-(k - 3.8) / .9))
speed = (speed - speed[0]) / (speed[-1] - speed[0])
whine = 2 * np.pi * np.cumsum(1500 * speed) / rate
roar = shaped_noise(9931, n, lambda q: between(60, 1800, q)) * np.clip((k - 2) / 1.5, 0, 1) * speed
hiss = shaped_noise(9932, n, lambda q: between(2500, 10000, q)) * speed ** 2
whump = np.where(k > 2, np.exp(-(k - 2) / .25) * np.sin(2 * np.pi * 45 * (k - 2)), 0)
write("gas_turbine_start", fade((.5 * np.sin(whine) + .15 * np.sin(2 * whine)) * speed + .8 * roar + .25 * hiss + .6 * whump, n, attack=.3, release=1.2),
      loop=False, peak=.7)

# Its stop: the fuel cut (the roar falls in 1 s), the rotor coasting down, the whine falling from 1,500 Hz to near
# nothing over 9 s (an exponential spool-down), the hiss with it.
n, k = shot(9.0)
speed = np.exp(-k / 2.6)
whine = 2 * np.pi * np.cumsum(1500 * speed) / rate
roar = shaped_noise(9941, n, lambda q: between(60, 1800, q)) * np.exp(-k / .6)
hiss = shaped_noise(9942, n, lambda q: between(2500, 10000, q)) * speed ** 2
write("gas_turbine_stop", fade((.5 * np.sin(whine) + .15 * np.sin(2 * whine)) * speed + .8 * roar + .25 * hiss, n, attack=.02, release=1.0), loop=False,
      peak=.7)
