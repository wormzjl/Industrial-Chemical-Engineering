"""Candidate alarm horn loops for the owner to choose from (batch 2026-11-15-fluid-interfaces, ALARM_BEACON_PLAN.md
section 5.2, decision A8). Authoring only; writes .ogg files to the given folder and touches nothing in the mod. The
valve_candidates.py route: synthesised, periodic over the loop, no recordings, 48 kHz mono.

Physics: an industrial alarm sounder is a tonal source of about 110 to 120 dB at 1 m (about 120 to 131 dB of sound
power; the game plays it at 130 dB, 48 blocks). Its signal is set by the plant's alarm tone (EN 54-3 style tones):
  A: an electronic horn: a compression driver pushes a clipped square-ish wave through a short horn, so odd harmonics
     ring out around the horn's 2 to 3 kHz resonance, and the throat cuts the low end. Two tones, 610 and 760 Hz,
     alternate at 1 Hz (0.5 s each), the familiar "dee-daa".
  B: a motor siren: a rotor chopping air through a stator gives a fundamental of rotor speed x ports, rising as it spins
     up from 500 to 1200 Hz over about 1.3 s, then coasting back as its supply cycles: a 1.5 s whoop with a few
     harmonics (2nd, 3rd, 5th).
  C: a piezo sounder: a disc at its 2.9 kHz resonance, nearly a pure tone with a weak second harmonic, pulsed at 4 Hz
     (125 ms on, 125 ms off) with a few ms of ring at each edge.

Each loop holds a whole number of tone cycles, so it repeats without a seam. Levels: the other loops' RMS (0.0926).

Usage: alarm_horn_candidates.py <output folder>
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


def response(sound, highpass, peak, peak_db, width, lowpass):
    """The horn's (or housing's) response, applied over the whole loop in the frequency domain (so it stays periodic):
    a second-order high-pass at highpass Hz, a log-Gaussian resonance of peak_db at peak Hz, width octaves, and a
    second-order low-pass at lowpass Hz."""
    spectrum = np.fft.rfft(sound)
    f = np.maximum(np.fft.rfftfreq(len(sound), 1 / rate), 1e-3)
    gain = (f / highpass) ** 2 / (1 + (f / highpass) ** 2)
    gain *= 10 ** (peak_db / 20 * np.exp(-.5 * (np.log2(f / peak) / width) ** 2))
    gain /= 1 + (f / lowpass) ** 2
    return np.fft.irfft(spectrum * gain, n=len(sound))


def integer_cycles(frequency):
    """The phase (rad) of an instantaneous frequency track (Hz per sample), its whole loop scaled to a whole number of
    cycles so the loop has no seam."""
    cycles = np.sum(frequency) / rate
    return 2 * np.pi * np.cumsum(frequency * round(cycles) / cycles) / rate


def two_tone():
    seconds = 4
    t = np.arange(rate * seconds) / rate
    # 610 Hz for 0.5 s (305 cycles), 760 Hz for 0.5 s (380 cycles): every switch falls on a zero of the phase.
    frequency = np.where((t % 1) < .5, 610.0, 760.0)
    phase = integer_cycles(frequency)
    # The driver clips: a rounded square wave, odd harmonics falling about 1/n.
    wave = np.tanh(3.5 * np.sin(phase))
    return response(wave, 450, 2400, 6, 1.0, 6500)


def sweep_siren():
    seconds, period, rise = 3, 1.5, 1.3
    t = np.arange(rate * seconds) / rate
    s = t % period
    # Spin-up toward 1200 Hz on the rotor's first-order lag (tau 0.5 s), then a cosine coast back to 500 Hz.
    tau = .5
    up = 500 + 700 * (1 - np.exp(-s / tau)) / (1 - np.exp(-rise / tau))
    down = 500 + 700 * .5 * (1 + np.cos(np.pi * (s - rise) / (period - rise)))
    frequency = np.where(s < rise, up, down)
    phase = integer_cycles(frequency)
    wave = np.sin(phase) + .3 * np.sin(2 * phase + .4) + .45 * np.sin(3 * phase + .9) + .2 * np.sin(5 * phase + 1.7)
    return response(wave, 350, 1800, 4, 1.2, 7000)


def piezo_pulse():
    seconds = 4
    t = np.arange(rate * seconds) / rate
    phase = integer_cycles(np.full(len(t), 2900.0))
    tone = np.sin(phase) + .1 * np.sin(2 * phase + .3)
    # 4 Hz, half on: 4 ms raised-cosine edges, and the disc's short ring after each pulse (T60 12 ms).
    s = t % .25
    edge = .004
    gate = np.where(s < edge, .5 - .5 * np.cos(np.pi * s / edge),
                    np.where(s < .125, 1.0, np.exp(-6.91 * (s - .125) / .012)))
    return response(tone * gate, 1000, 2900, 3, .5, 9000)


candidates = {
    "alarm_horn_A_two_tone": two_tone(),
    "alarm_horn_B_sweep_siren": sweep_siren(),
    "alarm_horn_C_piezo_pulse": piezo_pulse(),
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
