# Explanation cards, and the "What leaves my phone" screen

Revision 3, 2026-11-11. The wording the app shows wherever the user makes a choice. The design goal, in the owner's words: people are more comfortable when each change shows what it benefits and what it costs.

Built by [card 23](cards/23-explanation-cards.md). The facts behind the wording are in [PRIVACY.md](PRIVACY.md) and [PROVIDERS.md](PROVIDERS.md); if either changes, this page changes with it.

## The shape of a card

Every card has the same five lines, in the same order, so that they can be compared at a glance. A line that does not apply says "Nothing", not blank.

| Line | Answers |
| --- | --- |
| **Title and switch** | What is this, and is it on? |
| **What you get** | One sentence on the benefit |
| **What it sends** | Exactly what leaves the phone, and to whom. "Nothing leaves your phone" when true |
| **If it's off** | What stops working, and what still works |
| **What it costs** | Money, battery, a permission, storage. "Nothing" when true |

Rules for the wording: no "may", "might" or "we" where a plain fact will do; name the company; name the permission as Android names it; never say "secure" or "anonymous". Where a number is an estimate, say "about".

Text in `<angle brackets>` is filled in live.

## The cards

### Voice notes

- **What you get:** Speak a note instead of typing it, from the lock screen too.
- **What it sends:** Nothing leaves your phone. *(When "Keep the recording" is off or has no speech model:)* Your voice is turned into words by the phone's own speech recogniser and the sound is not kept. *(When it is on:)* Your voice is recorded and turned into words by Stash itself, on the phone; see "Keep the recording". *(Shown only until the aeroplane-mode test in the experiment log is recorded as passed: "We have not yet confirmed this on your phone model.")*
- **If it's off:** You can still type notes.
- **What it costs:** The Microphone permission. The microphone is used only while the "Listening" or "Recording" screen is showing.

### Keep the recording *(added 2026-11-12, D77, D82; built by cards 26g and 26k)*

Switch: **Keep a recording of each voice note.** On unless you turn it off; it takes effect once the speech model has been added.

- **What you get:** Every voice note keeps its sound. If a word was written down wrong, you can listen, fix it, and have the note written out again. A long note with pauses is written out whole.
- **What it sends:** Nothing leaves your phone. Recordings are kept inside Stash, encrypted, where no other app can open them. They are never sent to a model and never appear in notifications. Stash's own speech model writes them out on the phone.
- **If it's off:** Voice notes still work. The phone writes down your words as you speak and no sound is kept. You can't listen again or have a note written out again.
- **What it costs:** About <size per minute, measured: 170 KB> of storage a minute of speech, kept until you delete the note or the recording; so far <count> recordings, <size>. The speech model, <model size> on the phone. You do not see your words while you speak; they appear a moment after the note is saved. Whoever is nearby hears a recording if you play it aloud.

Variants, shown only when they are true:

> *(no speech model on the phone)* **On, but no speech model is added.** Voice notes are written down by the phone as you speak and nothing is recorded. · **Add the speech model**

> *(only if the lock-screen check failed, card 26a)* Added to "What it costs": **From a locked phone, a note is written down by the phone as you speak and is not recorded.** Unlock first to keep a recording.

> *(only if recordings are not written out with the screen off, card 26f)* Added to "What it costs": **A note recorded while the phone is locked is sometimes not written out until you next open Stash or charge the phone.** It is saved and can be played meanwhile.

When he removes recordings:

> **Remove this recording? The words stay. It can't be brought back.** · **Remove** · **Keep**

> **Delete <N> recordings (<size>)? The words of those notes stay. The recordings can't be brought back.** · **Delete <N> recordings** · **Cancel**

### Download the speech model *(not built; only if card 26l is ever scheduled)*

