# Voice, calls, messages, timers and media

Everything here is written and unit-tested in `:core` (837 tests, offline harness). The Android parts (`:app`) have **not been compiled or run on a device**.

## Voice

- **Push to talk.** Tap the mic on Home or in Chat; one listening session runs, then stops. The app also stops listening and speaking when it leaves the screen.
- **Hands-free "Jarvis" (optional, off by default).** A foreground service (`HandsFreeService`) listens for one keyword, "Jarvis", with Picovoice Porcupine, which runs on the phone. It needs your own free Picovoice **AccessKey** (Prefs > Hands-free); the key is stored encrypted, never in the source or the APK. When the word is heard, the service plays a short tone and runs one normal listening session; then it goes back to waiting.
  - "Hello Jarvis" and "hey Jarvis" work because they contain "Jarvis". **Only "Jarvis" is a wake word.** Other words ("hyee" on its own, say) would need a custom keyword file trained in the Picovoice console; that is not included.
  - It uses battery and shows a permanent notification (Android requires that for a microphone service). Android 14+ needs the microphone foreground-service permission, which the manifest declares.
  - Start it while the app is open. **After a reboot, open the app once**; Android does not let a microphone service start by itself from the boot broadcast.
  - Porcupine itself does not recognise speech, store audio or send it anywhere; it does contact Picovoice to check the AccessKey.
- **Recogniser.** Android's `SpeechRecognizer`. On Android 13+ the on-device recogniser is requested when the phone has one; otherwise the phone's default is used, and **that one may send the sound to its provider (usually Google)**. The app never records, stores or forwards audio.
- **Spoken replies** use Android text-to-speech, and only for requests that were spoken. At most about 400 characters are read; markup and links are removed ("a link"). A lookup's own answer (weather, Wikipedia, web answer, offline library) is spoken. Untrusted text that is being handed to an AI model is never read aloud (`SpeechText`).
- **Voice cannot approve anything sensitive, except the call countdown below.** The confirmation card is answered only by tapping its buttons. The recogniser's result goes through the same planner, executor and checks as typed text.

## Calls

| Say | Tool | Risk |
|---|---|---|
| "call mom", "call John Smith" | `call_contact` | high |
| "call +91 98765 43210" | `call_number` | high |
| "answer the call", "take call" | `answer_call` | medium |
| "hang up", "end the call" | `end_call` | medium |

**Two ways a call is approved**

