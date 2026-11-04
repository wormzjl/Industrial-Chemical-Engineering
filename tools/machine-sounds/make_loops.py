"""Original machine loops (pump and compressor casings, motor, steam turbine, a pump's cavitation and gas); authoring only, never part of the mod runtime.

The same route as tools/pump-compressor-controls/make_loops.py: periodic tones whose cycles fit the loop exactly,
plus noise shaped in the frequency domain over the whole loop (so it is periodic too), mono 24 kHz Vorbis, 3 s.
No recordings or external audio. Usage: make_loops.py <output folder>.
"""
from pathlib import Path
import json
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "pump-compressor-controls" / "python-deps"))
import numpy as np
import soundfile as sf

output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
rate, seconds = 24000, 3
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


# A TEFC induction motor at 50 Hz, 4 poles (1,500 rpm): magnetic hum at twice the line frequency and its harmonics,
# a faint rotor-slot whine, the cooling fan's blade pass (25 Hz x 10 blades) and its broadband rush.
motor = (tones([(100, 1, 0), (200, .45, .7), (300, .18, 1.9), (400, .08, .3), (1150, .035, 2.2), (250, .10, .5)])
         + .55 * noise(7201, band(700, .9)))
motor *= 1 + .04 * np.cos(2 * np.pi * 25 * t)  # the fan's once-per-rev pulse
# A mechanical-drive steam turbine: steam hiss through the nozzles and blading (broad, high), the blade-passing tone
# and its harmonic, and a low rotor rumble.
turbine = (.9 * noise(7202, band(3000, .8) + .35 * band(800, .7))
           + tones([(2400, .30, 0), (4800, .12, 1.1), (3600, .06, .4), (50, .35, 0), (150, .12, .9)]))
turbine *= 1 + .06 * np.cos(2 * np.pi * 2 * t)  # a slow steam surge, two per second

# The casings without their drivers' hum (decision D26): the hum is the motor loop's now. The wash keeps the
# 2026-10-23 loops' noise spectrum (rising from 90 Hz, peak about 700 Hz).
wash = (frequency / 300) * np.exp(-frequency / 700)
wash[frequency < 90] = 0
# A centrifugal pump: the liquid's wash through the casing, its vane pass (2,950 rpm x 6 vanes, about 300 Hz) with
# its harmonics, and a slow three-per-second flow ripple.
pump = (noise(7101, wash) * (1 + .12 * np.cos(2 * np.pi * 3 * t))
        + tones([(300, .55, 0), (600, .2, .8), (900, .07, 1.7)]))
# A compressor: the gas rush, pulsing six times a second (a reciprocating machine's valves and throws), and a faint
# impeller or valve tone.
compressor = ((noise(7102, wash + .4 * band(1500, .7)) + tones([(450, .25, 0), (900, .1, .6)]))
              * (1 + .28 * np.cos(2 * np.pi * 6 * t)))

# Cavitation (W10c, decision D35): bubbles collapsing at the impeller's eye, "gravel in the pump". A train of sharp,
# random clicks (short bright bursts, heavy-tailed in strength, about 140 a second) over a faint broadband hiss, the
# loudest snaps rounded by a soft limit so the loop keeps the pump's level without clipping. Each click wraps round the
# loop's end, so the train is periodic and the seam stays clean.
rng = np.random.default_rng(7301)
bright = noise(7302, band(4000, 1.1) + .5 * band(1200, .8))
crackle = .12 * noise(7303, band(2500, 1.4))
clicks = np.zeros(count)
for start in rng.integers(0, count, 420):
    length = int(rng.integers(rate // 2000, rate // 300))
    strength = min(2.5, rng.pareto(2.5) + .3)
    span = (start + np.arange(length)) % count
    clicks[span] += strength * np.exp(-np.arange(length) / (length / 5)) * bright[span]
crackle += clicks
crackle = np.tanh(crackle / (2.2 * np.sqrt(np.mean(crackle ** 2))))
# Gas in the impeller (W10c, D35): low irregular surges, 50 to 200 Hz, a churn that swells and slackens at random one to
# four times a second. The envelope is smooth periodic noise, so the loop has no seam.
slow = noise(7311, np.where((frequency >= .5) & (frequency <= 4), 1.0, 0.0))
churn = noise(7312, band(110, .55) + .3 * band(60, .3)) * np.exp(.55 * slow)

# Each loop's RMS: the 2026-10-23 pump and compressor loops' levels, the drivers at the pump's; the volume law
# (UnitDesign.soundGain) sets the loudness in game.
PUMP_RMS, COMPRESSOR_RMS = .0926, .1109
for name, sound, level in (("pump_running", pump, PUMP_RMS), ("compressor_running", compressor, COMPRESSOR_RMS),
                           ("motor_running", motor, PUMP_RMS), ("turbine_running", turbine, PUMP_RMS),
                           ("pump_cavitation", crackle, PUMP_RMS), ("pump_gas", churn, PUMP_RMS)):
    sound = sound - np.mean(sound)
    sound *= level / np.sqrt(np.mean(sound ** 2))
    path = output / (name + ".ogg")
    sf.write(path, sound, rate, format="OGG", subtype="VORBIS")
    decoded, decoded_rate = sf.read(path)
    print(json.dumps(dict(asset=name, bytes=path.stat().st_size, frames=len(decoded), rate=decoded_rate,
                          rms=float(np.sqrt(np.mean(decoded ** 2))), peak=float(np.max(np.abs(decoded))),
                          seam_step=float(abs(decoded[0] - decoded[-1])),
                          typical_step=float(np.mean(np.abs(np.diff(decoded)))))))
