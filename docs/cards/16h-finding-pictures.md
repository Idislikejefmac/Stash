# 16h · Finding pictures again: by place, by date, by their words

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

## Goal

"What was that bottle at Total Wine a couple of weeks ago?" and "Show me the bottles I photographed at Total Wine" are answered by code, on the phone, with no image recognition and no model: place, plus date, plus words, plus "has a picture".

## Depends on

**08** (the router, `searchNotes`, the answer screen), 16a, 16d (the thumbnail composable), 16f (a name found from the words in a picture). Do not start before card 08 pass 1 is done.

## Context

Read card 08 and `ask/Lookups.kt`, `ask/Router.kt`, `ask/AnswerWriter.kt` as built. Card 08 already gives `searchNotes` the parameters `on`, `place` and `hasPicture` and returns "whether it has a picture"; if it was built without the attachment table, `hasPicture` is a stub. This card makes it real and adds two router patterns and one way of showing results.

Privacy rules that apply (00-READ-FIRST rule 4): a picture never leaves the phone; the words read from a picture leave only when the switch from card 16j is on; Private matches are a count only.

## Scope

1. `searchNotes(..., hasPicture)`: `true` keeps only captures with at least one picture attachment (`picturesForCapture`, so audio does not count); `null` keeps all. Each result says how many pictures the capture has and the id of its cover (lowest position).
2. A capture's **place**, for these searches, is the first that exists of: a `place_text` or place subject on one of its records; its trip stop's name (card 17, only if that card is built); a saved place (`place_location`) whose radius contains the capture's position. Put this in one function, `ask/CapturePlace.kt`, with its inputs passed in so it is testable without a database. Matching a spoken place name against these uses the existing subject matching, not a new one.
3. `domain/DatePhrases` (extend, pure, resolved on the phone): "a couple of weeks ago" is 10 to 21 days back; "a few weeks ago" is 14 to 35; "last month" and "in October" as card 08 already resolves them.
4. Router patterns (`ask/Router.kt`), answered by code:

   | Question | Route |
   | --- | --- |
   | "what was that <thing> at <place>" with an optional time phrase ("a couple of weeks ago", "last month", "in October") | `PictureSearch(query = thing, place, on = range or null)`, pictures first |
   | "show me the <things> I (tagged / noted / photographed / took pictures of) at <place>" | `PictureGrid(query = thing, place, hasPicture = true)` |

   A bare name ("Allegrini") needs no route: full-text search already finds it in his words or in a picture's words (16f).
5. Ranking for `PictureSearch`: captures with a picture above those without; then card 08's ranking. `<thing>` is matched against his words and the picture's words; if nothing matches the thing but the place and date match captures with pictures, return those (he may never have said "bottle").
6. Showing results:
   - `PictureSearch`: card 08's answer with sources, each source showing its cover thumbnail beside the title and age.
   - `PictureGrid`: a grid of cover thumbnails, three across, each with the note's title and age beneath; tapping one opens the note's pictures in 16d's viewer. Thumbnails are drawn by 16d's `NotePictures`/loader and nothing else.
   - Private matches are not in either; the existing line "2 private notes also match · Unlock to see" covers them.
7. **Tool results for the model loop** (card 08 section D): a result may say that a note "has 2 pictures" and may include his words. It never includes a picture. It does not include the words read from a picture, and for a record made by the `picture-1` extractor (16f item 11) its title is replaced by "A picture", unless `PICTURE_TEXT_TO_MODEL` (card 16j) is on. If 16j is not built, treat it as off.
8. The answer trace (card 08 E2) records these routes like any other. Thumbnails are never stored in a trace.

## Out of scope

Recognising what a picture shows. "Find pictures like this one". A gallery tab of all pictures. Map views. Searching by colour, face or object. Sending anything.

## Acceptance checks

**Unit tests (seeded in-memory database: two Total Wine visits three weeks apart, each with two bottle notes with pictures and one note without; one Costco note with a picture; one Private Total Wine note with a picture)**

- "What was that bottle at Total Wine a couple of weeks ago?" returns the right visit's bottles and not the other visit's.
- "Show me the bottles I photographed at Total Wine" returns only notes with pictures, from both visits, none from Costco, and not the Private one; the private-count line says 1.
- "Allegrini" is found when it appears only in a picture's words.
- `CapturePlace`: a record's place wins over a saved place; a capture with only a position inside a saved place's radius gets that place; none gives null.
- The two date phrases resolve to the stated ranges with a fixed clock.
- Router: ten phrasings of the two patterns route; five look-alikes ("what was my pH at the pool last week") do not.
- Tool results: with the 16j switch off, no result contains a picture's words and a `picture-1` record's title is "A picture"; with it on, both appear; a Private capture appears in neither case. No result ever contains image bytes or a file name.
- One answer trace is written per question and holds no bitmap.

**Real phone (S25 Edge); write each result in the experiment log**

- After a real shop visit with two or three photographed items (or two staged "visits" with location on), ask both questions by voice. Are the right pictures shown? How long did each take?
- In aeroplane mode both still work.
- Ask about a place with no pictures: a plain "I don't have a note about that", not an empty grid.

## Notes and risks

- A capture's stored position is the phone's last known fix and can be stale by minutes and hundreds of metres (experiment log, 2026-11-11). Place from a spoken or recorded place name is therefore preferred over position.
- These searches depend on him having said or the phone having stored a place. With location off and no spoken place they find by date and words only; the answer should say "I couldn't tell where these were made" rather than return nothing.

**Suggested builder: Sonnet.** Router patterns, one pure place function and a grid, all against a seeded database.
