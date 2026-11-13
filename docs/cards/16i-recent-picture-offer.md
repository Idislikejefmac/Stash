# 16i ◆ · Offer my latest screenshot or photo (optional, off by default)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)). Optional: build it only to learn whether the offer is worth the permission (QUESTIONS.md Q34).

## Goal

With one switch turned on, when he starts a note within five minutes of taking a screenshot or photo, Stash shows one chip: "Include the screenshot from 2 minutes ago?". Without the switch nothing in the app ever looks at his photo library.

## Depends on

16a, 16b, 16c.

## Context

This is the one picture route that needs broad access: Android's `READ_MEDIA_IMAGES` ("Photos and videos"). Android cannot tell an app that a screenshot was just taken in another app, and with "selected photos only" access a new screenshot is invisible, so the offer needs **full** access or it cannot work. That is why it is a separate switch, off by default (D72).

Not built, ever, by this card: watching the library in the background, or a notification about a new picture.

If the app is ever offered through Google's store, broad photo access is allowed only where it is an app's core purpose. This switch would then have to go or be justified. Note it; do nothing about it now.

## Scope

1. Manifest: `<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />`. Capability `RECENT_MEDIA` in `domain/Capability.kt`: that permission, **`defaultOn = false`**, without it: "Share the screenshot to Stash yourself. It takes two taps."
2. Turning the switch on asks for the permission. If the owner grants only "selected photos" (the permission itself then reads as not granted), the switch goes back off and says: "This needs access to all photos, because a new screenshot is not among selected ones."
3. `pictures/RecentMediaSource`: `interface { suspend fun newest(withinMs): RecentPicture? }` with `NoRecentMedia` (returns null) and `MediaStoreRecentMedia`, chosen in `Container` by `capabilities.isOn(RECENT_MEDIA)`. The real one queries `MediaStore.Images` for the single newest row added within the last 5 minutes and returns its content `Uri`, its age in seconds, and its display name. It reads one row's columns, never the picture, and is called only from a visible screen while the phone is **unlocked**.
4. `domain/RecentChip.kt` (pure): the wording from the age and `PictureSizing.guessKind`: "Include the screenshot from 2 minutes ago?" / "Include the photo from just now?" (under 60 seconds is "just now").
5. Where the chip appears, at most one, only when the source returns something:
   - on the annotate screen (16c), above "Add another";
   - on `CaptureActivity`'s Saved screen while a note's Undo window is open (only when unlocked).
6. Tapping the chip prepares that one picture (`source = RECENT`) and adds it: to the strip on the annotate screen; or to the just-saved note with `Inbound.AddAttachments`. The chip then disappears. A picture offered once and not taken is not offered again (remember its `Uri` for the session).
7. No thumbnail of the offered picture is drawn before he taps: the chip is words only.
8. Events (`Dbg.event`): offered, taken. After a fortnight these two counts answer Q34.

## Out of scope

A background observer. A notification. Offering more than one picture. Offering pictures older than five minutes. Browsing the library inside Stash (the system photo picker does that with no permission).

## Acceptance checks

**Unit tests**

- With the capability off, `Container` gives `NoRecentMedia` and no code path touches `MediaStore` (a double that fails if called).
- `RecentChip` wording for a screenshot at 20, 90 and 280 seconds, and for a photo.
- With a fake source returning a picture 2 minutes old: the annotate screen shows one chip; tapping adds one picture with `source = RECENT` and removes the chip; a second open in the same session does not offer it again.
- With the phone reported as locked, the source is never called.
- Partial ("selected photos") access is treated as not granted.

**Real phone (S25 Edge); write each result in the experiment log**

- Switch on, grant all photos. Take a screenshot, open Stash's note screen within a minute: is the chip there, and is it the right picture? Tap it and Save.
- Wait six minutes after a screenshot: no chip.
- Choose "selected photos only" in the permission dialog: the switch explains and stays off.
- Switch off: `adb shell dumpsys package app.stash | findstr READ_MEDIA` shows whether the permission is still granted; either way no chip appears.
- After two weeks: the offered and taken counts from the event log, and the owner's one-line verdict.

## Notes and risks

- Confidence: the query columns and the five-minute window by `DATE_ADDED`, high. That partial access reports `READ_MEDIA_IMAGES` as denied, medium to high; the phone check settles it.
- Samsung may write a screenshot a second or two after it is taken; the chip is read when the screen opens and once more two seconds later.

**Suggested builder: Sonnet.** One query behind an interface with a do-nothing version, a pure wording function and a chip.
