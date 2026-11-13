# Features considered

Revisions 2 and 3, 2026-11-11. Revision 1's list is the base; the first version (three households) is at [archive/FEATURES-v1-2026-11-10.md](archive/FEATURES-v1-2026-11-10.md).

**Rank.** **Must** = the owner cannot rely on it daily without this. **Should** = planned, has a card, can slip. **Later** = worth doing once use shows the need. **Won't** = decided against, with the reason.

**Proto.** ✅ in the prototype today · ◐ partly · — not built.

**Card.** Numbers are the cards in `docs/cards/` (numbers are names, not the order; the order is in README). "arch. NN" is a retired first-design card.

**Tier** (who does the understanding; [PROVIDERS.md](PROVIDERS.md)). **0** works fully on the phone with no model · **0◐** works at tier 0 in a reduced form, fully with a model · **2** needs a hosted model · **1?** might work with a model on the phone (card 12 decides).

**Needs.** The Android permission or other requirement. "—" means nothing beyond the tier. What happens without each permission is in PROVIDERS.md §3 and on each capability's explanation card.

"New" marks features the owner did not ask for. *(r2)*, *(r3)* mark what revisions 2 and 3 added or changed.

## Capture

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| One-action voice capture from the locked phone, with tones | Must | ✅ (locked behaviour not yet recorded) | — | 0 | Microphone; Notifications for the lock-screen button | The whole idea. Needs the owner's test-script result in the log. |
| Hands-free finish | Must | ✅ | — | 0 | — | Long list-like notes may be cut off; RISKS R12. |
| The save tone means the words are on disk | Must | — (model is called before saving) | 13 | 0 | — | A slow network must never delay or lose a note. |
| Say anything: no required phrasing | Must | — | 03, 04 | 0◐ | — | The rules need phrasing; a model does not. Tier 0 marks what it could not sort. |
| Several things in one note become separate records | Must | — | 03, 04 | 2 (1?) | — | "Buy milk, and I put the ladder in the shed" is two things. |
| Typing a note | Must | — | 13 | 0 | — | Sometimes he cannot speak. |
| "Private note …" by voice | Must | — | 03, 13 | 0 | — | The only practical way to mark privacy at the moment of speaking. |
| Automatic Private for passwords, PINs, codes, long numbers | Must | — | 03 | 0 | — | He will tell it the gate code. That must never be sent. On by default, removable. |
| Suggest Private for health and money notes (r3) | Should | — | 03, 09 | 0 | — | A suggestion in the attention list, never forced. |
| Personal word list and learned corrections | Must | ✅ | — | 0 | — | Names are the weak spot of speech. |
| Place stored with each note | Should | ✅ (last known fix) | 13 | 0 | Location while in use | Feeds trips, "what was that at Total Wine", here-lists. Never sent. |
| Vibration alongside the tones | Should | — | 13 | 0 | — | The phone is often on silent. |
| Undo on the capture screen for a few seconds | Should | — | 13 | 0 | — | The cheapest moment to throw away nonsense. |
| Notification action, Quick Settings tile, in-app button | Must | ✅ | — | 0 | Notifications | Built. |
| "Add to the last note" within a minute (new) | Later | — | — | 0 | — | Only if R12's spike shows cut-offs. |
| Assistant role (side-key long-press), widgets, back-tap | Later | — | arch. 15, 16 | 0 | assistant role | Costs Gemini on that key. |
| Spoken read-back of what was saved | Later | — | — | 0 | — | Catches mis-heard numbers; says private things aloud (Q27). |
| Wake word, shake gesture, ambient recording | Won't | — | — | | | Not available to third-party apps; and unprompted recording is what makes people uneasy about the category. |

