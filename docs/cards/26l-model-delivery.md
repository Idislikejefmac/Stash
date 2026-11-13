# 26l ◆ · Later: getting the speech model onto other people's phones

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D78, D81, D82).

**Designed, not scheduled. Do not build this card without a new decision by the owner.** It matters only if Stash is given to someone who cannot use a PC and `adb`, or is sold.

## Goal

A person who installs Stash from a shop or a link gets the speech model without a computer, knowing exactly what was fetched and from whom.

## Depends on

26d (model packs, `KnownModels`, `ModelImporter`). For the download route also 13 (the outbound register) and 23 (explanation cards). A decision on how the app is distributed (D31, D34 are deferred).

## Context

Today (26d) the model is a pack file put on the phone once, by `adb` or the file picker. That is right for one owner with a PC. It is wrong for anyone else. Three ways to do better, in the order they should be considered:

| Route | What it costs | Network code in Stash | Suits |
| --- | --- | --- | --- |
| **A. In the APK** (assets `models/…`, which 26d's `ModelLocator` already reads) | About 107 MB more to download and 141 MB more installed, on every update. With the libraries, the measured total sits just under Google Play's base-download limit as remembered (200 MB; **not checked**), with little room | None | Sideloaded copies for family |
| **B. A Google Play install-time asset pack** | The same size, delivered by Play with the app, outside the base limit | None (Play does it) | A Play release. Preferred there: no server of ours and nothing for the app to fetch |
| **C. A download on first use** | About 107 MB compressed, once; a host that will still serve the same bytes in five years; a failure path | **Yes**: a new network package and a new kind of request | Only if neither A nor B is possible |

D81 points the same way: the model must be something a low-to-mid-range phone can hold, and Moonshine tiny (29 MB compressed) is the candidate to **bundle for everyone** (route A or B) with base as the larger option for phones with room. Card 26e's outcome decides which is the default.

## Scope (for whichever route is chosen)

1. **Route A or B:** a Gradle change and, for B, an asset-pack module. `ModelLocator` needs no change for A; for B it gains a third location (the pack's path as Play reports it). Check the limits on the live Play documentation before starting. The model's licence text is already shipped in the app (26d).
2. **Route C only:**
   - A new network package `app.stash.speech.net`, added to the allow-list of card 13's source-scan test. It does one thing: an HTTPS GET of a fixed address, with no cookie, header or parameter that says anything about the user or the phone.
   - A new `Outbound.MODEL_DOWNLOAD` with a new capability `MODEL_DOWNLOAD` (off until he taps the button), destination named in plain words, "sends: nothing but the request itself". It goes through the same gate and counter as every other request.
   - The bytes are streamed into 26d's `ModelImporter`, so the same hashes decide whether they are accepted. A download can be resumed; a failed or wrong file leaves nothing behind.
   - It runs only when he taps **Download the speech model**, after the explanation card below; on Wi-Fi unless he says otherwise; never by itself and never again once a pack is installed.
   - **Where from** is a decision, not a detail. The model's publishers' own pages (GitHub releases, Hugging Face) can change or remove a file and see every user's address; a host of ours contradicts "Stash has no server" unless it is a plain file store that keeps no logs we can read. Write the choice in DECISIONS.md first.
3. Explanation card text for route C (already in `docs/EXPLANATION_CARDS.md`, marked not built):
   - **What you get:** Stash can keep a recording of each voice note and write it out itself.
   - **What it sends:** Nothing about you or your notes. It fetches one file of about <size> from **<host>**, which will see your internet address and that a copy of Stash asked for it.
   - **If you don't:** Voice notes are written down by the phone as you speak and no recording is kept.
   - **What it costs:** About <size> of download, once, and <size> of storage.
4. The "What leaves my phone" row: "Speech model download · <host> · a request for one file; nothing of yours · Not running · last <date>".

## Out of scope

Automatic model updates. Larger opt-in models (card 25's ceiling run found a 630 MB model no better on his voice than Moonshine base). Languages other than English (the non-English Moonshine models are under a non-commercial licence and must never be fetched).

## Acceptance checks (for route C; A and B need only an install check and the size written down)

- **Unit:** the request carries no header beyond what HTTPS needs; a kind whose capability is off is refused before any connection; a wrong hash installs nothing; a resumed download yields the same bytes.
- **Unit:** card 13's network-package scan passes with exactly one new package and fails if the download code is moved elsewhere.
- **Real phone:** in aeroplane mode the button says there is no connection and nothing is half-installed. On Wi-Fi the pack installs and the "What leaves my phone" count rises by one.

## Notes and risks

- A download makes "nothing leaves your phone unless you switch something on" need one more sentence for every new user. Routes A and B do not. Prefer them.
- Not legal advice: redistributing the model inside an APK or an asset pack is redistribution under its MIT licence (carry the licence text, which 26d does). Q48 (a lawyer's review before any sale) stands.

**Suggested builder: Opus**, when it is scheduled: it would be the app's first network code outside the model and watch packages.
