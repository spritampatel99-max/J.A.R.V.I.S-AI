# Setup wizard, device analysis and feature selection (Phase 3)

Status: **written and unit-tested in an offline harness; the Android screens have never been compiled or shown on a
screen.** See `../testing/BUILD_AND_TEST_REPORT.md` for exactly what was and was not run.

## What the user sees

A resumable wizard opens on first launch and again until it is finished. Progress is the number of steps actually
completed or skipped (never a timer), is saved after every change, and survives closing the app: reopening lands on the
first unfinished step. After it is finished, **Providers > Run setup again** reopens it with every earlier choice kept.

| # | Step | Required | What it does |
|---|---|---|---|
| 1 | Welcome | yes | Name, version, what setup will and will not do. |
| 2 | Privacy | yes | What stays on the phone, what is read during analysis, what is sent to a cloud provider, when huggingface.co is contacted. Must be ticked to continue. |
| 3 | Device analysis | yes | Reads the phone (below). Local only. Rescan button. |
| 4 | Local AI model | yes | Recommendation, two model cards, "Check exact sizes online" button, four options (Gemma 3 1B / Gemma 4 E2B / both / skip). **Choosing downloads nothing.** |
| 5 | Cloud AI providers | no | The same provider screen as the Providers tab. Can be skipped. |
| 6 | Features | yes | Presets, select/deselect all, one card per feature with details. |
| 7 | Storage review | yes | What the choices add up to. |
| 8 | Permissions | no | Explains permissions for the chosen features. **Requests nothing.** |
| 9 | Summary | yes | What is true right now (Cloud AI ready? Local AI ready? Offline AI ready?). |

The prompt's "model download and test", "feature initialisation" and "health check" steps are **not** in this phase:
there is no model downloader (Phase 4) and nothing to initialise yet. Nothing in the wizard pretends otherwise: the
summary says Local AI and Offline AI are "Not ready", whatever was chosen.

There is no terms-of-use text in the project, so the welcome screen does not show one.

## Device analysis (`device/DeviceProfile.kt`, `android/device/AndroidDeviceAnalyzer.kt`)

Every value is a `Reading`: a value tagged **measured** or **estimate**, or **unavailable** with a reason. An unreadable
value is never replaced by a default, and a reading cannot claim to be measured without a value (enforced in the
constructor). The screen shows the tag next to every figure.

| Group | Read from | Notes |
|---|---|---|
| Device, Android version, API level, ABIs | `Build` | |
| CPU cores | `Runtime.availableProcessors()` | Tagged *estimate*: cores online right now. |
| Total / available RAM, low-memory flag, memory classes | `ActivityManager` | Available RAM is tagged *estimate* (changes constantly). |
| Total / free storage | `StatFs` on the app's files volume | The volume downloads will go to. |
| OpenGL ES version, Vulkan | `ActivityManager`, `PackageManager` | The GPU name is not read (needs a GL context). |
| CPU / GPU / NPU inference | nothing | **Always "not available"**: Android has no honest query for this; it can only be verified by loading a model (Phase 4). |
| Battery, charging, unrestricted battery use | sticky `ACTION_BATTERY_CHANGED`, `PowerManager` | |
| Online, connection type, metered | `ConnectivityManager` | "Online" means validated internet, not merely connected. |
| Microphone, camera, Bluetooth, audio output | `PackageManager.hasSystemFeature` | |
| Speech recognition, on-device speech recognition, text-to-speech | `SpeechRecognizer`, `PackageManager` | On-device recognition can only be queried on Android 13+; below that it is "not available". Needs the two `<queries>` entries added to the manifest. |

Not read: contacts, accounts, location, device identifiers, installed-app list.

## Model recommendation (`models/ModelAdvisor.kt`)

The verdict is one of *looks suitable*, *possible with warnings*, *not recommended* or *cannot be judged*. It is **never**
based on RAM alone, and "looks suitable" always carries the note that offline AI is only marked ready after a model has
been downloaded, verified and test-loaded.

