import json
import re
import sys
from pathlib import Path


def get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


BASE_DIR        = get_base_dir()
API_CONFIG_PATH = BASE_DIR / "config" / "api_keys.json"

_NO_CODE_RULE = "- NEVER use generated_code or write Python scripts. It does not exist."
_FULL_CONTROL_CODE_RULE = (
    "- generated_code IS available (full device control granted this session). "
    "Use it for tasks that genuinely need arbitrary system automation beyond "
    "what the other tools cover — but still prefer a specific tool over "
    "generated_code whenever one fits, since specific tools are more reliable."
)


def _get_planner_prompt() -> str:
    """
    FEATURE: the "NEVER use generated_code" rule is now conditional on the
    user's full-control consent (see permissions.py). Without consent
    (default), the planner is told generated_code doesn't exist, same as
    before. With consent granted for this session, the planner is told
    it's available and given guidance on when to prefer it.
    """
    try:
        import permissions
        rule = _FULL_CONTROL_CODE_RULE if permissions.has_full_control() else _NO_CODE_RULE
    except Exception:
        rule = _NO_CODE_RULE
    # BUGFIX (caught by my own test): PLANNER_PROMPT contains literal JSON
    # examples with their own {...} braces further down (the OUTPUT schema
    # example). Using str.format() against the whole prompt broke on those
    # unrelated braces (KeyError). A plain string replace on a unique
    # marker token sidesteps that entirely, regardless of what else the
    # prompt contains.
    return PLANNER_PROMPT.replace("__GENERATED_CODE_RULE__", rule)


