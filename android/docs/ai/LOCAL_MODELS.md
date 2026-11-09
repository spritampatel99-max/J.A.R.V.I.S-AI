# Local (on-device) models: install and import

Status: **installing works in code; chatting with an installed model does not exist yet.** The on-device engine
(LiteRT-LM) is the next step and needs a newer Kotlin than the pinned 2.1.20. Until then the Models tab says plainly that an
installed model is not active. The install code has been unit-tested offline; the screen, the service and the notification have not met a
device yet.

## What the Models tab does

| Action | What happens |
|---|---|
| **Download** | Asks for a clear yes first (size, and a warning when the connection may be metered). Then reads the exact file size and SHA-256 from Hugging Face, checks free space, downloads, verifies, and only then marks the model installed. |
| **Resume** | An interrupted or cancelled download keeps its `.part` file; the next attempt continues from that byte with an HTTP `Range` request. |
| **Import file** | Installs a model file you already have, picked with Android's file chooser. It is checked against Hugging Face's size and checksum when the repository can be reached; otherwise it is marked **Not verified**. |
| **Remove** | Deletes the model file (and any partial download). |

A download is **only ever started by an explicit tap**. Setup never downloads anything.

## Rules the installer enforces (`ModelInstaller`, tested offline)

- Downloads to `<name>.part`, then verifies, then moves into place atomically. A half-written file can never be mistaken for a model.
- Needs the file size plus a safety margin free before it starts.
- Rejects a file whose size differs from the repository's, and one whose SHA-256 differs from Hugging Face's published value.
  A model is marked "Checksum verified" only when the checksum really matched.
- The access token is sent **only** as an `Authorization` header to `huggingface.co`; redirects to other hosts drop it, and
  the HTTP client does not follow redirects to a non-https address.
- The token and file contents never appear in logs or notifications.
- Cancelling keeps the partial file and installs nothing. Failure messages name the reason (no space, access denied, network,
  size mismatch, checksum mismatch).

## Gated model: Gemma 3 1B

Google publishes it behind a licence you accept on Hugging Face. To download it in the app: open the model page, sign in and accept the
licence, create a read-only token at `https://huggingface.co/settings/tokens`, paste it in the Models tab. It is stored encrypted
(Android Keystore) and is only used for Hugging Face. If you would rather not give the app a token, download the file in a browser and
use **Import file**. Gemma 4 E2B is not gated.

## Background work

A running download uses a foreground service (type `dataSync`) with a progress notification so Android does not stop it when the app
is not on screen. Android 15 limits how long `dataSync` services may run; if Android ends it, the download stops, keeps its `.part`
file and can be resumed. Notification permission (Access tab) is needed to see the progress; the download works without it.

## Not done

Loading and running a model, the Kotlin bump, GPU or NPU use, memory calibration (the RAM rule of thumb in the advisor is uncalibrated),
and a test against a real Hugging Face download. The real file sizes and checksums have only been read through a web fetch.

## Routing between on-device and cloud AI (written and tested, not wired into the app yet)

`RoutePlanner` and `RoutedLlmClient` (in `:core`, `ai/router`) decide where a request goes, and `LocalEngine` (`ai/local`) is the boundary the
on-device engine will implement. No engine exists yet, so nothing in the app uses the router today: the app still asks the cloud providers
directly, and built-in commands still run first. Wiring comes with the engine.

| Mode | Order | Never |
|---|---|---|
| Local only | installed on-device models | the cloud, whatever else fails |
| Local first (default) | on-device models, then the cloud | |
| Cloud assisted | the cloud, then on-device models | |
| Manual | exactly the route the user picked | any other route |

- An on-device model is usable only when the app has an engine **and** the model's file is installed and verified.
- The cloud is usable only when the phone is online, a provider has a key and a model saved, and the mode allows it.
- Light requests try Gemma 3 1B first. Heavy ones (the planner's strict JSON plan) try Gemma 4 E2B first, because the smaller model is weaker at
  structured output. This ordering is my decision, not from the specification, which lists the small model first.
- A failed route does not end the request while another is allowed. The answer carries notes such as "Gemma 4 E2B: not enough memory", and the
  UI can show them. If nothing can answer, the message says why and what to do (add a cloud key, install a model).
- One request uses the engine at a time; a model is loaded only if it is not already in memory; an out-of-memory error unloads the model so the
  app is not the next thing Android stops; cancelling a request stops the engine, and a request that is only waiting its turn cannot stop
  another request's work.
- An on-device answer is labelled "On-device Gemma 3 1B" and never as a cloud provider.
- Local models have no JSON mode. The planner already parses defensively and retries once, but expect the small model to fail strict
  plans more often than a cloud model; if it does, the router moves on to the next route.

## Engine step (not started)

LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`): Google's guide shows `EngineConfig(modelPath, backend, cacheDir)`, `Engine(config).initialize()`
(up to about 10 seconds, so off the main thread), `engine.createConversation(...)`, and `sendMessage` or a Flow-returning `sendMessageAsync`. What is **not** confirmed:
a pinned version (the guide says `latest.release`), the Kotlin version it needs (my earlier audit noted Kotlin 2.4 metadata, which the pinned 2.1.20 cannot read),
and how to cancel a running generation. A lookup of the published versions was declined, so these stay open until the version and requirement are known.
