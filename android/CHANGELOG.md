# Changelog

## 0.1.0-dev (unreleased)

- Project audit of the desktop repository (`docs/PROJECT_AUDIT.md`).
- Gradle project with `:core` (pure Kotlin) and `:app` (Android, Compose).
- Agent core: planner, executor, tool registry, risk levels, native confirmation bound to the exact action, audit log, secret redaction.
- Cloud providers: Gemini, Groq, OpenRouter, OpenAI and Claude with ordered fallback; https only.
- Android tools: flashlight, alarm, open link, open YouTube, device info.
- Removed (scope change to a standalone phone-only product): the Ollama provider, the private-network/cleartext exception and any desktop pairing plan.
- Phase 3: resumable setup wizard (welcome, privacy, device analysis, local model choice, providers, features, storage review, permissions, summary); device analysis with measured / estimate / unavailable readings; model advisor with visible rules; exact model size and checksum lookup from Hugging Face on request; 25-feature catalogue with dependencies and presets (only the 3 features that exist are selectable); storage calculator; atomic, forgiving setup persistence. See `docs/setup/ONBOARDING.md`.
- `HttpTransport` gained a `get` method (used only for the model metadata lookup).
- Correction: Gemma 3 1B's file is 584,417,280 bytes per the repository listing, not the "about 529 MB" noted earlier.
- Fix: the Android `data` package (key store, settings, setup storage) had been left out of commits and the zip by a `data/` rule in the repository's root `.gitignore`; `android/.gitignore` now re-includes `data/` and `memory/`.
- 277 offline unit tests pass in a non-Gradle harness. Gradle build, the `:app` module and device behaviour are unverified.
- First real run (owner's phone, 2026-11-08): the app ran, but every cloud provider answered HTTP 404 for a stale pre-filled model name (`gemini-2.5-flash`, the `:free` OpenRouter models, a Groq model the key could not use), so no command could run.
- Fix: new defaults (`gemini-3.8-flash`, a Groq pool, `openrouter/free`, `claude-haiku-5-5`), a one-time migration of the retired defaults, **Load models** (live list from the user's own key) and **Test** in the Cloud tab.
- New: built-in commands (`NativeCommands`) run flashlight, alarm, YouTube, open-website and battery/storage requests with no AI, through the same executor and safety checks.
- New: Models tab. Resumable, size- and SHA-256-verified, atomic model download with a foreground-service progress notification, import of a file the user already has, a Hugging Face token for the gated Gemma 3 1B (Keystore-encrypted), and removal. An installed model is **not** active in chat yet.
- New: Access tab and an Access list in setup. Real runtime and special-access requests, an "Allow all" walk-through (Android has no single grant-all switch), and honest "Used by the app now / Not used yet" labels.
- New look: aurora backdrop, animated orb, staggered motion, sliding tab bar, animated chat bubbles, bundled Orbitron, Manrope and JetBrains Mono (SIL OFL 1.1, licences in `docs/legal`).
- Removed the Providers tab name: it is now **Cloud**. Tabs are Chat, Models, Cloud, Access, Log.
- 335 offline unit tests pass (58 new). The new `:app` code has not been compiled.
- New (core only): `LocalEngine` interface, `RoutePlanner` (Local only, Local first, Cloud assisted, Manual) and `RoutedLlmClient` with fallback between on-device and cloud, cancellation, out-of-memory handling and honest route notes. Requests now carry a weight (the planner's are heavy); responses can come from an on-device model. Not wired into the app: there is no engine yet.
- 369 offline tests pass (34 new), 18 more mutation checks caught.
- New (core): calls. `call_contact`, `call_number` (high risk, always confirmed), `answer_call`, `end_call` (medium). A tool can now resolve its exact action in a `prepare` step before the confirmation, so the confirmation shows, and the approval is bound to, the real name and number. Carrier codes (`*`/`#`) are never dialled, emergency numbers only open the dialer, ambiguity asks instead of guessing, and results say "Asked Android to call", never "called".
- New (core): `ConversationStore` for the Logs tab (capped, redacted, deletable, atomic file in the app).
- New (app, uncompiled): push-to-talk voice (`VoiceController`), spoken replies for spoken requests only, contacts and call ports, the Stitch-based Home / Chat / Tools / Logs / Prefs screens, and a confirmation card that can only be answered by a tap. Fonts changed to Inter and Space Grotesk.
- 456 offline tests pass; 21 of 22 call-safety mutation checks are caught (the 22nd is covered by a second safety net).
- New (core): memory (`MemoryStore`, explicit saving only, per-agent isolation, secret redaction, suggestion queue with no generator yet), `MemoryCommands` ("remember that …"), tasks / notes / reminders (`ProductivityStore` and six LOW-risk tools), automations (`AutomationRules`, `AutomationStore`, `AutomationRunner`; four allowed actions, three safety layers), custom agents (`AgentConfig`, `AgentBlueprint`, `AgentValidator`, `AgentStore`, `AgentPrompt`, `AgentRuntime`; draft, approve-by-tap, tools derived from features, private memory), `Planner` persona, `OfflineReadiness` and `OfflineSelfTest`, `SecurityReview`.
- New (app, uncompiled): atomic file storage, alarm scheduling with `WakeReceiver` (alarm, boot, app update), notifier, startup catch-up, screens for Memory, Planner, Automations, Agents (list, wizard, preview chat), Offline and Security under Prefs, and a "Use memory in chat" switch.
- Fix: the Offline screen's built-in-commands line named "volume" and "opening apps", which are not built-in commands; it now lists flashlight, alarms, battery and storage, and calls.
- 618 offline tests pass. 27 of 29 new safety mutation checks are caught; the 2 that survive are the automation runner's backup layers (its own registry filter and its deny-all confirmation), which cannot be told apart from the save-time check (see the test report).
- Release documentation added (`docs/release/RELEASE_CHECKLIST.md`). **No APK or signed build exists.**

## Round 2026-11-11 to 11-19 (commit 89339a4; core tested, `:app` not compiled)

- New (core): web answers (weather, Wikipedia, a grounded web answer) shown in chat and spoken, with no browser redirect; offline time and date; hedged provider calls and shorter spoken replies for speed; the voice-direct call countdown (a spoken, plain built-in call command is read back and dialled after 3 seconds unless cancelled; typed and AI-planned calls still need a tap).
- New (core): screen and notification tools behind `ScreenGuard` (read results are private: not logged, not given to a model; no tap, type or gesture tools); open-app tool.
- New (core): offline library: openZIM reader, HTML lead extraction, title lookup, the `offline_lookup` tool, "search offline for …", and a fallback when no AI is reachable. The installer was generalised (`InstallTarget`) so the library reuses resume and atomic rename.
- New (app, uncompiled): Picovoice Porcupine hands-free "Jarvis" service, app launcher, Telecom call placing, accessibility service (reads the screen, scrolls, system buttons; no gestures), notification listener, library manager with a zstd / xz decoder, and the Prefs screens for hands-free, phone control and the offline library.
- 756 offline tests passed at that commit.

## Round 2026-11-19 to 11-21 (core tested, `:app` not compiled)

- New (core): `send_sms` and `send_whatsapp` (high risk, always a tap, never sent by the app: the messaging app opens with the text ready and the result says "has not been sent"), `set_timer` (Clock app countdown), `media_control` (play, pause, next, previous, stop, volume up / down). Natural-language commands for all of them, including "text Mom hello", "WhatsApp +91… hello", "set a timer for 10 minutes", "next track", "volume up". Contact lookup shared with calls (`ContactLookup`).
- New (core): Mistral as a sixth cloud provider with live model listing. OpenAI and Mistral have no pre-filled model.
- Changed (core): the "install a local model" hint is shown only when the build actually has an engine.
- New (app, uncompiled): the AI-mode router is wired into the planner and chat (Prefs > AI mode: Local only, Local first, Cloud assisted, Manual), Android implementations of messaging, timers and media keys, manifest `<queries>` for the messaging apps.
- New (app, uncompiled, opt-in): a LiteRT-LM engine in `app/src/litert/`, compiled only with `enableLiteRt=true`; the default build uses an empty stand-in. It needs a Kotlin upgrade and will probably need small API fixes on its first build (`docs/ai/LOCAL_MODELS.md`).
- 798 offline tests pass. 16 of 17 new mutation checks are caught; the survivor is equivalent (no observable change).
- Reviewed from `JARVIS-Project.zip`: the parts that could be built honestly were rebuilt; the rest was left out on purpose (list in `STATUS.md`).
- Docs brought in line with the code: README, `VOICE_AND_CALLS.md`, `STATUS.md`, `ROADMAP.md`, `ACCESS.md`, `LOCAL_MODELS.md`, `RELEASE_CHECKLIST.md`.

## Round 2026-11-21 to 11-22: matching the pasted "JARVIS 3.0" summary (core tested, `:app` not compiled)

- New (core): a 7th cloud provider, "FreeLLM / own endpoint": an OpenAI-compatible service at an https address the person types (`CustomEndpoint` refuses http, passwords, tokens in the address, `#` parts and whitespace). No address is built in; the key is optional. Model listing works from that address.
- New (core): `compose_email` (high risk, one plain address, always a tap, opens the email app, never sends) and `open_camera` (photo or video mode, opens the camera app, cannot capture). Commands: "email a@b.com saying …", "write an email to a@b.com", "open the camera", "take a photo", "record a video". A name instead of an address gets a question, never a guess.
- Changed (core): "open the camera" now uses `open_camera` instead of the app-name lookup.
- New (app, uncompiled): `AndroidEmailPort`, `AndroidCameraLauncher`, the Cloud-tab address field for the own-endpoint provider, Tools-screen examples.
- Changed: the name people see is now "JARVIS 3.0" (app name, screens, notifications, permission instructions, planner self-description, README and setup docs). Package names, the repository name, the wake word and historical documents are unchanged.
- New doc: `docs/JARVIS_3_SUMMARY_MATCH.md`, the pasted summary compared line by line, with the deliberate differences.
- The own-endpoint provider never sends the JSON-mode field (an unknown gateway may reject it); the planner already reads JSON out of plain text. Two tests cover this.
- 837 offline tests pass; 18 of 18 mutation checks run before that change were caught (the JSON-mode line itself was not mutation-checked).