- **What you get:** Stash can keep a recording of each voice note and write it out itself.
- **What it sends:** Nothing about you or your notes. It fetches one file of about <size> from **<host>**, which will see your internet address and that a copy of Stash asked for it.
- **If you don't:** Voice notes are written down by the phone as you speak and no recording is kept.
- **What it costs:** About <size> of download, once, and <size> of storage.

### A model to understand your notes

- **What you get:** Notes are sorted properly whatever way you say them: several things from one sentence, readings with their numbers, things to do with their dates. Questions get a sentence for an answer instead of a list.
- **What it sends:** The words of each note, your word list, the names of things you have mentioned before and the titles of your open to-dos, to **<provider>**, under that company's terms (<link>). Never your voice, your location, the date or time, pictures, or anything marked Private.
- **If it's off:** Notes are saved and searchable by their words. Simple sentences ("I put X in Y", "I need to…", "pool pH 7.2") are still sorted by built-in rules. Other notes are kept unsorted and marked "needs a model". Questions return matching notes as a list.
- **What it costs:** About <cents> cents a month at your use, paid to <provider> with your own key. Your chat subscription does not cover this. You set a monthly limit; this month so far: <amount>.

Under it, when a provider is being chosen:

> **<Provider name>**: <model>. About <price> per 1,000 notes. Says it <does not train / may train> on what you send and keeps it for <period>. <One-line caveat, e.g. "On the free plan Google may use what you send to improve its products.">  Checked <date>.

And when the model is switched on with earlier notes waiting:

> **File your <N> earlier notes with the model?** About <cents> cents. This sends their words to <provider>. Private notes are not sent. · **File them** · **Not now**

### A model on this phone *(shown only if card 12's spike passed and the phone supports it)*

- **What you get:** Better sorting than the built-in rules, with nothing sent anywhere.
- **What it sends:** Nothing leaves your phone.
- **If it's off:** The built-in rules, or the hosted model if you have turned that on.
- **What it costs:** A one-time download by Android of about <size>. It works only while Stash is open on screen, so a note is sorted a moment after you save it or next time you open the app.

### Private notes

Shown beside the Private toggle on a note, and in Settings.

- **What you get:** A note, picture or scan marked Private is never sent anywhere, needs your fingerprint again to open even when the app is already unlocked, and is left out of reminders, suggestions, search previews and diagnostic reports.
- **What it sends:** Nothing, ever.
- **If it's off for a note:** The note is treated like any other.
- **What it costs:** A private to-do will **not** remind you; you will see it only when you open your private notes. A private note is sorted by the built-in rules only. Say "private note" before speaking to mark one as you make it.

When the app suggests it:

> **This looks like a <health / money> note. Keep it private?** Private notes are never sent and need your fingerprint to open. · **Make private** · **No**

When the app turned it on by default:

> **Marked private because it looks like a <password or code / boarding pass / ticket>.** · **Keep private** · **It isn't**

### Where you were

- **What you get:** Each note remembers where you made it, so the app can answer "what was that bottle at Total Wine?", build your outings from your notes, and show your shopping list when you are at the shop.
- **What it sends:** Nothing leaves your phone. Positions are stored with your notes and never go to a model.
- **If it's off:** Notes have no place unless you say one ("at Costco"). Outings are pieced together from the place names you speak.
- **What it costs:** The Location permission "while using the app". The app reads the position the phone already has when you make a note; it does not follow you.

### Reminders

- **What you get:** "You have 3 things you wanted to do", once on a day when there is something to say.
- **What it sends:** Nothing leaves your phone. On the lock screen a reminder shows a count, not your words.
- **If it's off:** Your list is still there when you open the app or ask "what should I handle?".
- **What it costs:** The Notifications permission. Never more than two scheduled reminders a day, none at night, and it backs off if you ignore it.

### Pictures

