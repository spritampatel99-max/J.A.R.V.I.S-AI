# Security design (what exists today)

Only implemented behaviour is listed. Code marked "unit-tested" was tested offline in `:core`; the Android parts (screens, alarms, receivers, storage on a device) have never been compiled or run.

## Rules enforced in code

1. **The model can never approve anything.** Confirmation is a suspended request in `ConfirmationGate`.
   Only the UI layer holds the `ConfirmationResponder` (`approve` / `deny`). No tool, prompt or tool result ever
   contains a request id or token. The dialog is native; tapping outside it counts as Deny.
2. **Approval is bound to the exact action.** The request carries a SHA-256 fingerprint of the tool name and the
   validated arguments (length-prefixed canonical form). The executor re-computes it after approval and refuses to run on a mismatch
   (`CONFIRMATION_MISMATCH` in the audit log).
3. **Single use, short life.** One request is pending at a time, it expires after 120 s, and a cancelled run withdraws it.
4. **Risk levels.** Tools are LOW, MEDIUM or HIGH. HIGH is always confirmed; MEDIUM is confirmed when the "confirm
   medium actions" setting is on. HIGH tools are never retried automatically.
5. **Outside content tightens the rules.** Text from web pages or files is wrapped as untrusted data, and once a run has
   seen any, the confirmation threshold drops to MEDIUM for the rest of that run.
6. **No code execution.** There is no path that runs model-written Python, shell or Kotlin. A plan can only name
   registered tools, with arguments checked against each tool's declared parameter types.
7. **Success is only reported after the system confirms it.** `set_alarm` checks `AlarmManager.nextAlarmClock`; the
   flashlight checks the torch callback; `open_url` and `open_youtube` report "handed to the system", not "loaded".
   A tool that can only hand off says so (`HANDED_OFF`), and the run summary shows it differently from a confirmed result.
8. **Foreground-only launches.** Android blocks activity starts from the background, so the app refuses to launch other
   apps when it is not in the foreground, instead of claiming it did.
9. **Only http(s) links** are opened; `javascript:`, `file:`, `intent:` and links with `user:pass@` are rejected.

## Memory, automations and agents (added in Phase 7 to 9)

10. **Memory saves only on an explicit act:** the Add button, a clear "remember that …" phrase, or approving a suggestion. Text is redacted before storing; chat cannot delete or edit memory. Memory is handed to the model as the person's notes (data) before the fixed rules, never as instructions.
11. **Automations can only run four low-risk actions** (add task, add note, show notification, notify overview), validated at save time, re-validated against the full registry before every run, and executed with a deny-all confirmation and no re-planner. The last two layers are backup and not independently tested (see the test report).
12. **Agents are configuration, not code.** A new agent is a draft with no tools; only the person's Approve tap activates it. Tools are derived from the chosen features, must be low risk, and are re-derived at run time so a hand-edited file grants nothing. Fixed safety rules come after the owner's text. An agent never sees another agent's memory.
13. **Voice cannot approve a confirmation.** Confirmations are answered by tapping only.
14. **Security review** lists what it checked and what it did not; it never reports the phone as "secure" and only counts API keys.

## Secrets

- API keys are encrypted with an AES-256-GCM key held in the Android Keystore (`SecureKeyStore`) and are not written to Room, logs or backups (`allowBackup=false`).
- `SecretRedactor` strips known keys and common key patterns (`AIza…`, `gsk_…`, `sk-or-…`, `Bearer …`, `api_key=…`) from errors and from the audit log. Provider settings print `apiKey=***`.
- Keys travel only in request headers, never in URLs.
- Limits: malware running as this app, or a rooted phone, can still use the Keystore key; anyone holding your unlocked phone can see what you type.

## Setup wizard (Phase 3)

- The saved setup state (`setup_state.json`) holds step progress and choices only: no keys, no personal data. It is written atomically.
- Device analysis reads hardware and system facts only; no contacts, accounts, location, identifiers or installed-app list. The result stays on the phone.
- The only network call the wizard makes itself is the exact-model-size lookup, a read-only GET to `huggingface.co` that
  runs only when the user presses the button, sends no credentials, and goes through the same https-only transport.
- The wizard itself starts no download. Its Permissions step embeds the Access list, which asks Android only when the user taps.

## Model downloads and access (Models and Access tabs)

- A model download or import starts only from an explicit tap, after a dialog showing the size. The file is size-checked and SHA-256-checked
  against Hugging Face before it counts as installed; the result says "Checksum verified" or "Not verified" and never claims more.
- The Hugging Face token is stored with the same Android Keystore AES-GCM store as API keys, sent only in the `Authorization` header to
  `huggingface.co`, dropped on redirects to other hosts, and never written to logs or notifications. Remove it any time in the Models tab.
- Permissions are requested only through Android's own dialogs and Settings pages, never silently, and the screen shows the state Android
  reports. A permission whose feature is not built is labelled "Not used yet". The manifest declares nothing without a matching row.
- Built-in commands run through the same executor as AI plans, so they get the same argument validation, permission checks, confirmation
  gate and audit log. Text matched as a command never reaches a model, and nothing a model says can approve an action.

## Audit log

`filesDir/audit.jsonl`: one JSON object per line, capped in size, redacted, corrupt lines skipped. Sensitive requests,
approvals, denials, expirations, mismatches, blocks and executions are recorded. The Activity tab shows it.

## Not done yet

Biometric lock, backup/export controls, instrumented tests, lint and dependency scans, R8 minification. The Access screen and the Security review screen exist but have never been compiled or run.
