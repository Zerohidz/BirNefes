# Bir Nefes / WhatsAppPause

Android app blocker and Chrome extension, backed by a private HTTPS quota service
on `zmanga.zerohidz.com/bir-nefes`. Server time is **Europe/Istanbul**.

Android restricts every app except its exception list. Phone, SMS, contacts, home,
keyboard, permission dialogs and system settings are always available. Camera,
calendar, maps, clock and calculator start as exceptions. Use **İstisna uygulamaları
seç** in Bir Nefes to change the list. Newly installed ordinary apps are restricted.
Phone browsers use the phone quota, including sites opened inside them.

Chrome restricts only these categories; other websites stay available:

| Category | Sites | Rule |
| --- | --- | --- |
| Messaging | WhatsApp (including Business web), Telegram / t.me | Shared with phone's daily windows |
| Video / film | YouTube / youtu.be, tranimeizle, fullhdfilmizlesene | Separate daily viewing time |

Named film-site domain families include their subdomains and changing top-level
domains. The extension does not cover unrelated mirrors or native desktop apps.

## Phone + messaging rules

- Five usage windows per budget day, renewed at **03:00 Europe/Istanbul**.
- A new window requires **30 seconds** of waiting, then grants **15 minutes**.
- Leaving/reentering, reloading, or switching devices never extends the deadline.
- Foreground expiry displays a blocker with **Ana sayfaya dön**. Another window
  needs leaving and reentering; it cannot start automatically behind the blocker.
- **22:30–03:00:** at most one remaining right, lasting **five minutes**. Zero
  remaining rights stays zero. The night rule continues through midnight.
- An existing session spanning 22:30 counts as that final night right and ends no
  later than 22:35, without extending an earlier deadline. This applies even away.
- Every ordinary session ends by the next 03:00 renewal. Rights persist on the server across
  device restarts. SQLite serializes simultaneous starts so only one right is used.
- Server connectivity is required to enter/obtain a new grant. A currently open,
  already authorized screen can finish its cached grant during an outage; local
  monotonic timers still enforce its deadline. Reopening requires a server check.

## Chrome video / film rules

- **05:00–08:00:** free.
- **08:00–17:00:** fifteen minutes total, accumulated across short visits. No 30-second
  wait and no fixed fifteen-minute session. This does not consume phone/message rights.
- **17:00–22:30:** free.
- **22:30–05:00:** blocked, unless a monthly urgent pass is used.
- Only a visible, focused video tab consumes the metered budget. Leaving the tab,
  switching windows or navigating away pauses playback and sends a stop message.
  Multiple overlapping tab requests count elapsed time once, not once per tab.
- The server measures milliseconds; the popup displays remaining seconds. Metered
  access uses five-second server leases refreshed every two seconds. If a tab
  crashes or the stop request is lost, up to the last five seconds can be charged.
  Offline metered viewing stops at the current lease deadline. A cached free
  period ends at its scheduled boundary even during a network outage.
- The extension pauses page media, exits fullscreen/Picture-in-Picture and mutes
  blocked tabs (including embedded player audio). Previous mute state is restored
  on access or navigation away. This is not DRM or a tamper-proof restriction.

## Monthly urgent pass

**Acele geçişi** appears on the blocker, with a second confirmation before spending.
It immediately gives fifteen minutes without a wait, including at night. Four passes
are shared by Android and both Chrome categories per month, renewed on the first
at **03:00 Istanbul**. No carry-over. Daily counts and video time are not refilled
or deducted. Reentry reuses the same pass. It needs a server connection to start;
an already issued pass keeps its original deadline. It can cross a day/month
boundary and finish its full fifteen minutes. Phone/SMS never need a pass.

## 1.3.1 update

All former ten-minute allowances are now fifteen minutes: ordinary windows,
monthly urgent passes and the cumulative Chrome video budget. The thirty-second
wait, five-minute night allowance, counts and renewal times are unchanged.
Existing active grants keep their issued deadline; new grants use fifteen minutes.
Already consumed video time is retained against the increased budget.

Home navigation now suppresses transitions from the departing app until the
launcher/allowed window is observed. Pane events and non-activity windows cannot
start a new blocking session. Older queued events cannot override a newer window.
The service still does not retrieve accessibility nodes or screen content.

## Ready-to-install artifacts

- `artifacts/BirNefes-1.3.1-test.apk`: paired Android client, versionCode 7.
- `artifacts/BirNefes-Chrome/`: paired, unpacked extension folder.
- `artifacts/BirNefes-Chrome-1.3.1.zip`: same extension as a ZIP.
- `artifacts/BirNefes-1.1.1-test.apk`: previous offline/local build with 03:00 renewal.

Install 1.3.1 over the existing Android app and enable its accessibility service.
Android may require **Allow restricted settings** for a sideloaded service.
In Chrome, open `chrome://extensions`, enable **Developer mode**, choose
**Load unpacked**, and select the `artifacts/BirNefes-Chrome` folder. Reload any
already open restricted tabs. For an existing installation, press **Reload** on
the extension card; Chrome may request the expanded site permissions. No token entry is needed in these paired artifacts.
The extension only applies in browsers/profiles where it is installed and enabled.