## Understanding

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| Raw words kept for good; everything else re-derivable | Must | ◐ | 01 | 0 | — | A wrong guess must never lose information. |
| The owner's actions recorded as revisions (r2) | Must | — | 01 | 0 | — | The third kind of truth; what makes merge, restore and sync cheap. |
| Fixed kinds: placed, loan, task, reading, event, reference, idea, other | Must | ◐ (four) | 01, 03 | 0◐ | — | Code must treat a task differently from a reading. |
| Model extraction with an enforced JSON shape | Must for the vision | ◐ (one record, hand-parsed) | 04 | 2 | internet, a key | What turns "OTHER" into something useful. |
| Offline rules extractor as the floor | Must | ✅ (few shapes) | 03 | 0 | — | The shapes it handles are listed in PROVIDERS.md §1. |
| Tier 0 says what it could not do (r3) | Must | — | 13, 08 | 0 | — | "Saved. Not sorted: needs a model." No silent failure. |
| File earlier notes when a model is switched on (r3) | Should | — | 04 | 2 (1?) | — | Free by design; about 35 cents per thousand notes; one confirming tap. |
| Subjects ("pool", "car") (new) | Must | — | 03, 07 | 0◐ | — | Readings and tasks need something to hang on. |
| Events and "when did I last…?" (new) | Must | — | 03, 08 | 0◐ | — | One of the commonest memory questions. |
| Dates worked out on the phone | Must | — | 03 | 0 | — | Keeps "time never leaves the phone" true. |
| Vague due hints become a schedule | Must | — | 05, 06 | 0◐ | — | Task apps that demand a date are why vague tasks never get entered. |
| Place and spot on any record ("at Ross"; "row 5, bucket 10") (r2, r3) | Should | — | 03 | 0◐ | — | One nullable field each; what trips, prices and bottles hang on. |
| Prices as readings (r2) | Should | — | 03, 07 | 0◐ | — | "What was the Tide price last time?" with no new kind. |
| Aliases and tags from the model | Should | — | 04 | 2 | — | Synonym search without an embedding model. |
| "Same as Frank?" for near-miss names | Should | — | 09 | 0 | — | Never merged silently. |
| Number checks on readings (new) | Must | — | 03, 07 | 0 | — | Speech mangles decimals. |
| A choice of model provider (r3) | Later | — | 04 (seam), 22 | 2 | a key at that provider | One seam now; a second adapter when wanted. Quality differs by model and is measured. |
| On-phone model (Gemini Nano) | Later (spike) | — | 12 | 1 | system model download | Foreground-only; quality unknown. Half a day to find out. |
| A small open model bundled in the app | Won't for now | — | — | 1 | | Hundreds of megabytes; unknown quality. |
| Items with several copies ("spare key") | Later | — | — | 0 | — | The data model has the column. |
| Speaker recognition | Won't | — | — | | | Not a security boundary. |
| The model rewrites what he said | Won't | — | — | | | Titles are added beside the words, never instead. |
| Words stored as tokens with a dictionary (r2) | Won't | — | — | | | DESIGN §19. Used instead at the boundary, in the diagnostic report. |