| Check | Rule | Basis shown |
|---|---|---|
| Architecture | `arm64-v8a` reported → ok. Anything else → **cannot be judged** (the runtime's supported architectures have not been verified), not "unsuitable". | measured / unverified |
| Storage | Free space below the file size → problem. Below file size + max(10 %, 256 MiB) → warning. | measured; the size is labelled estimate or measured |
| Memory | Rule of thumb: file size + 512 MiB working memory; total RAM must be at least 2× that (problem), free RAM now at least 1× (warning), plus Android's low-memory flag (warning). | **heuristic** |
| Unknown size, or unreadable storage/RAM | cannot be judged | unverified |
| Licence gate | Gemma 3 1B only: needs a Hugging Face account that accepted the licence (token) or a file you downloaded yourself. | note |
| Download advice (does not change the verdict) | metered/mobile data, battery under 20 % and not charging, offline. | |

**The memory rule of thumb is mine, not Google's, and has not been calibrated on any phone.** The only vendor figure
(about 1,733 MB peak on CPU for Gemma 4 E2B, one Galaxy S26 Ultra) is shown as "one phone, not a requirement". The
constants live in `AdvisorPolicy` so they can be corrected once real loads have been measured in Phase 4. The rule is
more conservative than that figure, so it may call a phone unsuitable that would actually cope; the user can still
choose the model.

Recommendation: the lighter model first when it looks suitable (a smaller download; the advanced one can be added
later); otherwise the first one with only warnings; otherwise none, with an explanation and the reminder that cloud AI
and the phone tools do not need a model.

### Model sizes

Sizes are an `Amount`: **Known** (read from the repository by the app), **Estimate** (labelled "about", with its basis) or
**Unknown** ("not known yet"). Nothing is shown as exact unless the app read it.

| Model | File | Starting figure (estimate) | Note |
|---|---|---|---|
| Gemma 3 1B | `gemma3-1b-it-int4.litertlm` | 584,417,280 bytes | **Differs from the "about 529 MB" in the earlier audit**, which came from a web page; this figure is from the repository's file listing. Exactly why the app re-reads it. |
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm` | 2,588,147,712 bytes | |

Both starting figures came from a web fetch of the Hugging Face file listing on 2026-11-07, which summarises the page
and was not independently checked. They are labelled estimates. The **Check exact sizes online** button reads
`https://huggingface.co/api/models/{repo}/tree/main` (a read-only GET with only an `Accept` header, no key, no personal
data) and replaces the estimate with the real size and the SHA-256 from the `lfs` block. The listing was readable
without signing in for both repositories, including the gated one, when fetched; whether that holds from the app is
untested. The button runs only when pressed, because it contacts huggingface.co.

## Features (`features/`)

All 25 features from the prompt are listed. Each has: internet need (none / optional / required), offline support
(full / partial / none), permissions (required or optional), the features it needs, shared capabilities, extra
download/storage (`Amount`), and **whether it exists yet**.

**Only three features exist today and can be selected: AI Assistant, Cloud AI Integration and Device Control.** The other 22
are shown with "Not built yet · planned for Phase N", have a disabled checkbox, and are refused by the selection logic
itself (not only by the UI). This is deliberate: a tick box on something that does not exist would be a fake control.
When a phase delivers a feature, its `Readiness.Planned(n)` becomes `Readiness.Available` in `Features.kt`.

- **Dependencies:** choosing Device Control also turns on AI Assistant ("added because …"). Turning a feature off turns off
  every chosen feature that needs it, and says which. Shared capabilities (for example the audio system) count once.
- **Presets:** Essential, Smart Assistant, Local AI. Applying one selects only the features that exist and says how many
  of its intended features are not built yet. A preset stays fully editable.
- **Statuses** (`FeatureStatusCalculator`): available, selected / not selected, needs permission, needs download, needs
  internet, works offline, unsupported (a measured "no" for all required hardware), partly supported (some), and *not built
  yet*. An unreadable hardware value is never treated as "missing". Granted runtime permissions are not read yet (no
  available feature needs one); Phase 6 adds that reader.

## Storage review (`setup/StorageCalculator.kt`)

Sums only what the choices add: chosen models, per-feature extras, and an itemised line for the app itself (already
installed, shown but not added). Exact, estimated and unknown parts are kept apart in the total ("about 584 MB, plus 1
item not sized yet"), never blended into one number. "Database, cache and logs" is listed as unknown rather than
invented. The verdict is *enough* / *tight* (less than max(10 %, 256 MiB) spare) / *not enough* (with the shortfall) /
*unknown*. Any download of unknown size, or of 50 MiB or more, is flagged as needing an explicit yes; nothing in this
phase starts a download.

## Persistence (`setup/SetupRepository.kt`, `data/SetupFileStorage.kt`)

`filesDir/setup_state.json`, written through `AtomicFile` so a crash mid-save cannot corrupt it. It holds step progress,
the privacy tick, the model choice, the preset and the chosen features. **No keys and no personal data.** Loading is
forgiving: bad JSON gives a fresh start, unknown step or feature names (from another version) are dropped, wrong-typed
fields fall back to defaults, a step cannot be both done and skipped, and a hand-edited "finished" flag is ignored unless
every required step is really completed. A failed save keeps the state in memory and shows a warning.

Changing an earlier choice invalidates what depended on it: a new model choice resets Storage and Summary; new features
also reset Permissions; unticking Privacy takes back that step.

## Not done in this phase

Model download and the Hugging Face token / import-a-file flow (Phase 4). Real runtime-permission requests and a
granted-permission reader (Phase 6). A "health check" step. Tests on a phone or emulator. Any visual check of the screens.
