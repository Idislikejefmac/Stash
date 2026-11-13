# 15 ◆ · Spike: does SQLCipher work with this Room, this schema and this phone? (one to two hours)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Prove now, cheaply, that database encryption can be added later without changing anything else, so that postponing it (card 14) is a decision and not a gamble. An experiment with a written result, not a feature.

## Depends on

01. Run it straight after card 01.

## Context

`docs/STORAGE.md` recommends leaving the database readable during development and encrypting it when the app becomes a release build. That is only safe if the encrypted engine is a drop-in replacement. Things that could make it not so: the library and Room disagreeing about versions; full-text search (`@Fts4`) missing from the encrypted engine's build; the native library not loading on Android 17 with 16 KB memory pages; a slow open.

Facts from a research helper on 2026-11-11, to confirm here: `net.zetetic:sqlcipher-android` 4.20.0, Room 2.x through `SupportOpenHelperFactory`; 16 KB page support claimed since 4.6.1 with one report to the contrary; the size of the arm64 library and whether its build includes FTS5 could not be found.

## Scope

1. **Throwaway code only**, in the `debug` source set, `debugImplementation` dependency, behind a debug menu item "Encryption spike". Nothing in `src/main` changes.
2. Copy the live database to a scratch file. Convert the copy with `sqlcipher_export` to an encrypted scratch file using a random 32-byte raw key held in memory.
3. Open the encrypted copy with Room through the SQLCipher factory. Run: count of every table; a full-text search; an insert and delete inside a transaction; Room's migration validation (the identity hash must match).
4. Time, five runs each, plain against encrypted: cold open; a full-text search; reading 200 captures with their records.
5. Print `PRAGMA compile_options` and `PRAGMA cipher_version` to the screen.
6. Delete the scratch files.

## Out of scope

Encrypting the real database. Key storage. Anything in `src/main`.

## Acceptance checks

- **Real phone, S25 Edge:** every item below written in `docs/EXPERIMENT_LOG.md` under "SQLCipher spike (card 15)":
  - Library version used; whether the app started (native library loaded) on this phone.
  - Whether the counts matched and the full-text search worked on the encrypted copy.
  - Whether FTS4, FTS5 and JSON1 appear in the compile options.
  - The timings, plain and encrypted.
  - Size of the debug APK before and after adding the dependency.
- **Developer PC:** `zipalign -c -P 16 -v 4 app-debug.apk` passes for the SQLCipher library (16 KB alignment).
- **Verdict line:** **Drop-in** (card 14 stands as written) / **Works with changes** (say which) / **Does not work** (say why; `docs/STORAGE.md` must then be revisited before more is built on Room).

## Notes and risks

- Leave the code in place; it is debug-only. Remove the `debugImplementation` line if it slows builds.
- If the verdict is not "Drop-in", stop and tell the owner before starting the next card.
