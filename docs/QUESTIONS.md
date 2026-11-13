# Questions only the owner can answer

Revisions 2 and 3, 2026-11-11. **Start with the three under "This week".** Then revision 2 and 3's questions (Q30 onward), then revision 1's (Q17–Q29), each with a recommendation, what it blocks, and what happens if it is left unanswered. The first design's questions follow, each marked **ANSWERED**, **DEFERRED**, **OBSOLETE**, **REVISED** or **STILL OPEN**; their original text is unchanged. Write an answer under a question and date it.


## This week (they unblock everything else)

1. **Q29**, the owner test script: ten minutes; three results the documents depend on.
2. **Q38**, a signing key that does not change: fifteen minutes; removes the likeliest way to lose every note.
3. **Q17**, $5 of API credit: lets the two-hour desk spike measure the central risk before any app code.

## New in revisions 2 and 3: needed soon

### Q37. May database encryption wait until the app becomes a release build?

The recommendation in STORAGE.md: keep the database readable while the build is a debug build (which exposes its own data anyway), prove in an hour that encryption drops in (card 15), then do backup, the app gate and encryption together before you rely on the app. Revision 1 had encryption second in the order.

- **Recommendation:** yes. Keep truly sensitive things out until then, as PRIVACY.md already asks.
- **Blocks:** the order of cards 02, 10, 14. Unanswered → the new order is assumed.

### Q38. Will you create a project signing key now?

Today the app is signed with this PC's automatic debug key. A build from another PC, or a later release build signed differently, cannot be installed over it without an uninstall, and an uninstall deletes every note. A project keystore used for every build removes that. Changing to it needs one uninstall now, while the notes are few.

- **Recommendation:** yes, as the first step of card 01. Keep the keystore file and its passwords in your password manager. Today, before anything else, copy `.android\debug.keystore` from your user folder somewhere safe.
- **Also decide:** keep the handful of test notes (the builder copies the database off and back) or start clean?
- **Blocks:** card 01.

### Q39. Is this likely to become a product, and is the name settled?

(Sharpens Q9.) The application id `app.stash` cannot change without a fresh install. Product thinking also decides how much of cards 20, 22 and 23 is worth building.