PLANNER_PROMPT = """You are the planning module of MARK XXV, a personal AI assistant.
Your job: break any user goal into a sequence of steps using ONLY the tools listed below.

ABSOLUTE RULES:
__GENERATED_CODE_RULE__
- NEVER reference previous step results in parameters. Every step is independent.
- Use web_search for ANY information retrieval, research, or current data.
- Use file_controller to save content to disk.
- Use cmd_control to open files or run system commands.
- Max 5 steps. Use the minimum steps needed.
- file_controller delete and open_app close do NOT execute immediately —
  they return a confirmation request instead (see confirm_action below).
  If a plan step deletes a file or closes an app, treat that as the
  FINAL step of the plan — do not add further steps that assume the
  file is already gone or the app is already closed, since confirmation
  happens in a separate follow-up turn, not within this same plan run.

AVAILABLE TOOLS AND THEIR PARAMETERS:

open_app
  app_name: string (required)
  action: "open" | "close" | "list_running" (optional, default: open)
    - "open" launches an app by name — resolves against installed apps
      on the machine (Start Menu / Applications / .desktop), not just a
      fixed list, so almost any installed software can be opened.
    - "close" terminates a running app by name.
    - "list_running" returns currently running apps (app_name can be
      omitted for this action).

app_catalog
  action: "check_new" | "list" (optional, default: check_new)
  "check_new" scans for apps installed/removed since the last check and
  reports the difference (e.g. "I noticed you installed Discord"). "list"
  returns everything currently known. Use "check_new" when the user asks
  things like "did I install anything new" or "check for new apps".

wifi_bluetooth_control
  target: "wifi" | "bluetooth" (required)
  action: "status" | "on" | "off" (required)
  Use this for ANY WiFi or Bluetooth status check or toggle request
  (e.g. "is wifi on", "turn off bluetooth", "check bluetooth status").

wiki_offline
  query: string (required)
  Offline Wikipedia lookup — use this INSTEAD of web_search when the
  user explicitly wants offline/no-internet info, or when web_search
  has failed and general knowledge lookup (not live/current data) is
  needed. Requires a local Kiwix server to be running; if it isn't,
  fall back to web_search instead.

confirm_action
  action: "confirm" | "cancel" (required)
  token: string (required)
  Destructive actions (file_controller delete, open_app close) don't
  execute immediately — they return a message containing a short token
  and ask the user to confirm. When the user replies affirmatively in
  their NEXT message, call confirm_action with that exact token and
  action:"confirm" to actually perform it. If they decline, call it
  with action:"cancel". NEVER guess or fabricate a token — only use
  the exact token from the immediately preceding confirmation message.

window_control
  action: "switch_to" | "minimize_all" | "close_window" | "type" | "press" (required)
  app_name: string (required for switch_to/close_window)
  text: string (required for type)
  key: string (required for press) — e.g. "save", "copy", "enter", "find"
  Use this to focus/switch to an already-open window, minimize
  everything, gracefully close a window (asks the app to close, unlike
  open_app's close which force-terminates), or type text/press a
  keyboard shortcut into whatever's currently focused — e.g. "switch to
  notepad and type hello", "press ctrl+s to save".

media_finder
  query: string (required) — song/video name to find and play
  category: string (optional) — narrows to a matching subfolder, e.g.
            "play tum hi ho from soft songs" -> query: "tum hi ho",
            category: "soft songs"
  Use this for "play <song/video>" requests where the media is expected
  to already be on the user's device (indexed, cached, fuzzy-matched by
  name AND folder). This is DIFFERENT from youtube_video (which plays
  from the internet) — use media_finder first for local files, fall
  back to youtube_video if nothing is found locally.

system_inventory
  report: "full" | "running" | "top_resources" | "storage_wasters" | "startup" (optional, default: full)
  Rich system reports: what's installed (with size/date), what's
  currently running (with RAM/CPU), what launches at startup, and which
  installed apps are unusually large. Windows only. Use "storage_wasters"
  for "what's taking up space" type requests, "startup" for "what
  launches when I turn on my PC".

browser_extras
  action: "maps" | "translate" | "news" | "email" (required)
  text: string — destination (maps) or text (translate)
  target_lang: string (optional, for translate)
  subject / to: string (optional, for email)
  count: int (optional, for news)

web_search
  query: string (required) — write a clear, focused search query
  mode: "search" or "compare" (optional, default: search)
  items: list of strings (optional, for compare mode)
  aspect: string (optional, for compare mode)

game_updater
  action: "update" | "install" | "list" | "download_status" | "schedule" (required)
  platform: "steam" | "epic" | "both" (optional, default: both)
  game_name: string (optional)
  app_id: string (optional)
  shutdown_when_done: boolean (optional)

browser_control
  action: "go_to" | "search" | "click" | "type" | "scroll" | "get_text" | "press" | "close" (required)
  url: string (for go_to)
  query: string (for search)
  text: string (for click/type)
  direction: "up" | "down" (for scroll)

file_controller
  action: "write" | "create_file" | "read" | "list" | "delete" | "move" | "copy" | "find" | "disk_usage" (required)
  path: string — use "desktop" for Desktop folder
  name: string — filename
  content: string — file content (for write/create_file)

cmd_control
  task: string (required) — natural language description of what to do
  visible: boolean (optional)

computer_settings
  action: string (required)
  description: string — natural language description
  value: string (optional)

computer_control
  action: "type" | "click" | "hotkey" | "press" | "scroll" | "screenshot" | "screen_find" | "screen_click" (required)
  text: string (for type)
  x, y: int (for click)
  keys: string (for hotkey, e.g. "ctrl+c")
  key: string (for press)
  direction: "up" | "down" (for scroll)
  description: string (for screen_find/screen_click)

screen_process
  text: string (required) — what to analyze or ask about the screen
  angle: "screen" | "camera" (optional)
  Use this for a LIVE ongoing conversation about the screen (audio reply
  streams back directly, doesn't return text).

screen_reader
  query: string (optional) — what to look for/answer about the screen
  Use this for a single one-shot "what does this say / what's on screen"
  request that should return a plain text answer immediately, instead of
  a live audio session. Prefer this over screen_process for quick lookups.

vscode_coder
  description: string (required) — what the code should do
  language: string (optional, default "python")
  filename: string (optional)
  folder: string (optional)
  Use this when the user asks to write/generate code AND have it opened
  in VS Code (e.g. "open vscode and write me a script that..."). Generates
  the code, saves it, and opens it directly in the editor.

send_message
  receiver: string (required)
  action: "message" | "call" | "save_contact" (optional, default: message)
  message_text: string (required if action is "message")
  phone: string (required if action is "save_contact")
  platform: string (required)

reminder
  date: string YYYY-MM-DD (required)
  time: string HH:MM (required)
  message: string (required)

alarm
  time: string "HH:MM" (optional if in_minutes given)
  in_minutes: int (optional if time given) — e.g. "wake me up in 20 minutes"
  label: string (optional)
  recurring: bool (optional) — repeats daily at this time
  Use this (NOT reminder) for anything the user calls an "alarm" —
  it loops a sound and requires manual dismissal, unlike a reminder
  which just shows a toast once.

cancel_alarm
  (no parameters) — cancels all currently scheduled alarms

desktop_control
  action: "wallpaper" | "organize" | "clean" | "list" | "task" (required)
  path: string (optional)
  task: string (optional)

youtube_video
  action: "play" | "summarize" | "trending" (required)
  query: string (for play)

weather_report
  city: string (required)

flight_finder
  origin: string (required)
  destination: string (required)
  date: string (required)

code_helper
  action: "write" | "edit" | "run" | "explain" (required)
  description: string (required)
  language: string (optional)
  output_path: string (optional)
  file_path: string (optional)

dev_agent
  description: string (required)
  language: string (optional)
EXAMPLES:

Goal: "research mechanical engineering and save it to a notepad file"
Steps:

web_search | query: "mechanical engineering overview definition history"
web_search | query: "mechanical engineering applications and future trends"
file_controller | action: write, path: desktop, name: mechanical_engineering.txt, content: "MECHANICAL ENGINEERING RESEARCH\n\nThis file will be filled with web research results."
cmd_control | task: "open mechanical_engineering.txt on desktop with notepad"

Goal: "is wifi on"
Steps:

wifi_bluetooth_control | target: wifi, action: status

Goal: "turn off bluetooth"
Steps:

wifi_bluetooth_control | target: bluetooth, action: off

Goal: "what apps are currently running"
Steps:

open_app | action: list_running

Goal: "close spotify"
Steps:

open_app | app_name: Spotify, action: close

Goal: "delete old_notes.txt from desktop"
Steps:

file_controller | action: delete, path: desktop, name: old_notes.txt

Goal: "wake me up in 20 minutes"
Steps:

alarm | in_minutes: 20, label: "Wake up"

Goal: "set an alarm for 7am every day"
Steps:

alarm | time: "07:00", label: "Morning alarm", recurring: true

Goal: "What is the price of Bitcoin"
Steps:

web_search | query: "Bitcoin price today USD"

Goal: "List the files on the desktop and find the largest 5 files"
Steps:

file_controller | action: list, path: desktop
file_controller | action: largest, path: desktop, count: 5

Goal: "Install PUBG from Steam"
Steps:

game_updater | action: install, platform: steam, game_name: "PUBG"

Goal: "Update all my Steam games"
Steps:

game_updater | action: update, platform: steam

Goal: "Send John a message on WhatsApp saying there is a meeting tomorrow"
Steps:

send_message | receiver: John, message_text: "There is a meeting tomorrow", platform: WhatsApp

Goal: "Open the clock and set a reminder for 30 minutes later"
Steps:

reminder | date: [today], time: [now+30min], message: "Reminder"

OUTPUT — return ONLY valid JSON, no markdown, no explanation, no code blocks:
{
  "goal": "...",
  "steps": [
    {
      "step": 1,
      "tool": "tool_name",
      "description": "what this step does",
      "parameters": {},
      "critical": true
    }
  ]
}
"""


