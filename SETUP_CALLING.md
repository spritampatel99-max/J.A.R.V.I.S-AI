# Setting up reliable WhatsApp messaging & calling

## Messaging (works out of the box once contacts are added)

Add real contacts to `config/contacts.json`:
```json
{
    "John Smith": "+14155552671",
    "Mom": "+919876543210"
}
```
Use full international format (`+` and country code). Once a name is in
here, "message John Smith saying I'm on my way" opens his exact chat via
WhatsApp's official `wa.me` link and sends it — no more guessing which
search result is the right contact.

If a name ISN'T in `contacts.json`, it still falls back to the old
name-search method automatically (less reliable, but doesn't just fail).

You can also add a contact by voice/command:
> "Save contact John Smith as +14155552671"
(routes to `send_message` with `action: "save_contact"`)

## Calling (needs 2 minutes of one-time setup — this is the honest part)

WhatsApp Desktop has no official deep link or keyboard shortcut to trigger
a call — unlike messaging, there's nothing to hook into directly. The only
way to automate the actual "click Call" step is image-template matching
against a screenshot of the call button, which depends on your exact
WhatsApp theme, window size, and screen resolution. Without this, Jarvis
opens the right contact's chat for you (the hard part) and asks you to
click Call yourself (one click).

**To enable automatic clicking:**
1. Open WhatsApp Desktop, open any chat, so the call icon (phone handset)
   is visible in the top-right.
2. Take a screenshot (Win+Shift+S on Windows) and crop tightly around
   JUST the call icon — the smaller and more precise, the better the match.
3. Save it as: `assets/whatsapp_call_button.png` in the project root
   (create the `assets` folder if it doesn't exist).
4. That's it — `call_contact` will now find and click it automatically.

If WhatsApp's UI changes (theme update, resize, etc.) and clicking stops
working, just redo steps 1-3 with a fresh screenshot.
