# llm_client.py
#
# Unified LLM access with automatic fallback:
#     Gemini  ->  OpenRouter free-tier pool  ->  local Ollama
#
# Any module can do:
#     from llm_client import ask, ask_json
# instead of hardcoding a single provider. If Gemini is out of quota,
# rate-limited, or misconfigured, and OpenRouter's whole free pool is
# also down/rate-limited, this automatically drops to a local Ollama
# model (if one is installed and running) instead of failing the task.
#
# Requires nothing extra to use Gemini/OpenRouter (same config as
# before). Ollama is fully optional — if it isn't installed/running,
# this client just skips it and reports the real errors from the
# other providers.

import json
import re
import sys
from pathlib import Path

import requests


def _get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent


BASE_DIR        = _get_base_dir()
API_CONFIG_PATH = BASE_DIR / "config" / "api_keys.json"

OLLAMA_URL           = "http://localhost:11434"
DEFAULT_OLLAMA_MODEL = "llama3.1"
OLLAMA_TIMEOUT       = 120

GROQ_URL             = "https://api.groq.com/openai/v1/chat/completions"
DEFAULT_GROQ_MODEL   = "llama-3.3-70b-versatile"
GROQ_TIMEOUT         = 30


def _load_config() -> dict:
    try:
        return json.loads(API_CONFIG_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {}


# ----------------------------------------------------------------- #
# Groq (OpenAI-compatible API, very high free-tier request limits —
# good as a fast middle tier between Gemini and OpenRouter's smaller
# free pool)
# ----------------------------------------------------------------- #

def _groq_chat(
    prompt: str,
    system: str = "",
    model: str | None = None,
    max_tokens: int = 2048,
    temperature: float = 0.7,
) -> str:
    cfg = _load_config()
    key = cfg.get("groq_api_key", "")
    if not key or "PASTE_YOUR" in key:
        raise RuntimeError("Groq API key not configured in config/api_keys.json")

    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})

    resp = requests.post(
        GROQ_URL,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json={
            "model":       model or cfg.get("groq_model", DEFAULT_GROQ_MODEL),
            "messages":    messages,
            "max_tokens":  max_tokens,
            "temperature": temperature,
        },
        timeout=GROQ_TIMEOUT,
    )
    resp.raise_for_status()
    data    = resp.json()
    content = (data.get("choices", [{}])[0].get("message", {}) or {}).get("content", "")

    if not content:
        raise RuntimeError("Groq returned an empty response.")
    return content.strip()


# ----------------------------------------------------------------- #
# Ollama
# ----------------------------------------------------------------- #

def ollama_available() -> bool:
    """Quick check whether a local Ollama server is reachable."""
    try:
        r = requests.get(f"{OLLAMA_URL}/api/tags", timeout=2)
        return r.status_code == 200
    except Exception:
        return False


def _ollama_chat(
    prompt: str,
    system: str = "",
    model: str | None = None,
    max_tokens: int = 2048,
    temperature: float = 0.7,
    json_mode: bool = False,
) -> str:
    cfg   = _load_config()
    model = model or cfg.get("ollama_model", DEFAULT_OLLAMA_MODEL)

    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})

    payload = {
        "model":    model,
        "messages": messages,
        "stream":   False,
        "options":  {"temperature": temperature, "num_predict": max_tokens},
    }
    if json_mode:
        payload["format"] = "json"

    resp = requests.post(f"{OLLAMA_URL}/api/chat", json=payload, timeout=OLLAMA_TIMEOUT)
    resp.raise_for_status()
    data    = resp.json()
    content = (data.get("message", {}) or {}).get("content", "")

    if not content:
        raise RuntimeError("Ollama returned an empty response.")
    return content.strip()


# ----------------------------------------------------------------- #
# Gemini
# ----------------------------------------------------------------- #

def _gemini_chat(
    prompt: str,
    system: str = "",
    model: str = "gemini-2.5-flash",
    max_tokens: int = 2048,
    temperature: float = 0.7,
) -> str:
    import google.generativeai as genai

    cfg = _load_config()
    key = cfg.get("gemini_api_key", "")
    if not key or "PASTE_YOUR" in key:
        raise RuntimeError("Gemini API key not configured in config/api_keys.json")

    genai.configure(api_key=key)
    gm = genai.GenerativeModel(model_name=model, system_instruction=system or None)
    response = gm.generate_content(
        prompt,
        generation_config={"max_output_tokens": max_tokens, "temperature": temperature},
    )
    text = (getattr(response, "text", "") or "").strip()
    if not text:
        raise RuntimeError("Gemini returned an empty response.")
    return text