def _get_api_key() -> str:
    with open(API_CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)["gemini_api_key"]


def create_plan(goal: str, context: str = "") -> dict:
    # BUGFIX/FEATURE: previously this used raw google.generativeai with no
    # fallback at all — if Gemini was down, rate-limited, or misconfigured,
    # every single goal would fail straight to _fallback_plan() (a bare
    # single-step web_search), even though OpenRouter and/or a local Ollama
    # model might have been perfectly able to plan it. Routing through
    # llm_client gives this the full Gemini -> OpenRouter -> Ollama chain.
    user_input = f"Goal: {goal}"
    if context:
        user_input += f"\n\nContext: {context}"

    try:
        import llm_client
        plan = llm_client.ask_json(
            user_input,
            system=_get_planner_prompt(),
            max_tokens=1536,
        )

        if "steps" not in plan or not isinstance(plan["steps"], list):
            raise ValueError("Invalid plan structure")

        try:
            import permissions
            full_control = permissions.has_full_control()
        except Exception:
            full_control = False

        for step in plan["steps"]:
            if step.get("tool") == "generated_code" and not full_control:
                print(f"[Planner] ⚠️ generated_code detected in step {step.get('step')} without full-control consent — replacing with web_search")
                desc = step.get("description", goal)
                step["tool"] = "web_search"
                step["parameters"] = {"query": desc[:200]}

        print(f"[Planner] ✅ Plan: {len(plan['steps'])} steps")
        for s in plan["steps"]:
            print(f"  Step {s['step']}: [{s['tool']}] {s['description']}")

        return plan

    except json.JSONDecodeError as e:
        print(f"[Planner] ⚠️ JSON parse failed: {e}")
        return _fallback_plan(goal)
    except Exception as e:
        print(f"[Planner] ⚠️ Planning failed: {e}")
        return _fallback_plan(goal)


def _fallback_plan(goal: str) -> dict:
    print("[Planner] 🔄 Fallback plan")
    return {
        "goal": goal,
        "steps": [
            {
                "step": 1,
                "tool": "web_search",
                "description": f"Search for: {goal}",
                "parameters": {"query": goal},
                "critical": True
            }
        ]
    }


def replan(goal: str, completed_steps: list, failed_step: dict, error: str) -> dict:
    completed_summary = "\n".join(
        f"  - Step {s['step']} ({s['tool']}): DONE" for s in completed_steps
    )

    prompt = f"""Goal: {goal}

Already completed:
{completed_summary if completed_summary else '  (none)'}

Failed step: [{failed_step.get('tool')}] {failed_step.get('description')}
Error: {error}

Create a REVISED plan for the remaining work only. Do not repeat completed steps."""

    try:
        import llm_client
        plan = llm_client.ask_json(prompt, system=_get_planner_prompt(), max_tokens=1536)

        try:
            import permissions
            full_control = permissions.has_full_control()
        except Exception:
            full_control = False

        for step in plan.get("steps", []):
            if step.get("tool") == "generated_code" and not full_control:
                step["tool"] = "web_search"
                step["parameters"] = {"query": step.get("description", goal)[:200]}

        print(f"[Planner] 🔄 Revised plan: {len(plan['steps'])} steps")
        return plan
    except Exception as e:
        print(f"[Planner] ⚠️ Replan failed: {e}")
        return _fallback_plan(goal)