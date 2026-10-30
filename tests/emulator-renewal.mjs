import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const adb = process.env.ADB ?? 'adb';
const serial = process.env.ANDROID_SERIAL;
assert.ok(serial?.startsWith('emulator-'), 'Use a disposable rooted emulator only');
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
const shell = (...args) => call('shell', ...args);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const pkg = 'com.zerohidz.whatsapppause';
const prefs = `/data/user/0/${pkg}/shared_prefs/usage.xml`;
const apk = 'artifacts/BirNefes-1.1.1-test.apk';
const focus = () => shell('dumpsys', 'window').split('\n').find(line => line.includes('mCurrentFocus=')) ?? '';
const now = () => Number(shell('date', '+%s').trim()) * 1000;
const setTime = epoch => shell('date', '-s', `@${Math.floor(epoch / 1000)}`);
const target = () => shell('am', 'start', '-f', '0x14000000', '-n', 'com.whatsapp/qa.TargetActivity');
const home = async () => { shell('input', 'keyevent', '3'); await wait(350); };
const readUsage = () => {
  const xml = shell('cat', prefs);
  return { day: xml.match(/<string name="day">([^<]+)<\/string>/)?.[1],
    used: Number(xml.match(/name="used" value="(\d+)"/)?.[1]),
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
const restore = (namespace, key, value) => value === 'null'
  ? shell('settings', 'delete', namespace, key) : shell('settings', 'put', namespace, key, value);
async function disable() {
  shell('settings', 'delete', 'secure', 'enabled_accessibility_services'); await wait(400);
}
try {
  shell('settings', 'put', 'global', 'auto_time', '0');
  call('install', '-r', apk); call('install', '-r', '.build/fixture/target.apk');
  await home(); await disable(); shell('am', 'force-stop', pkg);
  const renewal = Number(shell('date', '-d', '03:00:00', '+%s').trim()) * 1000;
  const previousDay = shell('date', '-d', `@${Math.floor((renewal - 12 * 3600000) / 1000)}`, '+%F').trim();
  const renewedDay = shell('date', '-d', `@${Math.floor(renewal / 1000)}`, '+%F').trim();
  // Explicit injected setup: exhausted prior usage day survives calendar midnight.
  shell('mkdir', '-p', `/data/user/0/${pkg}/shared_prefs`);
  const xml = `<?xml version="1.0" encoding="utf-8"?><map><string name="day">${previousDay}</string><int name="used" value="5"/><boolean name="nightUsed" value="true"/><long name="end" value="0"/></map>`;
  execFileSync(adb, ['-s', serial, 'shell', `cat > ${prefs}`], { input: xml });
  const uid = shell('stat', '-c', '%u', `/data/user/0/${pkg}`).trim();
  shell('chown', '-R', `${uid}:${uid}`, `/data/user/0/${pkg}/shared_prefs`);
  setTime(renewal - 170 * 60000); // 00:10 on the next calendar day.
  shell('am', 'start', '-n', `${pkg}/.MainActivity`); await wait(300);
  shell('settings', 'put', 'secure', 'enabled_accessibility_services', `${pkg}/${pkg}.PauseService`);
  shell('settings', 'put', 'secure', 'accessibility_enabled', '1'); await wait(800);
  await home(); target(); await wait(600);
  let usage = readUsage();
  check(focus().includes(pkg) && usage.day === previousDay && usage.used === 5 && usage.nightUsed,
    '00:10 preserves prior-day exhausted budget and night restriction');
  screenshot('renewal-before-0300');
  setTime(renewal - 1000);
  usage = readUsage();
  check(now() < renewal && usage.day === previousDay && usage.used === 5 && focus().includes(pkg),
    '02:59:59 remains exhausted without early renewal');
  setTime(renewal); await wait(450);
  usage = readUsage();
  check(usage.day === renewedDay && usage.used === 0 && !usage.nightUsed,
    '03:00 renews all five uses and clears night restriction');
  await home(); target(); await wait(500);
  check(focus().includes(pkg) && readUsage().used === 0,
    'New usage day entry requires countdown without consuming a use early');
  screenshot('renewal-countdown');
  await wait(10000);
  check(focus().includes(pkg), 'Renewed day keeps the full 30-second wait');
  await wait(20500); usage = readUsage();
  check(focus().includes('qa.TargetActivity') && usage.used === 1 && !usage.nightUsed,
    'Completed post-03:00 countdown consumes first normal use');
  check(usage.end > now() + 590000 && usage.end <= now() + 600000,
    'Post-03:00 allowance lasts ten minutes rather than night five minutes');
  screenshot('renewal-allowed');
  writeFileSync('artifacts/qa/renewal-result.json', JSON.stringify({ version: '1.1.1',
    apkSha256: createHash('sha256').update(readFileSync(apk)).digest('hex'),
    platform: 'Android 16 API 36 rooted emulator', target: 'com.whatsapp surrogate, not real WhatsApp',
    fixtureSetup: 'Injected previous usage-day used=5/nightUsed=true; advanced emulator wall clock through 00:10, 02:59:59, 03:00; unchanged production APK and genuine 30-second countdown',
    checks, result: 'passed' }, null, 2));
} finally {
  await home(); await disable(); setTime(originalTime + Date.now() - started);
  restore('global', 'auto_time', oldAutoTime);
  restore('secure', 'enabled_accessibility_services', oldServices);
  restore('secure', 'accessibility_enabled', oldEnabled);
}
