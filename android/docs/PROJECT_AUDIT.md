# Project audit: desktop J.A.R.V.I.S.-AI → standalone Android

Phase 1 deliverable. The audit covers the repository at
`https://github.com/spritampatel99-max/J.A.R.V.I.S-AI.git`, commit `77a48c8`
("Replace API keys with placeholders in api_keys.json"). The clone used here is shallow, so only that
one commit is visible.

How it was done: the file tree and sizes were listed, all 47 project Python files were syntax-checked
with `ast.parse` (nothing was executed), and the planner, executor, LLM clients, safety, permissions
and memory modules were read. The other action modules were only skimmed for what they do and which
OS APIs they use. Nothing in the desktop project was modified.

## 1. What the desktop project is

A Windows-first Python assistant: Gemini Live voice (PyAudio/sounddevice), a planner/executor agent
with 29 tools named in its planner prompt, an LLM fallback chain (Gemini → Groq → OpenRouter → Ollama), JSON memory, and a
desktop UI (`ui.py`, 1,558 lines). About 15,000 lines across 47 project files, plus a bundled
`venv/`.

## 2. Health of the repository as committed

| Finding | Evidence | Consequence for Android work |
|---|---|---|
| Unresolved merge-conflict markers | 7 of 47 project `.py` files do not parse: `main.py`, `ui.py`, `agent/executor.py`, `llm_client.py`, `or_client.py`, `permissions.py`, `actions/app_catalog.py`. `main.py` has three conflict blocks; `readme.md` has one covering about 210 lines. | The desktop app cannot start from this commit. The Android work therefore re-implements the *ideas*, not the code, and leaves the desktop files untouched. |
| Whole virtualenv committed | `venv/` is 14,538 tracked files (777 MB); 4,243 tracked `__pycache__` files. | Nothing to port. Untracking them and adding a `.gitignore` would stop the repo growing (the history keeps them). |
| Real-looking API keys in early history | Found earlier in this conversation. This shallow clone shows only the placeholder commit, so it was not re-verified here. | **Revoke and regenerate the Gemini, Groq and OpenRouter keys** if you have not. `android/.gitignore` already excludes `api_keys*.json`, keystores and APKs. |
| LLM can approve its own sensitive actions | `safety.py` returns a confirmation token as text; the model then calls the `confirm_action` tool with it. | Not carried over. The Android confirmation is a native dialog the model cannot reach (see `docs/security/SECURITY.md`). |
| Arbitrary code generation | `generated_code` in `agent/executor.py` writes model-written Python to a temp file and runs it; `cmd_control`; `exec`/`eval`/`shell`/`subprocess` calls appear in 20 files (56 in `computer_settings.py`). | Forbidden by the Android requirements. No code execution of any kind exists in the Android code. |
| Silent memory writes | `memory_manager.should_extract_memory` / `extract_memory` ask an LLM what to remember after conversations and save it without asking. | Android memory is saved only with user approval (Phase 7). |

## 3. What is reusable, and how it was reused

| Desktop idea | Android status |
|---|---|
| Planner rules: ≤5 steps, independent steps, JSON output, only registered tools | Re-implemented in Kotlin (`ai/planner`). Output is parsed defensively, tool names are checked against the registry and arguments are validated, with one corrective retry. |
| Executor with retry / skip / replan / abort | Re-implemented (`ai/executor`). It adds permission checks, native confirmation, timeouts, an audit log and a ban on auto-retrying HIGH-risk tools. |
| Provider fallback chain with retries | Re-implemented (`ai/fallback`), now over five *optional* cloud providers. Ollama is dropped. |
| OpenRouter free-model pool | Carried over as a comma-separated model list that is tried in order. |
| Alarm / reminder semantics | `set_alarm` hands the alarm to the system Clock app and only reports success after `AlarmManager.nextAlarmClock` shows it. |
| Memory categories | To be redesigned on Room in Phase 7. |

## 4. Desktop tools and their Android equivalents

