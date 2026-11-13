# Brief: design a voice-driven personal memory assistant (Android)

You are designing an app. **Do not write app code.** Produce a design, a researched list of features, a list of risky assumptions to test early, and a set of implementation cards in dependency order. A cheaper model (Sonnet) will build the cards one at a time, so each card must stand on its own.

All files go in `C:\jeffsource\PersonalAssistant\` (this folder). It is a new project, not a git repository yet; the owner will create the repository later. Don't run `git init`. Do not touch `C:\jeffsource\AndroidStudioProjects\StarterApplication` (the owner's HomeLights app); you may read its `README.md`, `TODO.md` and `docs/RELIABILITY_LOG.md` for the owner's conventions and tone (Kotlin, Jetpack Compose, tests, a log of what was tried so nothing is retried).

## The idea, in the owner's words

> I walk around all day, put my keys here, my glasses there, and sometimes put a hat in some drawer I'm sure I'll remember. But I reliably forget over time. If I put something in a place, I often don't remember where that is after a few weeks of not using it. I could leverage an agent as a method to voice-add reminders for myself. "I am putting the 110V outlet plug for the Tesla Charger in the Frunk of the car", "I'm putting my prescription reading glasses in my nightstand drawer", "I loaned my guitar to Frank on Jan 5th", "I need to remember to buy X when I go to Costco next, it was only $15.99 on April 3". It'd need to be frictionless and easily accessible. The voice would be converted to data (text, GPS location, datetime, etc.) and I'd then want to say "where did I put my guitar?" and have the app search back through what I've said: where I last put something, who I lent something to, what I need to buy while I'm here at Costco. Tasks like buying ground beef can be time limited: I wouldn't want a reminder six months later. Some sort of place-aware, personal-history-aware, secure store for the bits and pieces of my life.

## Users and devices

Three households of use, each person with their own phone and **their own private data** (no sharing between people unless you propose it as an optional later feature and explain why):

| Person | Phone | Notes |
| --- | --- | --- |
| Owner | Samsung Galaxy S25 Edge, Android 17 | Developer; builds and sideloads the app |
| Owner's wife | Motorola razr ultra 2025 | Flip phone; Motorola has its own system gestures (e.g. shake for flashlight, chop twice) |
| Owner's parents | Samsung Galaxy Z Flip 7 | Flip phone; older users, so simplicity, large text and forgiveness matter |

Design for Samsung One UI and Motorola's near-stock Android, and for **flip phones' cover screens** (can a note be taken, or a question answered, without opening the phone?). Identify where behaviour differs by manufacturer and say so. The owner builds and installs it himself; assume sideloading (no Play Store) unless you recommend otherwise, and explain how three households would get updates.

## Must-haves

1. **Frictionless capture by voice, without unlocking the phone.** It must be possible to start recording from a locked phone with one button action and finish hands-free (the user speaks, it stops, it saves, with a short audio cue at start and at save so no looking is needed). Candidate triggers to evaluate for feasibility on each phone: Bluetooth earbud/headset button (media key), the side key (double-press on Samsung; Motorola equivalents), back-tap (Samsung Good Lock RegiStar), a shake or chop gesture (needs a foreground service watching the accelerometer; evaluate battery, kill-by-OEM risk and false triggers), a quick-settings tile, a home-screen widget, a lock-screen shortcut, a NFC tag, and a voice trigger if it is genuinely viable. Say which survive on each device and what each costs in permissions and battery.
2. **Capture stores data, not just audio.** Transcribed text, timestamp, GPS location (and a human place name when possible), and structured fields extracted from the text (kind of note, item, place, person, dates, expiry).
3. **Ask by voice or text.** "Where did I put my guitar?", "Who did I lend the drill to?", "What did I need at Costco?" The answer is short and plain, says when the note was made, and says when it is probably stale.
4. **Place-aware reminders.** "Next time I'm at Costco" should notify on arrival (geofence), then stop reminding once it's done or expired. Note the cost of "allow location all the time" and minimise it.
5. **Expiry.** Reminder-type notes expire by default (the owner suggested months, not forever), with a sensible per-kind default and the ability to change it. Item-location notes do not expire but a newer note about the same item supersedes the old one (latest wins, history kept).
6. **Privacy first, local first.** Data lives on the phone, encrypted, with the key in the hardware-backed keystore. No server of ours. An encrypted backup to somewhere the owner controls (he runs Home Assistant at home; consider that, Syncthing-style sync, or a cloud drive that only sees an encrypted file). Explain the threat model you assume.
7. **Speech to text on the device by default** (Android on-device recognition and/or a bundled Whisper-class model), with a cloud fallback only if accuracy requires it. Raw audio is not kept after transcription unless you propose a reason. GPS and time never go to the speech service.
8. **A swappable, cheap language model** for turning text into structured fields and answering questions over retrieved notes. Hide it behind a small interface (e.g. "structure this note", "answer this question from these notes") so a Haiku-class hosted model, another provider, or an on-phone model can be swapped in. Only the note text (and, for a question, only the few retrieved notes) may be sent to a hosted model. Support marking certain notes **local-only** (never sent). The owner's Claude subscription does **not** cover API use; the API is a separate pay-as-you-go key per user, and you must say how three households each get a key (or propose an alternative) and what it will cost at occasional use.
9. **Locked-phone policy.** Adding a note while locked is allowed. **Reading, searching or answering questions requires the user's biometric or device credential.** This is the primary protection against someone else using the phone. Voice-print (speaker verification) is **not** a security boundary (no Android API exists; recordings can fool it; a cold can lock the owner out); evaluate it only as an optional convenience layer, and say whether it's worth the complexity.
10. **Works for non-technical people** (the owner's parents). First run, permissions, and "it just works" matter. Large touch targets, plain language, and nothing that can be broken by accident.

## What to research and decide (this is the part we most want from you)

- **Existing products and projects** (verify against their source, licence, privacy behaviour and maintenance; a web summary named these but did not verify them): Memora (Android, "last known location of belongings"), Dozy (voice-first location tasks), Memex (GPL-3.0, local-first AI journal), Mycelia, Cortex, Tangent, Offscript, Echo. For each: what we could learn, what would be risky to depend on, and whether any should be adopted instead of building. The owner expects to want custom triggers and a swappable model, which makes adopting a closed design unlikely; confirm or challenge that.
- **Missing features.** Think hard about what a "where did I put it" assistant should have that the owner hasn't mentioned. Starting points, not a limit: correcting or undoing the last note; "I moved it" and ambiguity ("my keys" has many places); attaching a photo of the drawer or shelf; loan tracking with an overdue nudge; "forgotten items" review; a weekly digest; items with multiple copies; recurring notes; household shared lists as an opt-in; widgets and cover-screen views; accessibility (TalkBack, large text); multilingual speech; export and delete-everything; handling a lost or replaced phone; how to avoid being annoying (notification budget); how to tell the user when the assistant is unsure. Rank them: must, should, later, and explain why.
- **Search design.** Keyword search versus semantic (embedding) search versus asking a model, with the privacy and cost of each; how to combine them; how to keep it fast offline.
- **Data model** for notes, items, places, people, reminders, supersession and expiry; migrations; and what the encrypted backup contains.
- **Failure modes**: transcription errors ("Frank" vs "Frankie"), no GPS indoors, no network, model returns nonsense, user forgets they set a reminder, the phone is killed by battery management, two notes disagree.
- **Platform realities**: Android 17 restrictions on background microphone, location, foreground services and starting activities; lock-screen behaviour; OEM battery managers; what Google Assistant/Gemini can and can't do for third-party apps (treat as optional, later).

## Deliverables (create these files here)

1. `docs/DESIGN.md`: the design: goals and non-goals, threat model, architecture, data model, the trigger strategy per device, the speech and model interfaces, privacy and security model, cost estimate, and the existing-products comparison.
2. `docs/FEATURES.md`: every feature considered, ranked (must / should / later / won't) with a sentence of reasoning, including the ones you found that the owner didn't mention.
3. `docs/RISKS.md`: the assumptions that could invalidate the design, each with a **spike**: a small, cheap experiment (what to build, on which phone, what result means pass or fail). The locked-phone trigger spike comes first.
4. `docs/cards/NN-short-name.md`: implementation cards, numbered in the order they are to be built. A card may be started only after every card it depends on is done. Each card contains: **Goal**; **Depends on** (card numbers, or none); **Context** (everything the builder needs; assume it has not read the design); **Scope** and explicit **out of scope**; **Acceptance checks** (observable, testable; say which are unit tests and which need a real phone, and which phone); **Notes/risks**. Keep cards small enough for one working session. Put the spikes first as their own cards.
5. `docs/DECISIONS.md`: choices you made and the alternatives you rejected, so they aren't re-litigated; and `docs/QUESTIONS.md`: anything only the owner can decide (e.g. each person's API key arrangement, whether notes may ever be shared, backup target), each with your recommendation.
6. `README.md`: a short index of the above and the build order.
7. A `.gitignore` suitable for an Android/Kotlin project (the owner will create the repository later).

## Conventions and constraints

- Kotlin, Jetpack Compose, Gradle, minSdk to be justified (the oldest phone is a 2025 flip; target Android 17 behaviour).
- Prefer well-maintained libraries; justify each dependency, especially anything native, large, or that phones home.
- No telemetry, analytics or crash reporting that sends data off the phone.
- Write for a reader who will act on it: plain language, no marketing tone, flag uncertainty honestly. Where you rely on a fact about Android, Samsung or Motorola behaviour, say how sure you are and how to verify it on a real phone.
- Keep a log (`docs/EXPERIMENT_LOG.md`, started empty with a header) for spike results so nothing is retried, in the style of the HomeLights reliability log.
- When done, summarise in your final message what you recommend building first, the three biggest risks, and the questions the owner must answer.
