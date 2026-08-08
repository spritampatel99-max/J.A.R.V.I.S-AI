# actions/screen_reader.py
#
# One-shot screen reading: "what does this error say", "summarize this
# document on screen", "read me this table". Unlike screen_processor.py
# (which opens a persistent Gemini Live audio session for real-time
# back-and-forth), this takes a single screenshot, asks a vision model
# ONE question, and returns a plain text answer immediately — much faster
# for a single "what's on my screen" style request, and works through the
# same Gemini -> Groq/OpenRouter -> local-OCR fallback chain as everything
# else, so it degrades gracefully instead of hard-depending on one API.

import base64
import io
import json
import sys
from pathlib import Path

import mss
import mss.tools

try:
    import PIL.Image
    _PIL_OK = True
except ImportError:
    _PIL_OK = False

try:
    import pytesseract
    _TESSERACT_OK = True
except ImportError:
    _TESSERACT_OK = False


def _get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


BASE_DIR        = _get_base_dir()
API_CONFIG_PATH = BASE_DIR / "config" / "api_keys.json"

IMG_MAX_W = 1280
IMG_MAX_H = 720
JPEG_Q    = 70


def _load_config() -> dict:
    try:
        return json.loads(API_CONFIG_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _capture_screenshot() -> bytes:
    with mss.mss() as sct:
        shot      = sct.grab(sct.monitors[1])
        png_bytes = mss.tools.to_png(shot.rgb, shot.size)

    if not _PIL_OK:
        return png_bytes

    img = PIL.Image.open(io.BytesIO(png_bytes)).convert("RGB")
    img.thumbnail([IMG_MAX_W, IMG_MAX_H], PIL.Image.BILINEAR)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=JPEG_Q)
    return buf.getvalue()


def _gemini_vision(image_bytes: bytes, prompt: str) -> str:
    import google.generativeai as genai

    cfg = _load_config()
    key = cfg.get("gemini_api_key", "")
    if not key or "PASTE_YOUR" in key:
        raise RuntimeError("Gemini API key not configured.")

    genai.configure(api_key=key)
    model = genai.GenerativeModel("gemini-2.5-flash")

    image_part = {"mime_type": "image/jpeg", "data": image_bytes}
    response   = model.generate_content([prompt, image_part])
    text       = (getattr(response, "text", "") or "").strip()

    if not text:
        raise RuntimeError("Gemini vision returned an empty response.")
    return text


def _openrouter_vision(image_bytes: bytes, prompt: str) -> str:
    from or_client import client
    b64 = base64.b64encode(image_bytes).decode("utf-8")
    return client.vision(prompt, b64, mime="image/jpeg")


def _ocr_only(image_bytes: bytes) -> str:
    """Fully offline fallback — extracts raw text only, no Q&A reasoning.
    Requires `pip install pytesseract` AND the Tesseract binary installed
    separately (https://github.com/tesseract-ocr/tesseract)."""
    if not _TESSERACT_OK or not _PIL_OK:
        raise RuntimeError(
            "pytesseract not available for offline OCR fallback. "
            "Install with: pip install pytesseract (and the Tesseract binary)."
        )
    img  = PIL.Image.open(io.BytesIO(image_bytes))
    text = pytesseract.image_to_string(img).strip()
    if not text:
        raise RuntimeError("OCR found no readable text on screen.")
    return text


def read_screen(query: str) -> str:
    """
    Tries Gemini vision -> OpenRouter vision pool -> local OCR (offline,
    raw text only, no reasoning) in order.
    """
    image_bytes = _capture_screenshot()
    errors = []

    try:
        return _gemini_vision(image_bytes, query)
    except Exception as e:
        print(f"[ScreenReader] ⚠️ Gemini vision failed: {e}")
        errors.append(f"gemini: {e}")

    try:
        return _openrouter_vision(image_bytes, query)
    except Exception as e:
        print(f"[ScreenReader] ⚠️ OpenRouter vision failed: {e}")
        errors.append(f"openrouter: {e}")

    try:
        raw_text = _ocr_only(image_bytes)
        return f"(Offline OCR — raw text only, no analysis)\n{raw_text[:2000]}"
    except Exception as e:
        print(f"[ScreenReader] ⚠️ OCR fallback failed: {e}")
        errors.append(f"ocr: {e}")

    raise RuntimeError("All screen-reading methods failed:\n" + "\n".join(errors))


def screen_reader(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        query: string (optional) — what to look for / ask about the screen.
               Defaults to "Describe what's on screen and read out any
               important text."
    """
    query = (parameters or {}).get("query", "").strip()
    if not query:
        query = "Describe what's on screen and read out any important text clearly and concisely."

    try:
        answer = read_screen(query)
        if player:
            player.write_log(f"[screen_reader] {query[:50]}")
        return answer
    except Exception as e:
        print(f"[ScreenReader] ❌ {e}")
        return f"I couldn't read the screen, sir: {e}"


if __name__ == "__main__":
    q = input("What should I look for on screen? ") or "Describe what's on screen."
    print(screen_reader({"query": q}))
