# Setting up Groq + Ollama fallback

Jarvis now tries providers in this order for most of its "thinking" (planning,
error recovery, web search, memory extraction, etc.):

    Gemini  ->  Groq  ->  OpenRouter  ->  local Ollama

If one fails (quota exceeded, rate-limited, down, misconfigured), it
automatically tries the next. You don't have to set all of them up — Gemini
alone still works exactly as before — but adding the others makes Jarvis much
harder to knock offline.

## 1. Groq (recommended — free, fast, high request limits)

1. Go to https://console.groq.com/keys and sign up (free).
2. Create an API key.
3. Paste it into `config/api_keys.json`:
   ```json
   "groq_api_key": "gsk_your_real_key_here"
   ```
That's it — no extra pip installs needed (it just uses `requests`, already
in `requirements.txt`).

## 2. Ollama (fully offline, unlimited, needs local hardware)

Ollama runs an LLM entirely on your own machine — no internet, no API key,
no rate limits, but it needs a reasonably capable PC (8GB+ RAM minimum for
small models, more for bigger ones) and uses disk space per model (a few GB
each).

1. Download and install Ollama: https://ollama.com/download
2. Pull a model (this downloads it once, ~4-5GB for an 8B model):
   ```bash
   ollama pull llama3.1
   ```
3. Make sure it's running (it usually auto-starts as a background service
   after install; if not):
   ```bash
   ollama serve
   ```
4. (Optional) If you pulled a different model than `llama3.1`, update
   `config/api_keys.json`:
   ```json
   "ollama_model": "your-model-name"
   ```

### Testing it directly
```bash
python llm_client.py
```
This prints whether Ollama is reachable and runs a one-line test prompt
through the full fallback chain.

## Notes

- Ollama is only used as a **text** fallback — the fallback chain in
  `or_client.py`/`llm_client.py` skips it for vision/multimodal requests,
  since Ollama's image payload format differs from OpenRouter's.
- If you want fully-offline operation as the *default* (not just a
  fallback when everything else fails), pass `prefer="ollama"` when calling
  `llm_client.ask(...)` from wherever you want that behavior.
- Screen/camera vision (`screen_processor.py`) still requires Gemini Live —
  that's a live audio+video streaming API with no equivalent fallback here.