## Tasks and reminders

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| Task lifecycle: open, snoozed, done, expired, dropped | Must | — | 05 | 0 | — | The owner's car example. |
| "What should I handle?" as a screen and a question | Must | — | 05, 08 | 0 | — | His words. No model. |
| Closing a task by speaking | Must | — | 05 | 0◐ | — | Tier 0 asks in the attention list instead of closing. |
| Daily digest, only on days with something to say | Must | — | 06 | 0 | Notifications | One notification, not one per task. |
| Notification budget: 2 scheduled + 2 event, 4 in all; quiet hours (r2) | Must | — | 06 | 0 | — | Trips, arrivals and watches would each have made their own exception. |
| Back-off; the digest stops itself after 5 unopened days (new) | Must | — | 06 | 0 | — | Reminder apps that never stop get uninstalled. |
| Lock screen shows a count, not text | Must | — | 06 | 0 | — | A notification is the easiest leak past the lock (Q19). |
| Private tasks never in a notification, even as a count (r3) | Must | — | 06 | 0 | — | The mark's stated cost: a private to-do does not remind. |
| Snooze presets | Should | — | 05 | 0 | — | No date picker needed. |
| Expiry by kind, "Stopped asking", "Bring back" | Must | — | 05 | 0 | — | Nothing is deleted. |
| Weekly review | Should | — | 06 | 0 | Notifications | Catches wrong closes and slow rot. |
| Errands listed by place | Must | ◐ | 05 | 0 | — | The Costco example. |
| "You're at Costco" when the app is used there | Later | — | 11 | 0 | Location while in use | Most of the value at no extra permission. |
| Arrival notification by geofence | Later | — | 11 | 0 | Location all the time; unrestricted battery | After the basics. |
| Loan nudge | Should | — | 06 | 0 | Notifications | One line in a weekly review. |
| Recurring readings learned from their rhythm (new) | Should | — | 07 | 0 | — | No setup. |
| Recurring tasks said aloud | Later | — | — | 2 | — | A calendar does this better. |
| Time-of-day reminders | Later | — | — | 0 | Alarms & reminders (exact) | Q26. |
| Sub-tasks, priorities, projects | Won't | — | — | | | An inbox, not a project manager. |
| "Remind me when I'm with Frank" | Won't | — | — | | | Needs contact or proximity tracking. |

## Readings

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| Measurements stored as values | Must | — | 07 | 0◐ | — | Tidy sentences at tier 0; loose speech needs a model. |
| "What was my pH last time?" with the previous value | Must | — | 07, 08 | 0 | — | Answered by code. |
| "What was the Tide price last time?" naming the shop (r2) | Should | — | 07 | 0 | — | A reading with a place. |
| Subject page | Should | — | 07 | 0 | — | "Tell me about the pool." |
| Trend line per metric (new) | Should | — | 07 | 0 | — | Drawn with Compose; no chart library. |
| Outlier flag | Should | — | 07 | 0 | — | Catches "72" for "7.2". Shown, never corrected. |
| CSV export | Should | — | 07, 10 | 0 | — | A spreadsheet is the right tool for more. |
| Target ranges, dosing advice | Won't | — | — | | | Advice the app cannot stand behind. |

## Finding

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| Ask by voice or text; short answer with the note and its date | Must | ✅ (keyword) | 08 | 0 | Microphone to ask aloud | Kept. |
| Common questions answered by code | Must | ◐ | 08 | 0 | — | Offline, free, cannot invent. |
| Free-form questions through a bounded read-only tool loop | Should | ◐ | 08 | 2 | internet, a key | Tier 0 shows matching notes and says why it is a list. |
| The answer shows the notes it used | Must | — | 08 | 0 | — | Provenance. |
| "Why this answer?" from a stored trace (r2) | Should | — | 08 | 0 | — | Lets him find the cause himself; the basis of any later support. |
| Search by date (new) | Should | — | 08 | 0 | — | A query on capture time. |
| Search by place and "has a picture" (r3) | Should | — | 08, 16 | 0 | Location, or a spoken place | "What was that bottle at Total Wine a couple of weeks ago?" |
| Stale warnings; latest wins with history | Must | ◐ | 08 | 0 | — | Saying the age is the fix. |
| Read the answer aloud | Should | — | 08 | 0 | — | Never for Private notes. |
| "That's not it" and a local log of failed questions (new) | Should | — | 08 | 0 | — | Evidence for whether better search is needed. |
| Private matches shown only as a count until a second unlock (r3) | Must | — | 08, 02 | 0 | — | No previews of private things. |
| On-device semantic search | Later | — | — | 1 | — | Only if the miss log shows synonym misses. |
| Hosted embeddings | Won't | — | — | | | Sends every note out. |
| Answers on the lock screen | Won't | — | — | | | Reading needs unlock. |
| General-knowledge answers; web search while answering; acting for him | Won't | — | — | | | Not a chat assistant. Watches are separate and fixed. |

