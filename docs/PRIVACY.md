# Privacy: where your notes live, what leaves the phone, and what cannot be promised

Written 2026-11-11 for the owner, and for anyone he shows the app to; extended the same day (revisions 2 and 3) to pictures, scanned codes, outings, watches, a choice of model provider, the Private mark and diagnostics. It describes two things and keeps them apart: **the prototype as it is today**, and **the app as designed**. Where something cannot be promised, it says so. The same facts, cut down to five lines per switch, are what the app itself shows: [EXPLANATION_CARDS.md](EXPLANATION_CARDS.md). Where something cannot be promised, it says so.

## The short version (the part to repeat to someone else)

- Everything you say is stored **on your phone only**. There is no account, no server of ours, and no company holding your notes.
- Your voice is turned into text **on the phone**, and the audio is never sent anywhere. *(Changed 2026-11-12, decision D77.)* As designed, a voice note also **keeps its recording**, on the phone only, encrypted like a picture, until you delete the note or the recording, so that a wrongly written word can be heard and put right. One switch turns that off; then no sound is kept, as before. Today's prototype keeps no sound at all. See "Recordings of voice notes".
- To understand free-form notes well, the app can send **the text of a note** to a language model run by Anthropic. This is optional. Location, audio and the time of the note are never sent. Notes marked private are never sent. Anthropic's terms for this service say it does not train its models on that text and deletes it within 30 days, with exceptions listed below.
- With that option switched off, **nothing leaves the phone at all**, and the app still captures, searches and reminds. It just files notes less cleverly.
- If the phone is lost, the notes are lost, unless you have made an encrypted backup. That is the price of not having a company hold them.
- Pictures you add are kept **inside the app**, not in your gallery, and are never sent anywhere. The app does not look at what they show.
- Anything you mark **Private** is never sent anywhere at all, needs your fingerprint again to open, and stays out of reminders and previews.
- Two optional things send something other than note text, and only if you turn them on: a **watch** (a search you approved, on a schedule) and a **product lookup** (a barcode number). The app has a screen, "What leaves my phone", that lists every such thing and whether it is on.
- One thing leaves without your choosing each time: Google's on-phone text and barcode readers send Google **usage figures** (not your pictures). See "Pictures".
- Today's prototype is **not yet protected** in the ways described here. See "The prototype today".

## The prototype today (be careful until the hardening cards are done)

| What | State in the prototype | Fixed by |
| --- | --- | --- |
| The database | A plain, unencrypted file in the app's private storage. Android's own storage encryption still protects it while the phone is off, and other apps cannot read it. It stays this way, on purpose, while the app is a development build ([STORAGE.md](STORAGE.md) says why). | Card 14 |
| Opening the app | No fingerprint or PIN of its own. Anyone holding the phone unlocked can read every note. | Card 02 |
| Private notes | **Not implemented.** If an API key is set and the hosted model is on, the text of **every** note is sent, including one that contains a password. Saying "private note" does nothing yet. | Cards 03, 13 |
| What is sent | The note's text and your personal word list (Syd, Costco, frunk, Frank, Tesla and anything you add). For a question: the question and the text of up to three matching notes. No audio, location or time. | As designed |
| The build | A debug build. A computer you have authorised for USB debugging can read the app's files. That is how `tools/peek.py` works. | A release build, when wanted |
| Backups | None. Android's own backup is switched off for the app, on purpose. | Card 10 |
| Losing everything by accident | The app is signed with this PC's throwaway key. Installing a build made elsewhere means uninstalling first, and an uninstall deletes every note. | Card 01, first step |
| Pictures, codes, outings, watches | None exist. | Cards 16–19, 21 |
| Recordings of voice notes | None are kept. The phone's recogniser listens and the sound is gone when it has finished. | Cards 26a–26k. If they are built before cards 02 and 14, recordings share the notes' exposure in the first two rows of this table |