| Desktop module | Android equivalent | Phase | Notes |
|---|---|---|---|
| `open_app`, `app_catalog` | App launcher via intents | 6 | Android 11 package visibility limits discovery; only `<queries>`-declared or user-picked apps. |
| `alarm`, `reminder` | `set_alarm` (done), Room + WorkManager reminders | 6 | Exact alarms need the user's permission on newer Android. |
| `youtube_video`, `browser_control`, `browser_extras`, `web_search`, `weather_report` | `open_url`, `open_youtube` (done); research via cloud only | 5–6 | Web content is untrusted input. |
| `send_message`, `contacts` | Compose-and-hand-off flows, never silent sending | 6 | The app can only claim "opened the composer", not "sent". |
| `file_controller`, `file_processor`, `media_finder` | Storage Access Framework / MediaStore | 6 | No broad storage permission. |
| `screen_processor`, `screen_reader` | Camera / image picker / MediaProjection | 6 | Needs per-session user consent. |
| `wifi_bluetooth`, `computer_settings` | Open the matching system settings screen | 6 | Android 11+ does not allow silent Wi-Fi/Bluetooth/airplane control. |
| `wiki_offline` (Kiwix) | Possible later as downloadable content | 8 | Not scheduled before the local model works. |
| Gemini Live voice | `SpeechRecognizer` + `TextToSpeech` | 6 | Offline recognition needs per-device checks. |
| `window_control`, `desktop`, `computer_control`, `system_inventory`, `game_updater`, `flight_finder` | None | n/a | Desktop-only (pyautogui, pywinauto, the Windows registry). |
| `code_helper`, `dev_agent`, `vscode_coder`, `generated_code` | None | n/a | Developer features, excluded by the product definition. |

## 5. Decisions for the standalone product

| Topic | Decision | Basis |
|---|---|---|
| Desktop link | None. No pairing, bridge, Ollama or computer-hosted inference. | Product definition. The Ollama provider and the LAN/cleartext exception were deleted. |
| Local runtime | Google LiteRT-LM (Kotlin API) | See §6. |
| Cloud providers | OpenRouter, Gemini, Groq, OpenAI, Claude, all optional; no shared key in the APK | `docs/ai/CLOUD_PROVIDERS.md` |
| Build layout | `:core` (pure Kotlin/JVM: security, planner, executor, providers) and `:app` (Android, Compose) | The core can be unit-tested without Android. Split further only when a module earns it. |
| Dependency injection | Manual (`AppContainer`) for now | Hilt remains an option; it adds an annotation-processing plugin that could not be verified here. |
| Settings storage | `SharedPreferences` for non-secret settings; DataStore and Room arrive with Phases 2–3 and 7 | Keys use Keystore-backed AES-GCM. |

## 6. Local AI facts checked against official pages (2026-11-07)

Sources: the Android Developers LiteRT-LM Kotlin guide, Google's LiteRT-LM Gemma 4 page, and the
Hugging Face model pages. Re-check before relying on them, since they change.

- **Kotlin library:** `com.google.ai.edge.litertlm:litertlm-android` (version 0.18.0 on the page read), minSdk 24.
  It is published with **Kotlin 2.4 metadata**, so the `:app` module will need a Kotlin compiler new
  enough to read it. The `2.1.20` currently pinned in `libs.versions.toml` is too old; this is a known
  Phase 4 task, not yet tested.
- **API shape (from the guide):** `Engine` / `EngineConfig`, `Conversation`, `sendMessageAsync` returning a
  `Flow`, `cancelProcess`, `close`. Engine initialisation blocks, so it must run off the main thread.
  GPU use needs a manifest `uses-native-library` entry. NPU support is not covered by that page.
- **Model format:** `.litertlm` files, downloaded separately from the APK.
- **Gemma 3 1B:** repo `litert-community/Gemma3-1B-IT`, file `gemma3-1b-it-int4.litertlm`, listed at about
  529 MB. **The repo is gated**: the user must accept Google's Gemma licence on Hugging Face while
  logged in. The app therefore needs a user-supplied Hugging Face token (kept in the Keystore) *or* an
  "import the file I downloaded" path through the file picker. It must not pretend it can fetch the model silently.
- **Gemma 4 E2B:** repo `litert-community/gemma-4-E2B-it-litert-lm`, file `gemma-4-E2B-it.litertlm`, listed at
  about 2.58 GB, Apache-2.0. The model card reports 1,733 MB peak memory on CPU and 676 MB on GPU on a Galaxy S26 Ultra.
  That is one device, so it is not a general RAM requirement. The card says vision and audio components are
  loaded on demand, but the Android guide does not cover image or audio input, so **multimodal support is unverified**.
- **Sizes and checksums** must be read from Hugging Face file metadata at runtime. The numbers above are
  from web pages and must not be hard-coded as exact values. Whether the metadata endpoint is reachable
  for a gated repo is untested.

None of this has run on a device: there is no Android SDK, no emulator and no phone in this environment.

## 7. Risks and open questions

1. **Gradle has never resolved this project** (§ `BUILD_AND_TEST_REPORT.md`). Pinned versions may need bumping.
2. **Gemma 3 1B licence gating** changes the onboarding design (token or import).
3. **Memory pressure:** the 2.58 GB model file plus runtime memory may not fit low-RAM phones. Phase 3 device analysis has to decide this from measured values, not marketing RAM.
4. **Background microphone and notification-listener rules** (Android 11+) constrain the hands-free design in Phase 6.
5. **The desktop repo is broken as committed.** If you want the desktop app kept alive, resolving the merge markers is a separate job I have not touched.
