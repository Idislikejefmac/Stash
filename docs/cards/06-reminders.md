# 06 · Reminders: the daily digest, the weekly review, the budget, and backing off

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

"You have 3 things you wanted to do" arrives once on a day when there is something worth saying, never more than twice a day, never at night, and less often when he ignores it. All of it decided by plain code that can be tested with a fixed clock.

## Depends on

05.

## Context

Tasks have a state and a due kind (`NONE`, `SOON`, `ON`, `BY`, `WINDOW`) with dates (card 05). `task.next_nudge_on`, `nudge_count`, `ignored_in_a_row` and `quiet` exist and are unused. The `nudge_log` table exists and is empty. The model plays no part in this card.

The owner's fear is leaking personal data and the product's risk is annoyance, so two rules dominate: **a lock-screen notification shows a count, not text**, and **when in doubt, send nothing**.

Android: use `AlarmManager.setAndAllowWhileIdle` (inexact) for the two daily times and WorkManager for upkeep. Do **not** request the exact-alarm permission; a delay of some minutes is fine. Samsung may delay more for a "sleeping" app: measure it (acceptance checks).

**Build it in two passes.** Pass 1 is what the owner needs to judge the idea: sections A, B items 4, 7 and 8, and C items 9a–13 for the daily digest only. Use it for a week. Pass 2 is the rest (afternoon check, weekly review and its screen, the self-pausing digest, the full settings).

## Scope

### A. When a task is next nudged (`domain/NudgePlan.kt`, pure)

1. `firstNudgeOn(task, capturedOn)`:

   | Due kind | First nudge |
   | --- | --- |
   | `SOON` | 2 days after capture |
   | `NONE` | never (weekly review only) |
   | `WINDOW` | first day of the window |
   | `BY` | halfway between capture and the date, at least 1 day before it; if captured the day before, that day |
   | `ON` | the day |
   | at a place (any due kind `NONE`/`SOON`) | never by time |

2. `nextAfter(task, shownOn)`:
   - `SOON` (and anything that has fallen back to it): gaps of 3, 7, 14, then 30 days, by `nudge_count`.
   - `WINDOW`: once more on the last day; after the window, treat as `SOON` starting at the 7-day gap.
   - `BY`: the last day; after it, one "was due" mention the next day, then `SOON` from the 7-day gap.
   - `ON`: one "was due yesterday" mention the next day, then `SOON` from the 7-day gap.
3. A task that is `quiet`, snoozed, or not `OPEN` has no next nudge. Waking from snooze sets `next_nudge_on` to that day.

### B. What to send today (`domain/DigestPlanner.kt`, pure)