Paired APK/extension artifacts contain private device credentials. Keep these
artifacts private. Credentials are generated in ignored `.build/sync-config.json`;
the server stores only their hashes. The source tree contains no credentials.
Upgrading from synced1.2.0 preserves the existing server quota and credentials.
The old1.1.x local-only ledger was never imported into the server ledger.

This is a voluntary self-control tool: disabling the service/extension or using
another browser bypasses it. It is not parental control or an OS-level app lock.

## Privacy

The Android service reads package identities only: no messages, accessibility
window contents, gesture injection, or screenshots. The extension places an overlay
and makes the underlying document inert; it does not inspect messages. HTTPS carries
only device authentication, attempt IDs, allowance counts and timing information.
There is no analytics. The existing server applications remain on their own routes.

## Build

Requires JDK 17+, Python3, Android SDK platform 36/build-tools36.0.0 and zip/unzip.

```sh
ANDROID_SDK_ROOT=/path/to/sdk bash build.sh
python3 scripts/package-extension.py
```

Both builds read `.build/sync-config.json`. The APK generator also accepts
`BIRNEFES_SYNC_CONFIG=/path/to/config.json`. Missing config builds a local-only APK.
Keep `.build/debug.jks` to preserve the test signing identity for in-place updates.
Never distribute a QA APK with test authority credentials as the production build.

## Latest verification (1.3.1)

30 backend tests, 83 local quota assertions, 11 window-transition assertions and
26 native emulator checks passed. Delayed pane/generic window events after Home
were exercised by a disposable fixture; the launcher remained clear and genuine
app reentry still blocked. Five browser checks verified fifteen-minute labels and
successful access on real logged-out YouTube using an isolated QA account.
See `docs/release-1.3.1.md` for evidence and limitations.

## Verification

```sh
mkdir -p .build/tests
javac -d .build/tests src/com/zerohidz/whatsapppause/DelayGate.java tests/DelayGateTest.java
java -cp .build/tests DelayGateTest
javac -d .build/tests src/com/zerohidz/whatsapppause/WindowEventGate.java tests/WindowEventGateTest.java
java -cp .build/tests WindowEventGateTest
python3 -m unittest discover -s server -v
```

The local Java suite covers 83 state checks including midnight continuation and the
exact 03:00 boundary. Backend tests cover atomic concurrent completion, replay,
shared access, five rights, month/night/renewal boundaries, urgent-pass races,
legacy migration, cumulative video usage and restart persistence (30 tests).

- `tests/emulator-smoke.mjs`: local 1.1.1 baseline scenarios.
- `tests/emulator-renewal.mjs`: seven focused 03:00 assertions against 1.1.1.
- `tests/emulator-general.mjs`: 26 checks of general app blocking, exceptions,
  urgent confirmation, fixed expiry and screen unlock (1.3.0 emulator).
- `tests/emulator-sync.mjs`: dedicated HTTPS QA authority + native Android flow.
- `tests/extension-smoke.mjs`: 13 checks of the real MV3 extension in isolated Chromium, served
  WhatsApp surrogate, dedicated live HTTPS QA authority. Requires Playwright;
  optionally set `PLAYWRIGHT_MODULE` to its absolute `index.mjs` path.
- `tests/extension-edge-smoke.mjs`: five checks of real logged-out WhatsApp Web,
  strict CSP/Trusted Types and local expiry during a network outage.

Integration checks require a temporary deployed QA service; recreate a
separate QA authority before rerunning integration scripts. Never substitute the
production database/credentials in these scripts.

1.3.0 Chrome verification passed27checks: category isolation, cumulative seconds,
stop/reentry, urgent passes, time boundaries, audio restore, popup and offline
expiry. Evidence: `/tmp/bir-nefes-browser-qa/`; test source:
`/tmp/bir-nefes-browser-general.mjs`. These used a separate clock-controlled QA
authority and surrogate web pages. The temporary remote QA service was removed.

QA configurations/databases are separate from the personal production allowance.
Tests may seed the QA database or shorten a QA lease; production durations remain
unchanged. Screenshots/results live in ignored `artifacts/qa/`. Native tests use a
labeled `com.whatsapp` surrogate, not real WhatsApp or a physical phone. Calls,
notifications, TalkBack, OEM battery handling and split-screen need device testing.

## Server operations

Python standard library + SQLite, no third-party runtime dependencies. The service
binds 127.0.0.1:8765 behind the existing nginx TLS endpoint. Systemd runs it as the
restricted `bir-nefes` user and restarts it on failure. `server/deploy.sh` installs
using the authorized `ssh zmanga` alias, backs up nginx before adding its route,
and validates config before reloading. It preserves an existing credential file
locally to avoid accidental device re-pairing.

```sh
ssh zmanga 'systemctl status bir-nefes --no-pager'
curl --fail https://zmanga.zerohidz.com/bir-nefes/health
ssh zmanga 'journalctl -u bir-nefes --no-pager -n 30'
```

Data: `/var/lib/bir-nefes/usage.sqlite3`; credential hashes:
`/etc/bir-nefes/devices.json`; executable: `/opt/bir-nefes/app.py`.
Back up the database with SQLite's online backup API before planned maintenance.
To rotate credentials, generate new private client credentials, deploy the new
hashes and rebuild/reinstall both paired artifacts. Do not reset the database.
