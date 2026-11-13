# Storage decision record

Written 2026-11-11 (revision 2); revision 3 added the section "Pictures, codes, private items and provider records". It answers one worry: *am I being casual about storage now in a way that forces an overhaul of the backend later?* Short answer: no, provided the short list under "Decide now" is built into card 01. Everything else on this page, including all the encryption, can be added later in days, not rewrites.

Confidence marks as in DESIGN.md. Library facts were read on 2026-11-11 by a research helper from the vendors' pages; **none has been tried on the owner's phone**. Card 15 is a one-to-two-hour spike that tries the ones that matter.

## The recommendation in five lines

1. **Now (card 01):** plain Room, as today, with the seven "decide now" rules below built in. Readable database while the app is a debug build.
2. **Straight after card 01 (card 15, an hour or two):** prove on the phone that SQLCipher opens this database through Room with full-text search working. Then stop thinking about it.
3. **Before relying on the app, in this order:** encrypted backup with a recovery code (card 10), app gate (card 02), whole-database encryption with SQLCipher and the move to a release-signed build (card 14).
4. **If the notes come to hold things worth a determined attacker's effort, or before a product:** the sealed inbox, which makes "a locked phone cannot read notes" a cryptographic fact.
5. **Never:** field-level encryption of the working database; a pseudonym dictionary in the database; a server of ours.

The most secure arrangement that keeps search and answers fast is **3 plus 4**: SQLCipher for the whole database with a hardware-held key, the app gate, and the sealed inbox for notes added while locked. Its speed cost on reading is too small to notice at this size, because reading only ever happens while the phone is unlocked and the database is open.

## The options compared

| Option | Security gained | Speed cost on search and answers | Cost to move to it from the prototype | When to choose it |
| --- | --- | --- | --- | --- |
| **Plain Room** (today) | Android's own storage encryption (the files are unreadable while the phone is off or not yet unlocked since restart); app-private storage. Nothing against a copy of the file taken from an unlocked phone, a debug-build pull, or a stray backup. | None | None | **Now**, while the build is a debug build. A debug build lets an authorised computer read the app's files and has a dump command by design, so encrypting its database protects almost nothing. |
| **Whole-database encryption: SQLCipher under Room**, random 256-bit key wrapped by a hardware-backed Android Keystore key | A copy of the database file is useless without this phone's secure hardware. Covers every table, the search index, and (through per-file keys kept in the database) every picture. **Does not** stop code running as the app, and because notes are added while locked, the key must work while locked: so it does not by itself enforce "reading needs unlock". | Vendor's figure: "as little as 5–15% overhead" on database work (medium: their claim, not a benchmark). At a few thousand short rows that is well under a millisecond per query. Opening the database is fast **if the key is given as raw bytes**; given as a passphrase SQLCipher runs 256,000 rounds of key stretching on every open (high: vendor design page). Card 15 measures both on the phone. | One library, one factory line in the Room builder, a one-time copy of the plain file into an encrypted file with `sqlcipher_export`, a native library in the APK. Half a day, most of it the conversion's safety checks. **Nothing else in the app changes**, which is what card 15 proves. | **When the app becomes a release build** and before anything sensitive goes in. Card 14. |
| **Field-level encryption** (encrypt chosen columns in the app, leave the database plain) | Protects those columns in a copied file. Leaves structure, counts, dates, kinds and everything unencrypted readable. | **Severe.** An encrypted column cannot be indexed, sorted, or full-text searched; every search becomes decrypt-everything-and-scan, or needs a second, plain index that leaks the words anyway. | High and permanent: every query that touches a protected column must be rewritten, and stays awkward. | **Don't.** It is the right tool when a server must hold data it may not read. Here the phone both holds and reads it. |
| **Sealed inbox** (the database's wrapping key is usable only while the phone is unlocked; a note added while locked is encrypted to a public key and parked in a file; the matching private key works only after unlock; parked notes are folded in at the next unlock) | Turns "reading needs unlock" from a rule the app follows into something the hardware enforces. Against a thief with a locked phone who can run code as the app, the notes stay closed. Android documents exactly this split: public-key operations are allowed while locked, and keys marked unlocked-device-required are refused (high: Android's `KeyGenParameterSpec` reference). | **None on reading.** Reading happens unlocked, with the database open as usual. The cost is elsewhere: a note added while locked is not filed, searchable or able to start a reminder until the next unlock; and nothing in the background can read the database while the phone is locked. | Moderate **if** three seams exist from the start (they are in "Decide now"): one way in for captures; background work that tolerates "database not available"; reminders that post from a stored plan. With them it is one card of perhaps two or three days. Without them it touches every worker and receiver, which is why the first design called it the most intricate card in the project. | **Later**, when the notes are worth it or before other people rely on it. Known caution: the unlocked-device rule has had bugs (one let a paired watch count as an unlock; medium, from an Android bug mirror). Test on the phone first. |
| **End-to-end encrypted backup** (one file, encrypted on the phone with a random key shown once as a recovery code, written to a folder he picks) | Closes the one real gap of local-only storage: a lost phone. Whoever holds the file sees its size and date and nothing else. | None (it runs in the background) | One card (10). Independent of the database's own encryption, because the backup is an export, not a copy of the file. | **Before relying on the app, and before card 14 and the release build**, both of which are moments when a slip loses the notes. |
| **End-to-end encrypted sync between devices**, if ever | A second device with the same guarantees | None on reading | Not planned. **Kept cheap** by the rules below: if truth is "captures plus the owner's actions", both append-only with permanent ids, then syncing two devices is exchanging encrypted change files through a folder both can reach, with no server of ours. Without those rules it would be a redesign. | Only if a second device becomes real. |

