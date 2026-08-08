# actions/media_finder.py
#
# Background-indexed, cached, category-aware fuzzy search for local media
# and documents — then plays/opens the best match directly.
#
# Migrated and adapted from an older assistant's file_agent.py. This is
# genuinely complementary to file_controller.py's find_files(), which
# does an on-demand directory walk every call (no cache, no fuzzy
# scoring, no category awareness, no "just play it" convenience). Use
# file_controller for general file management (create/move/delete/read);
# use this specifically for "play X" / "find my Y song" type requests.

import os
import re
import sys
import json
import time
import difflib
import threading
from pathlib import Path


def _get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


HOME = Path.home()

SEARCH_ROOTS = [
    HOME / "Music", HOME / "Downloads", HOME / "Documents",
    HOME / "Desktop", HOME / "Videos", HOME / "Pictures",
    HOME / "OneDrive" / "Music",
]

AUDIO_EXT = {".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".wma"}
VIDEO_EXT = {".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv"}
DOC_EXT   = {".pdf", ".docx", ".doc", ".txt", ".xlsx", ".pptx"}

INDEX_FILE = _get_base_dir() / "memory" / "media_index.json"

_index: list[dict] = []
_index_lock  = threading.Lock()
_loaded      = threading.Event()
_index_started = False


def _build_index_thread():
    if INDEX_FILE.exists():
        try:
            cached = json.loads(INDEX_FILE.read_text(encoding="utf-8"))
            if cached.get("entries"):
                with _index_lock:
                    _index[:] = cached["entries"]
                _loaded.set()
                threading.Thread(target=_scan_and_save, daemon=True).start()
                return
        except Exception:
            pass
    _scan_and_save()


def _scan_and_save():
    entries = []
    for root in SEARCH_ROOTS:
        if not root.exists():
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [
                d for d in dirnames
                if not d.startswith(".") and d.lower() not in
                ("node_modules", "$recycle.bin", "system volume information")
            ]
            rel_folder = os.path.relpath(dirpath, root)
            category   = "" if rel_folder == "." else rel_folder.replace("\\", "/")
            for fname in filenames:
                ext = os.path.splitext(fname)[1].lower()
                if ext in AUDIO_EXT or ext in VIDEO_EXT or ext in DOC_EXT:
                    entries.append({
                        "path": os.path.join(dirpath, fname),
                        "name": os.path.splitext(fname)[0],
                        "folder": root.name,
                        "category": category,
                        "ext": ext,
                    })
    with _index_lock:
        _index[:] = entries
    _loaded.set()
    try:
        INDEX_FILE.parent.mkdir(parents=True, exist_ok=True)
        INDEX_FILE.write_text(json.dumps({"entries": entries, "updated": time.time()}), encoding="utf-8")
    except Exception as e:
        print(f"[MediaFinder] ⚠️ Index save failed: {e}")


def ensure_index_started():
    global _index_started
    if not _index_started:
        _index_started = True
        threading.Thread(target=_build_index_thread, daemon=True, name="MediaFinder-Index").start()


def reindex() -> str:
    threading.Thread(target=_scan_and_save, daemon=True).start()
    return "Reindexing your media library in the background, sir."


def _score(query_words: set, entry: dict) -> float:
    name_l = entry["name"].lower()
    cat_l  = entry["category"].lower()
    score  = 0.0
    name_words = set(re.findall(r"[a-z0-9]+", name_l))
    for w in query_words:
        if w in name_words:
            score += 3
        elif w in name_l:
            score += 1
    ratio = difflib.SequenceMatcher(None, " ".join(query_words), name_l).ratio()
    score += ratio * 5
    for w in query_words:
        if len(w) > 3 and w in cat_l:
            score += 4
    return score


def find(query: str, category_hint: str = "", ext_filter: set | None = None) -> list[dict]:
    ensure_index_started()
    _loaded.wait(timeout=10)
    with _index_lock:
        candidates = list(_index)

    if ext_filter:
        candidates = [c for c in candidates if c["ext"] in ext_filter]

    query_words = set(re.findall(r"[a-z0-9]+", query.lower()))
    cat_words   = set(re.findall(r"[a-z0-9]+", category_hint.lower())) if category_hint else set()

    if cat_words:
        filtered = [c for c in candidates if any(w in c["category"].lower() for w in cat_words if len(w) > 2)]
        if filtered:
            candidates = filtered

    scored = [(_score(query_words, c), c) for c in candidates]
    scored.sort(key=lambda x: x[0], reverse=True)
    return [c for score, c in scored[:5] if score > 0.5]


def play_media(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        query: string (required) — what to find and play
        category: string (optional) — e.g. "soft songs", narrows the search
                  to files inside a matching subfolder
    """
    params   = parameters or {}
    query    = params.get("query", "").strip()
    category = params.get("category", "").strip()

    if not query:
        return "What should I play, sir?"

    results = find(query, category_hint=category, ext_filter=AUDIO_EXT | VIDEO_EXT)
    if not results:
        cat_note = f" in {category}" if category else ""
        return f"I couldn't find '{query}'{cat_note} on your device, sir. Should I search YouTube instead?"

    best = results[0]
    try:
        if sys.platform == "win32":
            os.startfile(best["path"])
        elif sys.platform == "darwin":
            import subprocess
            subprocess.run(["open", best["path"]])
        else:
            import subprocess
            subprocess.run(["xdg-open", best["path"]])

        loc = f" from {best['category']}" if best["category"] else ""
        if player:
            player.write_log(f"[media_finder] playing {best['name']}")
        return f"Playing {best['name']}{loc}, sir."
    except Exception as e:
        return f"Found {best['name']} but couldn't play it, sir: {e}"


def find_and_open_file(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """parameters: query (required), category (optional). For documents, not media."""
    params   = parameters or {}
    query    = params.get("query", "").strip()
    category = params.get("category", "").strip()

    if not query:
        return "What file should I find, sir?"

    results = find(query, category_hint=category)
    if not results:
        return f"Could not find '{query}' on your device, sir."

    best = results[0]
    try:
        if sys.platform == "win32":
            os.startfile(best["path"])
        elif sys.platform == "darwin":
            import subprocess
            subprocess.run(["open", best["path"]])
        else:
            import subprocess
            subprocess.run(["xdg-open", best["path"]])
        return f"Opening {best['name']}, sir."
    except Exception as e:
        return f"Found {best['name']} but couldn't open it, sir: {e}"
