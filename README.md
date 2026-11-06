# Bir Nefes / WhatsAppPause

Android app blocker and Chrome extension, backed by a private HTTPS quota service
on `zmanga.zerohidz.com/bir-nefes`. Server time is **Europe/Istanbul**.

Android restricts every app except its exception list. Phone, SMS, contacts, home,
keyboard, permission dialogs and system settings are always available. Camera,
calendar, maps, clock and calculator start as exceptions. Use **İstisnalar** in Bir
Nefes to change the list. Newly installed ordinary apps are restricted.
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
- A new window requires **30 seconds** of waiting, then grants **20 minutes**.
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
- **08:00–17:00:** twenty minutes total, accumulated across short visits. No 30-second
  wait and no fixed twenty-minute session. This does not consume phone/message rights.
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
It immediately gives twenty minutes without a wait, including at night. Four passes
are shared by Android and both Chrome categories per month, renewed on the first
at **03:00 Istanbul**. No carry-over. Daily counts and video time are not refilled
or deducted. Reentry reuses the same pass. It needs a server connection to start;
an already issued pass keeps its original deadline. It can cross a day/month
boundary and finish its full twenty minutes. Phone/SMS never need a pass.

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

## 1.3.2 update

Incoming phone calls can be answered. System in-call screens, including Samsung's
`com.samsung.android.incallui`, are always available; a third-party in-call app
qualifies only as the default dialer. While a call rings the blocker is hidden,
with its state and timers untouched, and it returns when ringing stops. During an
answered call restricted apps stay blocked, as hiding applies to ringing only.
Home is not forced on unlock or service reconnect during a call. Turning the
screen back on before the phone locks returns Home if a block was active.

**Aramalar için izin ver** in Bir Nefes grants `READ_PHONE_STATE`. If refused,
blocking still works and the audio mode is used instead: silent or vibrate calls
stay covered by the blocker, and it stays hidden for the whole call.
WhatsApp and other VoIP calls are not covered and behave as before.

## 1.4.0 update

Ordinary windows, urgent passes and the Chrome video budget are twenty minutes.
The thirty-second wait, five-minute night allowance, counts and renewal times are
unchanged. Grants issued before the update keep their deadline, and video time
already spent stays spent. Until the server is updated a 1.4.0 client says twenty
minutes while the server still grants fifteen.

The main screen shows only the status card, **İstisnalar**, **Kontrol süresi**,
**Kurallar** and, while running, **Bir Nefes'i durdur**, which asks for confirmation.
The rules and privacy text moved to **Kurallar**.

**İstisnalar** lists apps by name and icon, never by package name, in three
sections: exceptions, restricted apps and the collapsed always-open system apps.
Search matches names only and treats i, ı, İ and I as the same letter. The ⋮ menu
exports and imports the user's list as JSON through the system file picker:

```json
{"app":"bir-nefes","type":"exceptions","version":1,"exportedAt":"<ISO>","packages":["..."]}
```

The default file name is `bir-nefes-istisnalar-YYYY-MM-DD.json`. Always-open system
apps are never written. An import must be at most 256 KB, with at most 2000 valid
package names. A preview counts the apps not installed on this phone, which are
kept anyway. After an import, **Geri al** restores the previous list until the
screen is closed. A broken file leaves the list unchanged. While Bir Nefes waits
for the file picker, the system picker opens without a block and without using a
right, for at most five minutes. It never becomes a permanent exception.

**Kontrol süresi** sets how long the blocker waits for the server's answer on a
fresh entry: 0.5 to 3 seconds in half-second steps, 1 second by default.

## Kardeş onayı (guardian lock, Android 1.5.0)

An optional second person, the guardian (kardeş), makes the phone side harder to
switch off on impulse. Phone only: Chrome is unchanged.

Flow:

1. In the **Hesap** card, **Kardeş onayı** takes the guardian's e-mail and **Ekle**
   sends a verification mail. The lock starts when the guardian confirms on the
   web page linked in that mail. There is no approval screen on the phone.
2. While locked, the main screen shows **İzin iste** instead of **Bir Nefes'i durdur**.
   It sends an optional short reason (at most 200 characters) to the guardian, who
   chooses 1 hour, 24 hours, permanent removal or deny. Permanent removal deletes the
   guardian and the lock ends by itself on the next status answer.
3. While locked, exceptions can be removed and exported but not added or imported,
   and **Kontrol süresi** can only be shortened.
4. While locked, the service covers these windows with a lock screen (**Bu ayar
   kilitli.**, **Ana sayfaya dön**, **İzin iste**). It reads only the package and
   class name of the window: accessibility settings, the package installer's
   uninstall confirmation and Settings app-info pages. The rules are one list in
   `LockRules.java`. Other Settings pages such as Wi-Fi stay open. The lock screen
   does not use a right or start a timer.
5. **Kardeşi değiştir** asks the current guardian first; the new address is then
   verified the same way.

While released (until the time the guardian chose) or with no guardian, everything
works as before. The last guard answer is stored on the phone and refreshed when the
main screen opens and on the service's normal status calls; there is no extra poll.
Offline, the stored answer can only restrict: a stored lock holds, and a stored
release ends at its own time.

Limits: this is still a voluntary tool. Safe mode, `adb`, a factory reset or another
device bypass it. It needs a synced build and the server. Settings pages reached
through a generic class (AOSP `SubSettings`, `spa.SpaActivity` on Android 14+) are not
covered, so the path Settings → Accessibility from the Settings home page and the
app-info page on recent Pixel/AOSP builds stay open. Samsung class names are not
measured yet.

Privacy: the guardian's e-mail address and the release reason are stored on the
server and sent to the guardian by mail. The phone shows the address masked
(`a***@example.com`). Window detection still reads no screen content.

## Ready-to-install artifacts

- `artifacts/BirNefes-1.4.0-test.apk`: paired Android client, versionCode 9.
- `artifacts/BirNefes-Chrome/`: paired, unpacked extension folder.
- `artifacts/BirNefes-Chrome-1.4.0.zip`: same extension as a ZIP.
- `artifacts/BirNefes-1.1.1-test.apk`: previous offline/local build with 03:00 renewal.

Install 1.4.0 over the existing Android app and enable its accessibility service.
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
window contents, gesture injection, or screenshots. With permission it also reads
the call state (idle, ringing, in call) on the device; the phone number is never
read, stored, logged or sent. The extension places an overlay
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
`BIRNEFES_SYNC_CONFIG=/path/to/config.json`. Without a config the APK syncs with
`https://zmanga.zerohidz.com/bir-nefes` and registers its own account on first use.
`BIRNEFES_PUBLIC=1` leaves the personal token out (`artifacts/BirNefes-<version>.apk`);
`BIRNEFES_LOCAL_ONLY=1`, or `BIRNEFES_SYNC_CONFIG` naming a missing file, builds a local-only APK.
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
javac -d .build/tests src/com/zerohidz/whatsapppause/CallScreenGate.java tests/CallScreenGateTest.java
java -cp .build/tests CallScreenGateTest
javac -d .build/tests src/com/zerohidz/whatsapppause/ExceptionRules.java tests/ExceptionRulesTest.java
java -cp .build/tests ExceptionRulesTest
javac -d .build/tests src/com/zerohidz/whatsapppause/CheckDelay.java tests/CheckDelayTest.java
java -cp .build/tests CheckDelayTest
javac -d .build/tests src/com/zerohidz/whatsapppause/PickerPass.java tests/PickerPassTest.java
java -cp .build/tests PickerPassTest
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
