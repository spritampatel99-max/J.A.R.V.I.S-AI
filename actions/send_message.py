# actions/send_message.py
# Universal messaging — WhatsApp & Instagram
# Uses visual element detection (pyautogui + screen search) instead of
# hardcoded tab/click sequences — works on any screen resolution.

import time
import webbrowser
from urllib.parse import quote
import pyautogui
from pathlib import Path

from actions.contacts import find_number

pyautogui.FAILSAFE = True
pyautogui.PAUSE = 0.08

def _open_app(app_name: str) -> bool:
    """Opens an app via Windows search."""
    try:
        pyautogui.press("win")
        time.sleep(0.4)
        pyautogui.write(app_name, interval=0.04)
        time.sleep(0.5)
        pyautogui.press("enter")
        time.sleep(2.0)  
        return True
    except Exception as e:
        print(f"[SendMessage] Could not open {app_name}: {e}")
        return False


def _search_contact(contact: str, platform: str):
    """
    Searches for a contact inside the messaging app.
    Uses Ctrl+F (universal search shortcut) then types contact name.
    """
    time.sleep(0.5)
    pyautogui.hotkey("ctrl", "f")
    time.sleep(0.4)
    pyautogui.hotkey("ctrl", "a")
    pyautogui.write(contact, interval=0.04)
    time.sleep(0.8)
    pyautogui.press("enter")
    time.sleep(0.6)


def _type_and_send(message: str):
    """Types message and sends it."""
    pyautogui.press("tab")
    time.sleep(0.2)
    pyautogui.hotkey("ctrl", "a")
    pyautogui.write(message, interval=0.03)
    time.sleep(0.2)
    pyautogui.press("enter")
    time.sleep(0.3)


def _send_whatsapp_via_link(phone: str, message: str) -> str:
    """
    FEATURE: reliable WhatsApp send using the official click-to-chat deep
    link (https://wa.me/<phone>?text=...), instead of blind keystroke
    automation guessing which search result is the right contact. This
    opens the exact chat for that phone number directly — no ambiguity,
    no risk of the message landing in the wrong conversation.

    Still requires ONE keystroke (Enter) to actually send once the chat
    loads, since wa.me only pre-fills the message box, it doesn't send it.
    """
    digits = "".join(c for c in phone if c.isdigit())
    url    = f"https://wa.me/{digits}?text={quote(message)}"

    webbrowser.open(url)
    time.sleep(4.0)  # give WhatsApp Desktop/Web time to open and load the chat

    pyautogui.press("enter")
    time.sleep(0.3)

    return f"Message sent to {phone} via WhatsApp."


def _send_whatsapp(receiver: str, message: str) -> str:
    """
    Sends a WhatsApp message. Prefers the reliable phone-number deep-link
    method (see _send_whatsapp_via_link) when the receiver is a saved
    contact; falls back to the old name-search + keystroke method
    otherwise (still works, just less reliable for ambiguous names).
    """
    phone = find_number(receiver)
    if phone:
        try:
            return _send_whatsapp_via_link(phone, message)
        except Exception as e:
            print(f"[SendMessage] ⚠️ Link-based send failed ({e}), falling back to keystroke method")

    try:
        if not _open_app("WhatsApp"):
            return "Could not open WhatsApp."

        time.sleep(1.5)

        pyautogui.hotkey("ctrl", "f")
        time.sleep(0.4)
        pyautogui.hotkey("ctrl", "a")
        pyautogui.write(receiver, interval=0.04)
        time.sleep(1.0)

        pyautogui.press("enter")
        time.sleep(0.8)

        # BUGFIX: after selecting a search result with Enter, WhatsApp
        # Desktop keeps keyboard focus in the search box, not the message
        # compose box. Typing here without shifting focus first would type
        # the message into the search field instead of sending it. The Tab
        # press moves focus into the compose box (this is exactly what the
        # _type_and_send() helper below was written for, but it was never
        # actually being called from here — reusing it now).
        _type_and_send(message)

        return f"Message sent to {receiver} via WhatsApp."

    except Exception as e:
        return f"WhatsApp error: {e}"


def call_contact(receiver: str, platform: str = "whatsapp") -> str:
    """
    FEATURE: place a call to a saved contact.

    HONEST LIMITATION: WhatsApp Desktop has no scriptable/CLI way to
    trigger a call — there's no deep link or keyboard shortcut for it
    (unlike sending a message, which wa.me supports officially). The only
    way to automate clicking the actual call button is image-template
    matching against a screenshot of that button, which is fragile and
    depends on your exact WhatsApp theme/window size/resolution.

    This function opens the correct chat reliably (via the same wa.me
    link as messaging), and THEN attempts to click a call-button image if
    you've supplied one at assets/whatsapp_call_button.png (crop it from
    your own screenshot — see SETUP_CALLING.md). If that file doesn't
    exist, it opens the chat and asks you to click Call yourself — still
    saves you the contact search, just not the last click.
    """
    phone = find_number(receiver)
    if not phone:
        return f"I don't have a saved phone number for {receiver}, sir. Add one to config/contacts.json."

    digits = "".join(c for c in phone if c.isdigit())
    webbrowser.open(f"https://wa.me/{digits}")
    time.sleep(4.0)

    template_path = Path(__file__).resolve().parent.parent / "assets" / "whatsapp_call_button.png"
    if template_path.exists():
        try:
            import pyautogui as pag
            location = pag.locateCenterOnScreen(str(template_path), confidence=0.8)
            if location:
                pag.click(location)
                return f"Calling {receiver} via WhatsApp, sir."
        except Exception as e:
            print(f"[SendMessage] ⚠️ Call-button image match failed: {e}")

    return (
        f"Opened {receiver}'s chat on WhatsApp, sir — I couldn't confirm the call "
        f"button location automatically, so please click Call. "
        f"(See SETUP_CALLING.md to enable automatic call-button clicking.)"
    )


