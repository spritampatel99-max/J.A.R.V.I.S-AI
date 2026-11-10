# Voice and calls

## Voice
- **Push to talk only.** Tap the mic on Home or in Chat; one listening session runs, then stops. There is no always-listening mode and no wake word. The app also stops listening and speaking when it leaves the screen.
- **Recogniser.** Android's `SpeechRecognizer`. On Android 13+ the on-device recogniser is requested when the phone has one; otherwise the phone's default is used, and **that one may send the sound to its provider (usually Google)**. The app never records, stores or forwards audio. The Access list and Prefs say this.
- **Spoken replies** use Android text-to-speech, and only for requests that were spoken. Markup, links and anything that came from outside the app (web content) are not read aloud (`SpeechText`).
- **Voice cannot approve anything.** The confirmation card is answered only by tapping its buttons. The recogniser's result goes through the same planner, executor and checks as typed text.

## Calls
| Say | Tool | Risk |
|---|---|---|
| "call mom", "call John Smith" | `call_contact` | high, always confirmed |
| "call +91 98765 43210" | `call_number` | high, always confirmed |
| "answer the call", "take call" | `answer_call` | medium |
| "hang up", "end the call" | `end_call` | medium |

Rules, all covered by tests:
1. The contact is resolved **before** the confirmation (`Tool.prepare`). The card shows the contact's name and the exact number, and the approval is bound to those resolved arguments. A different number cannot be approved by accident or by the model.
2. More than one matching person, or a person with several numbers, **asks** which one. Nothing is guessed.
3. Codes containing `*` or `#` (USSD and carrier codes) are never dialled.
4. Emergency numbers (100, 101, 102, 108, 110, 112, 119, 911, 999, 000) only open the dialer with the number filled in. The app never places those itself.
5. Without the Phone permission, the dialer opens with the number filled in and the person presses call.
6. Android does not tell the app whether a call connected. Results say "Asked Android to call …", never "called".
7. Answering and ending a call need the "Answer phone calls" permission. If nothing is ringing, Android does not say so; the result says that.

## Known limits
- Matching of phrases is English only.
- Names are matched against the phone's contacts as typed by the recogniser; unusual names may need to be typed.
- Not tested on a device. The `:app` code is not compiled yet.
