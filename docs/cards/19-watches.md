# 19 · Watches: a recurring, read-only check on the web (build last)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

"The first Monday of every month, check whether Saves the Day are playing in or near Phoenix, and tell me when they are." The app runs that check from the phone, on a schedule, says nothing until there is a real match, and shows in one place every watch, exactly what it sends, to whom, how often, and what it has cost.

## Depends on

04 (the HTTP seam, the key, the spend guard), 06 (notifier and budget), 09 (attention list), 13 (capabilities, event log). Build after everything else in the README order.

## Context

**This is a different privacy class from the rest of the app.** Everything else sends, at most, note text to one model provider. A watch sends a search for something he cares about, with a place, on a schedule, while the phone sits idle. Whoever receives it learns an interest and a rough location, tied to an account and an IP address. `docs/PRIVACY.md` has a section for it. Three rules follow:

1. **Nothing is ever watched because the model thought it should be.** A note can produce a *proposal*. Only the owner's tap, behind the app gate, on a screen showing the exact outgoing text, starts a watch.
2. **Read-only.** A watch can fetch and compare. It cannot buy, book, sign in, post or message.
3. **It runs on the phone.** Anything on a server of ours would hold every user's interests, places and schedules in one spot, which is the thing this project exists to avoid.

Where it can run and what each choice shows to whom (prices and terms read on 2026-11-11 by a research helper; **re-read the live pages before building**):

| Engine | What is sent | Who sees it | Cost | Verdict |
| --- | --- | --- | --- | --- |
| **An events listing API** (Ticketmaster Discovery: search events by keyword and city or radius) | band name, city | Ticketmaster, under the owner's own free key | none stated; 5,000 calls a day | **First engine, for concerts.** Smallest possible query; results are structured, so code decides the match and no model is involved. Covers only what that company lists. Its terms forbid earning money from the API and long storage of results: fine for personal use, **a problem for a product** (medium confidence; the terms page was summarised, not read in full). |
| **The hosted model with its web-search tool** (Anthropic `web_search`) | the watch's sentence; the model then chooses search queries | Anthropic and its search supplier | $10 per 1,000 searches plus tokens: a few cents a run | **Second engine, for anything else.** Flexible. Less exact about what is sent, so cap searches per run at 2 and show the queries it actually ran. |
| A general search API (Brave, about $5 per 1,000) plus the model to judge the results | the query, then the snippets | two companies | similar | Not better than the row above. |
| Bandsintown, Songkick | — | — | — | Not open to this kind of app (medium to low confidence). |
| A server of ours | everything | us | hosting | **No.** |

## Scope

### A. Tables (migration and test)

