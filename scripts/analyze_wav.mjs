// Voice-quality investigation (2026-06-13) — spectral analyser for glasses-mic WAVs.
// Detects whether the Bluetooth SCO mic captured WIDEBAND (16 kHz, energy to ~8 kHz) or
// NARROWBAND (CVSD/8 kHz telephone, brick-wall ~3.4-4 kHz) audio. The WAV header always claims
// 16 kHz (the app hardcodes AudioRecord at 16 kHz), so only the spectrum reveals the truth.
//
//   node scripts/analyze_wav.mjs voicedbg/turn_*.wav
//   node scripts/analyze_wav.mjs voicedbg            (a directory = every *.wav inside)
//
// No dependencies. Pure radix-2 FFT.

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

function parseWav(buf) {
  if (buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WAVE") {
    throw new Error("not a RIFF/WAVE file");
  }
  let off = 12, fmt = null, dataOff = -1, dataLen = 0;
  while (off + 8 <= buf.length) {
    const id = buf.toString("ascii", off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    if (id === "fmt ") {
      fmt = {
        audioFormat: buf.readUInt16LE(off + 8),
        channels: buf.readUInt16LE(off + 10),
        sampleRate: buf.readUInt32LE(off + 12),
        bitsPerSample: buf.readUInt16LE(off + 22),
      };
    } else if (id === "data") {
      dataOff = off + 8;
      dataLen = size;
    }
    off += 8 + size + (size & 1);
  }
  if (!fmt || dataOff < 0) throw new Error("missing fmt/data chunk");
  const n = Math.floor(Math.min(dataLen, buf.length - dataOff) / 2);
  const pcm = new Float32Array(n);
  for (let i = 0; i < n; i++) pcm[i] = buf.readInt16LE(dataOff + i * 2) / 32768;
  return { fmt, pcm };
}

// In-place iterative radix-2 Cooley-Tukey FFT on real input (im starts at 0).
function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len;
    const wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const ur = re[i + k], ui = im[i + k];
        const vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
        const vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
        re[i + k] = ur + vr; im[i + k] = ui + vi;
        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
        const ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
      }
    }
  }
}

function spectrum(pcm, sampleRate) {
  const N = 4096; // ~256 ms at 16 kHz; resolution ~3.9 Hz/bin
  const bins = N / 2;
  const acc = new Float64Array(bins);
  let frames = 0;
  // Average magnitude over overlapping Hann-windowed frames across the whole clip.
  for (let start = 0; start + N <= pcm.length; start += N / 2) {
    const re = new Float64Array(N), im = new Float64Array(N);
    for (let i = 0; i < N; i++) {
      const w = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (N - 1));
      re[i] = pcm[start + i] * w;
    }
    fft(re, im);
    for (let k = 0; k < bins; k++) acc[k] += Math.hypot(re[k], im[k]);
    frames++;
  }
  if (frames === 0) return null;
  for (let k = 0; k < bins; k++) acc[k] /= frames;
  const hzPerBin = sampleRate / N;
  return { mag: acc, hzPerBin, bins };
}

function bandEnergy(sp, loHz, hiHz) {
  let e = 0;
  const lo = Math.floor(loHz / sp.hzPerBin), hi = Math.ceil(hiHz / sp.hzPerBin);
  for (let k = lo; k < Math.min(hi, sp.bins); k++) e += sp.mag[k] * sp.mag[k];
  return e;
}

function analyze(path) {
  const { fmt, pcm } = parseWav(readFileSync(path));
  const peak = pcm.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
  const rms = Math.sqrt(pcm.reduce((s, v) => s + v * v, 0) / (pcm.length || 1));
  const sp = spectrum(pcm, fmt.sampleRate);
  const secs = pcm.length / fmt.sampleRate;
  if (!sp) {
    console.log(`${path}\n  ${secs.toFixed(2)}s  TOO SHORT for spectral analysis (need >256ms)\n`);
    return;
  }
  const total = bandEnergy(sp, 0, fmt.sampleRate / 2) || 1e-12;
  const low = bandEnergy(sp, 0, 3400);
  const mid = bandEnergy(sp, 3400, 4000);
  const high = bandEnergy(sp, 4000, fmt.sampleRate / 2);
  const highPct = (100 * high) / total;
  // Effective cutoff: highest frequency whose local energy is >1% of the peak band energy.
  let peakBin = 0;
  for (let k = 1; k < sp.bins; k++) if (sp.mag[k] > sp.mag[peakBin]) peakBin = k;
  const floor = sp.mag[peakBin] * 0.05;
  let cutoffBin = 0;
  for (let k = sp.bins - 1; k >= 0; k--) { if (sp.mag[k] > floor) { cutoffBin = k; break; } }
  const cutoffHz = Math.round(cutoffBin * sp.hzPerBin);
  const verdict = highPct < 1.5
    ? "NARROWBAND (telephone ~8kHz CVSD) — brick wall, STT will struggle"
    : highPct < 6
      ? "BORDERLINE — limited high-frequency content"
      : "WIDEBAND-ish — real energy above 4 kHz";
  console.log(
    `${path}\n` +
    `  ${secs.toFixed(2)}s  header sr=${fmt.sampleRate}  peak=${peak.toFixed(3)} rms=${rms.toFixed(4)}\n` +
    `  energy: <3.4kHz=${(100 * low / total).toFixed(1)}%  3.4-4kHz=${(100 * mid / total).toFixed(1)}%  >4kHz=${highPct.toFixed(2)}%\n` +
    `  effective spectral cutoff ≈ ${cutoffHz} Hz\n` +
    `  VERDICT: ${verdict}\n`,
  );
}

const args = process.argv.slice(2);
if (args.length === 0) { console.error("usage: node analyze_wav.mjs <file.wav | dir> ..."); process.exit(1); }
const files = [];
for (const a of args) {
  if (statSync(a).isDirectory()) {
    for (const f of readdirSync(a)) if (f.toLowerCase().endsWith(".wav")) files.push(join(a, f));
  } else files.push(a);
}
files.sort();
for (const f of files) { try { analyze(f); } catch (e) { console.log(`${f}\n  ERROR: ${e.message}\n`); } }
