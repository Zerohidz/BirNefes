import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const adb = process.env.ADB ?? 'adb';
const serial = process.env.ANDROID_SERIAL;
assert.ok(serial?.startsWith('emulator-'), 'Use a disposable emulator only');
const config = JSON.parse(readFileSync('.build/qa-sync-config.json'));
assert.ok(config.url.endsWith('/bir-nefes-qa'));
const apk = '.build/qa-sync.apk';
const pkg = 'com.zerohidz.whatsapppause';
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding:'utf8' });
const shell = (...args) => call('shell', ...args);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const focus = () => shell('dumpsys','window').split('\n').find(line=>line.includes('mCurrentFocus=')) ?? '';
const home = async()=>{shell('input','keyevent','3');await wait(400);};
const open = async(component)=>{shell('am','start','-f','0x14000000','-n',component);await wait(1600);};
const chrome = ()=>open('com.android.chrome/com.google.android.apps.chrome.Main');
const disable = async()=>{shell('settings','delete','secure','enabled_accessibility_services');await wait(500);};
const enable = async()=>{shell('settings','put','secure','enabled_accessibility_services',`${pkg}/${pkg}.PauseService`);shell('settings','put','secure','accessibility_enabled','1');await wait(900);};
const api = async()=>{
 for(let retry=0;;retry++)try{
  const r=await fetch(`${config.url}/v1/status`,{headers:{Authorization:`Bearer ${config.androidToken}`}});
  assert.equal(r.status,200);return r.json();
 }catch(error){if(retry>=2)throw error;await wait(500);}
};
function mutate(sql){execFileSync('ssh',['-o','BatchMode=yes','zmanga','python3','-'],{input:`import sqlite3\nwith sqlite3.connect('/var/lib/bir-nefes/qa.sqlite3') as db:\n db.executescript(${JSON.stringify(sql)})\n`});}
const checks=[];
const check=(value,description)=>{assert.ok(value,description);checks.push(description);console.log(`PASS: ${description}`);};
mkdirSync('artifacts/qa',{recursive:true});
const screenshot=name=>writeFileSync(`artifacts/qa/general-${name}.png`,execFileSync(adb,['-s',serial,'exec-out','screencap','-p'],{maxBuffer:8*1024*1024}));
// UIAutomator is used only with accessibility service disabled: dumping suppresses/restarts it.
const ui=()=>{shell('uiautomator','dump','/sdcard/general.xml');return shell('cat','/sdcard/general.xml');};
const node=(xml,text)=>xml.split('<node').find(n=>n.includes(`text="${text}"`)||n.includes(`&#10;${text}"`));
function tapNode(n){assert.ok(n,'Expected UI node');const b=n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);assert.ok(b);shell('input','tap',String(Math.round((+b[1]+ +b[3])/2)),String(Math.round((+b[2]+ +b[4])/2)));}
async function exceptions(){await open(`${pkg}/.MainActivity`);let xml=ui();for(let i=0;i<5&&!node(xml,'İstisna uygulamaları seç');i++){shell('input','swipe','540','2000','540','700','400');await wait(400);xml=ui();}tapNode(node(xml,'İstisna uygulamaları seç'));await wait(400);return ui();}
const oldServices=shell('settings','get','secure','enabled_accessibility_services').trim();
const oldEnabled=shell('settings','get','secure','accessibility_enabled').trim();
const restore=(key,value)=>value==='null'?shell('settings','delete','secure',key):shell('settings','put','secure',key,value);
try{
 await home();await disable();call('install','-r',apk);shell('pm','clear',pkg);
 mutate("UPDATE quota SET day='',used=0,night=0,end=0,kind='normal'; DELETE FROM attempts; UPDATE emergency SET month='',used=0; DELETE FROM emergency_requests;");
 await api();mutate('UPDATE quota SET used=5;');
 await open(`${pkg}/.MainActivity`);screenshot('setup-final-top');shell('input','swipe','540','2000','540','700','400');await wait(400);screenshot('setup-final-bottom');
 let xml=await exceptions(); screenshot('exceptions-default');
 for(const p of ['com.google.android.dialer','com.google.android.apps.messaging']){
  const n=node(xml,p);check(n?.includes('checked="true"')&&n.includes('enabled="false"'),`${p} is protected and cannot be unchecked`);
 }
 for(const p of ['com.android.camera2','com.google.android.calendar','com.google.android.apps.maps'])check(node(xml,p)?.includes('checked="true"'),`${p} is a default exception`);
 check(node(xml,'com.android.chrome')?.includes('checked="false"'),'Non-WhatsApp Chrome is blocked by default');
 tapNode(node(xml,'com.android.chrome'));await wait(300);screenshot('exception-selected');
 shell('input','keyevent','4');shell('am','force-stop',pkg);
 xml=await exceptions();check(node(xml,'com.android.chrome')?.includes('checked="true"'),'Exception checkbox survives application process restart');
 shell('input','keyevent','4');await enable();await home();await chrome();
 check(focus().includes('com.android.chrome'),'Selected exception actually opens despite exhausted daily quota');
 await home();await disable();xml=await exceptions();tapNode(node(xml,'com.android.chrome'));await wait(500);check(node(ui(),'com.android.chrome')?.includes('checked="false"'),'Removing exception updates checkbox before closing dialog');shell('input','keyevent','4');await enable();await home();await chrome();await wait(1500);
 check(focus().includes(pkg),'Removing exception blocks non-WhatsApp launcher app');screenshot('blocked');
 const essentialApps=[
 ['phone','com.google.android.dialer/.extensions.GoogleDialtactsActivity','com.google.android.dialer'],
 ['sms','com.google.android.apps.messaging/.ui.ConversationListActivity','com.google.android.apps.messaging'],
 ['camera','com.android.camera2/com.android.camera.CameraLauncher','com.android.camera2'],
 ['calendar','com.google.android.calendar/com.android.calendar.AllInOneActivity','com.google.android.calendar'],
 ['maps','com.google.android.apps.maps/com.google.android.maps.MapsActivity','com.google.android.apps.maps'],
 ['settings','com.android.settings/.Settings','com.android.settings']];
 for(const [name,component,expected]of essentialApps){await home();await open(component);for(let i=0;i<12&&!focus().includes(expected);i++)await wait(500);screenshot(name);check(!focus().includes(pkg)&&focus().includes(expected),`${name} remains accessible with no daily uses`);}
 await home();check(!focus().includes(pkg)&&/launcher|nexuslauncher/.test(focus()),'Home launcher remains accessible');
 await chrome();await wait(1000);shell('input','keyevent','223');await wait(700);shell('input','keyevent','224');await wait(500);shell('wm','dismiss-keyguard');await wait(700);
 for(let i=0;i<6&&!/launcher|nexuslauncher/.test(focus());i++)await wait(500);screenshot('unlock-home');
 check(!focus().includes(pkg)&&/launcher|nexuslauncher/.test(focus()),'Screen unlock returns Home instead of revealing restricted app');
 await chrome();await wait(1500);let before=await api();
 check(before.used===5&&before.emergencyRemaining===4,'Essential flows and exception edits consume no quota');
 // Observed coordinates from general-blocker-preview.png, fixed1080x2400 emulator.
 shell('input','tap','540','1420');await wait(400);screenshot('emergency-confirm');
 let pending=await api();check(focus().includes(pkg)&&pending.emergencyRemaining===4&&pending.used===5,'First emergency click only asks for confirmation');
 shell('input','tap','540','1420');
 let granted;
 for(let i=0;i<12;i++){await wait(1000);granted=await api();if(granted.state==='allowed'&&!focus().includes(pkg))break;}
 check(focus().includes('com.android.chrome')&&granted.sessionKind==='emergency','Second emergency click opens restricted app without a 30-second wait');
 check(granted.emergencyRemaining===3&&granted.used===5,'Emergency consumes exactly 1 of 4 monthly uses and no daily use');
 check(granted.expiresAt-granted.serverNow>1160000&&granted.expiresAt-granted.serverNow<=1200000,'Emergency grants a fixed twenty-minute window');screenshot('emergency-open');
 await home();await chrome();await wait(1000);let reentry=await api();
 check(focus().includes('com.android.chrome')&&reentry.expiresAt===granted.expiresAt&&reentry.emergencyRemaining===3,'Reentry preserves emergency deadline without consuming another use');
 mutate("UPDATE quota SET end=CAST(strftime('%s','now') AS INTEGER)*1000+2000;");await wait(7000);
 check(focus().includes(pkg),'Emergency fixed deadline blocks foreground app');screenshot('expired');
 shell('input','keyevent','4');await wait(600);check(!focus().includes(pkg)&&/launcher|nexuslauncher/.test(focus()),'Expired overlay Back returns to Home');
 writeFileSync('artifacts/qa/general-result.json',JSON.stringify({version:'1.3.1',apkSha256:createHash('sha256').update(readFileSync(apk)).digest('hex'),platform:'Android 16 API 36 emulator',fixtureSetup:'Isolated QA server DB seeded daily used=5/monthly used=0; actual installed Chrome and essential apps; UI exception toggles; QA deadline shortened for expiry; no production quota mutation',checks,result:'passed'},null,2));
}finally{await home();await disable();restore('enabled_accessibility_services',oldServices);restore('accessibility_enabled',oldEnabled);}
