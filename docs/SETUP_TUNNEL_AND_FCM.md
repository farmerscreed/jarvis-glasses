# Setup: Tunnel (untethered agent lanes) + Firebase/FCM (push)

*Exact, step-by-step director-setup for the two infrastructure pieces JARVIS can't finish in code
alone. After each, the agent lanes work untethered (tunnel) and the app can receive push (FCM).*
*Written 2026-06-14.*

---

# Part A — Tunnel: make the agent lanes work untethered

**Why:** the fast lane (conversation, voice-vision, memory) already works untethered via the cloud.
The **agent lanes** (research / coding / email / calendar) call the **Agent Bridge** on your PC
(`agent-bridge/server.js`, `127.0.0.1:8765`). On the dev build the phone reaches it over USB
(`adb reverse`). To use them untethered, the phone must reach the bridge over the internet — that's
the tunnel. Two options; **Tailscale is recommended** (private, stable). Cloudflare is fastest to try.

Both keep the bridge protected by its bearer token (`agent-bridge/.env` → `BRIDGE_TOKEN`).

## Option 1 — Tailscale (recommended: private mesh VPN, stable IP)

Both the PC and the phone join your private "tailnet"; nothing is exposed to the public internet.

1. **Install Tailscale on the PC** (where the bridge runs): https://tailscale.com/download → install →
   sign in (Google/GitHub/email). Find the PC's tailnet IP: run `tailscale ip -4` → e.g. `100.101.102.103`.
2. **Install Tailscale on the Pixel** (Play Store) → sign in with the **same account** → toggle it ON.
   Confirm the phone can see the PC: both appear in the Tailscale admin console (login.tailscale.com).
3. **Bind the bridge to the tailnet IP** so the phone can reach it (it binds localhost by default).
   In `agent-bridge/.env` add:
   ```
   BRIDGE_HOST=100.101.102.103     # the PC's `tailscale ip -4` from step 1
   ```
   Restart the bridge: `node agent-bridge\server.js`. The startup log should show it listening on
   `http://100.101.102.103:8765`. (Token still required — Tailscale provides the network isolation.)
4. **Point the prod app at it.** In `android/local.properties` add:
   ```
   agentBridge.prodUrl=http://100.101.102.103:8765
   agentBridge.prodToken=<the BRIDGE_TOKEN from agent-bridge/.env>
   ```
5. **Rebuild + install prod:**
   `android\gradlew.bat -p android :app:assembleProdDebug` →
   `adb install -r android\app\build\outputs\apk\prod\debug\app-prod-debug.apk`
