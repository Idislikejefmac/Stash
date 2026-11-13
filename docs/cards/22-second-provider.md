# 22 ◆ · A second model provider, and the conformance run (optional)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Prove that the model behind the app can be swapped: add one adapter that serves providers speaking the OpenAI-style API (OpenAI itself, and with different addresses xAI's Grok, Google's Gemini through its compatibility endpoint, and Azure OpenAI), and score it on the same sentences as the Anthropic adapter so the difference in filing quality is a number.

## Depends on

04 (the `ModelProvider` seam, the seed set, the scorer), 08 if the tool loop is to be covered. Build only when the owner wants a second provider or is preparing a product; nothing else needs it.

## Context

`docs/PROVIDERS.md` has the comparison and its sources. The facts this card leans on, all read on 2026-11-11 by a research helper and all to be **confirmed on the provider's live pages before coding**, because model names and request shapes at every provider were in motion that week:

- OpenAI enforces a JSON schema with `response_format: { type: "json_schema", json_schema: { name, strict: true, schema } }` on Chat Completions (and `text.format` on its newer Responses API). Strict schemas need `additionalProperties: false` and every property required, the same discipline card 04's schema already follows. A refusal arrives in a `refusal` field instead of content.
- Tools on Chat Completions: `tools` with `type: "function"`, replies carry `tool_calls`, results go back as `role: "tool"` messages. Strict tool schemas exist.
- xAI's API is at `https://api.x.ai/v1` and is used with OpenAI's client. Google offers an OpenAI-compatible endpoint at `https://generativelanguage.googleapis.com/v1beta/openai/` which it calls beta. Azure's v1 API takes the standard client with the user's own resource address and a deployment name in place of the model name, and requires `parallel_tool_calls: false` with strict tools.
- Cheapest suitable model ids and prices were inconsistent between OpenAI's own pages on the day. **Do not hard-code a model id**: it is a setting with a suggested default.

## Scope

1. `model/hosted/openai/OpenAiCompatProvider`: implements `ModelProvider` for Chat Completions. Settings it needs: base URL, API key, model (or deployment) name, and which header carries the key (`Authorization: Bearer` or Azure's `api-key`). Presets fill these for "OpenAI", "xAI Grok", "Google Gemini (compatibility)", "Azure OpenAI" and "Other compatible".
2. `structure`: the neutral request's instruction, schema and user text mapped to `messages` and `response_format`. `toolTurn`: neutral tool declarations to `tools`, `tool_calls` back to neutral calls, results as `tool` messages. `parallel_tool_calls: false` always.
3. Map errors to the shared codes: 401/403 → `AUTH`; 429 → `RATE_LIMIT`; 5xx → `OVERLOADED`; a `refusal` → `REFUSED`; `finish_reason: "length"` → `TRUNCATED`; `content_filter` → `REFUSED`; 400 → `BAD_REQUEST`.
4. `ProviderFacts` entries (card 23) for each preset: training, retention, price, terms link, checked-on date, taken from `docs/PROVIDERS.md`. For Gemini the card must carry the free-plan caveat.
5. Settings → model: choose a provider; one key per provider in `SecretStore`; switching provider does not re-send old notes (the "File earlier notes" offer in card 04 is the only bulk path, and it names the new provider).
6. The extractor name carries the provider and model (`openai:<model>/p1`), so `extraction` rows and the scorer can tell them apart, and "re-read notes filed by another version" (card 04) can target one.
7. **Conformance run.** Record the seed cases for this adapter into `recorded/openai/extract/` and run the **same** assertions as `recorded/anthropic/extract/`. `tools/score_extraction.py --provider openai --model <id>` scores live. Write both providers' scores side by side in `docs/EXPERIMENT_LOG.md`.
8. The network allow-list already covers `model/hosted/`; add nothing.

## Out of scope

Native Gemini and native xAI adapters (only if the compatible route fails the conformance run). Microsoft's consumer Copilot (it has no API for this). Picture description. Web search through another provider (card 19 stays on Anthropic's tool). Choosing a provider automatically, or splitting work between providers.

## Acceptance checks

- **Unit:** request builders for `structure` and `toolTurn` equal stored expected files for each preset (header, URL, schema placement, `parallel_tool_calls`).
- **Unit:** recorded replies: a schema-conforming reply, a refusal, a truncated reply, a 401 and a 429 map to the right results.
- **Unit:** the whole of card 04's and card 08's test suites pass with a fake `ModelProvider`, unchanged: nothing above the seam knows which provider it is.
- **Developer PC, with a key:** the conformance run. Report, per provider and model: tasks found, false tasks, reading numbers exact, right record count, median tokens, cost per 100 notes. **There is no pass mark between providers**; the result is the table. A provider that misses card 04's targets is listed in the app with the line "Files less accurately in our tests".
- **Real phone, S25 Edge:** switch provider; make three notes; `peek.py dump` shows the new extractor name; the "What leaves my phone" rows name the new company.

## Notes and risks

- Quality differs by model, and a cheaper model is not the same model with a lower price. Say so in the provider picker rather than implying the choice is only about trust and cost.
- "Compatible" endpoints lag their native APIs. If structured output or tools misbehave through Gemini's compatibility endpoint, record it and leave Gemini as "later" rather than writing a third adapter unasked.
- Each provider is another company's terms to keep current. Two well-kept providers are better than five stale ones.