1. **Spoken as a plain built-in command** (`SPOKEN_BUILT_IN`, matched by the app's own fixed patterns, no AI model involved): Jarvis reads the resolved action back ("Calling Mom, mobile. Say stop to cancel.") and dials after **3 seconds** unless you say stop / cancel / no / wait (or tap Cancel). The read-back is built from the resolved contact or parsed number, never from model text. Only the four call tools can use this path.
2. **Typed, or planned by an AI model, or anything that involved outside content:** a confirmation card shows the exact name and number, and only a tap on the card approves it. The model never sees or passes a confirmation token.

Rules, all covered by tests:

1. The contact is resolved **before** the confirmation (`Tool.prepare`). The card or read-back shows the contact's name and the exact number, and the approval is bound to those resolved arguments. A different number cannot be approved by accident or by the model.
2. More than one matching person, or a person with several numbers, **asks** which one. Nothing is guessed.
3. Codes containing `*` or `#` (USSD and carrier codes) are never dialled.
4. Emergency numbers (100, 101, 102, 108, 110, 112, 119, 911, 999, 000) only open the dialer with the number filled in. The app never places those itself.
5. Without the Phone permission, the dialer opens with the number filled in and the person presses call.
6. Android does not tell the app whether a call connected. Results say "Asked Android to call …", never "called".
7. Answering and ending a call need the "Answer phone calls" permission. If nothing is ringing, Android does not say so; the result says that.

## Messages (SMS and WhatsApp): prepared; WhatsApp can be sent hands-free if you switch it on

| Say | Tool | Risk |
|---|---|---|
| "text Mom I'll be late", "send a message to Rahul saying hello" | `send_sms` | high |
| "WhatsApp Mom I'm on my way" | `send_whatsapp` | high |

- The recipient is resolved from your contacts first (same lookup and the same ask-when-ambiguous rule as calls); a typed phone number is also accepted.
- **A tap, or (WhatsApp only, switch on) the read-back.** Texts always need a tap. A *spoken built-in* WhatsApp command with "Press Send for me in WhatsApp" on gets "Sending WhatsApp to Asha: On my way. Say stop to cancel." and a 3-second countdown; typed or AI-planned ones still need a tap. The card shows the resolved name, the exact number and the exact text.
- **SMS is never sent by the app.** It opens your messaging app with the text filled in (`ACTION_SENDTO`); you press Send. **WhatsApp** opens the chat directly **only when the number starts with `+`**; otherwise it opens WhatsApp's own picker and you choose the chat and press Send. With the switch off the result says the message "has not been sent".
- **Hands-free WhatsApp send (switch on, `+` number only).** After WhatsApp is in front, the accessibility service reads the message box and presses Send only if it holds exactly the approved text and exactly one control is called Send (English "Send"; other app languages may not match, in which case nothing is pressed and you press Send yourself). The result is `HANDED_OFF`: "Pressed Send ... the message box emptied, which is what happens when a message goes out. WhatsApp does not tell me about delivery." If the box still has the text it says so; it never says "delivered".
- **Reading WhatsApp by voice:** "read my unread WhatsApp messages", "any new WhatsApp messages", "go to WhatsApp and read unread inbox" read the WhatsApp notifications that are still showing (needs Notification access). Messages you already opened, or whose notification you dismissed, are not listed; "open WhatsApp" then "read the screen" reads the chat list. The text is private and untrusted: read to you, never obeyed, never logged.
- The text is cleaned of control characters, limited to 1000 characters, and a body that looks like a command ("call 100", "delete everything") is just text; it is never parsed as a command.
- This reverses the project's earlier "never auto-send" rule at the owner's explicit request. The guards are the switch (off by default), the confirmation or read-back, the verified text, the package guard and the rate limit; they are listed in `SECURITY.md` rule 17.

## Wake word without Picovoice: Vosk (offline, no key)
Prefs > Hands-free > Wake engine lets you choose **Vosk**. It downloads a small English model (about 40 MB, no checksum is published for it, so it is size-checked only) and listens on the phone for "jarvis" / "hey jarvis" / "ok jarvis" with no account or access key. It is less battery-friendly and less accurate than Picovoice in noise; each detection has a 3-second cool-down, and a wake only starts one spoken command. The Vosk and JNA library versions in `gradle/libs.versions.toml` could not be checked from the build workspace; if Gradle cannot resolve them, send the log.

## Email and camera (hand-off only)

| Say | Tool | Risk | What happens |
|---|---|---|---|
| "email sam@example.com saying I'll be late" | `compose_email` | high | Shows the exact address and words; after your tap, opens your email app with the draft ready. **You press Send**; the reply says "has not been sent". |
| "write an email to sam@example.com" | `compose_email` | high | Same, with only the address filled in. |
| "email Rahul saying hello" | none | | Asks for the full address. The app cannot look up a contact's email and does not guess one. |
| "open the camera", "take a photo" | `open_camera` | low | Opens the phone's camera app ready for a photo. It cannot press the shutter. |
| "record a video" | `open_camera` | low | Opens the camera app in video mode. It cannot start recording. |

Email is never covered by the 3-second voice countdown, and only one plain address is accepted (no lists, no display names, no line breaks, no `Bcc` tricks). The camera needs no camera permission because it only starts the phone's own camera app.

## Timers and media keys

| Say | Tool | What happens |
|---|---|---|
| "set a timer for 10 minutes" | `set_timer` | Starts the Clock app's countdown (1 second to 24 hours; optional label). |
| "pause", "next track", "previous", "stop music" | `media_control` | Sends a media key event to whatever player is active. |
| "volume up", "volume down" (1 to 5 steps) | `media_control` | Changes the music volume; the result reports the level before and after as Android states it. |

Android does not report whether a timer started or a key was handled, so results say "asked", not "done". Timers and media keys are low risk and run without a card.

## Known limits

- Matching of phrases is English only.
- Names are matched against the phone's contacts as typed by the recogniser; unusual names may need to be typed.
- No tapping or typing inside other apps. Phone control is limited to reading the screen, scrolling, and the system buttons (Back, Home, Recents, lock, screenshot).
- Starting an app by voice from the background needs "Display over other apps"; Android 14 and 15 can still refuse on some phones.
- Not tested on a device. The `:app` code is not compiled yet.
