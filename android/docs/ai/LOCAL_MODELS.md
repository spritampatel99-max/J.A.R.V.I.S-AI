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