def _send_instagram(receiver: str, message: str) -> str:
    """
    Sends an Instagram DM via browser (instagram.com).
    Steps: Open Chrome → Go to instagram.com/direct → Search contact → Send
    """
    try:
        import webbrowser

        webbrowser.open("https://www.instagram.com/direct/new/")
        time.sleep(3.5)

        pyautogui.write(receiver, interval=0.05)
        time.sleep(1.5)

        pyautogui.press("down")
        time.sleep(0.3)
        pyautogui.press("enter")
        time.sleep(0.5)

        for _ in range(3):
            pyautogui.press("tab")
            time.sleep(0.1)
        pyautogui.press("enter")
        time.sleep(1.5)

        pyautogui.write(message, interval=0.04)
        time.sleep(0.2)
        pyautogui.press("enter")

        return f"Message sent to {receiver} via Instagram."

    except Exception as e:
        return f"Instagram error: {e}"

def _send_telegram(receiver: str, message: str) -> str:
    """Sends a Telegram message via Windows desktop app."""
    try:
        if not _open_app("Telegram"):
            return "Could not open Telegram."

        time.sleep(1.5)

        pyautogui.hotkey("ctrl", "f")
        time.sleep(0.4)
        pyautogui.write(receiver, interval=0.04)
        time.sleep(1.0)
        pyautogui.press("enter")
        time.sleep(0.8)

        # BUGFIX: same missing focus-shift as WhatsApp — Tab into the
        # compose box before typing instead of typing straight into the
        # still-focused search field.
        _type_and_send(message)

        return f"Message sent to {receiver} via Telegram."

    except Exception as e:
        return f"Telegram error: {e}"



def _send_generic(platform: str, receiver: str, message: str) -> str:
    """
    For any other platform not explicitly supported.
    Opens the app, searches for contact, types and sends.
    Works for: Messenger, Discord, Signal, etc.
    """
    try:
        if not _open_app(platform):
            return f"Could not open {platform}."

        time.sleep(1.5)
        pyautogui.hotkey("ctrl", "f")
        time.sleep(0.4)
        pyautogui.write(receiver, interval=0.04)
        time.sleep(1.0)
        pyautogui.press("enter")
        time.sleep(0.8)

        # BUGFIX: same missing focus-shift bug as the other senders.
        _type_and_send(message)

        return f"Message sent to {receiver} via {platform}."

    except Exception as e:
        return f"{platform} error: {e}"

def send_message(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None
) -> str:
    """
    Called from main.py.

    parameters:
        receiver     : Contact name to send to (required)
        action       : "message" | "call" (optional, default: message)
        message_text : The message content (required if action is "message")
        platform     : whatsapp | instagram | telegram | <any app name>
                       Default: whatsapp
    """
    params       = parameters or {}
    receiver     = params.get("receiver", "").strip()
    action       = params.get("action", "message").lower().strip()
    message_text = params.get("message_text", "").strip()
    platform     = params.get("platform", "whatsapp").strip().lower()

    if not receiver:
        return "Please specify who to send the message to, sir."

    if action == "call":
        result = call_contact(receiver, platform=platform)
        if player:
            player.write_log(f"[msg] call {receiver}: {result[:60]}")
        return result

    if action == "save_contact":
        phone = params.get("phone", "").strip()
        if not phone:
            return "Please provide the phone number to save, sir."
        from actions.contacts import save_contact
        result = save_contact(receiver, phone)
        if player:
            player.write_log(f"[msg] {result}")
        return result

    if not message_text:
        return "Please specify what message to send, sir."

    print(f"[SendMessage] 📨 {platform} → {receiver}: {message_text[:40]}")
    if player:
        player.write_log(f"[msg] Sending to {receiver} via {platform}...")

    if "whatsapp" in platform or "wp" in platform or "wapp" in platform:
        result = _send_whatsapp(receiver, message_text)

    elif "instagram" in platform or "ig" in platform or "insta" in platform:
        result = _send_instagram(receiver, message_text)

    elif "telegram" in platform or "tg" in platform:
        result = _send_telegram(receiver, message_text)

    else:
        result = _send_generic(platform, receiver, message_text)

    print(f"[SendMessage] ✅ {result}")
    if player:
        player.write_log(f"[msg] {result}")

    return result