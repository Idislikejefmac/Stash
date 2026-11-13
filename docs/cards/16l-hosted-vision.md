# 16l ◆ · Later: asking a hosted model about one picture ("hosted vision")

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

**Status: designed, not scheduled. Do not build this card until the owner says so in writing and a new row in `docs/DECISIONS.md` records it.** It is here so that the seam and the safety rules are settled before anyone is tempted to add "just one request". Cards 16a to 16k contain nothing of it.

## Goal

Optionally, for **one picture at a time** and only after a clear question each time, the owner (or a later user) sends a picture to the model provider they connected with their own key (Claude, Gemini, Grok, OpenAI …) to have it read or described: a wine label the phone's reader could not make out, a handwritten tag, a part with no text. The answer is kept beside the picture as something a model said, not as something he said.

## Why this needs its own decision

Today every design document says the same thing: **a picture never leaves the phone** (00-READ-FIRST rule 4; D69: "the app contains nothing that shares or uploads pictures"; D72; `docs/PRIVACY.md`, "Pictures" and "About illegal images"). This card ends that sentence. Building it therefore requires, first:

1. a new DECISIONS row that amends D69 and D72 and says why;
2. changes, in the same piece of work, to 00-READ-FIRST rule 4, `docs/PRIVACY.md` (short version, "Pictures", "What does leave", the threats table) and the "Never sent" line of `docs/EXPLANATION_CARDS.md`;
3. if the app is to be offered to anyone else, the lawyer's view that QUESTIONS.md Q48 already asks for: an app that can upload user pictures to a third party is a different thing, legally and under store policy, from one that cannot.

## Depends on

04 (the `ModelProvider` seam and `HttpPoster`), 13 (capabilities, the outbound register), 23 and 16k (explanation cards), **02 and 14** (the gate and encryption: this is not a feature for a debug build with no lock), 16a, 16d (the viewer). Card 22 for any provider other than Anthropic. So it waits until after the hardening block whatever else is pulled forward.

## The rules (these are the design; the scope only implements them)

| Rule | Meaning in code |
| --- | --- |
| **Off by default** | Capability `HOSTED_VISION`, `defaultOn = false`. Turning it on sends nothing; it only makes the button in the viewer appear |
| **Asked for every picture, every time** | A consent sheet before each send. No "don't ask again", no "send all", no remembered choice |
| **Never for Private** | No button on a picture whose capture is `sensitive`; and the sender itself refuses when `Sensitivity.sendable(capture)` is null, so a screen bug cannot send one |
| **Only when he taps** | Never automatic, never in the background, never at share or capture time, never from a worker, never in bulk |
| **Named in the register** | `Outbound.DESCRIBE_PICTURE` (the name card 13 already reserved), capability `HOSTED_VISION`. The "What leaves my phone" row reads "A picture · <provider> · one picture, each time you agree · Never without asking · N so far" |
| **Only the picture and his question** | Sent: the app's stored copy (already shrunk, re-encoded, with no camera data and no position), shrunk again to at most 1,568 px, and an optional question he types or says. Not sent: the note's words, other pictures, the words the phone read, place, date, time, file names |
| **Through the provider adapters** | Network code only in `app.stash.model.hosted.<provider>`, through `HttpPoster` with the kind named. A provider is offered only if its adapter implements pictures and its facts are filled in |
| **Plain warning about the provider's terms** | The sheet says who receives the picture, how long they say they keep it, and what not to send (below) |
| **The answer is data** | Stored as derived text, shown as plain text marked "Said by <provider>", never run, never linked, never treated as his words |

## The seam

- `ModelProvider.describePicture` is listed in `docs/PROVIDERS.md` as "not defined". This card defines it: in: an instruction, one image (`image/webp` bytes), an optional question; out: text and token counts, or a failure code (`REFUSED`, `TOO_LARGE`, `UNSUPPORTED`, `RETRYABLE`, `FAILED`).
- `model/PictureDescriber` is the interface the app calls, with `NoPictureDescriber` as the do-nothing version chosen in `Container` when `HOSTED_VISION` is off, there is no key, or the chosen provider has no picture support. Feature code never looks at the provider.
- `ProviderFacts` (card 23) gains per provider: `takesPictures`, `pictureRetention` (their stated period), `pictureRules` (one line on what their policy forbids), and the usual `checkedOn`. A provider whose facts are older than a year shows "Check <provider>'s current terms" on the sheet.
- Because it is an interface, a model **on the phone** (card 12) could implement the same seam later and would need no consent sheet, since nothing would leave.

## Scope (when it is built)

