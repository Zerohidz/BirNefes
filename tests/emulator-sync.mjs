import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const adb = process.env.ADB ?? 'adb';
const serial = process.env.ANDROID_SERIAL;
assert.ok(serial?.startsWith('emulator-'), 'Use a disposable emulator only');
const configPath = process.env.BIRNEFES_QA_CONFIG ?? '.build/qa-sync-config.json';
const config = JSON.parse(readFileSync(configPath));
assert.ok(config.url.endsWith('/bir-nefes-qa'), 'Only the dedicated QA authority may be mutated');
const apk = process.env.BIRNEFES_QA_APK ?? '.build/qa-sync.apk';
const dbPath = process.env.BIRNEFES_QA_DB;
assert.ok(dbPath?.includes('qa') && /^[\w/.-]+$/.test(dbPath), 'Explicit QA-only database path required');
const host = process.env.BIRNEFES_QA_SSH ?? 'zmanga';
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
const shell = (...args) => call('shell', ...args);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const pkg = 'com.zerohidz.whatsapppause';
const component = `${pkg}/${pkg}.PauseService`;
const focus = () => shell('dumpsys', 'window').split('\n').find(line => line.includes('mCurrentFocus=')) ?? '';
const target = () => shell('am', 'start', '-f', '0x14000000', '-n', 'com.whatsapp/qa.TargetActivity');
const home = async () => { shell('input', 'keyevent', '3'); await wait(450); };
const api = async (operation = 'status', token = config.androidToken) => {
  // Read-only test observation tolerates transient host TCP loss; app requests remain unchanged.
  for (let retry = 0; ; retry++) {
    try {
      const response = await fetch(`${config.url}/v1/${operation}`, {headers: {Authorization: `Bearer ${token}`}});
      assert.equal(response.status, 200, 'QA authority request succeeds'); return response.json();
    } catch (error) { if (retry >= 2) throw error; await wait(500); }
  }
};
function mutate(sql) {
  const script = `import sqlite3\nwith sqlite3.connect(${JSON.stringify(dbPath)}) as db:\n db.executescript(${JSON.stringify(sql)})\n`;
  execFileSync('ssh', ['-o', 'BatchMode=yes', host, 'python3', '-'], { input: script });
}
const checks = [];
function check(condition, text) { assert.ok(condition, text); checks.push(text); console.log(`PASS: ${text}`); }
mkdirSync('artifacts/qa', {recursive: true});
const screenshot = name => writeFileSync(`artifacts/qa/${name}.png`, execFileSync(adb, ['-s', serial, 'exec-out', 'screencap', '-p']));
const oldServices = shell('settings', 'get', 'secure', 'enabled_accessibility_services').trim();
const oldEnabled = shell('settings', 'get', 'secure', 'accessibility_enabled').trim();
const oldWifi = shell('settings', 'get', 'global', 'wifi_on').trim();
const oldData = shell('settings', 'get', 'global', 'mobile_data').trim();
const restore = (namespace, key, value) => value === 'null'
  ? shell('settings', 'delete', namespace, key) : shell('settings', 'put', namespace, key, value);
async function disable() { shell('settings', 'delete', 'secure', 'enabled_accessibility_services'); await wait(500); }
async function enable() {
  shell('settings', 'put', 'secure', 'enabled_accessibility_services', component);
  shell('settings', 'put', 'secure', 'accessibility_enabled', '1'); await wait(800);
}
try {
  await api(); // Validate isolated endpoint before touching fixture data.
  await home(); await disable();
  call('install', '-r', apk); call('install', '-r', '.build/fixture/target.apk');
  mutate("UPDATE quota SET day='',used=0,night=0,end=0; DELETE FROM attempts;");
  shell('svc', 'wifi', 'enable'); shell('svc', 'data', 'enable'); await wait(1500);
  shell('am', 'start', '-n', `${pkg}/.MainActivity`); await wait(700); screenshot('sync-setup');
  await enable(); await home(); target(); await wait(1500);
  check(focus().includes(pkg), 'Synced entry blocks target while checking server and waiting'); screenshot('sync-countdown');
  check((await api()).used === 0, 'Starting server countdown does not consume a use');
  await wait(10500);
  check(focus().includes(pkg), 'Synced countdown remains blocked after ten seconds');
  await wait(21000);
  let usage = await api();
  check(focus().includes('qa.TargetActivity') && usage.used === 1 && usage.state === 'allowed', 'Real 30-second server countdown grants one shared use');
  const chromeView = await api('status', config.chromeToken);
  check(chromeView.session === usage.session && chromeView.expiresAt === usage.expiresAt && chromeView.used === usage.used, 'Chrome credential observes the identical Android session and quota');
  check(usage.expiresAt - usage.serverNow > 585000 && usage.expiresAt - usage.serverNow <= 600000, 'Server grants a fixed ten-minute daytime deadline');
  await home(); target(); await wait(1600); const reentry = await api();
  check(focus().includes('qa.TargetActivity') && reentry.expiresAt === usage.expiresAt && reentry.used === 1, 'Synced reentry reuses identical server deadline and quota');
  // Isolated QA server fixture shortens the current window; production constants remain unchanged.
  mutate("UPDATE quota SET end=CAST(strftime('%s','now') AS INTEGER)*1000+2000;");
  await wait(6500);
  check(focus().includes(pkg), 'Server deadline shortening blocks an actively used foreground target'); screenshot('sync-expired');
  shell('input', 'keyevent', '4'); await wait(600);
  check(!/com.whatsapp|com.zerohidz.whatsapppause/.test(focus()), 'Synced expiry Back returns Home');
  shell('svc', 'wifi', 'disable'); shell('svc', 'data', 'disable'); await wait(1000);
  target(); await wait(10500);
  check(focus().includes(pkg) && (await api()).used === 1, 'Offline new entry stays blocked without granting or consuming a use');
  screenshot('sync-offline');
  // Observed ERROR overlay button position on fixed1080x2400 emulator, verified visually.
  shell('input', 'tap', '540', '1440'); await wait(600);
  check(!/com.whatsapp|com.zerohidz.whatsapppause/.test(focus()), 'Offline Home button exits blocked target');
  writeFileSync('artifacts/qa/sync-result.json', JSON.stringify({version:'1.2.0', apkSha256:createHash('sha256').update(readFileSync(apk)).digest('hex'),
    platform:'Android16 API36 emulator', target:'com.whatsapp surrogate, not real WhatsApp',
    fixtureSetup:'Dedicated HTTPS QA authority/account and QA-configured APK; real30-second wait; isolated QA database deadline shortened for foreground expiry; emulator Wi-Fi/mobile data disabled for offline case',
    checks,result:'passed'},null,2));
} finally {
  await home(); await disable();
  shell('svc','wifi',oldWifi === '1' ? 'enable' : 'disable');
  shell('svc','data',oldData === '1' ? 'enable' : 'disable');
  restore('secure','enabled_accessibility_services',oldServices);
  restore('secure','accessibility_enabled',oldEnabled);
}