1. `watch`: `id`, `capture_id`?, `title`, `engine` (`EVENTS`, `WEB_MODEL`), `what` ("Saves the Day"), `where_text` ("Phoenix, AZ"), `radius_km`?, `outgoing_text` (exactly what will be sent, built once and shown), `cadence` (`WEEKLY`, `MONTHLY`), `cadence_detail` (weekday; "first Monday"), `state` (`PROPOSED`, `ACTIVE`, `PAUSED`, `ENDED`), `pause_reason`?, `next_run_on`, `last_run_at`?, `last_ok_at`?, `fail_count`, `expires_on`, `created_at`, `updated_at`.
2. `watch_run`: `id`, `watch_id`, `at`, `status` (`NO_MATCH`, `MATCH`, `ERROR`, `SKIPPED`), `sent_text` (what actually went out, including any queries the model ran), `match_json`?, `error_code`?, `tokens_in`?, `tokens_out`?, `searches`?, `est_cost`.
3. `watch_seen`: `watch_id`, `key` (the event's id or a hash of its link and date), `first_seen_at`, `verdict` (`ALERTED`, `NOT_A_MATCH`). A match is alerted once.

### B. Proposals

4. Extraction schema gains an optional per-record `watch`: `{ "what": string, "where": string | null, "how_often_said": string | null }`, filled when he asks to be told when something becomes true in the world ("alert me if they're in town"). One instruction line. Rules extractor: none.
5. Projection creates a `watch` in state `PROPOSED` and sets attention `WATCH_PROPOSED` on the record. A sensitive capture produces **no** proposal: private means never sent anywhere, and a watch is a sending. (He can un-mark the note, or add the watch by hand on the Watches screen.)
6. The attention card and the Watches screen show the **confirmation sheet**: title; "This will send: `Saves the Day` and `Phoenix, AZ` to Ticketmaster"; how often; when it stops; **Start watching** / **Not now**. He can edit what and where; `outgoing_text` is rebuilt and shown before he confirms.

### C. Engines (`watch/` for logic; network code only in `watch/net/` and `model/hosted/`)

7. `interface WatchEngine { suspend fun run(watch): WatchResult }` with `Matches(list of {title, date, venue, link, key})`, `Nothing`, `Failed(code, retryable)`.
8. **`EventsEngine`** (`watch/net/`): one GET to the events API with keyword, city or centre point and radius. The centre point comes from a small bundled table of city names, never from the phone's position. Code keeps events whose performer name equals `what` after normalising (case, punctuation, a leading "the"), whose date is today or later (decided on the phone), and whose key is not in `watch_seen`. The API key is the owner's, stored in `SecretStore`.
9. **`WebModelEngine`** (`model/hosted/`): one Messages request with the web-search tool, `max_uses: 2`, `allowed_callers: ["direct"]`, structured output `{ items: [ { title, date_iso, venue, link, quote } ] }`. The request contains `outgoing_text` and a fixed instruction, and nothing else: no notes, no word list, no date, no position. The instruction says to list dated events found, not to judge "upcoming"; the phone filters by date. Code requires a link and a date for every item and records the queries the model ran in `sent_text`. Web pages are untrusted: the model has no other tool, and its output is only ever shown to the owner as a possible match with its link.
10. Add `watch/net/` to the allowed network packages in card 13's enforcement test, and `WATCH_EVENTS` and `WATCH_WEB` to card 13's outbound register. `WebModelEngine` is written for Anthropic's web-search tool; whether other providers offer an equivalent is noted in `docs/PROVIDERS.md` and is not this card's concern.

### D. Scheduling

11. `WatchWorker`: one WorkManager periodic job (daily, network required). Each run: for watches with `next_run_on <= today` and `ACTIVE`, run the engine, write a `watch_run`, set the next date from the cadence. A month-scale cadence makes Android's delays irrelevant; do not use exact alarms.
12. **Caps, in code:** at most 10 active watches; cadence no more often than weekly; at most 20 runs in a calendar month across all watches; every run's estimated cost counts toward card 04's monthly limit, and a run that would pass it is `SKIPPED`. A watch expires 12 months after it was started or last renewed; the weekly review asks once, a month before, "Still want to watch for Saves the Day?".
13. **Dead sources:** 3 failures running → `PAUSED` with `pause_reason`, one line in the weekly review. No retries in between beyond the next scheduled run.

### E. Telling him

14. Only a new match notifies. Channel "Watches"; public text "A watch found something."; the private text has the title, date and venue. It is an event notification under card 06's budget. Tapping opens the match behind the gate, with the link shown in full and opened only when he taps it.
15. On the match: **That's it** (keeps the watch running for further dates; a setting on the watch can end it after the first match) / **Not a match** (records the key as `NOT_A_MATCH`; it is not alerted again).
16. No match is silent. The Watches screen shows "Last checked 3 Nov, nothing found" so silence can be told from breakage.

### F. The Watches screen (behind the gate)

17. Every watch, any state: what it sends and to whom, cadence, next and last run, runs and estimated cost so far, **Pause**, **Stop**, **Run now** (counts toward the caps), and its run history with `sent_text` for each run.
18. A master capability `WATCHES` (needs internet and a key for the chosen engine; off by default). Off → no proposals are shown as startable, the worker is cancelled, and everything else in the app is unchanged.

## Out of scope

Anything that acts (booking, buying, messaging). Signing in to sites. Fetching pages he must be logged in to see. Price tracking and other watch types (the engine interface allows them; build none until this one has run for three months). Running on a server. Using the phone's position to decide "near me".

## Acceptance checks

- **Unit:** proposal → confirmation → active; nothing is sent while `PROPOSED` (an engine fake that fails the test if called).
- **Unit:** `EventsEngine` against recorded API bodies: exact-name match; a tribute act ("Saves the Day tribute night") is not a match; a past date is dropped; a seen key is not alerted twice.
- **Unit:** `WebModelEngine` request builder equals a stored expected file and contains no note text, date or coordinate; recorded replies parse; an item without a link or date is dropped.
- **Unit:** cadence arithmetic ("first Monday" across month ends); the caps; the spend limit; expiry; pause after three failures.
- **Unit:** the public notification contains no part of `what` or `where_text`.
- **Developer PC, with keys:** run both engines once for a band known to be touring; record what came back, tokens, searches and cost in `docs/EXPERIMENT_LOG.md`.
- **Real phone, S25 Edge:** say the owner's sentence; confirm the proposal; **Run now**; then leave it for two scheduled runs (use a weekly cadence for the test). Log when each run actually happened against when it was due.

## Notes and risks

- A missed concert (the band plays a venue the listing does not cover) is silent and is the worse failure. The Watches screen says which source is being checked, so he knows what "nothing found" means.
- If this is ever part of a product, the events API's terms must be read by someone who can judge them, and each user would need their own key or the product a commercial agreement.
- Do not let this card widen the question loop (card 08). Asking about his notes still never touches the web.
