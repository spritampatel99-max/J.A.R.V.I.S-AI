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
