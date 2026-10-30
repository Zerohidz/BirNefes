import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const adb = process.env.ADB ?? 'adb';
const serial = process.env.ANDROID_SERIAL;
assert.ok(serial?.startsWith('emulator-'), 'Only a disposable rooted emulator is supported');
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
const shell = (...args) => call('shell', ...args);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const pkg = 'com.zerohidz.whatsapppause';
const component = `${pkg}/${pkg}.PauseService`;
const prefs = `/data/user/0/${pkg}/shared_prefs/usage.xml`;
const focus = () => shell('dumpsys', 'window').split('\n').find(line => line.includes('mCurrentFocus=')) ?? '';
const target = () => shell('am', 'start', '-f', '0x14000000', '-n', 'com.whatsapp/qa.TargetActivity');
const home = async () => { shell('input', 'keyevent', '3'); await wait(450); };
const now = () => Number(shell('date', '+%s').trim()) * 1000;
const day = () => shell('date', '+%F').trim();
const setTime = epoch => shell('date', '-s', `@${Math.floor(epoch / 1000)}`);
const readUsage = () => {
  const xml = shell('cat', prefs);
  return { used: Number(xml.match(/name="used" value="(\d+)"/)?.[1]),
    end: Number(xml.match(/name="end" value="(\d+)"/)?.[1]),
    nightUsed: /name="nightUsed" value="true"/.test(xml) };
};
const checks = [];
function check(condition, description) { assert.ok(condition, description); checks.push(description); console.log(`PASS: ${description}`); }
mkdirSync('artifacts/qa', { recursive: true });
const screenshot = name => writeFileSync(`artifacts/qa/${name}.png`, execFileSync(adb, ['-s', serial, 'exec-out', 'screencap', '-p']));
const oldServices = shell('settings', 'get', 'secure', 'enabled_accessibility_services').trim();
const oldEnabled = shell('settings', 'get', 'secure', 'accessibility_enabled').trim();
const oldAutoTime = shell('settings', 'get', 'global', 'auto_time').trim();
const originalTime = now();
const started = Date.now();
const restoreSetting = (namespace, key, value) => value === 'null'
  ? shell('settings', 'delete', namespace, key) : shell('settings', 'put', namespace, key, value);
