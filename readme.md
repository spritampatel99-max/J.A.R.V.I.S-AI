<<<<<<< HEAD
# 🤖 J.A.R.V.I.S.-AI

### Personal AI Assistant

> A modular, local-first AI assistant designed to understand,
> reason, plan, remember, and interact with your computer.

---

## ✨ Overview

J.A.R.V.I.S.-AI is a voice-first personal assistant built around a
planner/executor agent architecture: a goal is broken into steps, each
step is dispatched to a specialized tool, and failures are analyzed and
recovered from automatically. It runs against a resilient chain of AI
providers (local-first via Ollama, with Groq, OpenRouter, and Gemini as
fallbacks) so no single API outage takes it offline, and it's built to
eventually control most of the host machine — apps, files, media,
browser shortcuts, WiFi/Bluetooth, and system inventory — behind an
explicit permission and confirmation system.

**Status note:** most tools below are fully wired into the
planner/executor path and independently tested. Reaching them from
simple one-off voice commands (not just multi-step planned tasks)
depends on `main.py`'s voice-dispatch layer, which is still being
connected up — see Project Status.

---

## 🚀 Capabilities

| Feature | Description |
|---|---|
| 🎙️ Voice AI | Real-time conversational voice interaction (Gemini Live) |
| 🧠 AI Intelligence | Local + cloud LLM architecture with automatic fallback |
| 🤖 Agent System | Planner, executor, and a registry of specialized tools |
| 🖥️ Desktop Control | App launching/closing, window focus/switching, keyboard injection |
| 📁 File Management | General file operations plus indexed, fuzzy, category-aware media search |
| 👁️ Multimodal AI | One-shot and live screen reading/vision |
| 🧠 Memory | Persistent long-term assistant memory |
| 📚 Knowledge | Offline Wikipedia via Kiwix/ZIM archives |
| 🌐 Browser Automation | Search, maps, translate, news, email compose |
| 💻 Developer Tools | AI-generated code opened directly in VS Code |
| 📶 Connectivity | WiFi/Bluetooth status and control |
| ⏰ Time | Real alarms (loops until dismissed) and one-shot reminders |
| 📇 Device Awareness | Installed-app catalog with new/removed detection, registry-based inventory with size and install date |
| 🔐 Permissions | Confirmation gate for destructive actions, session-based full-control consent |

---

## 🆕 What's Inside

- 🦙 **Local-First AI** — Ollama for fully offline inference
- ⚡ **Cloud Fallback Chain** — Gemini → Groq → OpenRouter → Ollama, in order, automatic
- 🎙️ **Gemini Live** — Real-time voice interaction and live screen vision
- 🤖 **Agent Architecture** — Planner + Executor with automatic error recovery (retry/skip/replan/abort)
- 📁 **File Tools** — General file management (`file_controller`) plus a separate indexed/cached fuzzy media finder (`media_finder`) for "play my song X from folder Y" style requests
- 🖥️ **Desktop Control** — Dynamic app discovery (Start Menu/Applications/.desktop scan, not a fixed list), window focus/switching, generic keyboard injection, graceful vs. force-close
- 📇 **App Catalog** — Detects and reports apps installed/removed since the last run; complemented by a registry-based inventory with size, install date, startup entries, and resource-usage reports
- 📚 **Offline Knowledge** — Wikipedia via Kiwix/ZIM archives
- 🌐 **Browser Shortcuts** — Maps, translate, news headlines, email compose
- 💻 **VS Code Integration** — Generates code through the AI fallback chain and opens it directly in the editor
- 📶 **WiFi/Bluetooth** — Status checks and on/off control
- ⏰ **Alarms & Reminders** — Alarms loop audibly until dismissed; reminders are one-shot notifications
- 💬 **Messaging** — WhatsApp messaging via a local contact book and reliable deep links (not blind UI automation)
- 🔐 **Permission System** — Destructive actions (delete, close app) require explicit confirmation via a token exchange; broader automation requires session-based full-control consent, asked fresh on every launch

---

## 🧠 AI Architecture

```text
User
 ↓
Voice / Text
 ↓
Planner  (breaks the goal into steps)
 ↓
Tool Dispatch
 ↓
Specialized Tools  (30+ actions)
 ↓
Executor  (runs steps, recovers from failures)
 ↓
Computer / OS
 ↓
Result
```

### AI Providers

| Provider | Purpose |
|---|---|
| 🦙 Ollama | Local, fully offline inference |
| ⚡ Groq | Fast cloud fallback, high free-tier limits |
| 🌐 OpenRouter | Multi-model cloud fallback pool |
| ✨ Google Gemini | Primary provider, multimodal, tool use |
| 🎙️ Gemini Live | Real-time voice and live screen vision |

### Fallback order
```text
Gemini
  ↓ (fails / rate-limited / unconfigured)
Groq
  ↓ (fails)
OpenRouter
  ↓ (fails)
Ollama  (fully offline, if installed locally)
```

---

## ⚙️ Desktop Automation

- Dynamic application discovery and launching (not a fixed app list)
- Window focus/switching, minimize-all, graceful window close
- Generic keyboard/text injection into whatever's focused
- Running-app detection and (confirmation-gated) closing
- File management, plus indexed fuzzy media search and playback
- Browser shortcuts: maps, translate, news, email
- YouTube playback (direct video, not just a search page)
- VS Code integration for AI-generated code
- WiFi and Bluetooth status/control
- One-shot screen reading and live screen vision
- Real alarms and reminders
- WhatsApp messaging via a local contact book

Destructive actions (deleting a file, closing an app) always go through
a confirm/cancel token exchange — never execute silently.

---

## 📚 Knowledge & Memory

- Offline Wikipedia via Kiwix / ZIM archives
- Persistent long-term memory
- Local application catalog with change detection
- Registry-based system inventory (installed size, install date, startup apps)

---

## ⚡ Quick Start

```bash
git clone <your-repo-url>
cd mark_project

py -3.12 -m venv venv
venv\Scripts\activate

pip install -r requirements.txt
python -m playwright install

python main.py
```

⚠️ Requires Python 3.11 or 3.12 — newer versions may lack prebuilt
wheels for some dependencies. See the various `SETUP_*.md` files for
optional features (Ollama/Groq, offline Wikipedia, WhatsApp calling,
full-control consent).

### Running in the background

```bash
start_jarvis.bat              # foreground, visible logs
start_jarvis_background.bat   # no console window, survives closing the terminal
stop_jarvis.bat                # stops the background instance
```

---

## 📋 Requirements

| Requirement | Details |
|---|---|
| OS | Windows-focused; several tools have cross-platform fallbacks |
| Python | 3.11 or 3.12 |
| Microphone | Required for voice features |
| Ollama | Optional — enables fully local/offline inference |
| API Keys | Gemini required; Groq/OpenRouter recommended as fallbacks |
| Internet | Required for cloud providers and most browser-based tools |

---

## 🚧 Project Status

🟡 Active Development

Current focus:
- Connecting all planner/executor tools to the direct voice-command
  path in `main.py` (currently, multi-step planned tasks can reach
  every tool below; simple one-off voice commands are still being
  wired up)
- Broader device control (system power state, hardware-level
  volume/brightness, tray/background persistence) — pending integration
- More reliable automation and error recovery
- Expanded AI provider support

---

## 🎯 Vision

**LISTEN → UNDERSTAND → REASON → PLAN → ACT → VERIFY → RESPOND**

The long-term goal is a practical AI assistant capable of connecting
natural-language intent with real computer actions — safely, with the
user always in control of what it's allowed to do.
=======

>>>>>>> 67c8ea33789aa1eeedffa001e492817756b17244