**What to put in it for now:** where things are, what you mean to do, pool readings, who borrowed what. **What to keep out until cards 13, 02 and 14 are done:** passwords, PINs, alarm and gate codes, account or card numbers, anything about someone else's health or money, anything you would mind a person reading over your shoulder. If you want to be certain nothing is sent, switch "Use the hosted model" off in Settings, or do not enter a key.

## What is stored, and where (as designed)

On the phone, in one encrypted database in the app's private storage:

- The words you said, exactly as the phone recognised them, and the same words after your personal spelling corrections.
- When you said them and in which time zone.
- Where the phone was, if you allowed location (the last position the phone already knew; the app does not track you between notes).
- Whether the phone was locked at the time.
- What the app worked out: the kind of note, short titles, items, people, places, tasks and their state, readings and their numbers.
- A record of the reminders it showed you and whether you opened them (so it can stop pestering).
- Questions that found nothing, so search can be improved. These stay on the phone.
- Your word list, your corrections, your settings, and your API key (encrypted separately).

- *(as designed, later cards)* Pictures you add, each as a small encrypted file of the app's own, with the printed words the phone read from it. Codes you scan, as the text they contain. Outings: when you left and returned, where you stopped, which car. Your leaving list. Watches you approved and what each run found. For each answer, the steps the app took to reach it.

- *(as designed, cards 26a–26k)* The recording of each voice note, as a small encrypted file of the app's own (about 170 KB for a minute of speech), and every version of the words the app wrote out from it. Kept until you delete the note or the recording. Not kept at all if you turn "Keep a recording of each voice note" off.

**Not stored anywhere:** sound from anything other than a voice note you made with recording on. A question you speak, and words you add by voice to a shared picture, are not recorded. Contacts and calendar are never read. Your photo library is not read unless you turn on "offer my latest screenshot"; otherwise the app sees only the pictures you hand it or take inside it.

## What never leaves the phone

