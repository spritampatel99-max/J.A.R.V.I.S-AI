# Payment and banking apps (2026-11-27)

**Problem reported:** payment apps jump back to the home screen or close themselves on the phone while JARVIS is installed.
**Cause: not confirmed.** I cannot run the app, so everything below separates what the code now does from what is only a likely cause. Nothing here has been tried on a phone.

## What JARVIS never does with payment apps
- It never reads or presses anything in a payment, banking, password-manager or authenticator app, or in an app on your own "Never read these apps" list (the "Never read these apps" list in Prefs > Access). This is a rule in the code that runs before any screen tool, not a setting.
- It never presses a money button ("pay", "send money", "transfer", "buy", "checkout", "UPI" and similar), whoever asks and however it is confirmed.
- It never presses Home by itself. Only the optional phone operator can press Home, and only when you told it to.
- The list of apps is a best guess by package name (words such as `bank`, `upi`, `paytm`, `phonepe`, `paisa`, `wallet`, `bhim`, `paypal`, `password`, `authenticator`). An app it does not recognise is not covered: add it to your own list.

## The two things that most likely make a payment app close
Payment and banking apps protect themselves against other apps that could watch or cover the screen. Two things JARVIS does can trigger that:

1. **JARVIS's accessibility service is switched on** (Settings > Accessibility). Many payment apps check the list of enabled accessibility services when they start and close if there is one they do not know. Only you can switch it on or off in Settings. JARVIS cannot hide it.
2. **A window is drawn over the payment app.** While hands-free is on, JARVIS draws a small card over other apps when it listens, thinks or speaks. A payment app that finds something drawn over it may close.

## What changed on 2026-11-27 (point 2)
The floating card is now kept off payment, banking and password apps (and apps on your own list):
- If **you ask JARVIS to open** such an app ("open PhonePe"), the card is taken down **before** the app starts and stays off for 45 seconds, so it cannot be on screen while the app starts. This works even when the accessibility service is off.
- If such an app is **already in front** (JARVIS asks Android only for its package name, never the text on screen), the card is not shown, and a card that is up is taken down when the window changes. This needs the accessibility service to be on; with it off, JARVIS cannot tell which app is in front.
- JARVIS's own screens are still fine to draw over.
- Tested in `:core` (the rule and its timing). The wiring in the app compiles in GitHub Actions (run 45899555324) but **has never been run on a phone**, and I could not check whether this was the cause.

## What JARVIS cannot fix (point 1), and what to do
- **Payment mode** ("payment mode" or "I am going to pay"): JARVIS asks Android to switch its accessibility service off and stops hands-free listening. Android lets an app switch its own service off, but only you can switch it back on: Settings > Accessibility > JARVIS 3.0.
- If you pay often, you can leave the accessibility service off. You then lose reading the screen, "how many unread chats" in WhatsApp, "play <song>" on YouTube and the phone operator. Hands-free, calls, alarms, apps and the other built-in commands do not need it.

## How to find the real cause (about five minutes, in this order)
Do each step, then open the payment app. Stop at the first step where it stops closing, and tell me which one.
1. In JARVIS turn **hands-free off** (and say nothing to it). Open the payment app.
2. Settings > Accessibility > JARVIS 3.0 > **off**. Open the payment app.
3. Settings > Apps > JARVIS 3.0 > Display over other apps > **off**. Open the payment app.
4. If it still closes with all three off, it is probably **not JARVIS**: check other apps that draw over the screen or use accessibility (screen filters, floating assistants, call recorders, other voice assistants), developer options (USB debugging, "show taps"), screen recording or casting, and a phone that is rooted or has an unlocked bootloader. Many payment apps close for those.
5. Try the same payment app with JARVIS uninstalled for one minute. That is the one test that cleanly tells JARVIS from everything else.

## What to tell me
Which step it stopped closing at, and the payment app's name. If it is step 2, the accessibility service is the cause and the answer is payment mode (or leaving the service off); no code change can hide that service from the app. If it is step 1 or 3, the card or the overlay permission is the cause and I can change the code further.
