# Bir Nefes 1.3.1

All former ten-minute allowances are fifteen minutes: phone/messaging sessions,
monthly urgent passes and the cumulative Chrome video budget. Counts, free video
hours, the thirty-second wait, five-minute night allowance and renewal times remain
unchanged. Existing active grants finish at their issued deadline. Consumed video
time is retained against the increased budget.

Home navigation is guarded before removing the blocker. Pane and non-activity
window events cannot open a new session; older events cannot override a newer
foreground transition. This addresses the reported blocker reappearing on Home
without a fixed debounce that would permit quick reentry. No accessibility nodes
or screen text are read.

Verification: 30 backend tests, 83 local quota assertions, 11 window-transition
assertions and 26 focused native emulator checks passed. Native checks include
three Home exits with a delayed reappearance check, fast reentry, a real thirty-
second wait, both fifteen-minute grant types and fixed deadlines. Five browser
checks passed for the updated popup and real logged-out YouTube against an isolated
QA account. Screenshots were inspected. The user's physical Samsung and actual
WhatsApp event sequence were not tested.

The server was backed up and updated through ssh zmanga. Health/version and shared
account consistency were checked with read-only requests; personal quotas were not
reset. The production APK was rebuilt with the real configuration and existing
signing key. The extension folder and versioned ZIP were updated.

- APK: artifacts/BirNefes-1.3.1-test.apk (versionCode7)
  SHA256: cda919de9fc4c6b8ecb08736da634cbe5186dab5e0c0a6d9bed8acc981dcc60b
- Extension: artifacts/BirNefes-Chrome or BirNefes-Chrome-1.3.1.zip
  ZIP SHA256: 030d7086bdeb712097d8af2c5c818b0e58efeaf414e172a5365ce71b774ac6ef

Install the APK over the old app. Reload the extension in chrome://extensions and
refresh existing target tabs. Paired artifacts contain private credentials.

Additional regression evidence: a disposable fixture sent a pane-disappeared event
500ms after leaving and a generic FrameLayout window event after1000ms. Both sends
were logged; the launcher stayed unobstructed at3seconds. Fresh Photos, YouTube
and fixture activities still blocked. Evidence: artifacts/qa/home-result.json,
home-pane-result.json, home-pane-events.log and home-pane-launcher.png. Temporary
QA route, service, remote credentials and database were removed after verification.