# ----------------------------------------------------------------- #
# Public API
# ----------------------------------------------------------------- #

def ask(
    prompt: str,
    system: str = "You are a helpful assistant.",
    model: str | None = None,
    max_tokens: int = 2048,
    temperature: float = 0.7,
    prefer: str = "ollama",
) -> str:
    """
    Tries providers in order until one succeeds. Raises RuntimeError
    only if every provider fails, with all individual errors attached.

    Chain (default): local Ollama -> Groq -> OpenRouter -> Gemini
      - Ollama: LOCAL-FIRST. Fully offline, unlimited, no API cost, no
        network dependency - but needs Ollama installed locally with a
        model pulled (see SETUP_OLLAMA_GROQ.md). This is the default
        preferred provider for ordinary text/reasoning requests.
      - Groq: very high free-tier request limits, fast, cloud-based.
      - OpenRouter: large free-model pool, but a low per-model daily cap.
      - Gemini: highest quality, but a small free daily cap - used last
        among these four so it's saved for when everything else failed.

    NOTE: this ordering applies ONLY to ordinary text/reasoning calls
    through this function (planning, error recovery, code generation,
    Wikipedia summarization, etc). It does NOT affect Gemini Live's
    real-time voice/vision pipeline in main.py, which is a completely
    separate system with no equivalent fallback (no other provider here
    offers real-time bidirectional audio+video streaming).

    prefer="gemini"      -> Gemini first (useful when quality matters more
                             than latency/cost for a specific call)
    prefer="groq"         -> Groq first
    prefer="openrouter"    -> OpenRouter first
    """
    orders = {
        "ollama":     ["ollama", "groq", "openrouter", "gemini"],
        "groq":       ["groq", "ollama", "openrouter", "gemini"],
        "openrouter": ["openrouter", "groq", "ollama", "gemini"],
        "gemini":     ["gemini", "groq", "openrouter", "ollama"],
    }
    order  = orders.get(prefer, orders["ollama"])
    errors = []

    for provider in order:
        try:
            if provider == "gemini":
                return _gemini_chat(prompt, system=system, max_tokens=max_tokens, temperature=temperature)

            elif provider == "groq":
                return _groq_chat(prompt, system=system, model=model,
                                   max_tokens=max_tokens, temperature=temperature)

            elif provider == "openrouter":
                from or_client import client
                return client.chat(prompt, system=system, model=model,
                                    max_tokens=max_tokens, temperature=temperature)

            elif provider == "ollama":
                if not ollama_available():
                    raise RuntimeError(
                        "Ollama is not reachable at http://localhost:11434 "
                        "(is it installed and running? try: ollama serve)"
                    )
                return _ollama_chat(prompt, system=system, model=model,
                                     max_tokens=max_tokens, temperature=temperature)

        except Exception as e:
            print(f"[LLMClient] ⚠️ {provider} failed: {e}")
            errors.append(f"{provider}: {e}")

    raise RuntimeError("All LLM providers failed:\n" + "\n".join(errors))


def ask_json(
    prompt: str,
    system: str = "Return ONLY valid JSON. No markdown, no extra text, no explanation.",
    model: str | None = None,
    max_tokens: int = 2048,
    prefer: str = "ollama",
) -> dict | list:
    """Same fallback chain as ask(), but parses the result as JSON."""
    raw   = ask(prompt, system=system, model=model, max_tokens=max_tokens,
                temperature=0.2, prefer=prefer)
    clean = raw.strip()
    clean = re.sub(r"```(?:json)?", "", clean).strip().rstrip("`").strip()
    return json.loads(clean)


if __name__ == "__main__":
    print("=" * 55)
    print("  llm_client self-test")
    print("=" * 55)
    print(f"Ollama reachable: {ollama_available()}")
    try:
        print(ask("Say hello in exactly 3 words."))
    except Exception as e:
        print(f"FAILED: {e}")