Two things that are sometimes proposed and are not on the list. **Jetpack Security** (`EncryptedFile`, `EncryptedSharedPreferences`): Google has deprecated all of it in favour of the platform Keystore used directly (high: AndroidX release notes), which is what the prototype's `SecretStore` already does. **A dictionary that swaps words for random identifiers inside the database:** examined in DESIGN.md §19 and rejected for storage; the idea is used where it works, in the diagnostic report.

## Decide now: expensive to change later

These go into card 01 (and cards 06 and 13 where noted). None adds a feature; together they are perhaps a day of work.

| # | Rule | Why it is expensive later | Where |
| --- | --- | --- | --- |
| 1 | **All data access through one layer.** Screens and workers use repositories; the database is opened in exactly one place (`Container.store()`), which is allowed to answer "not available right now". | Encryption, the sealed inbox, a different database engine and a new Room major version each change *how the database is opened*. With one door, each is one change. With DAOs called from screens, each is a hunt. | 01, 13 |
| 2 | **A permanent random id on every row; no code parses an id; no auto-increment number is ever a row's identity.** | Backup merge, restore onto a phone that already has notes, and sync all need "the same row" to mean the same thing on two databases. Renumbering rows later means rewriting every reference. | 01 |
| 3 | **Three kinds of data kept apart: what he said (captures), what he did (revisions), and what the app worked out (everything else).** The first two are append-only and are the only things that cannot be rebuilt. | This is what makes a model upgrade, a wrong guess, a restore and a sync all cheap. If the app's guesses and his own actions are mixed in the same columns with no record of which is which, none of them is. *Revision 2 adds the middle one: every owner action writes a revision row.* | 01 |
| 4 | **A versioned schema with a hand-written, tested migration for every change, from version 1.** Schema files exported and committed. No destructive fallback anywhere. | The first lost note costs more trust than all the migration tests cost time. And a habit of tested migrations is what lets feature tables (pictures, trips, watches) arrive one card at a time instead of being guessed at now. | 01, every card that adds a table |
| 5 | **A plain, open export format** with its own format number, covering every table that is memory rather than history, and files. | It is the escape hatch from every other decision on this page: any storage change can be done as export and import. It is also the backup format. | 10 (shape fixed by 01's tables) |
| 6 | **`updated_at` on every row that can change, and deletions leave a content-free tombstone.** | Without them, merging a backup into a phone cannot tell which copy is newer, and a note he deleted comes back from last month's backup. Adding the columns later is easy; back-filling truthful values is impossible. | 01 |
| 7 | **Deleting is defined once**: removing a capture removes the model's stored output for it, its records, its revisions, its search entries, its picture files, and blanks any answer trace that quoted it. | "Delete" that leaves the words in three other tables is the kind of privacy bug that is found by someone else. Every new table makes a late fix harder. | 01, then each feature card |
| 8 | **One way in for anything that arrives while the phone is locked** (`InboundSink`), and **background work that copes with "database not available"**; **reminders posted from a stored plan** (a count worked out while the data was at hand). | These are the three seams the sealed inbox needs. Built in now they cost a few lines each. Retrofitted, they are an audit of every worker, receiver and alarm. | 13, 06 |
| 9 | **Keys are always wrapped, never derived, and every encrypted thing starts with a format-version byte.** The data key is random; a Keystore key wraps it. | Changing the protection (the sealed inbox changes the wrapping key's rule) or rotating a key is then re-wrapping 32 bytes, not re-encrypting the data. | 14, 16, 10 |
| 10 | **A stable signing key from now.** | Android ties an app's data and its Keystore keys to its signature. Today the app is signed with this PC's throwaway debug key (code read: no signing configuration). A build from another PC, a lost debug key, or the move to a release build signed differently each force an uninstall, and an uninstall destroys the notes. This is the likeliest way to lose everything during development. | 01 item 0 |
| 11 | **The application id** (`app.stash`). | It cannot be changed without a new install. Decide the name before the release build (QUESTIONS.md Q9). | Owner |

Challenges to the brief's working list, answered: *image files* need rule 9 and rule 7 and a row per file, and otherwise nothing now (card 16 brings its own table). *Answer traces* need nothing now beyond rule 7; their table arrives with card 08. *Watch definitions* and *trip records* are ordinary tables under rules 2, 4 and 6; designing their columns now would be guessing. *Key rotation* is rule 9. *Extractor versions* were already right in revision 1 (`extraction.extractor`, `schema_version`, the prompt version in the name); revision 2 adds the same idea for the picture-text engine, the router and the app build that made each capture.

## Pictures, codes, private items and provider records *(revision 3)*

**Pictures are files, not rows.** A row in `attachment` per picture (permanent id, the capture it belongs to, size, a hash, the words read from it, which engine read them) and one file in the app's private storage. Decisions, all taken when card 16 is built and none needed before:

| Question | Decision | Why |
| --- | --- | --- |
| Where | `files/att/`, random file names, app-private storage only | Not in the gallery, not in a gallery's cloud backup, unreadable by other apps |
| Encrypted how | Each file under its own random 256-bit key (AES-256-GCM, streaming), the key stored in the picture's row; a version byte in the file header (rule 9) | The file is exactly as protected as the database, automatically: no change to files when SQLCipher arrives, and no key to manage. Deleting the row destroys the key |
| Format and size | Lossy WebP, quality 75; long edge 1,600 px for photos, 2,000 px for screenshots, 2,400 px when the print is small | Android can write JPEG, PNG and WebP only. WebP is the smallest of the three at the same look. Text is read at full size before shrinking, so the stored copy only has to stay legible to a person |
| Storage cost | About 150–350 KB each (estimate); 1,000 pictures about a quarter of a gigabyte | Measured in card 16 |
| Speed | Decrypting a 300 KB file is a few milliseconds; thumbnails are made in memory and never written | No plaintext image ever on disk |
| Deleting | With the note, through the one defined delete (rule 7); a sweep removes any file without a row | |
| Backup and export | Inside the encrypted backup container as separate entries; export on request | The backup format is a zip from the start so it can hold files |
| Android backup and phone transfer | Excluded | A transferred file would arrive without a usable key |

**Scanned codes** are rows (`code_scan`): raw payload, symbology, parsed type, a hash for "seen before". No file unless he keeps the picture.

**Private items need no separate store.** One column, `sensitive`, on the capture (pictures and codes follow their capture). The protections are behaviour, not a second database: lookups take an explicit "include private" argument with no default; planners are never given private rows; one screen component decides whether private content is drawn. A separately encrypted "vault" was considered and not chosen: it would double the key handling and every query, for a gain that the sealed inbox (which protects *everything* while locked) delivers better. The one storage consequence: the search index holds private notes' words like any other, so previews are suppressed in code, not by leaving them out of the index; that keeps "1 private note also matches" possible.

**Provider-neutral records.** Nothing a provider returns is stored in that provider's shape. `extraction.output_json` holds the app's own extraction schema; `extraction.extractor` holds `provider:model/prompt-version`; token counts are plain numbers; the answer trace stores the app's neutral tool calls. Changing provider therefore changes no stored data and needs no migration, and two providers' filings of the same note can sit side by side for comparison. The API keys are one entry per provider in `SecretStore`, never exported or backed up.

## Cheap to add later

| Thing | Why it is cheap | Size |
| --- | --- | --- |
| SQLCipher | Rule 1 and card 15's proof | half a day |
| The app gate | One wrapper around the one reading activity | half a day |
| Encrypted backup, restore | The export format already exists in shape | one card |
| Picture-file encryption | Each file has its own key in its row from the first picture; the database's encryption then covers the keys with no change to the files | built with card 16 |
| The sealed inbox | Rule 8 | two to three days |
| FTS5 instead of FTS4, or a different index | The index is rebuilt from the tables at any time and is never backed up | hours |
| Moving to Room 3 | Room 3 removed the older database-opening API that Room 2 and SQLCipher's classic factory use; SQLCipher added a driver for Room 3 in version 4.18 (high for both: release notes). Under rule 1 it is a change in one place. No reason to move now. | a day, whenever wanted |
| On-device embeddings for search | A second, rebuildable index beside the first | later, if the miss log asks |
| A second model provider | The seam is in card 04; stored data is provider-neutral | card 22, a day or two |
| A lighter permission tier | Every ability already has a switch and a do-nothing version (card 13) | a list |

## Things verified, and how to verify the rest

| Claim | Source and confidence | How to check |
| --- | --- | --- |
| `net.zetetic:sqlcipher-android` is the maintained library; latest 4.20.0 (27 October 2026); works with Room 2 through `SupportOpenHelperFactory`; from 4.18.0 also has `SQLCipherDriver` for Room 3; needs `androidx.sqlite` 2.7.0 and compileSdk 37 | High: project README and release notes (helper) | Card 15 |
| Room 2.8.4 (the prototype's version) still supports the older opening API; Room 3 does not | High for Room 3 (release notes); medium for 2.8's dual mode (migration guide summary) | Card 15 opens the database through it |
| Licence: BSD-style; the notice must appear somewhere the user can reach. Closed-source and paid apps are permitted | High for the terms (vendor licence page); the "paid apps" reading is an inference from BSD terms | Read `zetetic.net/sqlcipher/license` before a product; card 14 adds the notice |
| 16 KB memory-page support (needed on current Android) since 4.6.1 | Medium: vendor says yes; one user report against an older version | `zipalign -c -P 16` on the built APK (card 15) |
| Size added to the APK for arm64 | **Not found.** Expect a few megabytes | Compare APK sizes in card 15 |
| The prebuilt library includes FTS4 (which Room's `@Fts4` needs), FTS5, JSON functions | **Not verified** | `PRAGMA compile_options` in card 15. If FTS4 were missing the whole recommendation would need rethinking, which is why the spike is early |
| Overhead "5–15%" | Medium: vendor's own figure | Timings in card 15 |
| Public-key encryption works while locked; unlocked-device-required keys do not | High: Android reference text | A sealed-inbox spike before building it |
| Keystore keys do not move to a new phone, so a transferred encrypted database would be unreadable | Medium: library maintainers' statements; Android says keys are non-exportable | Card 14 excludes the files from device transfer so the question never arises |
| Tink 1.24.0 has hybrid (public-key) encryption; streaming encryption for files on Android | High for hybrid (release notes); streaming on Android assumed from long-standing support, not re-read | Card 10's container round-trip test |

Sources: [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android) and its [releases](https://github.com/sqlcipher/sqlcipher-android/releases) · [SQLCipher licence](https://www.zetetic.net/sqlcipher/license/) · [SQLCipher design](https://www.zetetic.net/sqlcipher/design/) · [Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3) · [KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder) · [Jetpack Security release notes](https://developer.android.com/jetpack/androidx/releases/security) · [Auto Backup](https://developer.android.com/identity/data/autobackup) · [Tink Java releases](https://github.com/tink-crypto/tink-java/releases). All read by a research helper on 2026-11-11.

## What this costs the owner to believe

- Until card 14, a copy of the database taken from the unlocked phone is readable. That is the stated condition of the development stage, and PRIVACY.md says what to keep out meanwhile.
- After card 14 and until the sealed inbox, "reading needs your unlock" is enforced by the app, not by the hardware. PRIVACY.md says so in plain words.
- None of the encryption has been reviewed by anyone independent. Before a product, it should be.
