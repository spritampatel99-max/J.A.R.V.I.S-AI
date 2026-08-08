# Setting up offline Wikipedia (Kiwix)

Jarvis can answer general-knowledge questions with zero internet connection
using a locally stored copy of Wikipedia, served by a tiny program called
Kiwix. This is a one-time setup; once done it works forever offline.

## 1. Install Kiwix Serve

Download from: https://www.kiwix.org/en/downloads/kiwix-serve/
(Windows/Mac/Linux builds available — grab the one for your OS.)

## 2. Download a Wikipedia ZIM file (the offline data)

Browse and download from: https://library.kiwix.org/  (search "wikipedia")

Pick based on how much disk space you're willing to spend:

| Option | Size | Contents |
|---|---|---|
| `wikipedia_en_top` | ~100MB | Just the ~50,000 most important articles |
| `wikipedia_en_all_nopic` | ~50GB | Full text of every article, no images |
| `wikipedia_en_all_maxi` | ~100GB | Everything, including images |

For a personal assistant, `wikipedia_en_all_nopic` is the practical
middle ground — full article coverage without the huge image storage cost.
If you're short on disk space, `wikipedia_en_top` still covers most
general-knowledge questions.

## 3. Run Kiwix Serve

```bash
kiwix-serve --port=8095 wikipedia_en_all_nopic.zim
```

Leave this running in the background. To make it start automatically:
- **Windows**: create a scheduled task that runs this command at login, or
  add a shortcut to it in your Startup folder.
- **macOS/Linux**: add it to your login items / a systemd user service.

## 4. That's it

Jarvis's `wiki_offline` action (`actions/wiki_offline.py`) automatically
detects whether Kiwix is running on `http://localhost:8095` and which
book/ZIM is loaded — no extra config needed. If Kiwix isn't running, it
returns a clear error instead of hanging, so the planner can fall back to
`web_search` instead.

### Testing it directly
```bash
python actions/wiki_offline.py
```

## Notes

- This gives Jarvis static, general-knowledge answers — not live/current
  data (prices, news, today's weather, etc.). Use `web_search` for that.
- The summarization step still uses whatever LLM is available (see
  `SETUP_OLLAMA_GROQ.md`) — pair this with Ollama for a **fully offline**
  question-answering pipeline with no internet dependency at all.
