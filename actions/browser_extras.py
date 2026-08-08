# actions/browser_extras.py
#
# Small browser-shortcut tools migrated from an older assistant's
# browser_agent.py — but ONLY the capabilities genuinely missing from
# this project. Deliberately NOT migrated (better equivalents exist):
#   - search()   -> web_search.py (LLM-answered, not just a raw DDG snippet)
#   - weather()  -> weather_report.py
#   - play()     -> youtube_video.py (identical first-video scrape technique)
#   - whatsapp() -> send_message.py (contacts.json + reliable wa.me deep
#                   link + auto-send, vs. just opening web.whatsapp.com
#                   and asking the user to pick the contact manually)

import webbrowser
from urllib.parse import quote_plus

try:
    import requests
    _WEB = True
except ImportError:
    _WEB = False

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}


def navigate_maps(destination: str) -> str:
    dest = destination.strip()
    if not dest:
        return "Where would you like directions to, sir?"
    webbrowser.open(f"https://www.google.com/maps/search/{quote_plus(dest)}")
    return f"Opening maps for {dest}, sir."


def translate_text(text: str, target_lang: str = "en") -> str:
    text = text.strip()
    if not text:
        return "What would you like me to translate, sir?"
    url = f"https://translate.google.com/?sl=auto&tl={target_lang}&text={quote_plus(text)}&op=translate"
    webbrowser.open(url)
    return f"Translation to {target_lang} opened in your browser, sir."


def get_news_headlines(count: int = 5) -> str:
    if _WEB:
        try:
            from xml.etree import ElementTree as ET
            r = requests.get("https://news.google.com/rss", timeout=5, headers=HEADERS)
            root  = ET.fromstring(r.content)
            items = [i.text for i in root.findall(".//item/title") if i.text][:count]
            if items:
                out = "Top headlines, sir:\n"
                for i, t in enumerate(items, 1):
                    out += f"  {i}. {t}\n"
                webbrowser.open("https://news.google.com")
                return out.strip()
        except Exception as e:
            print(f"[BrowserExtras] ⚠️ News RSS fetch failed: {e}")
    webbrowser.open("https://news.google.com")
    return "Opened Google News, sir — couldn't fetch headlines directly."


def compose_email(subject: str = "", to: str = "") -> str:
    url = f"https://mail.google.com/mail/?view=cm&fs=1&su={quote_plus(subject)}"
    if to:
        url += f"&to={quote_plus(to)}"
    webbrowser.open(url)
    return "Gmail compose opened, sir."


def browser_extras(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        action: "maps" | "translate" | "news" | "email" (required)
        text: string — destination (maps) or text to translate (translate)
        target_lang: string (optional, for translate, default "en")
        subject: string (optional, for email)
        to: string (optional, for email)
        count: int (optional, for news, default 5)
    """
    params = parameters or {}
    action = params.get("action", "").lower().strip()

    if action == "maps":
        result = navigate_maps(params.get("text", ""))
    elif action == "translate":
        result = translate_text(params.get("text", ""), params.get("target_lang", "en"))
    elif action == "news":
        result = get_news_headlines(params.get("count", 5))
    elif action == "email":
        result = compose_email(params.get("subject", ""), params.get("to", ""))
    else:
        return f"Unknown browser_extras action: '{action}', sir."

    if player:
        player.write_log(f"[browser_extras] {action}: {result[:60]}")

    return result
