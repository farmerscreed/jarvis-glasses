// Phase 2 · M3 — transcribe one ~5-minute meeting chunk with Gemini into speaker-labelled lines.
// Pure function of (audio, context) → segments, so it can be tested alone (scripts/test-meeting-stt.ts).
// Private vocabulary (names etc.) lives in the STT_VOCAB secret, not in this public repo.

export type Segment = { start: number; end: number; speaker: string; text: string };
export type SttResult = { segments: Segment[]; model: string; ms: number; tokens?: number; tokensIn?: number; tokensOut?: number };

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** "02:15" / "1:02:15" / "135" / "135.5" → seconds. */
export function parseTime(t: unknown): number {
  if (typeof t === "number") return Math.max(0, t);
  const s = String(t ?? "").trim();
  if (!s) return 0;
  if (!s.includes(":")) return Math.max(0, Number(s) || 0);
  return s.split(":").map((p) => Number(p) || 0).reduce((acc, p) => acc * 60 + p, 0);
}

function prompt(vocab: string, knownSpeakers: string[]): string {
  return `You are transcribing part of a recorded in-person business meeting in Nigeria (a ~5-minute piece of a longer meeting; the phone was on the table).
Speakers mostly use Nigerian English, sometimes Nigerian Pidgin, Yoruba, Igbo or Hausa.

Rules:
- Write exactly what was said, in normal spelling. Keep numbers, prices (naira) and dates exactly as spoken.
- Speech that is NOT English (Pidgin can stay as spoken): transcribe it, then add an English translation in [square brackets].
- Label speakers "Speaker 1", "Speaker 2", … consistently within this piece, in order of first speaking.${knownSpeakers.length ? `\n- Earlier in this meeting the speakers were: ${knownSpeakers.join(", ")}. Reuse a label only if you are confident it is the same voice.` : ""}
- A TV, video, radio or music playing in the room → label it "Media" (sung or broadcast audio is never a meeting speaker).
- Label "JARVIS" ONLY for the AI assistant answering right after someone said "Jarvis …" to it.
- One entry per sentence or short turn; start/end are times from the start of THIS audio as "MM:SS".
- Unclear words → [unclear]. Never invent speech. Silence or no speech → an empty list.
Common words: Jarvis, naira, Port Harcourt, Lagos, site, supplier, invoice, delivery, cement, blocks${vocab ? ", " + vocab : ""}.`;
}

const SCHEMA = {
  type: "OBJECT",
  properties: {
    segments: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          start: { type: "STRING" },
          end: { type: "STRING" },
          speaker: { type: "STRING" },
          text: { type: "STRING" },
        },
        required: ["start", "end", "speaker", "text"],
      },
    },
  },
  required: ["segments"],
};

export async function transcribeChunk(
  audio: Uint8Array,
  opts: { apiKey: string; models: string[]; vocab?: string; knownSpeakers?: string[]; mimeType?: string },
): Promise<SttResult> {
  const t0 = Date.now();
  // Base64 in slices (spreading 1 MB into String.fromCharCode overflows the stack).
  let bin = "";
  for (let i = 0; i < audio.length; i += 0x8000) bin += String.fromCharCode(...audio.subarray(i, i + 0x8000));
  const b64 = btoa(bin);
  const body = JSON.stringify({
    contents: [{ parts: [{ text: prompt(opts.vocab ?? "", opts.knownSpeakers ?? []) }, { inline_data: { mime_type: opts.mimeType ?? "audio/mp4", data: b64 } }] }],
    generationConfig: {
      temperature: 0,
      maxOutputTokens: 16384,
      responseMimeType: "application/json",
      responseSchema: SCHEMA,
      thinkingConfig: { thinkingBudget: 0 },
    },
  });

  // Budget: the whole job must fit in one function invocation (~150 s), so each call is capped and a slow or
  // overloaded model gives way to the next one quickly. (2026-10-15: Flash under load hung for minutes and once
  // answered a full 5-minute chunk of speech with an EMPTY list — Flash-Lite transcribed the same chunk fine.)
  const CALL_MS = 60_000;
  let lastErr = "";
  let empty: SttResult | null = null; // an empty answer is only believed if every model agrees
  for (const [mi, model] of opts.models.entries()) {
    const isLast = mi === opts.models.length - 1;
    for (let attempt = 0; attempt < 2; attempt++) {
      if (Date.now() - t0 > (isLast ? 110_000 : 70_000)) break; // leave time for the next model
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), CALL_MS);
      let res: Response;
      try {
        res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": opts.apiKey },
          body,
          signal: ctl.signal,
        });
      } catch (e) {
        clearTimeout(timer);
        lastErr = `${model}: ${e instanceof Error && e.name === "AbortError" ? `no answer in ${CALL_MS / 1000} s` : String(e)}`;
        break; // slow → next model rather than waiting again
      }
      if (res.ok) {
        const data = await res.json().finally(() => clearTimeout(timer));
        const text = (data.candidates?.[0]?.content?.parts ?? []).map((p: { text?: string }) => p.text ?? "").join("");
        let parsed: { segments?: Array<Record<string, unknown>> };
        try {
          parsed = JSON.parse(text || "{}");
        } catch {
          lastErr = `${model}: unparseable JSON (${data.candidates?.[0]?.finishReason ?? "?"})`;
          break; // truncated/garbled → try the next model
        }
        const segments = mergeFragments(
          (parsed.segments ?? [])
            .map((s) => ({
              start: parseTime(s.start),
              end: parseTime(s.end),
              speaker: String(s.speaker ?? "").trim().slice(0, 60) || "Speaker",
              text: String(s.text ?? "").trim(),
            }))
            .filter((s) => s.text.length > 0),
        );
        const u = data.usageMetadata ?? {};
        const result = { segments, model, ms: Date.now() - t0, tokens: u.totalTokenCount, tokensIn: u.promptTokenCount, tokensOut: u.candidatesTokenCount };
        if (segments.length === 0 && audio.length > 200_000 && !isLast) {
          empty = result; // a minute+ of audio with "no speech" → ask the next model before believing it
          lastErr = `${model}: empty transcript`;
          break;
        }
        return result;
      }
      clearTimeout(timer);
      lastErr = `${model} ${res.status}: ${(await res.text()).slice(0, 300)}`;
      if ((res.status === 503 || res.status === 429 || res.status >= 500) && attempt === 0) {
        await sleep(2000);
        continue;
      }
      break; // hard error, or overloaded twice → next model
    }
  }
  if (empty) return { ...empty, ms: Date.now() - t0 };
  throw new Error(`meeting stt failed: ${lastErr}`);
}

/**
 * Join a speaker's consecutive fragments ("my life" / "my life" / "And now if I want…") into readable turns:
 * same speaker, a gap under 1.5 s, and the joined line still under ~300 characters.
 */
export function mergeFragments(segs: Segment[]): Segment[] {
  const out: Segment[] = [];
  for (const s of segs) {
    const prev = out[out.length - 1];
    if (prev && prev.speaker === s.speaker && s.start - prev.end <= 1.5 && prev.text.length + s.text.length < 300) {
      const joiner = /[.!?…]$/.test(prev.text) ? " " : (/^[A-Z]/.test(s.text) ? ". " : " ");
      prev.text = prev.text + joiner + s.text;
      prev.end = Math.max(prev.end, s.end);
    } else {
      out.push({ ...s });
    }
  }
  return out;
}
