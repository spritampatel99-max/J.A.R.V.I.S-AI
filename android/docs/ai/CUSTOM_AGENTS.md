# Custom Agent Creator (Phase 7)

Status: configuration, validation, approval, isolation and prompt building are in `:core` and unit-tested offline. The screens (list, five-step wizard, preview chat) are in `:app` and **have never been compiled or run**.

## What a custom agent is

A *configuration*: a name, a purpose, instructions, a greeting, tone, language, a list of chosen features, a memory policy and a network policy. It is plain data.
It is **not code**. No agent can run Python, shell or Kotlin, and the builder has no field that could hold executable code.

## Life cycle

`Draft → Active → Disabled`

- A new agent is a **Draft**. A draft can be previewed in a chat that has **no tools at all** and saves nothing.
- A draft becomes Active only through `AgentStore.approve(id)`, which the screen calls from the person's tap on **Approve**. The model, an agent's own instructions and any tool result have no way to call it.
- `setEnabled` refuses drafts. Editing an agent keeps its status (a draft stays a draft; an active agent stays active), re-derives its tools from the features and re-validates everything. There is no second approval for an edit: the person's own "Save changes" tap applies it to an already-approved agent. That is acceptable only because derived tools are limited to low-risk ones; the person should know that editing an active agent takes effect immediately.
- Duplicate creates a new Draft with **no memory**. Export writes the configuration only (never memory or chat). Delete also deletes that agent's memory.
- At most 20 agents.

## Features and tools

The catalogue has 60+ features in 10 categories, at least 10 each (at least 7 selectable per category). Features marked *not built* cannot be chosen:
`web_research`, `doc_reading`, `local_knowledge`, `file_output`, `live_info`. The wizard states the reason instead of hiding them.

An agent's tools are **derived from the features picked** (`AgentBlueprint.toolsFor`), never typed in. Every derived tool must exist in the registry and be LOW risk.
At run time `AgentRuntime.registryFor(config, preview)` returns an *empty* registry for previews and for any non-Active agent, and re-derives the allowed tools
from the features again, so an edited or forged `enabledTools` list in the file grants nothing.

An agent never creates its own system permissions: it can use only tools the main app already has, and the person's Android permission state still applies.
Medium and high risk tools (calls, answering and ending calls) are not available to agents.

"Describe what you want" (`assessCustom`) classifies a free-text wish as *existing feature*, *text only* (the agent just talks, no tool) or *unsupported*
(files, web, calls, messages and similar are refused with the reason).

## Isolation and prompt

- Memory policy: **No memory**, **This agent's only**, or **This agent's + general**. Never another agent's. The main assistant sees general memory only.
- `AgentPrompt.FIXED_RULES` are appended **after** the owner's text, so instructions written into an agent cannot override them. Memory and tool output are labelled as data.
- Validation rejects text that looks like a secret (the same patterns the redactor uses), unknown or duplicate features, not-built features, and anything over the length limits
  (name 40, purpose 600, instructions 1,500, greeting 200, 30 features, 10 extra rules of 200).
- If a stored file holds an unreadable value, it falls back to the **most restrictive** one: memory none, network "on this phone only", status disabled.
- "On this phone only" agents are shown as unavailable while there is no on-device engine (today, always).

## Not built / does not work yet

- **Agent model preference** is stored but has no effect until an on-device engine exists; the router still picks.
- **Agent chats are not saved** (a deliberate choice for now; nothing leaves the screen).
- No knowledge sources, document reading, web research or file output.
- No sharing or import of agents (export only).
