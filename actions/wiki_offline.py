# actions/wiki_offline.py
#
# Offline Wikipedia lookup via Kiwix.
#
# HOW THIS WORKS (one-time setup required — see readme_offline_wiki.md):
#   1. Download Kiwix Serve (a tiny local web server for offline content):
#        https://www.kiwix.org/en/downloads/kiwix-serve/
#   2. Download a Wikipedia ZIM file (a compressed offline Wikipedia dump):
#        https://library.kiwix.org/  (search "wikipedia")
#      Sizes range from ~100MB (a topic subset, e.g. "wikipedia_en_top")
#      to ~100GB ("wikipedia_en_all_maxi" — the full thing with images).
#      A good practical middle ground for a personal assistant is
#      "wikipedia_en_all_nopic" (~50GB, full text, no images).
#   3. Run kiwix-serve pointed at the file, e.g.:
#        kiwix-serve --port=8095 wikipedia_en_all_nopic.zim
#      Leave this running (or set it up as a Windows service / scheduled
#      task at login).
#   4. That's it — this module talks to it over localhost. No internet
#      needed once steps 1-3 are done.
#
# If kiwix-serve isn't running, functions here raise/return a clear error
# so the caller (web_search.py or the planner) can fall back to an online
# search instead of silently failing.

import re
import requests
from urllib.parse import quote_plus

KIWIX_URL          = "http://localhost:8095"
KIWIX_TIMEOUT      = 8
DEFAULT_BOOK_NAME  = None  # auto-detected from /catalog if not set


def kiwix_available() -> bool:
    """Quick check whether a local kiwix-serve instance is reachable."""
    try:
        r = requests.get(f"{KIWIX_URL}/", timeout=3)
        return r.status_code == 200
    except Exception:
        return False


def _get_book_name() -> str | None:
    """
    Kiwix serves content under /viewer#<bookname>/... — the book name is
    derived from the ZIM filename. We ask kiwix-serve's own catalog API
    for whatever's actually loaded instead of hardcoding a name, so this
    works regardless of which Wikipedia ZIM file the user downloaded.
    """
    try:
        r = requests.get(f"{KIWIX_URL}/catalog/v2/entries", timeout=KIWIX_TIMEOUT)
        r.raise_for_status()
        match = re.search(r"<name>([^<]+)</name>", r.text)
        return match.group(1) if match else None
    except Exception:
        return None


def search_offline_wikipedia(query: str, max_results: int = 5) -> list[dict]:
    """
    Searches the local Kiwix-served Wikipedia ZIM and returns a list of
    {title, snippet, url} results, using kiwix-serve's built-in search API.
    Raises RuntimeError with a clear message if kiwix-serve isn't running.
    """
    if not kiwix_available():
        raise RuntimeError(
            "Offline Wikipedia (Kiwix) is not running at "
            f"{KIWIX_URL}. Start it with: kiwix-serve --port=8095 <your>.zim"
        )

    book = _get_book_name()
    if not book:
        raise RuntimeError(
            "Kiwix is running but no book/ZIM file is loaded. "
            "Restart kiwix-serve with a Wikipedia .zim file argument."
        )

    search_url = f"{KIWIX_URL}/search?books.name={quote_plus(book)}&pattern={quote_plus(query)}&pageLength={max_results}"
    r = requests.get(search_url, timeout=KIWIX_TIMEOUT)
    r.raise_for_status()

    # Kiwix's search results page is HTML; pull out title + link pairs.
    results = []
    for m in re.finditer(r'<a href="([^"]+)"[^>]*>\s*([^<]+)\s*</a>', r.text):
        href, title = m.group(1), m.group(2).strip()
        if not title or href.startswith("#"):
            continue
        results.append({
            "title": title,
            "url":   f"{KIWIX_URL}{href}" if href.startswith("/") else href,
        })
        if len(results) >= max_results:
            break

    return results


def get_offline_article_text(title_or_url: str, max_chars: int = 4000) -> str:
    """
    Fetches and strips HTML tags from a Kiwix Wikipedia article, returning
    plain text (roughly) for an LLM to summarize/answer from.
    """
    if title_or_url.startswith("http"):
        url = title_or_url
    else:
        book = _get_book_name()
        if not book:
            raise RuntimeError("No offline Wikipedia book loaded in Kiwix.")
        url = f"{KIWIX_URL}/viewer#{book}/A/{quote_plus(title_or_url)}"

    r = requests.get(url, timeout=KIWIX_TIMEOUT)
    r.raise_for_status()

    text = re.sub(r"<script.*?</script>", " ", r.text, flags=re.S)
    text = re.sub(r"<style.*?</style>", " ", text, flags=re.S)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text).strip()

    return text[:max_chars]


def wiki_offline(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    Action entry point matching this project's (parameters, response,
    player, session_memory) -> str convention.

    parameters:
        query: str (required) — what to look up
    """
    query = (parameters or {}).get("query", "").strip()
    if not query:
        return "Please specify what to look up, sir."

    try:
        results = search_offline_wikipedia(query, max_results=3)
        if not results:
            return f"No offline Wikipedia results found for '{query}', sir."

        top = results[0]
        article_text = get_offline_article_text(top["url"], max_chars=3000)

        # Summarize with whatever LLM is available (works fully offline too
        # if Ollama is the only reachable provider).
        try:
            import llm_client
            summary = llm_client.ask(
                f"Summarize this Wikipedia article about '{top['title']}' "
                f"in 3-5 concise sentences:\n\n{article_text}",
                system="You are JARVIS. Summarize clearly and concisely. Address the user as 'sir'.",
                prefer="ollama" if llm_client.ollama_available() else "gemini",
            )
        except Exception:
            summary = article_text[:600] + "..."

        if player:
            player.write_log(f"[wiki_offline] {query} -> {top['title']}")

        return f"From offline Wikipedia ({top['title']}): {summary}"

    except Exception as e:
        print(f"[WikiOffline] ❌ {e}")
        return f"Offline Wikipedia lookup failed, sir: {e}"


if __name__ == "__main__":
    print(f"Kiwix available: {kiwix_available()}")
    if kiwix_available():
        print(wiki_offline({"query": "artificial intelligence"}))
