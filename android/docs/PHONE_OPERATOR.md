# Phone operator and what "do anything I say" really covers

The phone operator (`operate_phone`) lets JARVIS 3.0 carry out a task inside other apps, for example "open Instagram and go to my messages". It is **off until you turn it on** in Prefs > Access > Phone operator. It also needs the Accessibility service and the "press buttons and type" switch.

## How it works
1. You see the task in words and approve it (this is always a high-risk step).
2. JARVIS opens the app, then repeats: read the visible text, ask your AI provider for ONE next move, do it.
3. The AI answers with a move and a **number** from the list of texts on screen. The app finds that text and presses it. The AI cannot press something that is not on screen, cannot give coordinates, and cannot call any other tool.
4. It stops when the task is done, after a step limit, when it repeats itself, or when the screen is off limits.

## What it asks you about first
Pressing a control named like send, post, share, call, delete, install and similar shows you the app and the exact name; it happens only if you approve with a tap in JARVIS. The AI can never approve.

## What it will never do
- Press anything that moves money or buys (pay, buy, transfer, checkout and similar).
- Read or touch banking, payment and password-manager apps, Android's permission and install screens, system settings, or JARVIS itself.
- Obey text it reads on screen. Screen, web and notification text is treated as data and can never become a command.
- Claim success when Android has not confirmed it. Android does not report whether an app did what was pressed, so the result says what was done, not that the app succeeded.

## Limits you should know
- At most 12 presses a minute (`ActionLimiter`).
- It works from the **text the app shows**. Screens made of pictures, games, and apps that hide their text from accessibility cannot be operated.
- What is on the screen is sent to your AI provider. Do not run it on screens with private information.
- Never run on a phone yet: compiled in CI, tested only with a stand-in screen on the PC.
- "Anything I say gets done" is not possible on Android. Hard limits: apps cannot silently change Wi-Fi / Bluetooth / airplane mode, cannot press other apps' permission dialogs, cannot start a microphone service from the background after Android 14, cannot install or update themselves, and cannot see inside apps that block accessibility. Where a limit applies, JARVIS says so and, where it can, opens the right Settings page or app for you.
