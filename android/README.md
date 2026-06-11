# jarvis — Android app

AI companion for the AIMB-G2 glasses. Multi-module Kotlin/Compose skeleton.
Plan: `..\..\Jarvis Glasses\01_IMPLEMENTATION_PLAN.md`. Device protocol: `00_HANDOFF_START_HERE.md`.

## Status
**Memory loop working on-device (Pixel 8).** The app signs in to local Supabase and runs the full
RAG loop — sign in → Ask → Gemini embed → pgvector recall (RLS) → Claude answer — shown in the UI.
`:memory` (`EchoBackend`/`MemoryRepository`) calls the Edge Functions; `:app` has Hilt DI + a dev console UI.
**Glasses audio loop (0C) WORKING**: `:device:audio` `BtAudioEngine` records from the glasses mic over
Bluetooth SCO and plays back over A2DP. Verified — captured voice at peak 13982/32767, playback heard in glasses.

**Glasses camera control (0D) WORKING**: `:device:ble` `GlassesBleManager` connects to the glasses GATT,
resolves the capture characteristic (`de5bf72a` in `de5bf728`, handle 0x008E), and writes `CAMERA_CAPTURE`.
Verified — 3 writes produced 3 new photos (stock app album confirmed). **All of Phase 0 is proven on hardware.**
`:device:wifi` (Wi-Fi media transfer) is the remaining device piece (Phase 2).

### Run on device (local backend)
```powershell
$adb reverse tcp:54421 tcp:54421     # phone 127.0.0.1:54421 -> PC Supabase
& "$a\gradlew.bat" -p $a :app:installDebug
```
Requires the local stack + `supabase functions serve` running (see ../supabase). `DevConfig` points the app at `http://127.0.0.1:54421`.

## Modules
| Module | Contents |
|---|---|
| `:app` | Hilt `Application`, `MainActivity`, Compose Material3 theme, status `HomeScreen`. |
| `:core` | `Memory` / `MemoryType` domain models (mirror the Supabase `memories` table). |
| `:ai` | `LlmClient` / `EmbeddingClient` / `SttClient` / `TtsClient` interfaces (Edge-Function backed). |
| `:memory` | `MemoryRepository` (`recall`→`match_memories`, `remember`→embed+insert). |
| `:assistant` | `AssistantOrchestrator`, `Tool`, `ToolRegistry` (the capture→think→speak→remember loop). |
| `:device:ble` | `GlassesBleClient` + `GlassesProtocol` — **real RE'd bytes** (`CAMERA_CAPTURE`, `WIFI_P2P_START`, NUS UUIDs). |
| `:device:audio` | `BtAudioManager` (HFP mic / A2DP playback). |
| `:device:wifi` | `GlassesMediaTransfer` + `MediaProtocol` (HTTP `/files/media.config` constants). |

`:app` depends on every module, so `:app:assembleDebug` compiles the whole graph.

## Toolchain (matched to this machine)
- AGP 8.6.1 · Gradle 8.10.2 · Kotlin 2.0.20 · KSP · Hilt 2.52 · Compose BOM 2024.09.03
- compileSdk/targetSdk **36** (only SDK installed; `android.suppressUnsupportedCompileSdk=36`) · minSdk **29** · buildTools 35.0.0
- JDK 17 (Adoptium, `JAVA_HOME`). SDK at `%LOCALAPPDATA%\Android\Sdk` (in `local.properties`, git-ignored).

## Build / run
```powershell
$a = "C:\Users\admin\Documents\APP\jarvis\android"
& "$a\gradlew.bat" -p $a :app:assembleDebug      # build the debug APK
& "$a\gradlew.bat" -p $a installDebug            # install to a connected device (Pixel 8 via adb)
# or just open the `android/` folder in Android Studio and Run.
```
First build downloads Gradle + deps (~9 min); subsequent builds are fast.

## Next (per plan §6, Phase 0)
- `:memory` — implement `MemoryRepository` against local Supabase (supabase-kt: postgrest/auth/storage).
- `:ai` — Edge-Function client impls (Claude / embeddings) once keys are set server-side.
- `:device:audio` — Phase 0C: prove SCO mic capture + A2DP playback.
- `:device:ble` — Phase 0D: GATT connect + send `CAMERA_CAPTURE`; resolve the camera-endpoint UUID.
- DI: add Hilt `@Module`s wiring the interfaces to implementations.
