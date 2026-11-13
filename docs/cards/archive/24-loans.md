# 24 · Loans: who has what, and a gentle nudge

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

A clear view of things lent and borrowed, a one-tap "returned", and an occasional reminder about loans that have been out a long time.

## Depends on

18, 19, 22.

## Context

"I loaned my guitar to Frank on Jan 5th" creates a `loan` row (item, person, direction `LENT` or `BORROWED`, `on_date`, optional `due_date`, `returned_at`, `last_nudge_at`) through the structuring pipeline (card 11). A lent item's location answer is "Frank has it" until the loan is returned (card 19). Loans never expire. Notifications go through `Notifier` and `NotificationBudget` (card 22), which limit the app to a few proactive notifications a day and keep text off the lock screen.

## Scope

1. **Loans screen** (behind the app lock): two sections, "Lent out" and "Borrowed", grouped by person, each row "Guitar · since 5 January (10 months)". Buttons: **Returned** (sets `returned_at = now`, asks "Where did you put it?" with a microphone button that starts a normal capture pre-tagged with the item, or "Skip"), **Edit**, **Remind me in a month**.
2. **Spoken returns** in `RulesStructurer` and the pipeline: "frank returned my guitar", "i got my guitar back (from frank)", "i gave dave his ladder back" → find the open loan for that item (and person if named) and mark it returned; if the sentence also says where it went ("…and put it in the hall closet"), create the placement. No matching open loan → keep as `FACT` and flag for review. (Extend the structurer's table tests and the hosted schema is unchanged: these are `PLACED`/`FACT` with a new boolean `is_return`; add it to `NoteFields` with default false and bump nothing else.)
3. **Nudges** `reminders/LoanNudger` (daily worker, pure decision logic): a loan is nudge-worthy when not returned and (`due_date` passed, or no due date and 30 days since `on_date`), and at least 60 days since `last_nudge_at`. At most one loan nudge per week across all loans; if several qualify, one notification: "3 things are still lent out" (public) / "Frank still has your guitar (10 months). Dave: ladder. …" (private). Actions: **Open**, **Stop reminding me about these** (sets a `no_nudge` flag on those loans; add the column with a migration and test).
4. Borrowed items nudge the same way with wording "You still have Dave's ladder (2 months)".
5. Person detail: everything lent to or borrowed from that person, including returned history.
6. Home shows "Lent out: 3" as a quiet row linking to the screen.

## Out of scope

Contacts integration (no contacts permission; people are just names), messaging the borrower, money debts, shared loan records between phones.

## Acceptance checks

- **Unit (JVM):** nudger decisions with a fixed clock: 29 days → no; 30 days → yes; nudged 59 days ago → no; due date passed yesterday → yes; `no_nudge` → never; weekly cap. Spoken return closes the right loan among two for the same person; return with a location creates a placement and the item's answer becomes that place; return with no open loan is flagged.
- **Unit:** migration test for the new column.
- **Real phone, S25 Edge:** say "I loaned my drill to Dave"; "who has my drill" → "Dave, since today"; say "Dave returned my drill and I put it in the garage cabinet"; "where is my drill" → "Garage cabinet…". Loans screen shows it under returned history.
- **Real phone:** with a loan back-dated 31 days in the debug screen, the next nudger run posts one calm notification with generic lock-screen text.

## Notes and risks

- Nudges about loans are the easiest way to annoy people; the weekly cap and "stop reminding me" are part of the feature, not extras.
- Names collide ("Frank" the neighbour and "Frank" the cousin). Persons can be renamed ("Frank next door") in the person detail screen; do not attempt to disambiguate automatically.
