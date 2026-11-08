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