3a. **Private tasks are never in a notification**, not as a title and not in a count. A task whose capture is sensitive is left out of every planner's input. It appears on the "To handle" screen after the second unlock (card 02) and nowhere else. This is a real cost of marking a to-do private and the mark's explanation says so.
4. `planDaily(openTasks, nudgeLog, today, settings) → DailyDigest?`: the tasks with `next_nudge_on <= today`, ordered as in card 05, first five listed, the rest counted. None → `null` (no notification). If today is the weekly-review day → `null` (the review replaces it).
5. `planDueToday(openTasks, nudgeLog, now, settings) → DueReminder?`: at the afternoon time (default 16:00), if a task with `ON` today, or the last day of a `BY` or `WINDOW`, is still open and no other `DUE` notification was sent today → one reminder for the single most pressing one.
6. `planWeekly(db snapshot, today) → WeeklyReview`: counts of open tasks; tasks done in the last 7 days, listing those closed by speaking; tasks that will stop being asked about in the next 14 days; open loans older than 30 days (first mention) or past `due_back_on`; number of records in the attention list; (from card 07, when present) readings that are overdue by their own rhythm. Always produced; if every count is zero the notification is skipped.
7. **Budget**, enforced in one place (`domain/NotificationBudget.kt`) and unit-tested. Two classes:
   - **Scheduled** (the daily digest, the weekly review, "due today"): at most 2 per calendar day; the weekly review counts as the day's digest.
   - **Event** (something that just happened in the world: card 11's arrival, card 18's leaving reminder, card 19's watch match): at most 2 per day, at least 30 minutes apart. These cards do not exist yet; the class is defined now so they cannot each invent an exception.
   - **At most 4 notifications a day in total**, and nothing of either class inside quiet hours (default 21:00–08:00; a scheduled one waits for the next digest; an event one is dropped, except a watch match, which waits).
   Every post goes through `NotificationBudget.mayPost(class, now, log)`; nothing calls the notifier directly.
8. **Ignored:** a `SHOWN` digest not opened, and none of its tasks acted on, within 24 hours becomes `IGNORED`; each listed task's `ignored_in_a_row` goes up by one. Three in a row → `quiet = 1`. Any action on a task resets its count.
9. **The digest stops itself:** five consecutive `DAILY` entries with outcome `IGNORED` or `DISMISSED` → setting `daily_digest_auto_paused = true`; no more daily digests; the next weekly review includes the line "I've stopped the daily reminder because you weren't opening it. You can turn it back on in Settings." Opening the "To handle" screen from a weekly review does not un-pause it; only the setting does.

### C. Delivery (`reminders/`)

9a. **Plan when the data is at hand, post from the plan.** Each planner runs whenever tasks change and at app start, and stores its result (type, count, and for the private version the record ids) in a small preferences file. The alarm receiver posts from that stored plan and then asks for a re-plan; it does not need the database to build the lock-screen text. Today this changes nothing visible. It is what lets a sealed database (`docs/STORAGE.md`) arrive later without rewriting reminders.
10. `ReminderScheduler`: keeps three alarms registered: daily digest time (default 08:30), afternoon check (16:00), weekly review (Sunday 17:00). Re-registered at boot, on app update, on settings change and at app start. Each alarm runs the matching planner and posts or does nothing, then registers the next one.
11. `Notifier`: three channels ("Daily reminder", "Weekly review", "Due today"), default importance with vibration and no sound.
    - **Public (lock-screen) version, always:** "You have 3 things you wanted to do." / "Your weekly review is ready." / "Something you wanted to do is due today." No titles, no places, no names.
    - **Private version** (shown when unlocked): the first few titles as lines; for "due today" the task title with **Done** and **Later** actions. The actions are attached only to the private version and are marked authentication-required.
    - Setting "Show the first task on the lock screen" (default off) puts the first title in the public version.
    - Tapping opens "To handle" (or the weekly review screen) behind the app gate.
12. Record every post in `nudge_log` (`SHOWN`), and update it on open (`OPENED`), on an action (`ACTED`), on swipe-away (`DISMISSED`, via a delete intent). The 24-hour sweep (in card 05's daily worker) marks the rest `IGNORED`.
13. `POST_NOTIFICATIONS` is already requested by the prototype for its capture notification; if denied, Settings says reminders are off and why.
14. **Weekly review screen** (behind the gate): the lines from item 6, each tappable to the relevant list.
15. **Settings:** digest time; afternoon time; weekly day and time; quiet hours; daily digest on/off (shows "paused because it was being ignored" when auto-paused); weekly review on/off; lock-screen text switch.
16. "Later today" (card 05's snooze preset) sets `next_nudge_on` to today and lets the afternoon check pick the task up.

## Out of scope

Place arrival and geofences (11). Time-of-day reminders and exact alarms (QUESTIONS.md Q26). Recurring tasks. Sounds. Any model involvement.

## Acceptance checks

- **Unit:** `firstNudgeOn` and `nextAfter` tables with a fixed clock for every due kind, including a `BY` captured the day before, and a window that has passed.
- **Unit:** a sensitive task changes no planner's output, including the counts.
- **Unit:** `planDaily`: nothing due → null; six due → five listed and "1 more"; weekly day → null; quiet task excluded.
- **Unit:** budget: a third scheduled notification in a day is never produced; a third event one neither; a fifth of any kind neither; two event posts 10 minutes apart → the second is refused; quiet hours hold.
- **Unit:** the alarm path posts the right public text from a stored plan with the database closed (a store fake that throws if opened).
- **Unit:** ignored → counts rise; three → quiet; an action resets; five ignored digests → auto-pause and the weekly line appears once.
- **Unit:** a simulated month: feed a list of tasks and a script of "opened / ignored" through the planners day by day and assert the total number of notifications and that no day exceeds two. Include the owner's two examples (clean out the car "soon"; a pool test task "this weekend").
- **Unit (Robolectric):** the public version of each notification contains no task title when the setting is off; the actions are absent from the public version.
- **Real phone, S25 Edge:** set the digest time to five minutes from now with one `SOON` task whose `next_nudge_on` is today (a debug menu item "make this due today" is allowed in the debug build). Lock the phone. The notification arrives; note how late. The lock screen shows the count only. Unlock: the title is visible. Open it: "To handle". `peek.py dump` shows `nudge_log` `SHOWN` then `OPENED`.
- **Real phone:** reboot with the alarm pending; it still fires without opening the app.
- **Real phone, one week of normal use:** each morning note the time the digest arrived (or that there was none and why). If it is more than 30 minutes late on two or more days, record it in `docs/EXPERIMENT_LOG.md` and add a Settings hint asking the owner to set the app's battery use to "Unrestricted".
- **Owner, after two weeks:** from `nudge_log`: digests shown, opened, ignored. Write the numbers and his one-line verdict (helpful / too much / too little) in the experiment log.

## Notes and risks

- Before building, read the owner's one-week notebook of "I wish I'd been reminded" moments if he kept one (RISKS.md R14) and replay it through the simulated-month test.
- Wording is calm and short. No exclamation marks, no emoji, no "REMINDER".
- Do not create a notification per task. If you find yourself adding a fourth kind of scheduled notification, stop and ask.
- Loans: only the weekly review mentions them. No separate loan notification.
