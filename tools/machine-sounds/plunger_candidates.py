"""Candidate running loops of the triplex plunger pump (batch 2026-11-16-supercritical-followups, package P4). Authoring
only; writes .ogg files to the given folder and touches nothing in the mod. The same route as make_loops.py and
tools/process-sounds/prime_candidates.py: synthesised, periodic over the loop, no recordings, mono 48 kHz Vorbis.

Physics. A triplex plunger pump at its rated 300 rpm (PlungerPumpDesign: every model turns at 300 rpm) turns its crank
5 times a second; three plungers 120 degrees apart give 15 strokes a second. Each plunger closes its discharge valve at
the end of the delivery stroke and its suction valve at the end of the suction stroke: 30 valve seatings a second, the
discharge ones harder (they seat against the full pressure rise). A seating is a short metallic knock, the valve plate's
and the valve cover's modes ringing for a few milliseconds. The delivery ripples three times per turn, so the pipes
thump at 15 Hz and its harmonics. The crossheads reverse twice per stroke (a soft clack). Under it all the gearbox
(a 1,500 rpm motor through a 5 : 1 reduction, a 23-tooth pinion: mesh 575 Hz) and the crank's bearings rumble low.
No pure tone that tires the ear: the mesh is faint, modulated by the crank and broadened by sidebands; each knock rings at
its own valve's modes with small cycle-to-cycle jitter.

The loop is 4 s: 20 crank turns, 60 strokes, 120 valve seatings; every tone holds a whole number of cycles (multiples of
0.25 Hz) and every noise is shaped over the whole loop, so the seam is clean. RMS is the pump loop's, 0.0926; a soft
limit at three times the RMS keeps the loudest knocks from clipping. The loop is authored at 300 rpm: the game's pitch is
the speed share (the plunger's loop states its machine's rated speed, UnitDesign.soundPitch), 0.5 to 2.

Candidates (the skill allows three):
  A valve_knock  clean modal valve knocks over a 15 Hz flow thump and a low crank and gearbox rumble, noise 13 dB down;
  B crank_heavy  the crank and crossheads forward (deep, long thumps), the valves quieter, the gearbox rumble louder;
  C clean        A's knocks and thump with no broadband noise except a faint low rumble (the owner's "cleaner" step).

Usage: plunger_candidates.py <output folder>
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
RPM = 300
CRANK = RPM / 60          # 5 Hz
STROKE = 3 * CRANK        # 15 Hz
MESH = 1500 / 60 * 23     # 575 Hz


def noise(seed, envelope):
    """Noise with the given amplitude spectrum, periodic over the loop, unit RMS."""
    rng = np.random.default_rng(seed)
    x = np.fft.irfft(envelope * np.exp(2j * np.pi * rng.random(len(envelope))), n=count)
    return x / np.sqrt(np.mean(x ** 2))


def band(centre, width):
    """A smooth band of noise around centre, Hz (log-Gaussian, width in octaves)."""
    return np.exp(-.5 * (np.log2(f / centre) / width) ** 2)


def tones(parts):
    """Sum of (frequency Hz, amplitude, phase); every frequency a whole number of cycles in the loop."""
    for fr, _, _ in parts:
        assert abs(fr * seconds - round(fr * seconds)) < 1e-9, fr
    return sum(a * np.sin(2 * np.pi * fr * t + p) for fr, a, p in parts)


def strike(into, at, modes, strength):
    """A damped modal ring placed at time at (s), wrapped round the loop: modes are (Hz, T60 s, relative amplitude)."""
    longest = max(m[1] for m in modes)
    n = int(longest * rate * 1.2)
    k = np.arange(n) / rate
    ring = sum(a * np.sin(2 * np.pi * fr * k + .3 * i) * np.exp(-6.91 * k / t60) for i, (fr, t60, a) in enumerate(modes))
    ring *= np.minimum(1, k / .0004)  # a 0.4 ms attack, no click
    span = (int(round(at * rate)) + np.arange(n)) % count
    into[span] += strength * ring


def train(seed, events, jitter):
    """Valve and crosshead events over the loop: events is a list of (phase in the crank turn 0..1, modes, strength).
    Each turn repeats them with cycle-to-cycle jitter in time (fraction of a stroke) and level."""
    rng = np.random.default_rng(seed)
    out = np.zeros(count)
    turns = int(seconds * CRANK)
    for turn in range(turns):
        for phase, modes, strength in events:
            at = (turn + phase) / CRANK + rng.normal(0, jitter / STROKE)
            out_modes = [(fr * (1 + rng.normal(0, .006)), t60, a) for fr, t60, a in modes]
            strike(out, at % seconds, out_modes, strength * (1 + rng.normal(0, 2 * jitter)))
    return out


def valves(discharge, suction):
    """The six valve seatings of one turn: throw i's discharge valve closes at its top dead centre (phase i/3), its
    suction valve at bottom dead centre (phase i/3 + 1/6). Each valve has its own modes (a few % apart) and strength."""
    events = []
    for i, (dk, sk) in enumerate(zip((1.0, .93, 1.06), (.97, 1.04, .95))):
        events.append((i / 3, [(fr * dk, t60, a) for fr, t60, a in discharge[0]], discharge[1] * (1 + .08 * (i - 1))))
        events.append((i / 3 + 1 / 6, [(fr * sk, t60, a) for fr, t60, a in suction[0]], suction[1]))
    return events


def crossheads(modes, strength):
    """The crossheads reverse at each dead centre: 6 soft clacks a turn, a quarter stroke after the valves."""
    return [(i / 6 + 1 / 24, modes, strength * (1.0 if i % 2 == 0 else .8)) for i in range(6)]


# The delivery ripple: three humps a turn (a triplex's rectified sines), the pipes answering at 15, 30 and 45 Hz, with a
# once-per-turn throb (one throw a little stronger).
thump = tones([(STROKE, .5, 0), (2 * STROKE, .6, .6), (3 * STROKE, .4, 1.4), (4 * STROKE, .2, 2.1), (CRANK, .15, 0)])
# The gearbox: a faint mesh with crank and pinion sidebands, never alone; and its housing's low rumble band.
mesh = tones([(MESH, .5, 0), (MESH - CRANK, .22, .4), (MESH + CRANK, .22, 1.1), (MESH - 25, .12, 2.0),
              (MESH + 25, .12, .7), (2 * MESH, .15, .3)]) * (1 + .3 * np.cos(2 * np.pi * CRANK * t))
rumble = noise(4401, band(70, .6) + .5 * band(160, .5)) * (1 + .2 * np.cos(2 * np.pi * STROKE * t))
# The liquid's wash through the valves: band noise around 1.2 kHz pulsing with the strokes.
wash = noise(4402, band(1200, .9) + .3 * band(3500, .7)) * (1 + .5 * np.cos(2 * np.pi * 2 * STROKE * t))


def unit(x):
    return x / np.sqrt(np.mean(x ** 2))


def candidate(name):
    if name == "valve_knock":
        knocks = train(4411, valves(([(1150, .006, 1), (1870, .004, .6), (2950, .003, .35)], 1.0),
                                    ([(760, .007, 1), (1320, .005, .5)], .55))
                       + crossheads([(240, .012, 1), (410, .008, .4)], .35), .015)
        pulses = unit(knocks) + .55 * unit(thump) + .12 * unit(mesh)
        floor = .22 * unit(rumble) + .12 * unit(wash)      # about 13 dB under the pulses
    elif name == "crank_heavy":
        knocks = train(4421, valves(([(980, .007, 1), (1600, .005, .5)], .55),
                                    ([(690, .008, 1), (1150, .006, .4)], .35))
                       + crossheads([(170, .025, 1), (300, .015, .55), (520, .008, .2)], 1.0), .015)
        pulses = unit(knocks) + .8 * unit(thump) + .18 * unit(mesh)
        floor = .3 * unit(rumble) + .08 * unit(wash)
    else:  # clean
        knocks = train(4431, valves(([(1150, .006, 1), (1870, .004, .6), (2950, .003, .35)], 1.0),
                                    ([(760, .007, 1), (1320, .005, .5)], .55))
                       + crossheads([(240, .012, 1), (410, .008, .4)], .35), .0075)
        pulses = unit(knocks) + .55 * unit(thump) + .08 * unit(mesh)
        floor = .12 * unit(rumble)
    return pulses, floor


for letter, name in (("a", "valve_knock"), ("b", "crank_heavy"), ("c", "clean")):
    pulses, floor = candidate(name)
    sound = pulses + floor
    sound = sound - np.mean(sound)
    rms = np.sqrt(np.mean(sound ** 2))
    sound = np.tanh(sound / (3 * rms)) * 3 * rms
    sound *= PUMP_RMS / np.sqrt(np.mean(sound ** 2))
    # Put the seam in the quietest moment between knocks: roll the periodic loop so it starts where the 2 ms envelope is
    # least and the wave crosses zero (a roll keeps the loop seamless; it only moves where the file begins).
    envelope = np.convolve(np.concatenate([sound[-96:], sound, sound[:96]]) ** 2, np.ones(193) / 193, "valid")
    quiet = int(np.argmin(envelope))
    crossing = quiet + int(np.argmin(np.abs(np.take(sound, quiet + np.arange(-48, 49), mode="wrap")))) - 48
    sound = np.roll(sound, -crossing)
    path = output / f"plunger_pump_{letter}_{name}.ogg"
    sf.write(path, sound, rate, format="OGG", subtype="VORBIS")
    decoded, decoded_rate = sf.read(path)
    spectrum = np.abs(np.fft.rfft(decoded)) ** 2
    print(json.dumps(dict(asset=path.name, bytes=path.stat().st_size, frames=len(decoded), rate=decoded_rate,
                          seconds=len(decoded) / decoded_rate,
                          rms=round(float(np.sqrt(np.mean(decoded ** 2))), 4), peak=round(float(np.max(np.abs(decoded))), 4),
                          seam_step=round(float(abs(decoded[0] - decoded[-1])), 5),
                          typical_step=round(float(np.mean(np.abs(np.diff(decoded)))), 5),
                          floor_under_pulses_db=round(float(20 * np.log10(np.sqrt(np.mean(floor ** 2)) / np.sqrt(np.mean(pulses ** 2)))), 1),
                          largest_bin_share=round(float(spectrum[1:].max() / spectrum[1:].sum()), 4),
                          largest_bin_hz=float(np.argmax(spectrum[1:]) + 1) * decoded_rate / len(decoded),
                          largest_bin_above_100hz_share=round(float(spectrum[400:].max() / spectrum[1:].sum()), 4),
                          largest_bin_above_100hz=float(np.argmax(spectrum[400:]) + 400) * decoded_rate / len(decoded))))
