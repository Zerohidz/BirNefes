# Bir Nefes 1.3.0 — delivered

The user authorized implementation and deployment through `ssh zmanga`.
Production data and credentials were preserved. The repository has no commits and
no CodeGraph index; an empty Git diff does not describe its current changes.

## Current behavior

- Android restricts all apps except the exception list. Phone, SMS, contacts, home,
  keyboard, permission dialogs and system settings remain accessible. Camera,
  calendar, maps, clock and calculator are initial editable exceptions.
- Android and Chrome messaging share five fixed ten-minute windows, with a
  thirty-second wait. Reentry never extends them. Existing night rules and the
  03:00 Istanbul renewal remain in effect.
- Chrome video sites have a separate budget: free at 05:00–08:00 and 17:00–22:30;
  ten cumulative minutes at 08:00–17:00. Visible, focused tabs consume time.
  The server measures elapsed milliseconds and counts overlapping leases once.
- An ordinary close sends a stop request. A crash or lost stop request can charge
  up to the final five-second lease. Offline metered access stops at lease expiry.
- Four monthly urgent passes are shared across devices and categories. The stated
  default is ten minutes immediately, independent of daily allowances, renewing
  on the first of the month at 03:00. The other stated default is that video sites
  are blocked at 22:30–05:00 unless an urgent pass is used. These two defaults were
  offered as asynchronous questions; the user did not explicitly confirm them.
- Phone browsers still use the phone quota. Chrome's video schedule applies only
  where the desktop extension is installed.

README.md contains the complete behavior and installation instructions.

## Verification

- 28 backend tests, including migration, races, monthly boundaries and video time.
- 83 local Java state assertions.
- 26 native emulator UI checks, including editable exceptions, urgent confirmation,
  unchanged daily consumption, reentry, active expiry and unlock-to-Home.
- 27 Chromium checks: category isolation, short visits, stop/reentry, urgent passes,
  time boundaries, audio restoration, popup and offline expiry. Zero page errors.

The Browser plugin returned no connected browsers. Browser testing used an isolated
Playwright Chromium instance, the actual MV3 extension, surrogate web documents
and an independent HTTPS QA authority with a controlled clock. Screenshots were
inspected at desktop and narrow widths. Native tests used installed emulator apps.
Physical phones, actual calls/SMS transmission and OEM behavior remain untested.

A native defect was found and fixed: USER_PRESENT came from SystemUI's own UID,
so the receiver needed RECEIVER_EXPORTED for its protected system actions.
A browser test fixture initially returned HTML for new-tab JavaScript; limiting
the fixture to the intended hosts removed that test error.

Evidence:
- `tests/emulator-general.mjs`, `artifacts/qa/general-result.json`, `general-*.png`.
- `/tmp/bir-nefes-browser-general.mjs`, `/tmp/bir-nefes-browser-qa/`.

## Deployment and artifacts

An online SQLite backup and previous server code were saved in
`/var/backups/bir-nefes` before deployment. Production 1.3.0 health/authentication,
shared Android/Chrome quota and the separate video endpoint were verified.
The existing nginx and Kavita services remain active; Bir Nefes starts at boot.
The temporary QA route, service, remote credentials, database and clock fixture
were removed. Tests did not consume or reset personal production allowances.

The paired APK was rebuilt using the production configuration, checked against
accidental QA configuration, and signed with the existing key. Packaging now
reads version numbers from manifests to prevent mislabeled release files.

- `artifacts/BirNefes-1.3.0-test.apk`, versionCode 6.
  SHA256: `0b3902d0eb12f7f336787efe0039c7e0ad2db155542e55ca35e4770769c82daa`
- `artifacts/BirNefes-Chrome/` and `artifacts/BirNefes-Chrome-1.3.0.zip`.
  ZIP SHA256: `2aca2405cb1edac6e3489ae233d721fdf7f2b47105c9ab605836704be97356ef`

These paired artifacts contain private credentials. Do not publish them.
User-side steps remain: install the APK over the old version, update/reload the
extension and refresh already open restricted tabs.
