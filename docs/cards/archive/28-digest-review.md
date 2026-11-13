# 28 · Weekly digest and "still true?" review

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Once a week, one calm notification leading to one screen that keeps the memory honest: what is about to expire, what is still lent out, which old "I put it there" notes are worth confirming, and which notes the app was unsure about.

## Depends on

18, 19, 22, 24.

## Context

The failure this prevents is slow rot: reminders the user forgot they set, items whose recorded place is a year old, loans nobody remembers. The app has a strict notification budget (`NotificationBudget`, card 22: at most 3 proactive notifications a day, quiet hours) and lock-screen-safe notifications (`Notifier`). Loans have their own occasional nudge (card 24); when the digest is on, loan nudges are folded into it instead of being sent separately. Staleness rules are in `domain/Staleness` (card 09). "This is still right" re-confirmation exists on the note screen (card 18).

## Scope

1. `reminders/DigestBuilder` (pure Kotlin, clock injected) produces sections, each possibly empty:
   - **Expiring soon:** errands and reminders whose `expires_at` falls in the next 7 days. Actions: Keep another month · Done · Let it go.
   - **Still on your list:** active errands and reminders older than 21 days, at most 5.
   - **Lent and borrowed:** open loans older than 30 days, at most 5.
   - **Still there?:** up to 3 `PLACED` notes that became stale since the last digest, preferring items asked about before (count asks per item: add `item.ask_count`, with migration and test, incremented by `AskEngine`). Actions: Still there · I moved it (starts a capture pre-tagged with the item) · I don't have it any more (marks the item retired: add `item.retired`, excluded from future review, still searchable).
   - **Check these:** count of notes needing review, linking to that list.
   - **Forgotten things:** up to 3 items never asked about and placed more than a year ago, shown as a list only ("You put the Christmas tree stand in the attic, 14 months ago"), no action required.
2. `reminders/DigestWorker`: weekly at a chosen day and time (default Sunday 10:00, setting), through `NotificationBudget`. Notifies only if at least one actionable section is non-empty. Public text: "Your weekly Stash check". Private text: "2 expiring, 3 lent out, 1 to confirm".
3. **Digest screen** (behind the app lock), also reachable any time from Home ("Weekly check"): the sections above with large one-tap actions; finishing all shows "All caught up".
4. Settings: digest on/off (default on; in simple mode default on but with only the "Expiring soon", "Lent and borrowed" and "Still there?" sections), day and time, and "Also read it to me" (speaks a two-sentence summary when opened).
5. When the digest is on, `LoanNudger` (card 24) does not post its own notification.

## Out of scope

Daily summaries, email, statistics or charts, "insights" written by a model.

## Acceptance checks

- **Unit (JVM):** builder with a seeded database and fixed clock: each section's membership and caps; nothing in a section → that section omitted; all empty → worker posts nothing; "Still there" marks fresh; "I don't have it any more" retires and excludes; loan nudger suppressed when digest is on. Migration test for the new columns.
- **Unit (Robolectric):** the public notification text has no counts or names.
- **Real phone, S25 Edge:** with back-dated data from the debug screen, trigger the worker ("run now"): one notification, generic on the lock screen; the screen shows the right items; actions update the notes.
- **Real phone, after two real weeks of use by the owner:** record in `docs/EXPERIMENT_LOG.md` whether the digest was useful or ignored, and what was noise. Adjust caps accordingly.

## Notes and risks

- A digest that always has something in it becomes wallpaper. The caps and the "only if actionable" rule matter more than completeness.
- "Forgotten things" is deliberately small; it is a pleasant surprise, not a to-do list.
