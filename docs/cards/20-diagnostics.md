# 20 ◆ · Diagnostics for a possible product: "Try a sentence" now, the diagnostic report later

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

When the app does something unexpected, the cause can be found without anyone but the owner reading his notes. Stage 1 is useful to the developer today. Stages 2 and 3 are for the day the app has users who are not the developer; they are designed here so that nothing built before then gets in their way.

## Depends on

Stage 1: 13, 04. Stages 2–3: 08 (the answer trace), 10, and a decision to ship to other people.

## Context

The owner proposed storing words as random identifiers with a dictionary that turns them back into names, so records are unique but not identifiable. `docs/DESIGN.md` §19 examines that honestly. In short: the instinct (support staff should see structure, not content) is right, but the database is the wrong place for it. The extractor and the search index need real words; a dictionary kept on the same phone protects nothing that encryption does not; a fixed word-to-token mapping is a substitution cipher that word frequencies undo; and dates, prices, counts and places identify a person without any names. The right place for tokens is the **boundary**: a report built at the moment the user chooses to share one, with fresh random tokens each time.

What already exists by this point and is the raw material: `extraction` rows (which extractor and prompt version produced what), attention reason codes, `nudge_log`, `watch_run`, `answer_trace` (card 08), `app_event` (card 13), the consistency check. None holds anything the report needs to invent.

## Scope

### Stage 1 (now, debug builds): Try a sentence

1. A debug screen: type or dictate a sentence; it runs the same private-note check, extractor, checks and projection as a real note, **without saving anything**, and shows: private or not and why; each extractor's raw output; which checks failed; the rows that would be written.
2. A second box does the same for a question: the route chosen, the lookups, the answer.
3. This is also the tool for *substituted reproduction*: rewrite a troublesome note with made-up names ("Costco" → "Shopmart") and see whether the problem still happens. If it does, that harmless sentence is the bug report.

### Stage 2 (product stage): Why did this happen?

4. On any record: **Why was this filed this way?** → extractor and version, its confidence, checks that fired, whether it was re-read, whether the owner changed it. On any answer: card 08's "Why this answer?". On any reminder: the `nudge_log` row and the rule that chose the day. All from data already stored; nothing leaves the phone.

### Stage 3 (product stage): the diagnostic report

5. Settings → Help → **Make a diagnostic report.** Built on the phone at that moment from:
   - app version, Android version, phone model, schema version, extractor and prompt versions, capability switches and permission states;
   - counts by table, kind, state and attention reason; records per capture as a histogram; database and picture storage sizes;
   - the event log (codes, numbers, durations);
   - the last 50 answer traces and the last 50 extractions **as structure only**.
5a. **Never in a report, in any form:** pictures or their thumbnails; text read from pictures; scanned code payloads; anything belonging to a sensitive capture (it is counted as "private items: N" and otherwise absent, with no tokens, no kinds, no times).
6. **Tokens.** Every name, title, quote, question and place in that structure is replaced by a token minted for this report alone: "Item 2", "Person 1", "Place 3", "Text 14 (9 words)". Numbers become their shape ("N.N"); dates become offsets from the report time rounded to the hour ("−3 d 4 h"); coordinates are dropped. The same thing gets the same token *within* one report, so support can follow it; tokens are random and never reused, so two reports cannot be joined.
7. The token table (token → real value) is saved **on the phone** with the report's id. Help → "Decode a token" lets the user look one up if support asks "what is Item 2?" and decide whether to say.
8. **Preview, then a file.** The whole report is shown as text before anything happens. "Save or send" hands a file to Android's share sheet; the user picks where it goes. The app has no network code for this and nothing is ever sent by itself.
9. **Send one note** (never offered for a sensitive note or a picture) (for cases the report cannot explain): the user picks a capture; a preview shows exactly what is included (its words, its records, the extractor output, optionally its picture); the bundle is encrypted to a support public key shipped in the app (Tink hybrid encryption) and saved as a file for the share sheet. The bundle carries a "delete after" date. Be plain in the UI: the date is a promise support must keep, not something the file can enforce.
10. A unit test builds a report from a seeded database full of known strings, with pictures and private notes, and asserts none of the strings, no picture bytes and no id of a private row appears in the output.

## Out of scope

Automatic crash reports or analytics of any kind (ground rule). A support server, ticketing, or remote access. Storing tokens instead of words in the database (rejected; DESIGN §19).

## Acceptance checks

- **Stage 1, real phone:** "Try a sentence" with the pool sentence and with a private one; nothing new appears in Notes; `peek.py dump` count is unchanged.
- **Stage 3, unit:** the no-known-strings test; the same entity maps to one token within a report and different tokens across two reports; decode returns the original; a report from an empty database is valid.
- **Stage 3, unit:** the encrypted note bundle opens with the matching private key and not without it.

## Notes and risks

- A report with tokens is still about one person and can still identify them to someone with other knowledge (an unusual pattern of stops, a rare price). Say "this report contains no words from your notes", never "this report is anonymous".
- Stage 3 is not worth building for one user who is also the developer. Its cost before then is zero provided cards 08 and 13 are built as written.