6. **Verify:** with the phone on cellular (Wi-Fi off, to prove it's not the LAN) and Tailscale ON, open
   JARVIS (prod) and say *"Jarvis, research …"* → it should run (not fall back to chat). The bridge's
   `agent-bridge/audit.log` shows the task.

**Notes:** the PC must be on with the bridge running for the agent lanes to work (it's still the engine —
the tunnel just makes it reachable). To survive PC reboots, run the bridge as a service (e.g. `pm2` or a
Scheduled Task) — optional. Tailscale's IP is stable, so step 4 is one-time.

## Option 2 — Cloudflare Tunnel (fastest to try; public URL, token-guarded)

No bridge bind change needed — `cloudflared` connects to `127.0.0.1:8765` locally and exposes an https URL.

1. **Install cloudflared** on the PC: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/ (or `winget install --id Cloudflare.cloudflared`).
2. **Quick tunnel (ephemeral, zero account):**
   ```
   cloudflared tunnel --url http://127.0.0.1:8765
   ```
   It prints a public URL like `https://random-words.trycloudflare.com`. Keep this terminal open (the
   URL lives only while it runs, and changes each restart).
3. **Point the prod app at it** — `android/local.properties`:
   ```
   agentBridge.prodUrl=https://random-words.trycloudflare.com
   agentBridge.prodToken=<the BRIDGE_TOKEN>
   ```
   Rebuild + install prod (same as Tailscale step 5). Re-edit + rebuild whenever the quick-tunnel URL
   changes.
4. **Stable URL (optional):** a **named tunnel** with your own domain survives restarts — needs a free
   Cloudflare account + a domain: `cloudflared tunnel login`, `cloudflared tunnel create jarvis`,
   route a hostname to it, `cloudflared tunnel run`. Then `agentBridge.prodUrl=https://jarvis.yourdomain.com`.

**Security:** a Cloudflare quick/named tunnel is **public** — anyone with the URL can hit it, so the
bearer token is the only guard. Keep the token secret; prefer Tailscale for ongoing use. (For real
production, also add Cloudflare Access in front.)

## After either option
- The **dev** build still uses `adb reverse` (unchanged) — keep it for plugged-in work.
- If you later move the bridge to a **hosted VM** (Phase 2): install Node + Claude Code there, `claude
  login` with the Max sub, run the bridge, and point `agentBridge.prodUrl` at the VM (behind Tailscale
  or Access). Same app config.

---

# Part B — Firebase Cloud Messaging (push notifications)

**Why:** for the M4 async "your task is done" alert (agent finishes a long job → JARVIS notifies +
speaks the summary) and the future proactive "noticer". The backend (Supabase) is kept; FCM is added
**additively** (the settled decision: Supabase backbone + FCM for push only — no Firestore migration).

This has **director steps** (Firebase console — only you can do these) and **code steps** (which I can
implement once `google-services.json` exists). Do Part B1; then tell me and I'll do B3–B5.

## B1 — Firebase console (director)
1. Go to https://console.firebase.google.com → **Add project** → name it (e.g. "JARVIS") → you can
   disable Google Analytics. Create.
2. In the project: **Add app → Android**. Register **two** Android apps (one per build flavor):
   - package name **`com.echo.companion`** (prod)
   - package name **`com.echo.companion.dev`** (dev)
   (App nickname optional; SHA-1 not required for FCM — only for Google sign-in.)
3. For **each** app, **download `google-services.json`**. Firebase lets one file hold multiple apps —
   download the file after registering both, so it contains both package names. Put it at:
   ```
   android/app/google-services.json
   ```
   (It's safe-ish but **gitignore it** — it identifies your Firebase project. I'll add the ignore rule.)
4. **Get a service account for the backend to SEND pushes:** Firebase console → **Project settings →
   Service accounts → Generate new private key** → downloads a JSON. **Keep it secret** — this goes into
   Supabase function secrets (not the repo), used by an Edge Function to call the FCM HTTP v1 API.

## B2 — what you give me
- `android/app/google-services.json` (in place), and
- the service-account JSON contents (I'll set it as a Supabase secret `FCM_SERVICE_ACCOUNT`, never commit it),
- confirmation of your Firebase **project ID**.

## B3 — app code (I implement)
- Add the Google Services Gradle plugin + `firebase-bom` + `firebase-messaging` deps.
- A `JarvisFirebaseMessagingService` (extends `FirebaseMessagingService`): on `onNewToken`, register the
  device token with the backend; on `onMessageReceived`, post a notification and (if foreground) hand the
  payload to the assistant so it can speak the summary.
- Request the `POST_NOTIFICATIONS` runtime permission (already declared).
- On sign-in, fetch + upload the FCM token.

## B4 — backend (I implement, with your authorization to deploy)
- Migration: a `device_tokens` table (`user_id`, `token`, `platform`, `updated_at`, RLS owner-only).
- An Edge Function `push` that, given a user + title/body/data, looks up their tokens and calls the
  **FCM HTTP v1** endpoint authenticated with the `FCM_SERVICE_ACCOUNT` secret.
- Wire the M4 async flow: when an `agent_tasks` row flips to `done`, call `push` so the phone is
  notified and JARVIS speaks "Your research on X is ready."

## B5 — verify
- Sign in → confirm a `device_tokens` row appears.
- Trigger a test push (a temporary admin call to `push`) → the phone shows a notification.
- Then end-to-end: submit a long async agent task → get the "done" push + spoken summary.

**Scope note:** B1–B2 are yours (≈10 min in the Firebase console). B3–B5 are mine once the files exist;
B4 includes a prod deploy, which needs your explicit OK per the standing rule.
