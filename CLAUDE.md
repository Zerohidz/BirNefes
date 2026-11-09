# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Bir Nefes: a voluntary self-control tool. An Android accessibility-service app blocker and a Chrome MV3 extension share one quota, held by a small private HTTPS service. There is no Gradle, npm, or third-party runtime dependency anywhere; the Android app is built with raw SDK tools, the extension is vanilla JS, and the server is Python stdlib + SQLite.

The README is the specification for the quota rules (windows, night rule, video schedule, urgent pass). Read it before changing any policy.

## Commands

Build (needs JDK 17+, Python 3, Android SDK platform 36 / build-tools 36.0.0, zip/unzip):

```sh
ANDROID_SDK_ROOT=/path/to/sdk bash build.sh   # artifacts/BirNefes-<versionName>.apk, no token; each install registers
BIRNEFES_LOCAL_ONLY=1 bash build.sh           # sync off, local DelayGate (also: BIRNEFES_SYNC_CONFIG=/missing/file)
BIRNEFES_TEST_HTTP=1 BIRNEFES_SYNC_CONFIG=cfg.json bash build.sh  # http:// test server, cleartext and cfg token: -http-test.apk, never distribute
python3 scripts/package-extension.py          # artifacts/BirNefes-Chrome/, zip, signed crx, updates.xml; needs chromium and .build/extension.pem
```

Local tests, no device or network:

```sh
mkdir -p .build/tests
javac -d .build/tests src/com/zerohidz/whatsapppause/DelayGate.java tests/DelayGateTest.java
java -cp .build/tests DelayGateTest
javac -d .build/tests src/com/zerohidz/whatsapppause/WindowEventGate.java tests/WindowEventGateTest.java
java -cp .build/tests WindowEventGateTest
javac -d .build/tests src/com/zerohidz/whatsapppause/CallScreenGate.java tests/CallScreenGateTest.java
java -cp .build/tests CallScreenGateTest
javac -d .build/tests src/com/zerohidz/whatsapppause/AccountStore.java tests/AccountStoreTest.java
java -cp .build/tests com.zerohidz.whatsapppause.AccountStoreTest
javac -d .build/tests src/com/zerohidz/whatsapppause/ExceptionRules.java tests/ExceptionRulesTest.java
java -cp .build/tests ExceptionRulesTest
javac -d .build/tests src/com/zerohidz/whatsapppause/CheckDelay.java tests/CheckDelayTest.java
java -cp .build/tests CheckDelayTest
javac -d .build/tests src/com/zerohidz/whatsapppause/PickerPass.java tests/PickerPassTest.java
java -cp .build/tests PickerPassTest
javac -d .build/tests src/com/zerohidz/whatsapppause/LockRules.java tests/LockRulesTest.java
java -cp .build/tests LockRulesTest
javac -d .build/tests src/com/zerohidz/whatsapppause/BrowserUrl.java tests/BrowserUrlProbe.java tests/BrowserUrlTest.java
java -cp .build/tests BrowserUrlTest
javac -d .build/tests src/com/zerohidz/whatsapppause/ExtraLimit.java tests/ExtraLimitTest.java
java -cp .build/tests com.zerohidz.whatsapppause.ExtraLimitTest
python3 -m unittest discover -s server -v
```

Single backend test (imports `app`, so run from `server/`):

```sh
cd server && python3 -m unittest test_app.PolicyTests.test_shared_window_and_no_extension
```

The Java tests are plain `main` programs with hand-rolled `check()` assertions, so they run all-or-nothing and stop at the first failure.

Integration tests (`tests/emulator-*.mjs`, `tests/extension-*.mjs`) are run with `node` and are not self-contained:

- `emulator-*`: require `ANDROID_SERIAL=emulator-...` on a disposable rooted emulator (`ADB` overrides the binary). Fixture APKs come from `tests/build-fixture.sh` and `tests/build-pane-fixture.sh`. The fixture is a labeled `com.whatsapp` surrogate.
- `extension-*`: require Playwright (`PLAYWRIGHT_MODULE` for an absolute `index.mjs` path), `.build/qa-sync-config.json`, and a deployed QA authority whose URL ends in `/bir-nefes-qa`. That QA service was removed after the last run and has to be recreated first.

Never point these at production; they seed the database they talk to.

Production operations are in the README's "Server operations" section. `server/deploy.sh` changes the live host over `ssh zmanga`.

## Architecture

**The server is the authority; clients only render its answers.** `server/app.py` (`Store`) owns the clock and quota in SQLite and serializes allocation in transactions. It listens on 127.0.0.1:8765 behind nginx at `/bir-nefes/`. Android and Chrome call `/v1/enter|complete|status|emergency` with a per-device bearer token and a client-generated `attempt` ID; Chrome's video category uses the separate `/v1/video-*` operations. Once issued, a deadline is shared across devices and never extended by re-entry.