- **What you get:** A photo or screenshot kept with a note. The app reads any printed words in it so you can find it by them later.
- **What it sends:** Nothing leaves your phone. Pictures are kept inside Stash, not in your gallery, so your photo backup does not pick them up. They never appear in notifications.
- **If it's off:** Notes are words only.
- **What it costs:** About 250 KB of storage a picture. The Camera permission if you take pictures inside Stash; none if you share them in from another app. A picture you share in from your gallery is still in your gallery too.

### Offer my latest screenshot

- **What you get:** When you start a note just after taking a screenshot, Stash offers to include it.
- **What it sends:** Nothing leaves your phone.
- **If it's off:** Share the screenshot to Stash yourself (two taps).
- **What it costs:** Permission to see **all** your photos and videos. Stash looks only at the newest few minutes, only while it is open.

### Send the words in a picture to the model

- **What you get:** The model can use a label's printed text ("Valpolicella Classico, Allegrini, 2022") to sort the note, without you reading it out.
- **What it sends:** The **text** the phone read from the picture, with the note, to <provider>. Not the picture. Never for Private notes.
- **If it's off:** The printed text is still searchable on your phone. The note is sorted from what you said.
- **What it costs:** A screenshot's text can include other people's names and words. Nothing extra in money.

### Send a picture to the model *(asked per picture; never a switch)*

> **Send this picture to <provider> to describe it?** The phone could not read it well. The picture itself will be sent, under <provider>'s terms, and kept by them for up to <period>. Do not send pictures of other people, documents, or anything intimate: providers' rules often forbid sexual content, and sending it can get an account closed. · **Send this one** · **No**

Never offered for a Private note or picture.

### Scanning codes

- **What you get:** Scan a barcode or QR code into a note. Scan it again later and Stash tells you when and where you noted it.
- **What it sends:** Nothing leaves your phone. A link in a code is shown as text and is never opened unless you tap it. Wi-Fi and payment codes are stored as text and never acted on.
- **If it's off:** No scanning.
- **What it costs:** The Camera permission. Boarding passes, tickets and loyalty cards hold your name and numbers, so Stash marks them Private unless you say otherwise.

### Look up products by barcode

- **What you get:** A product's name filled in from its barcode.
- **What it sends:** The barcode number to **<lookup service>**, which will see it together with your internet address. That tells them what you were looking at.
- **If it's off:** The number is stored and you name the product yourself. This is usually enough.
- **What it costs:** Nothing in money. Off unless you turn it on; asks the first time.

### Outings

- **What you get:** An answer to "when did I last go out?" and a record of where you stopped and what you noted there, built from your notes.
- **What it sends:** Nothing leaves your phone.
- **If it's off:** Your notes are unchanged; they are just not grouped into outings.
- **What it costs:** Nothing extra. It only knows about outings on which you made a note, unless you turn on one of the two below.

### Notice when I leave: my car's Bluetooth

- **What you get:** An outing starts when your phone connects to your car, and your leaving list ("wallet, keys, glasses") appears then. It also records which car.
- **What it sends:** Nothing leaves your phone.
- **If it's off:** Say or tap "Start a trip" to get your leaving list.
- **What it costs:** The Nearby devices permission. For this to work reliably, Stash's battery setting must be "Unrestricted".

### Notice when I leave: home Wi-Fi

- **What you get:** Outings are recorded even when you make no note and take no car.
- **What it sends:** Nothing leaves your phone.
- **If it's off:** Outings come from your notes and your car.
- **What it costs:** The Location permission (Android requires it to tell one Wi-Fi network from another). It notices after you have left, so it is too late for the leaving list.

### Tell me when I arrive

- **What you get:** "A reminder for where you are" when you reach a shop you have a list for.
- **What it sends:** Nothing leaves your phone.
- **If it's off:** The list shows when you open Stash at the shop.
- **What it costs:** The Location permission "all the time". A little battery. It can be a few minutes late.

### Watches

