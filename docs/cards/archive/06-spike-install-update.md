# 06 · Spike: getting the app onto the family's phones and keeping it updated

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Decide how the wife's Motorola and the parents' Samsung get the app and its updates with the least effort for them. Needed before card 29, not before the owner starts using the app himself.

## Depends on

None. Needs the owner's answer to QUESTIONS.md Q4 (is a $25 Play developer account acceptable).

## Context

The owner builds and installs with Android Studio on his own phone. For the other two phones:

- **Samsung Auto Blocker** is on by default and refuses app installs from anywhere but the Play Store and Galaxy Store. It has to be switched off (Settings → Security and privacy → Auto Blocker) for each sideloaded install or update.
- **Google developer verification**: from 2027 (earlier in a few countries) Android will refuse sideloaded apps whose developer has not registered with Google. `adb install` stays allowed. A free "limited distribution" account for hobbyists (reported limit 20 devices, no government ID) is the intended route for cases like this one. Dates for the US were not confirmed when this was written.
- **Google Play internal testing** lets a developer account publish a build to up to 100 invited Google accounts. It is not listed publicly, updates arrive automatically, and none of the sideloading obstacles apply. It costs $25 once and identity verification.

## Scope

Using the TriggerLab app from card 01 (or any trivial signed release APK), try and log under "Installing and updating" in `docs/EXPERIMENT_LOG.md`:

1. **Sideload on the Flip 7** with Auto Blocker on: what message appears? With it off: how many taps to install from a downloaded APK? Does turning it back on affect the installed app?
2. **Sideload on the razr:** taps and warnings (Play Protect scan prompts included).
3. **Update by sideload:** install version 2 over version 1 on both. Does data survive? What does the user have to do?
4. **In-app update prompt:** a button in the app that downloads the APK from a URL and hands it to the system installer (`PackageInstaller` session; `REQUEST_INSTALL_PACKAGES`). Count the taps on each phone; note whether Auto Blocker stops it.
5. **Play internal testing** (only if the owner agreed in Q4): create the developer account, upload the APK to the internal testing track, invite the two family Google accounts, install from the opt-in link, then upload version 2 and note how long the update takes to arrive by itself.
6. Check Google's developer verification page for the current enforcement date in the United States and whether a limited-distribution account is open for registration; record both with the date checked.

## Out of scope

Building the real updater (card 29), signing-key management beyond creating one release key for the test.

## Acceptance checks

- **Real phone, Z Flip 7 and razr ultra:** each numbered step logged with a status mark.
- **Pass:** one method is recorded as the choice for each family phone, with the exact steps a non-technical person had to perform. Zero steps for an update is the goal.
- No unit tests.

## Notes and risks

- The release signing key must be created once and kept safe (outside the repository, with a backup). Changing it later forces an uninstall on every phone. Record where it is kept in the owner's own notes, not in the repository.
- If Play internal testing is chosen, the Play listing needs a privacy policy URL and a data-safety form even for internal tracks; a one-page statement ("stores everything on the device; sends note text to Anthropic if a key is set") is enough. Confirm during the spike whether background location or the assistant role triggers any review for an internal-only track.