- **Audio, live or recorded.** *(Extended 2026-11-12.)* A recording is written out by a speech model that runs inside the app, on the phone. (Confidence: the engine's code has no network part and the model is a file on the phone. This has not yet been checked on the phone as a finished feature; the check is a day of use in aeroplane mode, cards 26g and 26k.) A recording is never sent to a model, never put in a notification, a log or a diagnostic report, and never exported. The one other place it may go is your own encrypted backup file, when backups exist, and you can leave recordings out of that. When recording is off, or for a spoken question, recognition is done by Android's on-device recogniser. (Confidence for that recogniser: the app asks for the on-device recogniser. Whether the S25 Edge's works with no network has not been recorded yet. Test: dictate a note in aeroplane mode. If it fails, speech may be going to Google or Samsung and this document must be corrected.)
- **Location.** Coordinates are stored with the note and used only on the phone.
- **The time of a note and the phone's clock.** The model is never told today's date. Dates you mention ("before Friday") are worked out on the phone.
- **Anything marked Private.** Decided on the phone before any network code runs. See "Private".
- **Pictures.** Never sent to a model, never in a backup that is not encrypted, never in a notification. See "Pictures".
- **Where you went.** Outings are a history of places and times. They are built on the phone from your notes and stay there.
- **Anything at all, if the hosted model is off.** The app has no analytics, no crash reporting, no advertising code and no update check. The only code that opens a network connection is one small file (true of the prototype today: checked by searching the source on 2026-11-11). Card 13 adds a test that fails the build if code outside the named network packages tries, and a register through which every request must declare what kind it is.

## What does leave, and when

Only if you have entered an API key **and** left the hosted model switched on:

1. **When a note is filed:** the note's text; your word list; the names of things you have mentioned before (for example "pool", "pH", "Frank") and the short titles of your open tasks, so that the model reuses the same names and can tell when you have finished something. Those lists are built only from notes that are not private.
2. **When you ask a question the app cannot answer by itself:** the question, and short excerpts of the notes the app looked up for it, each with its age in words ("9 days ago"). Never private notes; the phone adds those to the answer itself.

Things that go along with any internet request and that this app cannot prevent: Anthropic's servers see **when the request arrived** and **the IP address** it came from, which usually reveals your internet provider and rough area. The request is tied to your API key, and the key to the account you paid with. So Anthropic can know that *your account* sent *this text* at *this moment*. It is not anonymous.

3. **If you start a watch** (optional, off by default): the few words shown on that watch ("Saves the Day", "Phoenix, AZ"), to the service named on it, on its schedule. See "Watches".
4. **If you turn on product lookup** (optional, off by default): a barcode number, to a public product database.
5. **If you turn on "send the words in a picture to the model"** (optional, off by default): the printed text the phone read from a picture, along with that note. Never the picture.

### What Anthropic does with it

Checked against Anthropic's own pages on 2026-11-11. An API key from the Anthropic Console falls under the **Commercial Terms of Service**, not the terms for the Claude chat apps.

| Point | What Anthropic's page says | Source | Confidence |
| --- | --- | --- | --- |
| Training | "Anthropic may not train models on Customer Content from Services." | [Commercial Terms](https://www.anthropic.com/legal/commercial-terms), section B, effective 17 June 2025 | High: read directly |
| Training, in the privacy centre | "By default, we will not use your inputs or outputs from our commercial products" to train models. Exceptions: if you explicitly send feedback or a bug report, or otherwise choose to allow it. | [Is my data used for model training?](https://privacy.claude.com/en/articles/7996868-is-my-data-used-for-model-training), dated 18 August 2026 | High: read directly |
| How long it is kept | "we automatically delete inputs and outputs on our backend within 30 days of receipt or generation", except: services you control with longer retention (the Files API; not used here), where agreed otherwise, where needed to enforce the usage policy, or to comply with the law. | [How long do you store my organization's data?](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-my-organization-s-data), updated 1 July 2026 | High: read directly |
| If a request is flagged as breaking the usage policy | Inputs and outputs may be kept "for up to 2 years" and safety classification scores "for up to 7 years". | Same page | High |
| Zero retention | A "zero data retention" arrangement exists, under which prompts are not stored after the reply. It is arranged through Anthropic's sales team for an organisation. | [API and data retention](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention) | Medium: read by a research helper. Whether one individual can get it could not be established; assume not. |
| The output format the app uses | The JSON schema (the *shape* of the answer, which holds nothing personal) is cached for up to 24 hours. Prompts and replies are not stored by that feature. | Same page | Medium: helper |
| Where it is processed | Anthropic's listed infrastructure providers include Google Cloud and Amazon Web Services. | Anthropic's trust centre | Low: the list could not be opened directly |

What this adds up to, plainly:

- Anthropic says it will **not train on** your notes and will **delete them within 30 days**. That is a contractual statement by the company, read on the date above. This app cannot verify that it is honoured, and terms can change. Re-read the three pages when you buy credit and once a year.
- For up to 30 days your note text **exists on someone else's computers**. It could be disclosed by a breach there or demanded by a court. That risk is small and it is not zero.
- An automated safety system reads requests. If it flags one, that text can be kept for up to two years. A note about pool chlorine will not be flagged; this is mentioned because it is in the terms.
- One uncertainty: the Commercial Terms say they "are not for consumer use" and describe the customer as "you or the organization … that you represent". An individual with a Console account and a key is using the commercial service, and the no-training rule is written for that service. That reading is confident but it is a reading: the page does not address a private person's hobby app in so many words.
- A different thing with a similar name: the Claude *chat apps* (Free, Pro, Max) have their own rules, under which you choose whether chats may be used for training. Those rules do not apply to an API key. Do not paste notes into the chat app expecting the API's terms.

**If that is not acceptable for a note, say "private note" first. If it is not acceptable at all, leave the hosted model off.** The app is built so that "off" costs convenience, not function.

## Private

One mark, for anything: health, money, a gate code, an intimate picture, something about another person.

A note, picture or scanned code marked Private:

- is **never sent anywhere**: not to a model, not as part of a watch, a lookup or a diagnostic report;
- needs **your fingerprint or PIN again** to open, even if you unlocked the app a moment ago;
- is **left out** of reminders (it is not even counted), suggestions, the preview that appears as you type a search, answers read aloud, and the list of things the app was unsure about, until you unlock your private items. A search says "1 private note also matches" and nothing more.

How it gets marked: you say "private note" first, or "this is private"; you tap the Private switch on a note; or the app turns it on by itself for things that are secrets by their shape (a password, a PIN, a long number, a scanned boarding pass, ticket, Wi-Fi or sign-in code). You can turn that off. For softer things, such as a blood-pressure reading or a bank balance, the app only asks once whether you want it private.

Two honest costs. **A private to-do will not remind you.** And a private note is sorted only by the phone's simple rules, so it is filed less cleverly.

What cannot be promised: the automatic marking looks for words and shapes. It will miss a secret said in an unusual way, and a ticket in a format it does not know. When in doubt, say "private note".

*(In the prototype there is a `localOnly` flag that nothing sets. This mark replaces it.)*

## Pictures

- **Where they live.** A picture you take inside the app, or share into it, is stored as the app's own file in the app's private storage. It is **not** in your gallery, so the gallery's cloud backup does not pick it up, and other apps cannot open it. It is excluded from Android's own backup and phone-to-phone transfer. If you shared it in from the gallery, the original is still in the gallery, and wherever your gallery backs up to.
- **How they are protected.** Each file is encrypted with its own key. The keys are in the app's database, so pictures are exactly as protected as your notes: today that means the phone's own storage encryption; after card 14, the database's as well.
- **Where they never appear.** Notifications, the lock screen, the recent-apps view, widgets, diagnostic reports, logs.
- **Reading the words in them.** The phone reads printed text in a picture (a label, a poster) so you can search for it. This happens on the phone; the picture is not sent to Google or anyone. **But** the library that does it is Google's (ML Kit), and Google's terms say it sends Google measurements of how the library is used and how it performs. That is not your picture or its text. It is the one thing in this app that leaves the phone without your switching it on each time, and the app cannot turn it off except by not reading pictures at all (there is a switch for that).
- **The app does not judge pictures.** There is no detector for explicit or any other kind of content. Such detectors get it wrong in both directions, and a wrong label on a family or medical picture is worse than none. The protections above apply to every picture equally. Mark one Private for the second unlock.
- **Sending a picture to a model is not built.** If it ever is, it will ask for each picture, say who receives it and for how long they keep it, and it will never be offered for a Private picture. A caution that would apply then: model providers' rules commonly forbid or refuse sexual content, so sending such a picture could break those rules and put your account with them at risk.
- **Deleting.** Deleting a note deletes its pictures: the files, not just the links.

**About illegal images.** This app contains nothing that shares, uploads or processes pictures for anyone else: there is no sharing feature, no server, and no picture leaves the phone. Sexual images of children are illegal to possess whatever app or device holds them, and nothing about this design changes that. If this app is ever offered to other people, its handling of private pictures needs a lawyer's review and a check against app-store policy first. Nothing in this document is legal advice.

## Recordings of voice notes

*(Added 2026-11-12. As designed in cards 26a to 26l; not built yet. Decisions D77 and D82.)*

- **Why they are kept.** Speech recognition makes mistakes, especially with names, brands and prices, and a wrong word otherwise becomes "the fact". With the recording you can hear what you said, fix the words, and have the note written out again.
- **Where they live.** Each recording is a file of the app's own in the app's private storage, beside the pictures and protected the same way: its own key, kept in the database; not visible to other apps, to a music or recorder app, or to a file manager; excluded from Android's own backup and phone-to-phone transfer. About 170 KB for a minute of speech (measured).
- **Who writes them out.** A speech model inside the app (Moonshine, an open model), on the phone. Nothing is sent to Google, Samsung or anyone. Measured on your voice, it is about as accurate as the phone's own recogniser, not better; what it adds is that it writes out the whole note, pauses included, and can be run again.
- **Where they never appear.** Notifications, the lock screen, the recent-apps view, widgets, answers read aloud, diagnostic reports, logs, exports. A recording plays only inside the app, after the app's unlock, and stops when you leave the app or the screen turns off. There is no player notification and nothing on a car or watch display.
- **Never sent.** Not to a model, not with a watch or a lookup, not anywhere. The words written out from a recording are note text like any other and follow the rules for note text: sent to a hosted model only if you turned that on, never if the note is Private.
- **Private.** A recording follows its note. On a Private note it needs the second unlock to play.
- **How long.** Until you delete it. Deleting a note deletes its recording (after the same 30 days in the bin). You can also remove the recording from one note and keep the words, or delete many recordings at once and keep all the words; those removals are immediate and cannot be undone. Nothing is deleted automatically.
- **Turning it off.** One switch. With it off, voice notes are taken as before: the phone's recogniser listens and no sound is kept.
- **The speech model** is a file of about 141 MB that is put on the phone once. As designed for your phone, the app does not download it; if a later version does, that download will have its own switch and its own line on "What leaves my phone".

Two honest limits. **A recording is the most personal thing the app holds**: it is your voice, and it may catch another voice or a sound in the room. And for the seconds between saving a note and its words being written out, the app cannot yet know that a note began "private note …"; nothing can be sent in that time, but the note is not yet behind the second unlock. Press the Private button on the capture screen if that matters.

## Scanned codes

A barcode or QR code is read on the phone (the same Google library and the same usage-figures caveat as above) and kept as the text it contains.

- **The app never acts on a code.** A link is shown in full and is opened only if you tap, and then tap again to confirm the address. Wi-Fi, payment and sign-in codes are stored as text; the app never joins, pays or adds anything. This matters because QR codes are a known way of sending people to fake sites.
- **Codes about you** (boarding passes, tickets, loyalty cards) contain your name and booking or account numbers in readable form. So do Wi-Fi codes (the password) and sign-in set-up codes (the secret). The app marks these Private by default. Its recognition of them is best-effort.
- **Looking up a product** from its barcode means sending the number to a public product database, which then knows, together with your internet address, what you were looking at. It is off unless you turn it on, it asks the first time, and it is never done for a Private note. Naming the product yourself usually makes it unnecessary.

## Outings, and where you were

- Each note stores where the phone was when you made it, if you allow location. The app uses the position the phone already has; it does not follow you between notes.
- Outings are put together from those notes: when you were first and last out, where you stopped. That is a **history of your movements**, incomplete but real. It is stored on the phone only, is never sent, and reaches a model only as place names you said yourself and ages in words.
- Optional signals (your car's Bluetooth connecting, your home Wi-Fi dropping) can start an outing without a note. Each is a separate switch, off until you turn it on, and uses the signal on the phone only. Telling Wi-Fi networks apart needs the Location permission; that is Android's rule.
- Stops are named from places you saved or words you spoke. The app never sends coordinates to a service to get a name.
- The leaving list ("wallet, keys") shows its items on the lock screen if you allow it. They are labels you typed, not notes. Anyone who sees your locked phone at that moment sees them.

## Watches

A watch is the app checking something on the web for you, on a schedule: "is this band playing near this city?".

- **It is a different kind of sending.** A note's text goes out once, to one company, to be filed. A watch sends **what you are interested in and a place**, again and again, while the phone sits idle. The company that receives it sees those words, your internet address, and the account the key belongs to.
- **Nothing is watched unless you approve it.** A note can only suggest a watch. You see the exact words that will be sent, to whom, and how often, and you start it or not. A Private note never suggests one.
- **The place in a watch is one you said**, such as a city's name. The app never uses the phone's position for it.
- **It only reads.** It cannot sign in, buy or book.
- **You can see and stop all of it** on one screen: every watch, every run, what was sent each time, what it cost.
- **Limits are built in:** ten watches, no more often than weekly, twenty checks a month, a spending ceiling, and each watch ends after a year unless you renew it.
- **Who receives it.** For concerts, an events-listing company (Ticketmaster), using a free key in your name. For anything else, your model provider, whose model runs a web search: then that provider and its search supplier see the query. What those companies keep, and for how long, is theirs to say; apart from Anthropic's terms above, no retention statement for such queries was found.
- **It can be wrong both ways.** It may alert you to a tribute act, and it may miss a show at a venue the listing does not cover. "Nothing found" means "nothing found in that source".

## A choice of model provider

The app is built so the model can come from different companies, each with your own key. Only Anthropic's is built now. Their positions as read on 2026-11-11 (details, sources and how sure in [PROVIDERS.md](PROVIDERS.md)):

| Provider | Uses what you send to train? | Keeps it | Note |
| --- | --- | --- | --- |
| Anthropic | No | up to 30 days; longer if flagged | Read directly; see above |
| OpenAI | No | abuse logs up to 30 days | Their page, read by a helper |
| Google Gemini, **paid** | No | "a limited period", length not stated | |
| Google Gemini, **free plan** | **Yes, and people may read it** | not stated | Do not use the free plan for personal notes |
| xAI Grok | Not without your permission | 30 days | |
| Microsoft Azure OpenAI | No; not shared with OpenAI | not stated on the page read | Needs an Azure subscription |

A chat subscription (Claude Pro, ChatGPT Plus, Google AI Pro, SuperGrok, Copilot) does **not** cover this kind of use at any of them, and its privacy rules are different from the API's. Whichever provider you choose sees the same things Anthropic would: the text, when it arrived, your internet address, your account. These are companies' statements read on one day; the app shows the date each was last checked.

## If something goes wrong: diagnostics

There is no crash reporting and no usage reporting, and there will not be. The app keeps a log on the phone of **events and codes** (for example "extraction failed, reason 3, 412 ms"); the logging code has no way to record a word of a note.

Under any answer, "Why this answer?" shows the steps the app took, from data on the phone.

If the app is ever supported by someone else, a **diagnostic report** is something *you* make: settings, counts, timings, error codes and recent steps, with every word from your notes replaced by a label ("Item 2", "Place 3") invented for that one report. No pictures, nothing Private, no coordinates. You read it before it goes anywhere, and you send the file yourself. The list that turns labels back into words stays on your phone.

What cannot be promised about such a report: **it is not anonymous.** It is about one person, and someone who knows you, or an unusual pattern in it, could tell it is you. The app will say "contains none of your words", which is true, and will not say "anonymous", which would not be.

An idea that was considered and not used: storing your words as random codes with a separate dictionary. On one phone the dictionary sits beside the data, so it protects nothing that encryption does not; and codes do not hide the dates, prices and places that identify a person. The labelled report above is that idea put where it works.

## Why on the phone, and not in a cloud database

The honest case for local storage:

1. **No single place to breach.** A cloud notes service is one target holding everyone's notes. Your phone holds one person's, and an attacker has to get to that phone.
2. **No one to serve with a demand.** A company holding your notes can be ordered to hand them over, or can change its mind about what it does with them. Notes that are only on your phone can be demanded only from you.
3. **No account to take over.** No password to phish, no "forgot my password" route for someone else to use.
4. **No lock-in, and no shutdown.** Several recording gadgets and note services have been sold or closed within a couple of years of launch, taking their users' data paths with them. A file on your phone plus an export in ordinary formats outlives any company, including this project.
5. **You can see it and delete it.** "Delete everything" removes the only copy (and you delete your own backups).

The honest costs:

1. **Lose the phone, lose the notes**, unless you made a backup and kept the recovery code. Nobody can reset it for you.
2. **No sync.** One phone. A second device would not see the notes.
3. **Our encryption could have a bug.** It is built from well-known libraries (SQLCipher, Android Keystore, Google's Tink), but it is assembled by one developer and has not been reviewed by anyone independent. A big provider's security team is better resourced than this project.
4. **The protection is only as good as the phone's lock.** A weak PIN, or a phone handed over unlocked, undoes much of it.
5. **You are the administrator.** Backups, the recovery code, updates: all yours to do.

Local storage does not make the notes safe. It makes you the only one responsible for them, and removes the companies from the list of people who could lose or misuse them.

## How the notes are protected on the phone (as designed), and where that stops

- **Android encrypts the phone's storage** by itself. With the phone switched off, or restarted and not yet unlocked, the data is unreadable without your PIN.
- **The app encrypts its database again** (SQLCipher, AES-256; from card 14, when the app stops being a development build), with a random key kept in the phone's secure hardware. The key cannot be copied off the phone, so a copy of the database file taken elsewhere is useless.
- **The app asks for your fingerprint or PIN** before showing, searching or reading out any stored note, even if the phone is already unlocked, with a short grace period so it is not tedious. Screens that show notes cannot be screenshotted and are blank in the recent-apps view.
- **Reminders on the lock screen show a count, not the text.**

**The known weakness, stated plainly:** you can add a note while the phone is locked. For that to work, the app must be able to write to its database while locked, which means the database key must work without your fingerprint. So "reading needs your unlock" is a rule the *app* enforces, not something the encryption enforces. Against a person holding your phone, that is enough: they cannot get past the app's lock. Against someone who can run their own code *as the app* on your phone (which takes a serious, targeted attack or a compromised phone), it is not. For the same reason, the second layer of encryption adds less than it sounds like: it mostly protects against the database file being copied off the phone. The fingerprint gate is what protects you day to day.

There is a designed fix (a "sealed inbox": notes added while locked go into a one-way box that only your unlock can open). It is not scheduled; revision 2 builds the three small things it depends on into earlier cards so that it stays a few days' work ([STORAGE.md](STORAGE.md)). Until it is built, do not rely on this app to keep secrets from an attacker of that kind.

## Is there something better than "only on the phone"?

Not a cloud database. But local-only has one real gap, the lost phone, and there is a better arrangement that closes it without giving anyone your notes:

**Local, plus an encrypted backup file in a place you control.** The app writes one file, encrypted on the phone with a key that exists only as a recovery code you print or put in a password manager. The file can then sit anywhere: a folder that a sync tool copies to a computer or server at home, or a cloud drive. Whoever holds the file sees its size and date and nothing else. This is "end-to-end encrypted" in the proper sense: only the two ends, your old phone and your new one, can read it. It is card 10.

Two cautions. The recovery code is now the thing to protect and not to lose: with the code and the file, anyone can read everything; without the code, no one can, including you. And the sync tool is your choice and your upkeep: the official Syncthing app for Android was discontinued in 2024 and the community "Syncthing-Fork" has changed maintainers since (as reported; check before relying on it). A cloud-drive folder is the least effort and, because the file is encrypted before it leaves, a reasonable choice.

Live syncing between devices with end-to-end encryption (as some note apps offer) would be better still for someone with two devices. It needs a server or a service and a much larger design. It is not planned.

## Other people in your notes

"I loaned my guitar to Frank" is information about Frank. It is the same as writing it in a paper notebook, with two differences worth knowing: if the hosted model is on, Frank's name and the sentence go to Anthropic under the terms above; and a notebook that listens makes it easy to record more about people than you would bother to write. The app's rules: it never records anyone but you, never listens unless you start it, and never shares. Yours to follow: do not dictate other people's health, money or secrets into it; say "private note" when a note is mainly about someone else. A way to find and delete everything mentioning one person is planned (FEATURES.md).

## Threats, in one table

| What could happen | How likely | What protects you | What does not |
| --- | --- | --- | --- |
| Someone picks up your locked phone | Common | They can add a note, not read one. Lock-screen reminders show only a count. | A weak phone PIN |
| You hand someone your unlocked phone | Common | The app's own fingerprint or PIN gate; no screenshots; blank in recent apps | The 2-minute grace period just after you used it; the prototype has no gate yet |
| The phone is lost or stolen | Occasional | Phone encryption, database encryption, the app gate. Notes are recoverable from your encrypted backup. | No backup made; recovery code lost |
| Another app tries to read the notes | Possible | Android keeps each app's storage private; the app offers other apps nothing | A phone that has been rooted or compromised |
| Anthropic is breached, or is ordered to hand over data | Unlikely for any one person | Only non-private note text was ever sent; it is deleted within 30 days under their terms; private notes and all locations were never there | The last 30 days of non-private text; anything flagged |
| Anthropic changes its terms | Possible over years | The hosted model can be switched off; another provider or an on-phone model can replace it; re-read the terms yearly | Text already sent |
| Your API key is stolen | Unlikely | It is encrypted on the phone; a monthly limit in Anthropic's console caps the cost; you can revoke it | Notes are not exposed by the key; only money |
| Someone reads the backup file | Depends where you keep it | It is encrypted with a key only you hold | The file and the recovery code kept in the same place |
| A targeted attacker runs code on your unlocked-since-boot phone | Rare | Nothing in this app | This is out of scope and said so |
| A bug in this app exposes notes | Possible | Small code, tests on the privacy rules, no network code outside one package | No independent review |
| Someone glances at a notification | Common | Counts and generic text only; nothing from a Private item or a picture | The leaving list, if you chose to show it |
| Someone scrolling your gallery, or your photo backup, sees a picture you gave the app | Would be common | Pictures taken in the app never go to the gallery | A picture you shared in from the gallery is still there |
| Someone plays back a voice note *(once recordings are kept)* | Possible, by someone holding the unlocked phone | The app's own fingerprint or PIN gate; a second unlock for Private notes; recordings are not files any other app can open; playing stops when the app is left | The prototype has no gate yet; the speaker, if you play one in company |
| A scanned QR code leads somewhere harmful | Possible | The app shows the address and opens nothing by itself | Tapping through anyway |
| A watch tells a company what you care about | Certain, if you start one | You approved the exact words; caps; one list; off by default | The query itself, once sent |
| A diagnostic report identifies you | Possible | You make it, read it, and choose who gets it; no words, pictures or Private items | It is still about one person |
| You lose the notes by reinstalling during development | Possible today | A fixed signing key (card 01); then the encrypted backup | An uninstall before either exists |
| The project is abandoned | Possible | Export to plain files (text, spreadsheet, JSON); the data is yours on your phone | Updates stop |

## What cannot be promised

- That Anthropic does what its terms say. They are quoted with their dates; they are a company's statement.
- That the phone's speech recogniser never uses the network, until the aeroplane-mode test is recorded.
- That the encryption has no flaw. It has not been independently reviewed.
- That a determined, skilled attacker with your phone in hand cannot get in.
- That a recording is written out correctly. It is about as good as the phone's own recogniser; the recording is there so you can check.
- That a recording catches only your voice.
- That a picture's printed words are read correctly, or at all.
- That the app recognises every personal code, or every secret said aloud.
- That a watch finds everything, or only real matches.
- That another provider's terms are as summarised here; they were read on one day, some at second hand.
- That Google's text and barcode libraries send Google only what its terms say.
- That nothing sensitive will ever be sent by mistake. The private-note check looks for words like "password" and "code"; it will miss a secret said in an unusual way. When in doubt, say "private note".

## How to check these claims yourself

- **Nothing is sent with the model off:** switch it off, put the phone in aeroplane mode, and use the app for a day. Everything except the hosted filing works.
- **Only one part of the app can use the network:** search the source for `java.net`, `HttpURLConnection` and `okhttp`; today the only hit is `app/stash/model/hosted/Anthropic.kt`. From card 13 a test in the suite does this search on every build.
- **Speech is on the phone:** aeroplane mode, dictate a note.
- **The database is encrypted** (after card 14): `tools/peek.py` can no longer open the raw file; card 14's acceptance check records this.
- **What can leave right now:** Settings → What leaves my phone. Every row is something the code is able to send; a request that is not on the list is refused by the code that sends requests.
- **Recordings stay on the phone** (once they exist): aeroplane mode, record a note, and it is still written out and still plays. Then look for it in your recorder app, music app and file manager: it is not there.
- **Pictures are not in the gallery:** take one in the app, then look for it in the gallery and in your photo backup.
- **A private note stays hidden:** mark one, then search for a word in it without the second unlock.
- **Anthropic's terms:** the three links above. Read the dates at the top of each.