- **What you get:** Stash checks the web for you on a schedule ("is Saves the Day playing near Phoenix?") and tells you only when the answer is yes.
- **What it sends:** For each watch, the exact words shown on that watch, to the service named on it, as often as it says. Whoever receives it learns that someone at your internet address is interested in that, in that place.
- **If it's off:** No watch runs. Notes that ask for one wait as suggestions.
- **What it costs:** About <cents> cents a check with the model's web search; nothing with the events listing. At most 20 checks a month. Each watch stops after a year unless you renew it.

### Read answers aloud

- **What you get:** Answers spoken, for when you are driving.
- **What it sends:** Nothing leaves your phone.
- **If it's off:** Answers are shown.
- **What it costs:** Whoever is nearby hears the answer. Private notes are never spoken.

### Backups

- **What you get:** If the phone is lost, your notes are not.
- **What it sends:** One encrypted file to the folder you choose. Stash itself sends nothing; your cloud drive or sync app moves the file. Whoever stores it sees its size and date and nothing else.
- **If it's off:** Lose the phone or uninstall the app and the notes are gone.
- **What it costs:** You must keep the recovery code. Without it nobody can open the backup, including you.

### Diagnostic report *(product stage)*

- **What you get:** A file you can send to support when something goes wrong.
- **What it sends:** Nothing by itself. It makes a file and shows it to you; you choose where it goes. It holds settings, counts, timings and error codes, with every word from your notes replaced by a label like "Item 2". No pictures. No recordings. Nothing private.
- **If you don't:** Support can only guess.
- **What it costs:** A report is about you even without your words. Send it only to someone you would tell that you use the app.

## The "What leaves my phone" screen

Settings → **What leaves my phone**. Drawn from one register in the code (card 13): a kind of request that is not in the register cannot be made, so the list cannot fall behind the app.

At the top, one of:

> **Nothing leaves your phone.** Every option that sends anything is off.

> **<N> things can leave your phone.** They are listed first.

Then every kind of outbound request the app is able to make, on or off, each as one row that opens its explanation card:

| Row | Goes to | Sent | State (examples) |
| --- | --- | --- | --- |
| Sorting a note | <provider> | the note's words, your word list, names of things, open to-do titles | **On** · 212 this month · last today 9:14 |
| Answering a question | <provider> | your question and short excerpts of the notes looked up | **On** · 6 this month |
| Filing earlier notes | <provider> | the words of older notes, once, when you ask | Not running |
| Words read from a picture | <provider> | printed text from a picture, with its note | **Off** |
| A picture | <provider> | one picture, each time you agree | **Never without asking** · 0 so far |
| Watch: Saves the Day | Ticketmaster | "Saves the Day", "Phoenix, AZ" | **On** · monthly · last 3 Nov |
| Product lookup | <lookup service> | a barcode number | **Off** |
| Backups | a folder you chose | one encrypted file (moved by your drive app, not by Stash); it holds your recordings too unless you leave them out | **On** · last Sunday |

There is **no row for recordings or for writing them out**: neither sends anything (cards 26a to 26k add no outbound kind). A row "Speech model download" would exist only if card 26l were built.

Under the table, always:

> **Never sent, whatever is switched on:** your voice and any recording of it · your location · the date and time of a note · anything marked Private · pictures (unless you agree to one) · usage statistics or crash reports (there are none).
>
> **Sent with any request and not in Stash's control:** the receiving company sees your internet address and the moment the request arrives, and knows which key (so which account) it came from.
>
> **Stash has no server.** There is no Stash account and nobody at Stash to send anything to.

A button: **Turn everything off.** Sets every sending capability off in one tap; the app keeps working at the "phone only" level.

## How to keep this page honest

- A unit test (card 23) fails if a capability exists without a card, or an outbound kind without a row.
- The provider line's facts (training, retention, price) come from one table in the code with a "checked on" date; a date older than a year shows "Check <provider>'s current terms" beside it.
- When the experiment log contradicts a sentence here (for example, speech turns out to need the network), the sentence is changed in the same commit as the log entry.
