// Synthesises public/audio/{whoosh,hit,tick,music}.wav (no ffmpeg needed). Replace music.wav with a licensed track if you prefer.
import { mkdirSync, writeFileSync } from "node:fs";
const SR = 44100;
const BPM = 124;
const wav = (f, s) => {
  const b = Buffer.alloc(44 + s.length * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + s.length * 2, 4); b.write("WAVEfmt ", 8);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(SR, 24);
  b.writeUInt32LE(SR * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(s.length * 2, 40);
  s.forEach((v, i) => b.writeInt16LE(Math.max(-1, Math.min(1, v)) * 32767, 44 + i * 2));
  mkdirSync("public/audio", { recursive: true });
  writeFileSync(`public/audio/${f}.wav`, b);
};
const buf = (sec) => new Float32Array(Math.floor(sec * SR));
const rnd = () => Math.random() * 2 - 1;

// Whoosh: noise through a sweeping one-pole low-pass, swelling then fading.
const whoosh = buf(0.7); let lp = 0;
whoosh.forEach((_, i) => { const t = i / whoosh.length; const a = 0.02 + 0.5 * Math.sin(Math.PI * t) ** 2; lp += a * (rnd() - lp); whoosh[i] = lp * 3 * Math.sin(Math.PI * t) ** 1.5; });
wav("whoosh", whoosh);

// Hit: pitch-dropping sine thump plus a noise crack.
const hit = buf(0.6);
hit.forEach((_, i) => { const t = i / SR; hit[i] = (Math.sin(2 * Math.PI * (45 + 120 * Math.exp(-t * 30)) * t) * Math.exp(-t * 7) + rnd() * 0.5 * Math.exp(-t * 40)) * 0.95; });
wav("hit", hit);

// Tick: short click for counters.
const tick = buf(0.05);
tick.forEach((_, i) => { const t = i / SR; tick[i] = Math.sin(2 * Math.PI * 1800 * t) * Math.exp(-t * 90) * 0.5; });
wav("tick", tick);

// Music: 4-on-the-floor kick, offbeat hat, 8th-note bass in A minor-ish; 48s loop.
const beat = 60 / BPM; const secs = 48; const music = buf(secs);
const bass = [55, 55, 65.4, 55, 73.4, 55, 65.4, 49];
const add = (at, len, fn) => { const s = Math.floor(at * SR); for (let i = 0; i < len * SR && s + i < music.length; i++) music[s + i] += fn(i / SR); };
for (let n = 0; n * beat < secs; n++) {
  const t0 = n * beat;
  add(t0, 0.25, (t) => Math.sin(2 * Math.PI * (48 + 90 * Math.exp(-t * 40)) * t) * Math.exp(-t * 12) * 0.9);
  add(t0 + beat / 2, 0.05, (t) => rnd() * Math.exp(-t * 90) * 0.18);
  for (let h = 0; h < 2; h++) {
    const f = bass[(n * 2 + h) % bass.length];
    add(t0 + (h * beat) / 2, beat / 2, (t) => (Math.sin(2 * Math.PI * f * t) + 0.4 * Math.sin(4 * Math.PI * f * t)) * Math.exp(-t * 6) * 0.35);
  }
}
const peak = music.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
wav("music", music.map((v) => (v / peak) * 0.8));
console.log("wrote public/audio/{whoosh,hit,tick,music}.wav");
