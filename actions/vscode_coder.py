# actions/vscode_coder.py
#
# "Open VS Code and write me a script that does X" — generates code via
# the same Gemini -> Groq -> OpenRouter -> Ollama fallback chain as
# everything else, saves it to a file, and opens it directly in VS Code
# via its `code` CLI (falls back to a plain editor/open_app if the VS Code
# CLI isn't on PATH).

import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

_EXT_MAP = {
    "python": ".py", "py": ".py",
    "javascript": ".js", "js": ".js",
    "typescript": ".ts", "ts": ".ts",
    "html": ".html",
    "css": ".css",
    "java": ".java",
    "c": ".c",
    "c++": ".cpp", "cpp": ".cpp",
    "c#": ".cs", "csharp": ".cs",
    "go": ".go",
    "rust": ".rs",
    "bash": ".sh", "shell": ".sh",
    "sql": ".sql",
    "json": ".json",
}


def _default_project_dir() -> Path:
    d = Path.home() / "Desktop" / "JarvisCode"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _guess_extension(language: str) -> str:
    return _EXT_MAP.get(language.lower().strip(), ".txt")


def _generate_code(description: str, language: str) -> str:
    import llm_client

    prompt = (
        f"Write complete, working {language} code for this request:\n\n"
        f"{description}\n\n"
        f"Return ONLY the code. No explanation, no markdown fences, "
        f"no commentary before or after."
    )
    code = llm_client.ask(
        prompt,
        system=(
            f"You are an expert {language} developer. Write clean, complete, "
            f"working code with no missing pieces. Return only raw code."
        ),
        max_tokens=3072,
        temperature=0.3,
    )

    code = re.sub(r"```(?:\w+)?", "", code).strip().rstrip("`").strip()
    return code


def _open_in_vscode(path: Path) -> bool:
    """Tries the `code` CLI first (most reliable), falls back to generic open_app."""
    code_bin = shutil.which("code") or shutil.which("code.cmd")
    if code_bin:
        try:
            subprocess.Popen([code_bin, str(path)])
            return True
        except Exception as e:
            print(f"[VSCodeCoder] ⚠️ 'code' CLI launch failed: {e}")

    # Fallback: reuse open_app's VS Code alias, then the user opens the
    # file manually — we still tell them exactly where it is.
    try:
        from actions.open_app import open_app
        open_app(parameters={"app_name": "vscode"})
        return False  # opened the editor, but not the specific file
    except Exception as e:
        print(f"[VSCodeCoder] ⚠️ open_app fallback also failed: {e}")
        return False


def vscode_coder(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        description: string (required) — what the code should do
        language: string (optional, default "python")
        filename: string (optional) — auto-generated if omitted
        folder: string (optional) — defaults to ~/Desktop/JarvisCode
    """
    params      = parameters or {}
    description = params.get("description", "").strip()
    language    = params.get("language", "python").strip() or "python"
    filename    = params.get("filename", "").strip()
    folder_raw  = params.get("folder", "").strip()

    if not description:
        return "Please describe what the code should do, sir."

    folder = Path(folder_raw).expanduser() if folder_raw else _default_project_dir()
    folder.mkdir(parents=True, exist_ok=True)

    if not filename:
        ts       = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"jarvis_code_{ts}{_guess_extension(language)}"
    elif not Path(filename).suffix:
        filename += _guess_extension(language)

    filepath = folder / filename

    try:
        code = _generate_code(description, language)
    except Exception as e:
        print(f"[VSCodeCoder] ❌ Code generation failed: {e}")
        return f"I couldn't generate the code, sir: {e}"

    try:
        filepath.write_text(code, encoding="utf-8")
    except Exception as e:
        return f"Generated the code but couldn't save it, sir: {e}"

    opened_directly = _open_in_vscode(filepath)

    if player:
        player.write_log(f"[vscode_coder] wrote {filepath.name}")

    if opened_directly:
        return f"Written and opened in VS Code, sir: {filepath.name}"
    return (
        f"Written to {filepath}, sir, but I couldn't confirm VS Code opened it "
        f"directly — the 'code' command may not be on PATH. Opened VS Code "
        f"separately; you may need to open the file yourself this time."
    )


if __name__ == "__main__":
    desc = input("Describe the code you want: ")
    lang = input("Language (default python): ") or "python"
    print(vscode_coder({"description": desc, "language": lang}))