- **Recommendation:** decide the name before card 14 (the release build). Assume "maybe a product": the plan already protects that (STORAGE.md's rules, capability switches, the answer trace) without building for it.

### Q40. The stop after cards 05–08

The plan now has a hard stop: two weeks of real use and a written answer to "am I still speaking to it daily, and did it tell me something I had forgotten?" before pictures, trips, codes or watches are built.

- **Recommendation:** agree to it. It is the cheapest protection against building a lot on something you do not use.

## New in revisions 2 and 3: preferences

### Q30. Should the app ask "Track this trip?" each time you leave?

Your original idea. The design does not ask: a trip is put together from the notes you make while out, or starts quietly on a signal, and is thrown away if it comes to nothing. A question at every departure would be the most frequent notification the app sends.

- **Recommendation:** do not ask. If you find you want a say, the choices are a setting: never ask / ask / never track.

### Q31. Where is home, and which cars?

Trips need a home position (one tap at home). Car Bluetooth as a departure signal needs you to tick your cars in a list of paired devices and name them.

- **Recommendation:** set home when card 17 is installed. Try car Bluetooth for a week when card 18 reaches stage 2, with a paper log of when you actually left.

### Q32. May the leaving list show its items on the lock screen?

"Wallet, keys, reading glasses" is only useful if you can read it at the door without unlocking. They are labels you typed for that purpose, not notes. Task titles would still never show.

- **Recommendation:** yes for list items, with a switch to hide them.

### Q33. What goes on the leaving list?

Always: ? With the Tesla: ? For Costco: ?

- **Recommendation:** write three to five items when card 18 arrives; fewer is better.

### Q34. Pictures: is sharing in, plus a camera inside the app, enough?

Those two need no access to your photo library. A third option, "offer my latest screenshot when I start a note", needs permission to see all your photos.

- **Recommendation:** build the first two. Turn the third on for a fortnight during development to learn whether you miss it when it is off.

### Q35. May the printed words read from a picture be sent to the model?

Not the picture; the text the phone read from it. It lets the model fill in "Valpolicella Classico, Allegrini" without your reading the label aloud. A screenshot's text can include other people's names and words.

- **Recommendation:** off at first. Reading the name aloud costs three seconds; decide after a month of pictures.

### Q36. Watches: is it acceptable to send "a band and a city" to a third party once a month, and to which?

For concerts, Ticketmaster's listing (free key; sees the band and city; covers only its own venues). For anything else, Anthropic's model with web search (a few cents a check). Either way a company learns the interest and the place.

- **Recommendation:** yes for specific things you ask for, never automatically. Start with the listing engine. Built last; no decision needed until then.

### Q41. A private to-do will not remind you. Acceptable?

Private means left out of every notification, even the count. A to-do in a private note therefore appears only when you open your private notes.

- **Recommendation:** accept it; it is what makes the mark trustworthy. The alternative is "count it, never name it", a small leak for a real convenience: say if you would prefer that.

### Q42. Should health and money notes be private automatically, or only suggested?

The app turns Private on by itself only for things that are secrets by their form (passwords, codes, boarding passes). For a blood-pressure reading or a bank balance it asks once, quietly.

- **Recommendation:** suggested. An automatic rule would hide ordinary notes and stop their reminders.

### Q43. Do you want to accept "Add a picture?" by saying "yes"?

The design offers a button only. Listening for "yes" means reopening the microphone after such notes and guessing.

- **Recommendation:** not at first.

### Q44. Which model provider, and will anyone else's be needed?

Claude is yours and is the only one built. A second adapter (card 22) would cover OpenAI, Grok, Azure and Gemini's compatibility endpoint in one go. Each added provider is another set of terms to keep current.

- **Recommendation:** Claude only until the app has proved itself. If someone you want to show it to insists on another, build card 22 then.

### Q45. Should a "phone only" user be shown "needs a model" on unsorted notes?

It is honest, and it can read as a sales pitch.

- **Recommendation:** yes, in small type, with a setting "I don't use a model: stop mentioning it". Decide after your own day with the model off (RISKS.md R22).

### Q46. Google's text and barcode reading libraries report usage figures to Google. Acceptable?

The pictures and codes are processed on the phone and not sent. The libraries do send Google performance and usage measurements, which the app cannot switch off. It is the one thing that leaves the phone without your choosing it each time, and it is stated in PRIVACY.md.

- **Recommendation:** accept for development. For a product whose promise is "nothing leaves", evaluate open alternatives (a bundled open decoder for codes exists; text recognition has no equally good one).

### Q47. "Google Argon": what did you mean?

You listed it among providers. A search found reports of a new flagship Google model announced around 30 September 2026 as "Gemini 4 Argon" (not read at source; medium to low confidence). If that is it, it is Google's large, expensive model, not what this app would use for filing; "Google's Gemini models" is how the documents treat it.

- **Recommendation:** confirm. Nothing depends on the answer unless you meant something else.

### Q48. If this becomes a product: a lawyer and the store rules

Private pictures, including adult ones, stay on the phone by design, and the app has no way to share or upload them. Before any release to other people, a lawyer should look at that design and at app-store policies on user content. This document is not legal advice.

- **Recommendation:** note it against the product decision (Q39). Nothing to do now.

## New in revision 1: needed soon

### Q17. Will you buy $5 of Anthropic API credit?

The new scope depends on a model to file free speech; the offline rules will understand only a minority of what you say (everything is still stored and searchable). The hosted model costs roughly 17 to 65 cents a month at your likely use (estimate; card 04 measures it). Your Claude subscription does not include API use. The smallest credit purchase has been $5, which would last a year or more. This is the only spend the design asks for.

- **Recommendation:** yes. Create the key in the Anthropic Console, set a monthly limit of $2 there, and read the three terms pages linked in PRIVACY.md on the day.
- **Blocks:** card 04 (including its desk spike, which should be the first thing done) and the risk test R11. Unanswered → cards 01, 03, 13 and 05–07 are built on the rules extractor and a fake model; the app works at tier 0.

### Q18. When should "You have things you wanted to do" arrive, and when must the phone stay quiet?

- **Recommendation:** daily digest at 08:30, only on days with something to say; weekly review Sunday at 17:00; quiet from 21:00 to 08:00. All three are settings.
- **Blocks:** nothing; defaults in card 06.

### Q19. May a reminder show its text on your lock screen?

Default is a count only ("You have 3 things you wanted to do"); the titles appear after unlock. Showing the first title is more useful and visible to anyone who glances at your phone.

- **Recommendation:** count only. Try it for two weeks before relaxing it.
- **Blocks:** nothing; a setting in card 06.

### Q20. Are you content for the names of things you have mentioned, and the titles of your open tasks, to be sent along with each note?

To keep names consistent ("the car" stays one thing) and to let "I cleaned out the car" close the right task, each filing request carries your word list, up to about 60 names of things and measurements from earlier notes, and up to about 30 open task titles. All of it comes from notes that are not private. Without the task titles, closing a task by speaking falls back to simple word matching and will more often ask you instead.

- **Recommendation:** yes. It is the same kind of text as the notes themselves, under the same terms.
- **Blocks:** card 04's request builder. Unanswered → assumed yes; a setting can turn the lists off.

### Q21. What is off limits for the hosted model?

Private notes are never sent, and the app marks a note private when you say "private note" first or when it spots a password-like word. Beyond that, are there subjects you want kept on the phone always, even though the filing will be cruder? Candidates: anything health-related, anything naming family members, money.

- **Recommendation:** start with the defaults. Add "always private" words in Settings as you notice things you would rather not send (card 13 makes the word list editable). *(Revision 3: Private now also means a second unlock and no reminders; see Q41, Q42.)*
- **Blocks:** nothing.

### Q22. How cautious should the app be about closing a task when you say you did it?

Choices: close when confident and show it under "Done this week" (default); always ask first in the attention list; close on any reasonable match.

- **Recommendation:** the default. A wrongly closed task is silent, so the bar is high, and you will see what was closed in the weekly review.
- **Blocks:** nothing; a setting in card 05.

### Q23. May the developer read your real notes to measure how well the app files them?

Quality can only be measured on what you actually say. Once a week for the first month, the newest 30 notes and what the app made of them would be pulled to the PC over USB and marked right or wrong. They stay in a folder that git ignores and are never committed.

- **Recommendation:** yes, and keep anything truly private out of the app until the hardening block is done (PRIVACY.md).
- **Blocks:** the real-notes half of card 04's measurement. Unanswered → invented sentences only, which will flatter the model.

### Q24. Where should the encrypted backup go?

(Replaces Q7 for one phone.) The app writes one encrypted file to a folder you pick. Options: a cloud-drive folder (least effort; the drive sees only an encrypted file); a local folder copied home by a sync app (the official Syncthing app for Android was discontinued; the community fork has changed maintainers, so check its state first); a local folder only (protects against a broken app, not a lost phone).

- **Recommendation:** a cloud-drive folder, with the recovery code in your password manager and on paper.
- **Blocks:** nothing to build; a choice when card 10 is set up.

## New in revision 1: preferences

### Q25. Do you want a daily nudge at all, or only a weekly review plus reminders for dated things?

- **Recommendation:** start with the daily digest. It switches itself off if you ignore it five days running, and says so.

### Q26. Do you want reminders at a time of day ("at 3 pm")?

The design reminds by day, not by clock time, and leaves alarms to the phone's clock and calendar.

- **Recommendation:** not yet. Notice whether you say such things; if you do, it is a small addition to card 06.

### Q27. Should the phone read a pool test back to you after saving?

It catches a mis-heard number without looking; it also speaks aloud whatever you just said.

- **Recommendation:** try the spike in RISKS.md R12 first (dictate the pool sentence ten times). If numbers come out wrong more than once in ten, turn read-back on for notes that contain readings.

### Q28. Is a debug build on your phone acceptable for now?

A debug build lets a computer you have authorised for USB debugging read the app's files, which is how the development tools inspect notes. A release build closes that and makes the tools blind.

- **Recommendation:** stay on the debug build until card 14, which makes the switch safely (same signing key, backup first). *(Revision 2: switching builds with a different signing key would force an uninstall and lose the notes; Q38.)*

### Q29. The owner test script results

README's "Owner test script" has not been written up in `docs/EXPERIMENT_LOG.md`. Three results matter to the documents: does the notification's "Add a note" open from the locked screen without a PIN; does the tile; and does dictation work in aeroplane mode (is speech really on the phone)?

- **Recommendation:** ten minutes with the script; fill in the "Prototype build" table. PRIVACY.md's claim about speech depends on the third.

## From the first design

### (first design) Blocking early work

#### Q1. [DEFERRED: the assistant role is not being built now; the notification and tile are the triggers] May the app replace Gemini as the "digital assistant" on each person's phone?

Being the default assistant is the only one-press hardware trigger Android documents for a third-party app with microphone access from a locked phone (long-press of the side or power key, and a headset's assistant gesture). It means that gesture, and probably "Hey Google", no longer open Gemini on that phone. Gemini stays installed as an app.

- **Recommendation:** yes for the owner (try it during spike 01 and see what you miss); ask your wife; **no for your parents** unless they never use Gemini by voice, since they have the cover-screen widget and the notification button instead.
- **Blocks:** card 16 only. Unanswered → card 16 is skipped; other triggers are built.

#### Q2. [ANSWERED by assumption: English only, one user] Which languages do the three households speak to their phones?

English-only allows smaller, more accurate speech models and makes the offline rules work for everyone.

- **Recommendation:** English only for version 1.
- **Blocks:** the model shortlist in spike 03. Unanswered → English assumed.

#### Q3. [REVISED: one key for the owner, see Q17; per-person keys deferred with the family] How do the three households get API keys for the hosted model, and do your parents get one at all?

Your Claude subscription does not cover API use. Estimated cost at occasional use is about ten cents a month per person on Claude Haiku 5.5 (to be measured in spike 04). The app works without any key (offline rules and coded answers).

- **Options:** (a) one Anthropic Console organisation in your name, a workspace and key per person, each with a spend cap of $1–2 a month; (b) each household opens its own account and buys $5 of credit; (c) one shared key; (d) no hosted model for some or all.
- **Recommendation:** (a), and start your parents on (d): give them a key only if their notes turn out to need it. A stolen phone can then cost at most the cap, and you can revoke one key without touching the others.
- **Also needed from you:** one key for spike 04.
- **Blocks:** spike 04, card 21. Unanswered → the app is built key-less through card 20.

#### Q10. [OBSOLETE for now: only the S25 Edge is in scope] What exactly is on the other two phones?

Android version, One UI / Motorola software version, and whether Samsung Good Lock is acceptable on your parents' phone (it may be needed for full apps on the Flip 7 cover screen). The phones table at the top of EXPERIMENT_LOG.md has the columns.

- **Recommendation:** fill in the table before spike 01. Avoid Good Lock on the parents' phone if a widget or notification route works.
- **Blocks:** `minSdk` (35 assumed; 36 if both are on Android 16 or later) and how to read the spike results.

### (first design) Needed before the family uses it

#### Q4. (DEFERRED 2026-11-11: owner will iterate on his own phone first and spend nothing until the idea is proven.) Is a Google Play developer account ($25 once, identity verification) acceptable, to deliver the app to the family through Play's private "internal testing" track?

Samsung's Auto Blocker refuses sideloaded installs unless switched off each time, and Google is phasing in a rule that sideloaded apps must come from a registered developer. Play internal testing avoids both and updates the family phones automatically. The app is not listed publicly; only invited Google accounts can install it. The app's code passes through Google; the notes do not.

- **Recommendation:** yes. Keep `adb` for your own phone. If no: sideload with an in-app update prompt, and register for Google's free limited-distribution developer status before 2027.
- **Blocks:** spike 06, card 29.
- **Owner's answer (2026-11-11):** deferred. Build and use the app on the owner's own phone only (installed with `adb`, no cost). Revisit when the family phones are about to be involved.

#### Q5. [DEFERRED for the parents; the owner gets the 2-minute default in card 02] How strict should the app lock be on your parents' phone?

Choices: ask for fingerprint/PIN every time the app shows notes; allow a 2-minute grace (default); or trust the unlocked phone (no extra prompt once the phone itself is unlocked).

- **Recommendation:** "trust the unlocked phone" for your parents if their phone has a screen lock they reliably use; 2 minutes for you and your wife. The extra prompt protects against an unlocked phone being handed to someone; for your parents, the risk of them giving up on the app is larger.
- **Blocks:** nothing; it is a setting. Set during card 27's "set up for a family member".

#### Q6. [REVISED: the owner keeps his own code; see Q24] Who keeps each person's backup recovery code?

Without the code a backup cannot be opened by anyone. With it, whoever also has the backup file can read every note.

- **Recommendation:** each person's code goes in your password manager and on a printed sheet in their own house. That means you could read your wife's and parents' backups; say so to them. If that is not acceptable, they keep their own and accept that a lost code means lost backups.
- **Blocks:** card 25's set-up flow wording.

#### Q7. [REVISED: see Q24] Where should backups go?

The app writes an encrypted file to any folder you pick with Android's folder picker. Candidates: a local folder replicated by Syncthing-Fork to your Home Assistant box or a NAS; a cloud drive folder (Google Drive, OneDrive), which sees only the encrypted file; a local folder only (protects against a broken app, not a lost phone).

- **Recommendation:** you: Syncthing-Fork to the home server. Wife and parents: the cloud drive already on their phone, because it needs no extra app and works away from home. Card 25 tests whether unattended writes work for each.
- **Would you want a direct "upload to Home Assistant" target?** It needs network code in the app and a receiving add-on. Recommendation: not unless the folder route fails.
- **Blocks:** nothing to build; a choice at set-up.

#### Q8. [DEFERRED: one user] Should notes ever be shared between people?

The brief says private by default. The most-wanted case is probably a shared shopping list between you and your wife.

- **Recommendation:** not in version 1. If wanted later, start with "send this list" through Android's share sheet (a message with the current Costco list), which needs no server and no trust model. True shared lists need a sync service and are a different project.
- **Blocks:** nothing.

### (first design) Preferences

#### Q9. [STILL OPEN: the package is already app.stash on the phone; renaming later means a fresh install and a restore] What is the app called?

Working name "Stash", package `app.stash`. The package name is hard to change after people have data in it.

- **Recommendation:** decide before card 07. Avoid "Recall" (Microsoft's feature of that name has privacy baggage).

#### Q11. [STILL OPEN, still recommended as a benchmark for the "where is it" half] Will you try Google's Find Hub "Remembered" feature for a week?

Since September 2026, "Hey Google, remember in Find Hub that I put my passport in the bedroom drawer" works on Android 16+ with Gemini. It covers item locations only, stores them with your Google account, and has no loans, errands, place reminders, expiry or model choice.

- **Recommendation:** yes, on your phone, while spikes run. It is the convenience benchmark, it will show you which sentences you actually say, and it tells you whether your parents' needs might be met by it alone.

#### Q12. [REVISED: see Q19 and Q27] Two defaults: should reminder text show on the lock screen, and should the phone say back what it saved?

- **Recommendation:** lock-screen text hidden for everyone ("A reminder for Costco"; unlock to read). Spoken read-back on for your parents (it catches mis-hearings without looking), off for you and your wife (it says private things aloud).

#### Q13. [STILL OPEN] Does anyone wear a smartwatch or use earbuds daily?

A watch tile would be the best trigger there is; earbuds with an assistant gesture work with card 16.

- **Recommendation:** list the devices here; a Wear OS tile becomes a "later" card if someone wears a Galaxy Watch.

#### Q14. [STILL OPEN: STORAGE.md now says when and what it needs; revision 2 builds its three seams into cards 13 and 06 so it stays a two-to-three-day job] When, if ever, do you want the sealed inbox (card 30)?

It makes "a locked phone cannot read notes" true at the cryptographic level instead of only in app code, at the cost of the most intricate card in the project.

- **Recommendation:** after you have used the app for a month and before the notes hold anything you would hate a determined attacker to read. Not before the family rollout.

#### Q15. [ANSWERED by revisions 2 and 3: yes; see card 16 and Q34, Q35] Do you want photos attached to notes?

- **Recommendation:** later. It requires unlocking and looking at the phone, which is the opposite of the main flow; add it once the voice flow is a habit.

#### Q16. [REVISED: the terms were read on 2026-11-11 and are quoted in PRIVACY.md; the recommendation stands] Are you content for note text to be processed by Anthropic's API under its standard terms?

By default API inputs are not used for training and are kept for a limited period (check the current terms when you buy credit; this was not re-verified for this design). Private notes and anything with a password-like word are never sent.

- **Recommendation:** yes for ordinary notes, with the hosted model off for anyone who is uneasy. The app is built so that "off" costs little.

## Answered 2026-11-12 (see D80)

- **Q46 (Google's usage reporting in ML Kit):** accepted for development. Revisit for a product.
- **Q35 (send a picture's text to the model):** still open (card 16j waits on it).
- Card 16 split questions: small print may use 2,400 px when registered as small text; removing one picture is immediate; Stash blocks screenshots from card 16d on, with a debug-only toggle that is absent from release builds; ML Kit text reading accepted; Share screen listens automatically, review after about two weeks.
