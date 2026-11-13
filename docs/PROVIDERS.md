# Providers and capability tiers

Revision 3, 2026-11-11. Who can do the "understanding" work for the app, what the app is like with each, and what every feature needs.

**How far to trust this page.** Provider facts were read from the providers' own pages on 2026-11-11 by a research helper whose page reader summarises what it fetches; they are marked **high** (provider's page read), **medium** (search result or a secondary source) or **low** (could not be verified). Several providers' documentation was visibly mid-change that week (model names, request shapes), and two of OpenAI's own pages disagreed about prices. **Nothing here has been run.** Before building an adapter or quoting a price to anyone, read the live page. Card 22 says which.

## 1. Three tiers

| Tier | What does the understanding | Leaves the phone | Cost |
| --- | --- | --- | --- |
| **0 · Phone only** | The phone's own speech recogniser, then code: sentence rules, the personal word list, full-text search, answers written from templates | Nothing | Nothing |
| **1 · A model on the phone** | As tier 0, plus a small model run by Android (Gemini Nano through ML Kit's Prompt API) | Nothing | A system download; works only while the app is on screen |
| **2 · A hosted model, your own key** | As tier 0, plus a model at a provider you choose | The words of non-private notes and questions | Cents a month, paid to the provider |

Tier 0 is not a degraded mode that the app apologises for; it is the app, and tiers 1 and 2 are things added to it. Everything is saved and findable by its words at every tier.

### Tier 0, precisely

**Sentence shapes the rules file correctly** (card 03, `rules-2`; one record per note):

| Shape | Example | Becomes |
| --- | --- | --- |
| I put / left / am putting X in/on/under Y | "I'm putting my reading glasses in the nightstand drawer" | placement |
| I lent / loaned X to P; I borrowed X from P; P gave/brought X back | "I loaned my guitar to Frank on Tuesday" | loan, or its return |
| I need / have / want / ought to …; I should / must …; remember to …; don't forget to …; remind me to …; buy X at P | "I have to remember to clean out the back of the car soon" | task, with a due hint from a trailing "soon / tomorrow / this weekend / next week / by Friday" |
| I (just) finished / changed / replaced / cleaned / fixed / paid / called / bought / picked up …; I'm …ing … right now | "I changed the furnace filter today" | event, with a place from a trailing "at Ross" |
| <subject> then two or more name-number pairs | "Pool test free chlorine 5 pH 7.2 alkalinity 70" | one reading with several values |
| heading out / start a trip / I'm home; taking the <car>; P is driving | "I'm taking the Tesla" | trip start, end, car, driver (card 17) |
| a place-within-a-place phrase | "row 5, bucket 10" | kept as the spot; a picture is offered |
| private note … | "Private note the gate code is 4471" | marked Private |

**What degrades without a model, and what the user sees instead of silence:**

| Without a model | The app shows |
| --- | --- |
| A sentence in none of the shapes above is not sorted | The note in the list, marked "Saved. Not sorted: needs a model". Found by its words |
| Several things in one sentence stay one record | The whole sentence under the first thing recognised |
| Readings spoken loosely ("the chlorine was about five and the pH looked like seven two") | Saved as text; "what was my pH" will not find it |
| Prices in a sentence ("the Tide is on sale for $24.99") | Saved as text, found by "Tide"; no price history |
| "I cleaned out the car" closing the car task | The event is saved; the attention list asks "Did this finish …?" when exactly one open task shares its words |
| A question outside the fixed patterns | "Without a model I can only match words. Notes that match:" and a list |
| Trend and combining questions ("how has the chlorine been this month?") | The same list |
| Synonyms ("specs" for "glasses") | Not found unless he said that word; the miss is logged |
| Pulling name, maker and type out of a label's text | The label's text is searchable as it was read; the fields are not filled |
| Watch proposals from a sentence | None; a watch can be added by hand |

**Filing later.** Every note is kept with its raw words, and a note filed by rules is marked as such. Switching a model on later therefore costs nothing in design: the app offers once, "File your 212 earlier notes with the model? About 7 cents" (card 04). At about 1,400 tokens in and 400 out per note on the cheapest Claude model that is roughly $0.00034 a note: a thousand notes for about 35 cents (estimate until card 04 measures it). Private notes are never included. Records he has acted on are pinned and are not replaced. Nothing is sent in bulk without that tap.

### Tier 1, as far as is known

Unchanged from DESIGN.md §7.9; nothing new was learned on the phone. Gemini Nano through ML Kit's GenAI Prompt API: beta; Google's list includes the Galaxy S25 Edge (medium: Google's page, not tried); the model is downloaded and managed by the system, size not published; input limited to roughly 4,000 tokens; a per-app quota; **inference only while the app is the top foreground app**, so no background filing; typed output through an alpha library. Whether it can do the extraction job on multi-part notes and numbers is the question card 12 answers in half a day. Whether it can drive the question loop (choosing among seven tools) is unknown and less likely; plan on tier 1 doing extraction only, with questions answered at tier 0. A small open model shipped in the app remains "not now" (hundreds of megabytes to gigabytes, native code, unknown quality).

## 2. Providers for tier 2

### Subscriptions do not include the API

| Provider | Does a chat subscription include API use? | Confidence and source |
| --- | --- | --- |
| Anthropic (Claude Pro/Max) | No. The API is prepaid separately in the Console | high for the separate terms (PRIVACY.md); the smallest purchase has been $5 (medium) |
| OpenAI (ChatGPT Plus/Pro) | No. "API usage is billed separately from your ChatGPT subscription" | medium: OpenAI's help article, seen as a search summary (the page refused a direct read) |
| Google (Google AI Pro/Ultra) | No. "Google bills and manages Gemini API usage … separately" | high: [Google AI plans page](https://aistudio.google.com/docs/google-ai-plans) |
| xAI (SuperGrok, X Premium) | Believed no; the API is paid from a prepaid balance | **low to medium**: no xAI page found that says so either way |
| Microsoft (Copilot Pro, Microsoft 365 Copilot) | There is no personal API for consumer Copilot. The route is Azure OpenAI, which needs an Azure subscription | medium for "no API" (absence, plus Microsoft's enterprise-only Copilot APIs); high that Azure needs a subscription ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/foundry/openai/api-version-lifecycle)) |

### The comparison

| | **Anthropic** | **OpenAI** | **Google Gemini** | **xAI Grok** | **Microsoft (Azure OpenAI)** |
| --- | --- | --- | --- | --- | --- |
| Small model and price per million tokens (in / out) | `claude-haiku-5-5`, $0.10 / $0.50 (high, from revision 1) | In flux: `gpt-5-nano` $0.05 / $0.40 but marked deprecated; successor `gpt-5.6-luna` $0.20 / $1.20; the pricing page showed a different entry (medium: pages disagree) | `gemini-2.5-flash-lite` $0.10 / $0.40; newer Flash-Lite models $0.25–0.30 / $1.50–2.50; price changes announced for December 2026 (high for the table) | cheapest listed `grok-build-0.1` $1.00 / $2.00, which may be a coding model; mainstream models $1.25 / $2.50 and up (high for the table; suitability unknown) | OpenAI's small models as "deployments"; price not found (low) |
| Enforcing the JSON shape | `output_config.format` with a JSON schema | `response_format` `json_schema`, `strict: true` (or `text.format` on the Responses API). A refusal comes back in a `refusal` field | A `response_format` with a schema in the current docs; older field names not seen; combining schema output with tools is "preview" on newer models only | `response_format` `json_schema`; schema output together with tools needs a Grok 4-family model | As OpenAI; at most 100 properties and 5 levels |
| Schema rules | no bounds, all properties required, no recursion | `additionalProperties: false`, all properties required | length bounds ignored; too-deep schemas rejected | `additionalProperties` false by default | as OpenAI, with more keywords unsupported |
| Tool calling | `tools` + `tool_use` blocks + `tool_result` | `tools` + `tool_calls` + `role: tool` messages (Chat Completions) | its own newer "Interactions" shape; whether the classic shape remains was not confirmed | OpenAI-style | OpenAI-style; strict tools need `parallel_tool_calls: false` |
| Speaks the OpenAI-style API? | no | it is the reference | yes, a compatibility endpoint Google calls beta | yes (`https://api.x.ai/v1`) | yes (v1 API, own address and deployment name) |
| Trains on API input by default? | No (high) | No (high) | **Paid: no. Free plan: yes, and people may read it** (high: Gemini API terms effective 23 March 2026) | No "without your explicit permission" (high: xAI security FAQ) | No, and not shared with OpenAI (high) |
| Kept for | 30 days; flagged content up to 2 years (high) | abuse logs up to 30 days (high) | "a limited period"; length not stated (high that it is not stated) | 30 days (high) | not stated on the page read; flagged samples may be reviewed |
| A no-retention arrangement | through sales, for organisations | needs approval | not found | a team-wide switch that disables some features | by application |
| Free API use | no | a limited free tier is listed | yes, on the Flash-Lite models, with the training caveat above | not found | not found |
| Smallest payment | $5 (medium) | $5 unlocks the first paid tier (medium) | none found; billed through a Google Cloud account | not found | pay-as-you-go on an Azure subscription |
| Limits for a new account | not re-read | first paid tier is generous for this use (thousands of requests a minute on main models) | not read | not found | not read |
| Takes pictures | yes | yes on the small models | yes | "some models" | not checked |
| Rules on sexual content | has a "sexually explicit content" category in its usage policy; a new version takes effect 12 November 2026 (medium) | prohibits sexual content involving minors and reports it; wording on adult images not found (medium) | lists sexually explicit content as not allowed, with educational and similar exceptions (medium) | bars sexualising children and pornographic depictions of real people's likenesses (medium) | Microsoft's AI code of conduct; not read (low) |

"Argon": the owner mentioned "Google Argon". A search turned up reports of a flagship Google model announced around 30 September 2026 under the name "Gemini 4 Argon" (medium to low: found by search, the announcement itself was not read). If that is what was meant, it is a large, expensive model and not the one this app would use for filing; the Gemini column above is the relevant one. QUESTIONS.md Q47 asks.

### What differs in practice, and what the adapter hides

1. **How the shape is enforced.** All five can be made to return JSON matching a schema. Card 04's schema already obeys the strictest common rules (every property required, nullable instead of optional, no bounds, no recursion, shallow), so one schema serves all. The adapter decides where it goes in the request.
2. **Tool calling.** Three request shapes (Anthropic's, the OpenAI style, Google's native). The loop in card 08 speaks a neutral shape; each adapter translates.
3. **Thinking.** Some small models think before answering and bill for it; the adapter sets the lowest effort and enough output room. This is the likeliest source of surprise costs.
4. **Refusals and cut-offs.** A different field at each provider; all map to the same two codes.
5. **Errors and limits.** All use HTTP 429 for "slow down"; the body shapes differ. All map to one code and the pipeline's existing back-off.
6. **Money.** Each adapter carries its own prices; the monthly limit is in cents, so it works across providers.
7. **Terms.** These do not hide behind an interface. Each provider's training and retention position is shown to the user on its explanation card with the date it was checked.

### The provider-neutral interface

Defined in card 04 as `ModelProvider`:

| Operation | In | Out |
| --- | --- | --- |
| `structure` | instruction, JSON schema, the user text (note plus context lists) | text that parses as the schema, token counts; or a failure code |
| `toolTurn` | instruction, the conversation so far in a neutral form, tool declarations | either tool calls or a final text, token counts; or a failure code |
| `describePicture` | **not defined.** Not built, off by default if ever built, per-picture consent, never for private items | |

A provider is *supported* when its adapter passes the conformance set.

### The conformance set

- The **same seed sentences** card 04 defines (about 43 cases: every kind, splits, readings and prices with decimals, vague dates, task closing and look-alikes, rambling speech, an instruction hidden in a note).
- **Recorded replies per provider** under `recorded/<provider>/extract/`, run through the same parsing, checks and projection in unit tests. A provider's quirks therefore fail a test, not a user.
- **A live scoring run per provider and model** (`tools/score_extraction.py --provider …`), reporting tasks found, false tasks, reading numbers exact, right record count, tokens and cost. Written side by side in the experiment log.
- For the question loop, card 08's fifteen questions, scored the same way.

**Quality differs by model.** A cheaper or smaller model will miss more tasks, split worse and get more numbers wrong, and no interface changes that. The numbers will say by how much; until they exist, this page claims nothing about any provider's quality except that Claude Haiku 5.5 is the one the design was written against, and it too is unmeasured.

### Which to support, and when

| Provider | Recommendation | Why |
| --- | --- | --- |
| **Anthropic** | **First; the only one until the app has earned more** | The owner's. The design and the watch engine are written against it |
| **OpenAI-style adapter** (OpenAI; then xAI, Azure, Gemini's compatibility endpoint as presets) | **Second, as card 22, when wanted** | One adapter reaches four providers. OpenAI's strict schema support is the closest match to what is built |
| **Google Gemini, native** | **Later / maybe** | Only if the compatibility endpoint fails the conformance run. The free plan's training and human-review terms need a prominent warning either way |
| **xAI Grok** | **Later / maybe**, as a preset of the OpenAI-style adapter | The cheapest listed model costs ten times the others' small models; subscription-versus-API position unverified |
| **Azure OpenAI** | **Later / maybe**, as a preset | Works, but asks an ordinary person to create an Azure subscription, a resource and a deployment |
| **Microsoft consumer Copilot** | **No** | No API for it |
| Anything "OpenAI-compatible" the user points it at (including a model on their own computer) | Falls out of the second adapter | Listed as "Other compatible: untested" |

## 3. Every feature by tier and permission

**0 / 1 / 2:** ● works fully · ◐ works in a reduced form (see the note) · — not available. Tier 1 entries are **expected**, not measured; card 12 decides them. "Needs" lists Android permissions or other requirements beyond the tier; "nothing" means none.

### Capture

| Feature | 0 | 1 | 2 | Needs | Without the permission |
| --- | --- | --- | --- | --- | --- |
| Voice capture from the lock screen | ● | ● | ● | Microphone; Notifications for the lock-screen button | Typed notes; the tile and in-app button |
| Typed note | ● | ● | ● | nothing | |
| Save before understanding | ● | ● | ● | nothing | |
| Say anything, any phrasing | ◐ shapes in §1 | ◐ expected for single-thing notes | ● | nothing | |
| Several things from one note | — | ◐ uncertain | ● | nothing | |
| "Private note …" and automatic private marking | ● | ● | ● | nothing | |
| Suggesting Private for health or money notes | ● | ● | ● | nothing | |
| Personal word list and corrections | ● | ● | ● | nothing | |
| Vibration, Undo | ● | ● | ● | nothing | |
| Place stored with a note | ● | ● | ● | Location while in use | Notes have no place unless he says one |

### Understanding

| Feature | 0 | 1 | 2 | Needs | Without |
| --- | --- | --- | --- | --- | --- |
| Raw words kept; re-filing later | ● | ● | ● | nothing | |
| Eight kinds | ◐ rule shapes; the rest "not sorted" | ◐ | ● | nothing | |
| Subjects ("pool", "car") | ◐ when the rule names one | ◐ | ● | nothing | |
| Events and "when did I last…" | ◐ verb list in §1 | ◐ | ● | nothing | |
| Dates worked out on the phone | ● | ● | ● | nothing | |
| Vague due hints | ◐ trailing phrases only | ◐ | ● | nothing | |
| Aliases for search | — | ◐ | ● | nothing | |
| Number checks on readings | ● | ● | ● | nothing | |
| Prices as readings | ◐ "X price N" shapes | ◐ | ● | nothing | |
| Spot within a place ("row 5, bucket 10") | ◐ phrase rule | ◐ | ● | nothing | |
| Filing earlier notes when a model is turned on | n/a | ● while the app is open | ● | nothing | |

### Tasks and reminders

| Feature | 0 | 1 | 2 | Needs | Without |
| --- | --- | --- | --- | --- | --- |
| Task lifecycle, "To handle" screen | ● | ● | ● | nothing | |
| Closing a task by speaking | ◐ asks in the attention list | ◐ | ● | nothing | |
| Daily digest, weekly review, budget, back-off | ● | ● | ● | Notifications | The list on opening the app |
| Count-only lock screen | ● | ● | ● | Notifications | |
| Errands by place | ● | ● | ● | nothing | |
| "You're at Costco" on opening the app | ● | ● | ● | Location while in use | No here-list |
| Arrival notification | ● | ● | ● | Location all the time; unrestricted battery | Stage 1 only (above) |
| Loan nudge, recurring-reading line | ● | ● | ● | Notifications | Shown in the review screen only |
| Time-of-day reminders (not planned) | | | | would need exact alarms | |

### Readings and finding

| Feature | 0 | 1 | 2 | Needs | Without |
| --- | --- | --- | --- | --- | --- |
| Readings stored as values | ◐ tidy sentences | ◐ | ● | nothing | |
| "What was my pH last time?" | ● | ● | ● | nothing | |
| Subject page, trend line, outlier flag, CSV | ● | ● | ● | nothing | |
| Fixed-pattern questions answered by code | ● | ● | ● | Microphone to ask aloud | Type the question |
| Free-form questions | — list of matches | — expected | ● | nothing | |
| "Why this answer?" | ● | ● | ● | nothing | |
| Search by date, by place, "has a picture" | ● | ● | ● | Location for place when not spoken | Place from spoken names only |
| Read answers aloud | ● | ● | ● | nothing | |
| Semantic search (later) | — | maybe | — never hosted | nothing | |

### Pictures and codes

| Feature | 0 | 1 | 2 | Needs | Without |
| --- | --- | --- | --- | --- | --- |
| Share to Stash | ● | ● | ● | nothing | |
| Camera inside Stash | ● | ● | ● | Camera | Share and picker only |
| Words read from pictures, searchable | ● | ● | ● | nothing | Found by his words, place, date |
| Label fields filled from picture text | — | ◐ uncertain | ◐ only with "send picture text" on | nothing | |
| Picture offered after a note about a thing | ◐ phrase rule | ◐ | ● | Camera | Picker |
| Offer my latest screenshot | ● | ● | ● | Photos and videos (all) | Share it in |
| "What was that bottle at Total Wine…" | ● | ● | ● | Location, or a spoken place | |
| Sending a picture to a model (not built) | — | — | per picture, by consent | nothing | |
| Scanning barcodes and QR codes | ● | ● | ● | Camera | Codes found in shared pictures only |
| "You noted this one before" | ● | ● | ● | nothing | |
| Product lookup by barcode | ● | ● | ● | internet; off by default | He names it |

### Outings

| Feature | 0 | 1 | 2 | Needs | Without |
| --- | --- | --- | --- | --- | --- |
| Trips built from notes | ● | ● | ● | Location while in use | Grouped by spoken place names; weaker |
| "When did I last go out?" | ● | ● | ● | as above | |
| Car and driver from speech | ◐ phrase rules | ◐ | ● | nothing | |
| Leaving list on "Start a trip" | ● | ● | ● | Notifications | Shown in the app |
| Departure by car Bluetooth | ● | ● | ● | Nearby devices; unrestricted battery | Manual start |
| Departure by home Wi-Fi | ● | ● | ● | Location (to tell networks apart) | Trips from notes and car |
| Departure by geofence / activity (not built) | | | | Location all the time / Physical activity | |

### Watches, safety, upkeep

| Feature | 0 | 1 | 2 | Needs | Without |
| --- | --- | --- | --- | --- | --- |
| Watch through an events listing | ● added by hand | ● | ● proposed from a note | internet, that service's key | No watches |
| Watch through model web search | — | — | ● Anthropic only | internet, key | |
| App gate, second unlock for Private | ● | ● | ● | a screen lock on the phone | Reading is refused |
| Encrypted database, encrypted pictures | ● | ● | ● | nothing | |
| Backup, restore, export, delete everything | ● | ● | ● | a folder he picks | No backup |
| Explanation cards, "What leaves my phone" | ● | ● | ● | nothing | |
| Attention list and repair | ● | ● | ● | nothing | |
| Diagnostic report (product stage) | ● | ● | ● | nothing | |

## 4. Sources

Read 2026-11-11 by a research helper unless noted. OpenAI: [structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs) · [function calling](https://developers.openai.com/api/docs/guides/function-calling) · [your data](https://developers.openai.com/api/docs/guides/your-data) · [rate limits](https://developers.openai.com/api/docs/guides/rate-limits) · [pricing](https://developers.openai.com/api/docs/pricing). Google: [AI plans and the API](https://aistudio.google.com/docs/google-ai-plans) · [terms](https://aistudio.google.com/docs/terms) · [pricing](https://aistudio.google.com/docs/pricing) · [structured output](https://aistudio.google.com/docs/structured-output) · [OpenAI compatibility](https://aistudio.google.com/docs/openai). xAI: [models](https://docs.x.ai/docs/models) · [structured outputs](https://docs.x.ai/docs/guides/structured-outputs) · [security FAQ](https://docs.x.ai/developers/faq/security). Microsoft: [Azure OpenAI v1 API](https://learn.microsoft.com/en-us/azure/foundry/openai/api-version-lifecycle) · [structured outputs](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/structured-outputs) · [data privacy](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/openai/data-privacy). Anthropic: PRIVACY.md and DESIGN.md §15.

**How to verify:** for each provider you intend to offer, open its pricing, structured-output, tool-calling and data-use pages on the day; run card 22's conformance run; put the date in `ProviderFacts`.
