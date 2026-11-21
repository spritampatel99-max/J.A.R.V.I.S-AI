# The pasted "JARVIS 3.0" summary against this project

Checked 2026-11-21 against the code in this repository. "Core tested" means `:core` passes in the offline harness (837 tests); `:app` has **never been compiled**, so every `:app` line is "written", not "working".

| Item in the summary | This project | Notes |
|---|---|---|
| `:core` pure Kotlin, `:app` Android, ports pattern | **Matches** | Two Gradle modules; the tools talk to the phone only through port interfaces (`Ports.kt`, `PhoneActionPorts.kt`, `CallPorts.kt`, `EmailAndCameraTools.kt`). |
| Tool: call contact / number / answer / end | **Matches** | Core tested. Typed or AI-planned calls need a tap; a spoken plain command gets the 3-second read-back. |
| Tool: camera | **Added this round** | `open_camera` opens the phone's camera app in photo or video mode. It cannot press the shutter and takes nothing itself. |
| Tool: device info, flashlight, open URL, open YouTube, alarm, timer, time | **Matches** | Alarm and timer are handed to the Clock app, so they survive the app being closed. |
| Tool: media control | **Matches, different mechanism** | Media keys plus volume (no extra permission). The summary's `MediaController` seek is **not built**: Android has no key for "go to 1:20", and a controller needs notification access. |
| Tool: SMS / WhatsApp messaging | **Matches, hand-off only** | Opens the messaging app with the text ready; the person presses Send. |
| Tool: email | **Added this round** | `compose_email` opens the email app with address, subject and text ready; always a tap; the address must be typed out (the app cannot look up a contact's email). |
| Tool: notifications | **Matches** | Read and clear notifications through a notification listener; text is private (not logged, not given to a model). |
| Tool: open app | **Matches** | See "Dynamic app discovery" below. |
| Tool: research | **Matches** | Weather, Wikipedia and a grounded web answer, shown in chat or spoken, plus the offline library. |
| 11 ports | **Matches and goes beyond** | Torch, alarm, URL, device info, timer, contacts, call, app launcher, messaging, notifications, media, plus email and camera. |
| 7 cloud providers | **Matches** | Gemini, Groq, OpenAI, OpenRouter, Claude, Mistral, and a 7th "FreeLLM / own endpoint". |
| FreeLLM at `https://freellm.com/v1/chat/completions`, no key, 60 requests a minute, 87+ models | **Not taken as written** | I could not verify that endpoint. The projects named FreeLLM that I could find are gateways you run yourself with your own provider keys. So the 7th provider has **no built-in address**: you type an https address (and a key if it needs one). Prompts go to whatever address you enter, and the screen says so. |
| Model names (gemini-1.5-flash, claude-3.5-sonnet, gpt-4o …) | **Not copied** | Those names are retired or stale; the app loads the model list from your own key (Load models) and tests it. |
| Dynamic app discovery (`AppCatalog`, 5-minute cache) | **Done in a safer form** | `AndroidAppLauncher` lists apps that have a launcher icon, within Android's package-visibility rules (the manifest declares no "all packages" permission), cached for 60 seconds, so "open <any installed app>" works by name. |
| `DynamicToolRegistry` (a new tool per installed app: `open_app_*`, `view_web_*`, `compose_email_*`) | **Not built, on purpose** | An app's name is text written by that app's author. Putting it into the tool list would let an installed app write into the planner's instructions. A tool's rules (risk, confirmation, arguments) are also fixed at start-up in this design. The same results come from fixed tools with arguments: `open_app(name)`, `open_url`, `compose_email`, `send_sms`. |
| `JarvisAccessibilityService` that auto-sends WhatsApp | **Not built, on purpose** | The project's master prompt says not to use Accessibility Services as an automation bypass and to use only supported methods for WhatsApp. The accessibility service here reads the screen and presses system buttons only. See `docs/VOICE_AND_CALLS.md`. |
| Wake word: Porcupine, foreground service, keywords "jarvis", "hey jarvis", "ok jarvis" (configurable) | **Partly** | Real Porcupine service; the built-in keyword is "Jarvis", so "hey Jarvis" and "ok Jarvis" work because they contain it. Separate configurable keywords need a keyword file trained in Picovoice's console; importing one is **not built**. |
| Local models: Gemma 3 1B (584 MB, gated), Gemma 4 E2B (2.6 GB, Apache-2.0), Hugging Face litert-community, `.litertlm`, no global size limit | **Matches** | Same catalog and sizes. No on-device engine in the default build (an opt-in, uncompiled LiteRT-LM engine exists). |
| Alarms with `AlarmManager.setExactAndAllowWhileDoze` | **Different** | Alarms and timers go to the Clock app. Reminders and automations use exact alarms where the person allowed them. |
| Branding "JARVIS 3.0" | **Done for what people see** | App name, screens, notifications, permission instructions, the planner's self-description, README and setup docs. Package names, the repository name, the wake word "Jarvis" and historical documents (`PROJECT_AUDIT.md`, `CHANGELOG.md`) keep their old wording. |
| Build status "pending verification" | **Same** | Nothing here has been compiled with Gradle in this workspace. |

## What to expect on the first build
Both new Android classes (`AndroidEmailPort`, `AndroidCameraLauncher`) and the Cloud-tab address field are uncompiled. If Gradle complains, send the log back.
