# A soft, short in-breath for Yamini's Expressive voice: band-limited noise shaped like an inhale.
import numpy as np, wave, sys
sr = 22050
rng = np.random.default_rng(7)
dur = 0.42
n = int(sr * dur)
noise = rng.standard_normal(n + 4096)
# Spectral shaping: breath energy mostly 500 Hz - 3.5 kHz with a soft tilt.
spec = np.fft.rfft(noise)
f = np.fft.rfftfreq(len(noise), 1 / sr)
shape = np.exp(-((np.log(f + 1) - np.log(1400)) ** 2) / (2 * 0.55 ** 2))
shape += 0.25 * np.exp(-((np.log(f + 1) - np.log(4200)) ** 2) / (2 * 0.3 ** 2))
x = np.fft.irfft(spec * shape, len(noise))[2048:2048 + n]
t = np.arange(n) / sr
# Inhale envelope: quick rise, slow swell, soft end; gentle flutter.
env = np.clip(t / 0.09, 0, 1) ** 1.5 * np.clip((dur - t) / 0.16, 0, 1) ** 1.2
env *= 0.85 + 0.15 * np.sin(2 * np.pi * 5.5 * t)
x = x * env
x = x / np.max(np.abs(x)) * (10 ** (-24 / 20))   # peak -24 dBFS: soft
pad = np.zeros(int(sr * 0.06))
y = np.concatenate([pad, x, pad])
pcm = (y * 32767).astype('<i2')
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())
print(len(pcm), 'samples', round(len(pcm) / sr, 3), 's')
