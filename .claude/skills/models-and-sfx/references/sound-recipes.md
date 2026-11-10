# Sound recipes

These recipes are how the existing scripts make their sounds. See `tools/machine-sounds/README.md`,
`tools/process-sounds/README.md` and the scripts named below. Copy a script's structure for a new sound instead of
starting from nothing.

## A seamless loop

- Periodic tones: choose each frequency so a whole number of cycles fits the loop. A 4 s loop takes multiples of
  0.25 Hz.
- Noise: shape white noise in the frequency domain over the whole loop (`rfft`, a gain per bin, `irfft`), so it is
  periodic and has no seam.
- Bursts (crackles, bubbles, knocks): place them at times modulo the loop length, so a burst near the end wraps to the
  start.
- Level envelopes: smooth periodic noise, for example a flutter of 0.5 to 2 Hz, for living sources.
- Mono Ogg Vorbis. The older loops are 24 kHz; new candidates are 48 kHz. The length is 3 to 4 s.
- Set the RMS to the pump loop's, 0.0926. A soft limit (tanh at about 3 times the RMS) keeps heavy-tailed bursts from
  clipping.
- Print bytes, frames, rate, RMS, peak, and the seam step against the mean step between samples.

## A one-shot

- About 0.6 to 9 s. A 5 ms fade in and an 80 ms squared fade out. The first and last samples are 0. Peak 0.7, as
  `pipe_knock`.
- Build it in stages placed on a timeline, as `valve_turn_synth.py` does: grab, turn, detent, stop, tail.

## Parts

- **Damped mode:** `sin(2π f t + φ) · exp(−6.91 t / T60)`. A strike is a sum of two or three modes, for example 900,
  1450 and 2300 Hz at 6, 4 and 3 ms. This is the clean way to make knocks, clicks and clunks.
- **Band noise:** white noise kept to a band in the frequency domain, scaled to unit RMS. Use it for rush, hiss and roar,
  and keep it 12 to 15 dB under the tonal parts unless the owner wants it forward.
- **Firing or blade train:** a pulse every 1/f, each with its own decay, plus small cycle-to-cycle jitter (a few % in
  time and level) and a cylinder-to-cylinder throb.
- **Whine:** the blade pass with a few sidebands, and a higher stage tone, over the roar.
- **Spool-up and spool-down:** sweep the whine's frequency along the speed curve. Add light-off as a roar step about 2 s in.

## Frequencies from the machine

| Source | Frequency |
| --- | --- |
| Pump vanes | rpm/60 × vanes (2950 rpm × 6 = 295 Hz) |
| Fan blades | rpm/60 × blades (300 rpm × 4 = 20 Hz) |
| Gear mesh | rpm/60 × teeth (1500 rpm × 30 = 750 Hz) |
| Engine firing | cylinders × rpm/120 (V16 at 1500 rpm = 200 Hz; V18 at 600 = 90 Hz) |
| Motor hum (50 Hz grid) | 100 Hz |
| Burner fan | rpm/60 × blades (2900 × 12 = 580 Hz) |

## Cleaning a recording (only when the owner chose a sample)

`tools/process-sounds/valve_turn_cc0.py clean`:

1. Take the noise profile from the clip's own quiet part, after the event. The background before the event may sit too
   far under it to help.
2. Spectral subtraction: 1024-sample frames, the gain smoothed over 3 bins and 3 frames, with a floor of 0.03 to 0.05.
3. A downward expander about 18 dB under the 30 ms peak envelope (the owner's "Gated").
4. Optionally limit the band to 70 Hz to 10 kHz.

## Candidates the owner picked (for reference)

| Sound | Pick | Character |
| --- | --- | --- |
| Gas valve | A | broadband 1 to 4 kHz decompression hiss |
| Liquid valve | L1 | a plain liquid rush |
| Cavitating valve or pump | L2 | crackle |
| Flashing valve | F1 | hiss and plug rattle |
| Steam ejector | J2 | bright 2 to 8 kHz hiss, faint 3 kHz screech |
| Hand valve turn | R3 cleaned, gated | CC0 shower-handle turn |
| Gas turbine | A | whine over roar |
| Combustion engine | C1 | clean modal knocks over an 80 Hz exhaust thump, noise 13 dB down, one loop per speed family |