**Android** (`src/com/zerohidz/whatsapppause/`):

- `PauseService` receives `TYPE_WINDOW_STATE_CHANGED` events and draws the blocking overlay.
- `AppPolicy` decides allowed vs. restricted by package name. A package is allowed if it is in the hardcoded/derived `essential` set or the user's exception list. Everything else is restricted by default.
- `WindowEventGate` orders window transitions so stale events cannot start a block during Home navigation.
- `CallScreenGate` decides overlay hiding during ringing and the call-aware Home skips; `CallWatcher` feeds it call states (`READ_PHONE_STATE`), and `AudioManager` mode is the fallback without that permission. Hiding is visual: gate state and `tick` keep running.
- `MainActivity`, `RulesActivity` and `ExceptionsActivity` share the look in `Ui` (at most one primary button per screen). The exception list never shows package names. Its file format and name search live in plain-Java `ExceptionRules`. `PickerPass` lets the system file picker through while the exceptions screen waits for it. `CheckDelay` holds the 0.5–3 s blocker wait for the server's answer.
- `RemoteGate` is the quota client when `SyncConfig.ENABLED` (a URL is set; default `https://zmanga.zerohidz.com/bir-nefes`). Its token comes from `Account`/`AccountStore`: saved token in the `account` prefs (survives updates), else `/v1/register` with install = SHA-256(ANDROID_ID + ":birnefes"). A 401 drops that token and the next call registers again (same install, same account). `MainActivity`'s "Hesap" card pairs devices via `/v1/pair-code` and `/v1/pair`. `DelayGate` is the local state machine used only in a local-only build. Both drive the same `DelayGate.State`, so `PauseService` branches on `remote != null`.

**Chrome** (`extension/`): `background.js` is the only code that talks to the server, and it validates that messages come from a target site or the extension itself. `content.js` draws the blocker, `video.js` handles the metered video behavior, and `sites.js` holds the target-domain classification.

## Things that must change together

- **Policy constants** live in both `server/app.py` and `DelayGate.java`, and the values also appear in user-facing strings in `PauseService`, `MainActivity`, and the extension. The README and tests state them as well.
- **Target domains** live in both `extension/sites.js` and the `content_scripts` matches/`include_globs` in `extension/manifest.json`.
- **Version** lives in both `AndroidManifest.xml` (`versionCode` + `versionName`) and `extension/manifest.json`. The artifact filenames derive from these.
- **Accessing a new Android package** via `PackageManager` intents may need a matching `<queries>` entry in `AndroidManifest.xml`.

## Constraints

- **Privacy is a product guarantee.** The accessibility service reads package identities, with one window-content exception since 1.8.2 (the user's decision): in the browser in front it reads the address-bar node (`BrowserUrl` picks it by view id or editability and an address-like text) to see whether it shows YouTube. That text is matched and dropped, never stored, sent or logged; the debug log has view ids only. `canRetrieveWindowContent` is true for that reason alone; `canPerformGestures` is false, and nothing else reads event text, nodes, or screenshots. Call handling reads the call state only, never the number. One narrow exception: while the guardian lock holds, `LockRules` compares Settings window-state event titles (Samsung opens every Settings page as `SubSettings`); titles are never stored or logged. Do not add these to solve a detection problem.
- **Credentials:** No APK or extension package carries a token: devices register or pair and keep the token in app storage (Android `account` prefs, Chrome `storage.local`), which survives updates. Only a `BIRNEFES_TEST_HTTP=1` build embeds the config's token, which it saves on first use. `SyncConfig.java` is generated into `.build/generated/` by `scripts/generate-sync-config.py`. `.build/` and `artifacts/` are gitignored and may hold real credentials; the server stores hashes only. With no `.build/sync-config.json`, `build.sh` still produces a synced APK against the default URL; only `BIRNEFES_LOCAL_ONLY=1` or an explicit `BIRNEFES_SYNC_CONFIG` pointing at a missing file produce a local-only APK.
- **Keep `.build/debug.jks`.** It is the signing identity that allows in-place updates.
- **Releasing an APK** means uploading it to `/var/www/birnefes`, writing `surum.json` with the new `versionCode`, and repointing the `BirNefes-son.apk` symlink; installed apps announce the update from that file.
- **Keep `.build/extension.pem`.** It fixes the Chrome extension ID that `scripts/install-chromium-lock.sh` force-installs.
- **Do not reset the production database.** Rotating credentials means deploying new hashes and rebuilding both paired artifacts.
- **Emulator UI checks:** `uiautomator dump` rebinds accessibility services, and `PauseService` goes Home on every reconnect. Use screenshots and fixed taps instead.
- **Verification gap:** emulator tests do not cover real calls, notifications, TalkBack, OEM battery handling, or split-screen. These need a physical device.