## Pictures and codes (r2, r3)

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| Share to Stash (screenshots, photos, text, links) | Should | — | 16 | 0 | — | No permission; he chooses each time. The main route for screenshots. |
| A camera inside Stash | Should | — | 16 | 0 | Camera | The photo never enters the gallery or its cloud backup. |
| "Anything to add about this?" after a picture | Should | — | 16 | 0 | Microphone | Picture first. |
| "Add a picture" offered after a note about a physical thing | Should | — | 16 | 0◐ | Camera | Note first. A button, never a notification; gone if ignored. |
| Words in a picture read on the phone and searchable | Should | — | 16 | 0 | — | A label or poster findable with nothing sent. ML Kit reports usage figures to Google. |
| His words win over the label; both kept | Should | — | 16 | 0 | — | The label can be misread; he was there. |
| Pictures stored small (WebP, 1,600–2,000 px), each with its own key | Should | — | 16 | 0 | — | About 250 KB each. |
| Every picture private by default | Must (with pictures) | — | 16 | 0 | — | Never in the gallery, notifications, recents, reports, or sent. |
| Picture deleted with its note | Must (with pictures) | — | 01, 16 | 0 | — | One defined delete. |
| "Show me the bottles I tagged at Total Wine" | Should | — | 16, 08 | 0 | Location, or a spoken place | Place plus "has a picture". No image recognition. |
| Offer my latest screenshot when I start a note | Later (switch, off) | — | 16 | 0 | Photos and videos (all) | To learn whether it is worth the permission. |
| Notify "include your screengrab?" by watching the library | Won't | — | — | | all photos + background trigger | Broad access and another notification. |
| Send a picture's printed text to the model | Later (switch, off) | — | 16 | 2 | — | Fills in name and maker; can carry other people's words. |
| Send the picture itself to a model | Later | — | — | 2 | per-picture consent | Not built. Never for Private. |
| Detecting explicit images | Won't | — | — | | | Errs both ways; DESIGN §21. |
| Scan a barcode or QR code into a note | Should | — | 21 | 0 | Camera | Raw payload, symbology, parsed type. |
| Codes found inside saved pictures | Should | — | 21 | 0 | — | Same scanner, on the still. |
| "You noted this one on … at …" on re-scan | Should | — | 21 | 0 | — | A lookup in his own notes. |
| Links shown, never opened without a confirming tap; Wi-Fi and payment never acted on | Must (with codes) | — | 21 | 0 | — | Phishing and credentials. |
| Personal codes default to Private | Must (with codes) | — | 21 | 0 | — | Boarding passes hold a name and booking reference. |
| Product name looked up from a barcode | Later (switch, off) | — | 21 | 0 | internet | Tells a third party what he scanned. |

## Outings (r2)

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| Trips built from the notes made while out | Should | — | 17 | 0 | Location while in use (weaker without) | The core of the idea with no background location. |
| "When did I last go out?", "when was I last at Costco?" | Should | — | 17 | 0 | — | Answered by code; says "that I know about". |
| Car and driver, from speech | Should | — | 17 | 0◐ | — | Phrase rules at tier 0. |
| Start and end a trip by voice or button | Should | — | 17 | 0 | — | Exact times instead of estimates. |
| Leaving list (always / per car / per place), one notification | Should | — | 18 | 0 | Notifications | Only on signals early enough to act on. |
| Departure by car Bluetooth | Later (measured first) | — | 18 | 0 | Nearby devices; unrestricted battery | In the driveway; says which car. |
| Departure by home Wi-Fi | Later | — | 18 | 0 | Location | Too late for the list; records trips. |
| Departure by geofence or activity recognition | Later / maybe | — | — | 0 | Location all the time / Physical activity | Late, and the heaviest permissions. |
| "Track this trip? Yes/No" each time | Won't | — | — | | | A question at every departure is a nag (Q30). |
| Reverse geocoding to name stops | Won't | — | — | | | Sends coordinates out (D22). |

