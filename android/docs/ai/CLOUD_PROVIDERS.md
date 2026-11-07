# Cloud providers

All five are optional. With no key entered the app has no AI provider, and says so rather than pretending
(`No AI provider is configured. Add an API key under Providers.`). Every request goes over https only:
`JsonHttp` refuses any other address and the OkHttp client is restricted to modern TLS with redirects off.

| Provider | Endpoint | Auth | Request details | Source of the contract |
|---|---|---|---|---|
| Gemini | `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` | header `x-goog-api-key` (never in the URL) | `systemInstruction`, `contents` with roles `user`/`model`, `generationConfig` (`maxOutputTokens`, `temperature`, `responseMimeType`) | Google API docs, checked 2026-11-07 |
| Groq | `https://api.groq.com/openai/v1/chat/completions` | `Authorization: Bearer` | OpenAI chat format, `max_tokens`, `temperature`, JSON mode | Carried over from the desktop project; **not re-checked** against Groq's current docs |
| OpenRouter | `https://openrouter.ai/api/v1/chat/completions` | `Authorization: Bearer`, plus an `X-Title` header | Same format; the model field may be a comma-separated list, tried in order | Carried over from the desktop project; **not re-checked** |
| OpenAI | `https://api.openai.com/v1/chat/completions` | `Authorization: Bearer` | Sends `max_completion_tokens` (the official OpenAPI spec marks `max_tokens` as deprecated and unsupported by its reasoning models) and **no temperature** (some models accept only the default) | OpenAI's published OpenAPI spec |
| Claude | `POST https://api.anthropic.com/v1/messages` | `Authorization: Bearer`; header `anthropic-version: 2026-10-21` | `model`, `max_tokens`, `messages`, `system` as a string. **`temperature`, `top_p` and `top_k` are never sent**: the docs say that version rejects them with HTTP 400 | Anthropic API reference, checked 2026-11-07 |

## Models

The pre-filled model names are suggestions only. Gemini and Groq values come from the desktop project and Claude's
from Anthropic's documentation. **OpenAI has no default**: no current model name was verified, so that provider
stays unconfigured until you type one. Model names change, so check each provider's current list.

## Fallback behaviour (`FallbackLlmClient`)

- Providers are tried in your order (Providers tab, up/down buttons). Unconfigured ones are skipped.
- Timeout, network failure and 5xx responses (including Claude's 529 "overloaded") are retried once more with exponential backoff.
- HTTP 429 puts that provider on cooldown (`Retry-After` seconds if numeric, otherwise 60 s) and moves to the next.
- 401/403 and other 4xx errors are not retried.
- The provider that answered is shown in the chat.
- If every provider fails, the error lists each provider's failure with secrets redacted.

## Not implemented yet

Connection test, model listing, streaming, usage metadata, per-request cancel. The key is stored encrypted
(`SecureKeyStore`) but, as the prompt notes, a client-side key can never be fully secret from the phone's owner.