1. The capability, the outbound kind and the `PictureDescriber` seam, as above.
2. Table `picture_description` (a migration and a migration test): `id`, `attachment_id`, `provider_model` (`provider:model/prompt-version`, as `extraction.extractor`), `question`?, `text`, `tokens_in`?, `tokens_out`?, `created_at`. Several per picture are allowed (he may ask twice). Deleted with its attachment (extend 16a's `remove` and `purge`, and the consistency check: a description always has an attachment).
3. The viewer (16d) gets **Ask <provider> about this picture**, shown only when `HOSTED_VISION` is on, a key exists, the provider takes pictures, there is a network, and the capture is not Private.
4. The consent sheet, shown every time, with the picture itself on it so he sees exactly what goes:

   > **Send this picture to <provider>?**
   > The picture itself will be sent, under <provider>'s terms, and they say they keep it for up to <period>. Stash cannot take it back.
   > Do not send pictures of other people, documents, cards or tickets, or anything intimate. Providers' rules often forbid sexual content, and sending it can get your account closed.
   > About <cents> cents.
   > [ optional: "What do you want to know?" ]
   > **Send this one** · **No**

   The wording starts from `docs/EXPLANATION_CARDS.md`, "Send a picture to the model"; update that page to match.
5. The request: a fixed instruction ("Describe what is printed or shown in this picture in plain words. If there is a label, give the name, maker, type and year as written. If you cannot tell, say so. Do not guess who a person is.") plus his question if any. Lowest thinking effort, a small output limit, one request, no tools, no retry on refusal.
6. The result is shown under the picture: "Said by <provider>, <date>: …" with **Remove**. It is added to the capture's search document, so the picture becomes findable by it. It does **not** create or change records by itself. A second button, "Use this in the note", may copy it into the note's text for him to edit; then it is his words and the note is re-read as usual.
7. Failures are said plainly: "<provider> would not describe this picture." (refusal: nothing more is tried) · "That didn't go through. Nothing was kept." · "This provider can't take pictures."
8. The monthly spend limit from card 04 applies; the sheet shows the estimate before sending.
9. Events (`Dbg.event`): asked, sent (bytes, ms, tokens), refused, failed. Never a word of the reply.

## Out of scope, even then

Sending without a tap. Sending more than one picture in a request. Sending a Private picture. A "describe all my pictures" action. Sending from the share or camera flow before the note is saved. Letting the reply file the note by itself. Face recognition or naming people. Video. A server of ours in the middle. Any provider reached other than with the user's own key.

## Acceptance checks (when it is built)

**Unit tests**

- With `HOSTED_VISION` off, `Container` gives `NoPictureDescriber` and no button is drawn.
- A Private capture: no button; and calling the sender directly for it is refused before the transport (use one that throws).
- The sender cannot be reached without a consent token made by the sheet's "Send this one" for that attachment id; a token is single-use.
- The request body contains exactly one image and, apart from the fixed instruction and his question, no text from the note, no words read from the picture, no coordinate, no date, no file name.
- The image sent has no `EXIF`, `XMP ` or `ICCP` chunk (16b's `WebpChunks`) and its long edge is at most 1,568 px.
- The gate refuses `DESCRIBE_PICTURE` when the switch is off; the count rises by one per send.
- Recorded replies per provider: a description, a refusal, an oversize error; each maps to the right outcome.
- Removing a picture removes its descriptions; purge too; the search document loses the text.
- A reply containing a link or an instruction is rendered as plain text and changes nothing.
- No worker, receiver or share path references `PictureDescriber` (source scan).

**Real phone**

- A wine label the phone's reader got wrong: ask, read the sheet, send. Is the answer right? Tokens and cents against the estimate.
- The same on a Private note: no button.
- Aeroplane mode: no button, or a plain failure; nothing queued for later.
- "What leaves my phone" shows the row and a count of 1. Turn everything off: the button is gone.
- Ask the owner to read the consent sheet aloud once and say whether anything on it is unclear.

## Notes and risks

- Provider facts to re-read on the day, not trusted from here: which image types each accepts (Anthropic and the OpenAI-style APIs are believed to accept WebP; medium to high confidence), size limits, how a picture is priced in tokens, retention for images, and each one's content rules (`docs/PROVIDERS.md` has the comparison as read on 2026-11-11, with "takes pictures: yes" for three and unknown for two).
- A provider's safety system looks at what is sent. A picture it flags may be kept far longer than the usual period (for Anthropic, up to two years: `docs/PRIVACY.md`). The sheet's warning is there for that reason.
- People in pictures have not agreed to anything. The instruction tells the model not to identify people; the sheet tells him not to send them. Neither is a guarantee.
- The most likely way this goes wrong is convenience creep: "ask automatically when the reader finds nothing". The rule "only when he taps, one picture, every time" is the feature's safety; a later card that wants to relax it needs its own decision.

**Suggested builder: Opus.** It is the first code that sends a picture off the phone; the consent and Private rules must hold against bugs elsewhere, and a mistake cannot be recalled.