## Watches (r2)

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| A watch proposed from a note, started only on approval | Later (last) | — | 19 | 2 to propose; 0 by hand | internet | Nothing is watched because a model thought so. |
| The Watches list: what is sent, to whom, how often, cost; pause, stop | Later | — | 19 | 0 | — | One place to see and end all of it. |
| Events-listing engine (concerts) | Later | — | 19 | 0 | internet, that service's key | Smallest query; code decides the match. |
| Model web-search engine (anything else) | Later | — | 19 | 2 | internet, key | A few cents a run; Anthropic only. |
| Caps, expiry after a year, pause on dead source | Later | — | 19 | 0 | — | Forgotten watches must not run for ever. |
| A server of ours to run watches | Won't | — | — | | | Would hold everyone's interests and places. |
| Watches that act (buy, book) | Won't | — | — | | | Read-only. |

## Keeping it right without making him check

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| Attention list (new) | Should | — | 09 | 0 | — | Doubts are parked, never block. |
| Fix a note: correct a word, change kind, "treat as one note", "read it again" | Should | ◐ | 09 | 0 | — | Possible because the raw words are kept. |
| "Is this the same as …?" (new) | Should | — | 09 | 0 | — | Suggested, never automatic. |
| Merge two subjects | Should | — | 09 | 0 | — | One tap; teaches an alias. |
| "Try a sentence" without saving (r2) | Should (debug) | — | 20 | 0 | — | The prompt-tuning tool, and how to reproduce a problem with made-up words. |
| Weekly quality numbers on a debug screen | Should | — | 04, 09 | 0 | — | The developer's view. |
| Timeline by day (new) | Later | — | — | 0 | — | The Notes list sorted by date is most of it. |

## Privacy, safety and upkeep

| Feature | Rank | Proto | Card | Tier | Needs | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| One switch per capability, with what works without it (r2) | Must | — | 13 | 0 | — | A lighter tier later is a different list, not a rewrite. |
| Explanation card beside every choice (r3) | Should | — | 23 | 0 | — | What you get, what it sends, what is lost, what it costs. |
| "What leaves my phone", live (r3) | Should | — | 13, 23 | 0 | — | Drawn from a register network code cannot bypass. |
| One "Private" mark: never sent, second unlock, left out of reminders and previews (r3) | Must | — | 03, 02, 06, 08 | 0 | a screen lock | Replaces the prototype's unused `localOnly`. |
| Private items never sent, enforced by the type system and a test | Must | — | 03, 13 | 0 | — | Ground rule. |
| App gate: fingerprint or PIN before reading | Must before relying on it | — | 02 | 0 | a screen lock | The protection that matters day to day. |
| Encrypted database, key in secure hardware | Must before relying on it | — | 14 (spike 15) | 0 | — | With the release build; STORAGE.md. |
| A signing key that does not change (r2) | Must, now | — | 01 | 0 | — | Otherwise a rebuild can force an uninstall, which destroys the notes. |
| Logs of events and codes only (r2) | Must | ✅ (the prototype logs nothing) | 13 | 0 | — | Keeps it that way with a test. |
| The hosted model can be switched off with everything still working | Must | ✅ | — | 0 | — | Built. |
| Plain-language privacy page | Must | — | PRIVACY.md; 23 | 0 | — | A story he can repeat. |
| Encrypted backup with a recovery code; restore | Must before relying on it | — | 10 | 0 | a folder he picks | Before encryption and the release build. |
| Export in open formats | Should | ◐ (text) | 10 | 0 | — | His data must be able to leave. |
| Delete everything; a delete that really deletes (tombstones) (r2) | Should | — | 01, 10 | 0 | — | A deleted note must not return from a backup. |
| Monthly spend ceiling | Should | — | 04 | 2 | — | Covers extraction, questions and watches. |
| Diagnostic report with per-report tokens; "send one note" (r2) | Later (product) | — | 20 | 0 | — | Designed now; costs nothing until built. |
| "Forget this person" (new) | Later | — | — | 0 | — | The subject table makes it a query. |
| Sealed inbox | Later | — | arch. 30; STORAGE.md | 0 | — | Makes "locked means unreadable" a hardware fact. |
| Live sync between devices | Won't for now | — | — | | | One phone. The storage rules keep it possible. |
| Telemetry, analytics, crash reporting | Won't | — | — | | | Ground rule. |
| Android's own cloud backup | Won't | — | — | | | Would copy an unreadable database to Google. |

