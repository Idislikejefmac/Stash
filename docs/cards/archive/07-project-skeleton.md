# 07 · Project skeleton

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

An empty but well-formed Android project that builds, runs a placeholder screen, runs unit tests, and enforces the privacy boundary on network code from the first commit.

## Depends on

None.

## Context

This is a new project in `C:\jeffsource\PersonalAssistant\`. A `.gitignore` and a `docs/` folder already exist; leave them. The `spikes/` folder (if present) holds throwaway projects and is not part of this build. The owner uses Android Studio on Windows; the JDK is Android Studio's bundled one (`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` in PowerShell).

## Scope

1. Gradle project with the Kotlin DSL and a version catalog (`gradle/libs.versions.toml`), one module `app`, `applicationId` `app.stash`, `minSdk 35`, `compileSdk`/`targetSdk 37`, Kotlin with the Compose compiler plugin, Java 17 toolchain. Use current stable versions of the Android Gradle Plugin, Kotlin, Compose BOM and AndroidX libraries; write the versions chosen in the hand-back.
2. Only `arm64-v8a` in `ndk.abiFilters` (all three phones are 64-bit ARM; this keeps later native libraries small).
3. Manifest: no `INTERNET` permission yet (card 21 adds it); `android:allowBackup="false"` and a `dataExtractionRules` XML that excludes everything from cloud backup and device transfer; one launcher activity.
4. Compose theme: Material 3, light and dark, dynamic colour off (consistent look across Samsung and Motorola), a type scale whose body text is at least 18 sp, and a `LargeButton` composable with a 64 dp minimum height. A placeholder home screen with the app name.
5. The package folders from 00-READ-FIRST.md, each with a `package-info`-style `README` comment file or a placeholder so the layout is visible.
6. Test setup: JUnit, kotlinx-coroutines-test, Turbine, Robolectric. One trivial passing test.
7. **Boundary test** `NetworkBoundaryTest` (JVM): walks `app/src/main/java`, fails if any file outside `app/stash/model/hosted/` and `app/stash/update/` imports `okhttp3.`, `java.net.`, `javax.net.`, `io.ktor.`, `android.webkit.`, `com.anthropic.` or `android.net.http.`. This is a text scan, simple on purpose.
8. **Logging rule:** a tiny `Log` wrapper (`app.stash.Dbg`) that is a no-op in release builds; a second boundary test fails if `android.util.Log` is imported anywhere else.
9. Release build type with R8 minification and resource shrinking on, signing config read from `keystore.properties` if present (file ignored by git), otherwise unsigned.
10. Update the root `README.md` "Building" section with the exact commands that worked.

## Out of scope

Any feature. No database, no permissions, no services.

## Acceptance checks

- **Unit (JVM):** `./gradlew testDebugUnitTest` passes, including both boundary tests. Adding `import java.net.URL` to a file in `app.stash.ui` makes `NetworkBoundaryTest` fail (try it, then remove it).
- **Build:** `./gradlew assembleDebug` and `./gradlew assembleRelease` succeed.
- **Real phone, S25 Edge:** `./gradlew installDebug` installs; the placeholder screen shows; at system font size "largest" nothing is clipped.

## Notes and risks

- If `compileSdk 37` is not yet supported by the stable Android Gradle Plugin on the owner's machine, use the newest that is and say so; do not use preview plugins without asking.
- Do not add Hilt or another dependency-injection framework. A hand-written `AppContainer` object created in `Application.onCreate` is enough for an app this size and keeps the build simple.
