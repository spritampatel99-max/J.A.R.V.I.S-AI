# Cloud providers

All five are optional. With no key entered the app has no AI provider, and says so rather than pretending
(`No AI provider is configured. Add an API key in the Cloud tab.`). Built-in commands (flashlight, alarm, YouTube, open a website, battery and storage) do not need a provider: see "Built-in commands" below. Every request goes over https only:
`JsonHttp` refuses any other address and the OkHttp client is restricted to modern TLS with redirects off.

| Provider | Endpoint | Auth | Request details | Source of the contract |
|---|---|---|---|---|
| Gemini | `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` | header `x-goog-api-key` (never in the URL) | `systemInstruction`, `contents` with roles `user`/`model`, `generationConfig` (`maxOutputTokens`, `temperature`, `responseMimeType`) | Google API docs, checked 2026-11-07 |
| Groq | `https://api.groq.com/openai/v1/chat/completions` | `Authorization: Bearer` | OpenAI chat format, `max_tokens`, `temperature`, JSON mode | Carried over from the desktop project; **not re-checked** against Groq's current docs |
| OpenRouter | `https://openrouter.ai/api/v1/chat/completions` | `Authorization: Bearer`, plus an `X-Title` header | Same format; the model field may be a comma-separated list, tried in order | Carried over from the desktop project; **not re-checked** |
| OpenAI | `https://api.openai.com/v1/chat/completions` | `Authorization: Bearer` | Sends `max_completion_tokens` (the official OpenAPI spec marks `max_tokens` as deprecated and unsupported by its reasoning models) and **no temperature** (some models accept only the default) | OpenAI's published OpenAPI spec |
| Claude | `POST https://api.anthropic.com/v1/messages` | `Authorization: Bearer`; header `anthropic-version: 2026-10-21` | `model`, `max_tokens`, `messages`, `system` as a string. **`temperature`, `top_p` and `top_k` are never sent**: the docs say that version rejects them with HTTP 400 | Anthropic API reference, checked 2026-11-07 |

## Models

The pre-filled model names are suggestions only, and they go stale. The first real run on 2026-11-08 showed three of them
failing with HTTP 404 (`gemini-2.5-flash` refused for new Gemini users, the four `:free` OpenRouter models withdrawn, and a
Groq key without access to `llama-3.3-70b-versatile`), so no command could run. Current defaults:

| Provider | Default | Why |
|---|---|---|
| Gemini | `gemini-3.8-flash` | Google's model page (checked 2026-11-08) recommends it for new projects, and the API's own 404 for the old name pointed to it. Not yet confirmed to work with `generateContent` on the owner's key. |
| Groq | `llama-3.3-70b-versatile,openai/gpt-oss-20b,llama-3.1-8b-instant` | A pool tried in order, from Groq's production-model list (checked 2026-11-08). |
| OpenRouter | `openrouter/free` | OpenRouter's router, which picks a currently available free model. |
| Claude | `claude-haiku-5-5` | Anthropic's API reference. |
| OpenAI | none | No current model name was verified; the provider stays unconfigured until you pick one. |

A saved value equal to one of the retired defaults is dropped once at start-up (`SettingsStore`), so an old install moves to the
new default without the user doing anything. A model name the user typed or picked themselves is never touched.

**The reliable source is the list from your own key.** In the Cloud tab, **Load models** asks the provider which models that key can use
(`GET /models`, no tokens spent, and it also shows at once whether the key is accepted), and tapping a model fills the field.
Groq and OpenRouter add the model to their ordered list; the others replace it. **Test** sends one tiny request with the saved key and
model and shows the provider's own error if it fails. Endpoints used for the lists: Gemini `v1beta/models`, Groq and OpenAI
`/v1/models`, OpenRouter `/api/v1/models` (public, no key), Claude `/v1/models`. Non-chat models (embeddings, image, audio) are filtered
out, and free OpenRouter models are listed first.

## Built-in commands (no AI needed)

`NativeCommands` runs plain-language commands on the phone with no provider, no internet and no local model:
`turn on/off the flashlight`, `open YouTube`, `search/play X on YouTube`, `set an alarm for 6:30 am / in 20 minutes`,
`open example.com`, and `battery` / `storage` / `device info`. Several can be chained ("turn on the flashlight and open YouTube").
The matcher only accepts a whole sentence of a known shape, so a question such as "how do I set an alarm?" still goes to the AI.
When a detail matters and is missing (6 AM or 6 PM?) it asks instead of guessing. These commands go through the same executor as
AI-written plans: argument validation, permission checks, the confirmation gate, the audit log and honest result reporting.

## Fallback behaviour (`FallbackLlmClient`)

- Providers are tried in your order (Cloud tab, up/down buttons). Unconfigured ones are skipped.
- Timeout, network failure and 5xx responses (including Claude's 529 "overloaded") are retried once more with exponential backoff.
- HTTP 429 puts that provider on cooldown (`Retry-After` seconds if numeric, otherwise 60 s) and moves to the next.
- 401/403 and other 4xx errors are not retried.
- The provider that answered is shown in the chat.
- If every provider fails, the error lists each provider's failure with secrets redacted.

## Not implemented yet

Streaming, usage metadata. (Connection test and model listing now exist; see "Models".) The key is stored encrypted
(`SecureKeyStore`) but, as the prompt notes, a client-side key can never be fully secret from the phone's owner.