## For other phones and other people (all deferred)

Cover-screen widgets, simple mode, onboarding for a family member, Play internal testing and updates, per-person API keys: as designed in the first version, cards archived (arch. 02, 06, 27, 29). Revision 2's capability switches and revision 3's tiers and explanation cards are the groundwork a lighter or family build would stand on; nothing else is built for them now.

## What existing products got wrong

Surveyed 2026-11-11 for revision 1; unchanged. Most of this is general knowledge of the products gathered by a research helper; items marked ✔ were checked against a source that day. None was verified hands-on.

| Product family | What they do | What they got wrong, for this purpose | What to take |
| --- | --- | --- | --- |
| Voice note apps with AI (AudioPen, Voicenotes, Letterly, Cleft, Whisper Memos) | Record, transcribe in the cloud, rewrite into tidy prose, tag; some extract to-dos and offer "ask your notes" | Rewriting replaces the user's words; notes pile up unprocessed; retrieval weakens with hundreds of notes; subscriptions; thin export; cloud by default | Keep the raw words visible beside the result; "ask your notes" |
| Phone makers' own (Google Recorder, Pixel Screenshots, Samsung Notes, Apple Notes with Siri) | Reliable on-device dictation; Pixel Screenshots organises with an on-device model | Dictation lands in a flat note; no typed records; Siri sets reminders well and answers questions about your own notes badly | On-device transcription is good enough; on-device models can organise; screenshots are worth capturing |
| Google Find Hub "Remembered" ✔ | "Remember that I put my passport in the drawer", optional photo, ask Gemini later | Items only. Reported to go stale when an item moves without being told; storage and retention not documented; needs Gemini; tied to the Google account | The phrasing; the photo. The benchmark for ease on the "where is it" half |
| Assistants with memory (ChatGPT memory, Gemini saved info, Alexa "remember this") | Store loose facts and recall them in conversation | Free-form strings with no type, time or history; opaque about what was stored; silent drops | Typed records with dates and provenance; show what was stored |
| "Second brain" tools (Obsidian, Logseq, Notion, Tana, Capacities, Mem) | Linked notes; typed objects in some; local plain text in some | The user must organise, which non-enthusiasts stop doing; Mem's self-organising promise was widely criticised as unreliable | Typed objects; plain-text export; local first |
| Task apps (Todoist, TickTick, Google Tasks, Due) | Natural-language dates, recurrence, snooze; Due re-nags until done | A vague task must be given a date or it is forgotten; persistence is what some users uninstall for | Snooze presets; back off and say so |
| Measurement trackers (Pool Math, Orenda, Daylio, Bearable, Nomie) | Trackers with a name, unit and type; charts | Trackers must be set up before use; fixed parameter lists; entry by tapping | A tracker inferred from first mention; "last and change since" |
| Recording wearables (Limitless, Humane AI Pin, Bee, Plaud) ✔ | Record continuously, summarise in the cloud | Consent of other people; cloud storage; the companies were sold or shut within a couple of years | Do not depend on a hosted product for memory; never record what he did not start |
| Local-first note apps (Joplin, Standard Notes, Notesnook, Anytype; Obsidian with a sync tool) | Local data with end-to-end encrypted sync | Sync needs their service or your own upkeep; the official Syncthing Android app ended in 2024 ✔ | An encrypted file to a folder the user picks is the smallest version of this idea |
| Password managers' support tools (Bitwarden's Flight Recorder ✔, 1Password diagnostics ✔) (r2) | A log the user starts, stops and shares; kept on the device, never sent automatically | 1Password's report includes device name, username and IP address: "no secrets" is not "nothing about you" | User-started, on-device, previewed, shared by the user's own hand |
