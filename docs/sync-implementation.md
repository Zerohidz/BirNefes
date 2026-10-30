# Sync implementation checkpoint

User authorizes implementation and deployment via `ssh zmanga`, no questions.
First deliverable 1.1.1 APK (local budget resets03) built, 83 Java checks pass;
agent ui_scout owns tests/emulator-renewal.mjs and is checking that APK.
Do not overwrite1.1.1 artifact; sync APK will be1.2.0.

Rules: shared5 windows,30s wait before allocation,10min fixed usage,22:30-03
only one remaining5min right; existing window crossing22:30 counts and caps22:35.
Quota date is local Istanbul date shifted at03; active window capped at renewal.
Server owns clock/quota; SQLite transaction serializes allocation; active session
shared across devices/tabs. No new grants offline; valid current grant can finish.

Deployment: Debian13 root via ssh alias zmanga. Existing nginx HTTPS host
zmanga.zerohidz.com proxies Kavita at5000 and /obsidian-sync/ to5984.
Add isolated /bir-nefes/ location to loopback Python service; preserve routes.
Device bearer credentials generated privately under ignored .build, only hashes
on server. Private ready-to-load extension folder under artifacts and paired APK.
Never print credentials, commit them, or expose artifacts on public web routes.

Build implementation: stdlib Python + SQLite behind nginx/systemd; vanilla MV3
extension matches only web.whatsapp.com; native Android RemoteGate client.
Keep UI consistent with existing dark green Bir Nefes blocker.
Final checks: server race/boundary tests, Java state tests, native remote flow,
Chromium extension UI/runtime including real30seconds, authenticated deployed
endpoint health. Use dedicated test credentials/db for tests, leave personal
production quota unused. Update README with setup instructions and limitations.

## Current checkpoint

Production service deployed and healthy, quota unused0. QA service exists at
/bir-nefes-qa/ port8766 with separate DB and credentials. Remove its nginx route,
service and remote QA credentials after tests. Native sync11 checks passed against
latest retry-enabled QA APK; current production artifact is rebuilt with real
config. Chrome smoke currently running; previous pass confirmed actual30sec wait,
shared expiry, reload, active expiry and Home, but test expected Chrome newtab URL
instead of canonical new-tab-page. Assertion fixed for latest run. Browser plugin
bootstrap returned no available browsers, so testing isolated bundled Chromium via
Playwright (official extension testing path).

Remaining: finish Chrome smoke and inspect screenshots, cleanup QA remote service,
verify production health/unauthorized access and zero personal usage, final docs
and artifact paths. Existing local-only ledger is not imported into new shared
server allowance (explicit in README); do not claim otherwise.

## Delivered verification

1.1.1 local APK:83 state checks +7 renewal UI checks.
1.2.0 sync:10 backend policy tests +11 native sync checks +13 extension smoke
checks +5 browser edge checks, plus final popup text/layout check. Real logged-out
WhatsApp Web shows blocker; strict CSP/Trusted Types works; offline cached grant
expires. Native remains an emulator surrogate test, not a physical phone test.
QA remote route/service/credentials/DB removed after tests. Production service and
existing nginx/Kavita active; startup enabled. Personal quota remains unused5.
Paired production APK and extension folder/ZIP are ready under artifacts/.
Browser extension installation and phone APK installation remain user-side steps.