async function enable() {
  shell('settings', 'put', 'secure', 'enabled_accessibility_services', component);
  shell('settings', 'put', 'secure', 'accessibility_enabled', '1');
  await wait(900);
}
async function disable() {
  shell('settings', 'delete', 'secure', 'enabled_accessibility_services');
  await wait(400);
}
// Explicit fixture setup: rooted-emulator preferences, never production data.
async function seed(used, end = 0, nightUsed = false) {
  await home(); await disable(); shell('am', 'force-stop', pkg);
  const xml = `<?xml version="1.0" encoding="utf-8"?><map><string name="day">${day()}</string><int name="used" value="${used}"/><boolean name="nightUsed" value="${nightUsed}"/><long name="end" value="${end}"/></map>`;
  execFileSync(adb, ['-s', serial, 'shell', `cat > ${prefs}`], { input: xml });
  // Keep app ownership after replacing fixture contents.
  const uid = shell('stat', '-c', '%u', `/data/user/0/${pkg}`).trim();
  shell('chown', `${uid}:${uid}`, prefs);
  shell('am', 'start', '-n', `${pkg}/.MainActivity`); await wait(350);
  await enable(); await home();
}
try {
  shell('settings', 'put', 'global', 'auto_time', '0');
  call('install', '-r', 'artifacts/BirNefes-1.1.1-test.apk');
  call('install', '-r', '.build/fixture/target.apk');
  await disable(); shell('pm', 'clear', pkg);
  shell('am', 'start', '-n', `${pkg}/.MainActivity`); await enable();
  await wait(400); screenshot('setup'); await home();
  // Use deterministic daytime while preserving the original date.
  const noon = Number(shell('date', '-d', '12:00:00', '+%s').trim()) * 1000;
  setTime(noon); target(); await wait(700);
  check(focus().includes(pkg), '30-second overlay owns focus'); screenshot('countdown');
  shell('input', 'tap', '500', '320'); await wait(350);
  check(focus().includes(pkg), 'Countdown blocks underlying target touch');
  await wait(10000);
  check(focus().includes(pkg), 'Countdown remains blocked after former 10-second threshold');
  await wait(21000);
  check(focus().includes('qa.TargetActivity'), 'Full 30-second countdown releases target');
  let usage = readUsage();
  check(usage.used === 1 && usage.end > now() + 590000 && usage.end <= now() + 600000, 'Completed countdown consumes one fixed ten-minute use');
  screenshot('after-countdown');
  shell('input', 'tap', '500', '320'); await wait(400);
  check(focus().includes('qa.ChatActivity'), 'Same-package navigation preserves access');
  await home(); target(); await wait(500);
  check(focus().includes('qa.TargetActivity') && readUsage().end === usage.end, 'Reentry preserves the original deadline');
  shell('input', 'tap', '540', '447'); await wait(900);
  check(!/com.whatsapp|com.zerohidz.whatsapppause/.test(focus()), 'System file picker opens');
  shell('input', 'keyevent', '4'); await wait(500);
  check(focus().includes('qa.TargetActivity') && readUsage().end === usage.end, 'Returning from file picker preserves use');
  // Advance real wall clock with target foreground; production duration stays unchanged.
  setTime(usage.end + 1000); await wait(700);
  check(focus().includes(pkg), 'Fixed deadline blocks an actively used foreground target'); screenshot('expired');
  shell('input', 'keyevent', '4'); await wait(500);
  check(!/com.whatsapp|com.zerohidz.whatsapppause/.test(focus()), 'Expired-screen Back exits to Home');
  target(); await wait(500);
  check(focus().includes(pkg) && readUsage().used === 1, 'Next entry requires a new countdown without consuming it early');
  await seed(5); target(); await wait(700); screenshot('exhausted');
  check(focus().includes(pkg) && readUsage().used === 5, 'Injected five-use budget blocks further access');
  // Coordinates observed from this fixed 1080x2400 emulator screenshot.
  shell('input', 'tap', '540', '1418'); await wait(500);
  check(!/com.whatsapp|com.zerohidz.whatsapppause/.test(focus()), 'Exhausted-screen Home button exits target');
  // Night fixture: first use after 22:30 must be a real 30-second countdown and five minutes.
  const late = Number(shell('date', '-d', '22:31:00', '+%s').trim()) * 1000;
  setTime(late); await seed(1); target(); await wait(700);
  check(focus().includes(pkg), 'Night entry also starts a 30-second countdown');
  await wait(30500); usage = readUsage();
  check(focus().includes('qa.TargetActivity') && usage.used === 2 && usage.nightUsed && usage.end > now() + 295000 && usage.end <= now() + 300000, 'Night countdown grants exactly one five-minute use');
  setTime(usage.end + 1000); await wait(600); await home(); target(); await wait(600);
  check(focus().includes(pkg) && readUsage().used === 2, 'Night budget blocks another use despite three daytime uses remaining'); screenshot('night-exhausted');
  // Persisted active window fixture proves service restart does not replace its deadline.
  setTime(noon + 3600000); const persistedEnd = now() + 120000;
  await seed(3, persistedEnd); target(); await wait(600);
  check(focus().includes('qa.TargetActivity') && readUsage().end === persistedEnd, 'Service restart restores persisted active deadline');
  await home(); shell('am', 'start', '-n', `${pkg}/.MainActivity`); await wait(500); screenshot('setup-final');
  shell('input', 'tap', '540', '1653'); await wait(600);
  check(focus().includes('com.android.settings'), 'Setup button opens accessibility settings');
  shell('input', 'keyevent', '4'); await wait(600);
  shell('input', 'tap', '540', '1798'); await wait(600);
  check(!shell('settings', 'get', 'secure', 'enabled_accessibility_services').includes('PauseService'), 'Stop button disables accessibility service');
  await home(); target(); await wait(600);
  check(focus().includes('qa.TargetActivity'), 'Disabling service removes enforcement');
  writeFileSync('artifacts/qa/result.json', JSON.stringify({ version: '1.1.1', platform: 'Android 16 API 36 rooted emulator', target: 'com.whatsapp surrogate, not real WhatsApp', fixtureSetup: 'Injected budget preferences for quota/restart cases; advanced emulator wall clock for foreground expiry and night cases; real production 30-second waits and unchanged duration constants', checks, result: 'passed' }, null, 2));
} finally {
  await home(); await disable();
  setTime(originalTime + Date.now() - started);
  restoreSetting('global', 'auto_time', oldAutoTime);
  restoreSetting('secure', 'enabled_accessibility_services', oldServices);
  restoreSetting('secure', 'accessibility_enabled', oldEnabled);
}
