import { execFileSync } from 'node:child_process';
import { readFileSync,writeFileSync,mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const adb=process.env.ADB??'adb',serial=process.env.ANDROID_SERIAL;
assert.ok(serial?.startsWith('emulator-'),'Disposable emulator only');
const cfg=JSON.parse(readFileSync('.build/qa-sync-config.json'));
assert.ok(cfg.url.endsWith('/bir-nefes-qa'),'Dedicated QA authority only');
const pkg='com.zerohidz.whatsapppause',apk='.build/qa-sync.apk';
const call=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',maxBuffer:8*1024*1024});
const shell=(...args)=>call('shell',...args);
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const focus=()=>shell('dumpsys','window').split('\n').find(l=>l.includes('mCurrentFocus='))??'';
const isHome=()=>/launcher|nexuslauncher/.test(focus())&&!focus().includes(pkg);
const home=async()=>{shell('input','keyevent','3');await wait(400);};
const open=async()=>{shell('am','start','-f','0x14000000','-n','com.android.chrome/com.google.android.apps.chrome.Main');await wait(1800);};
const disable=async()=>{shell('settings','delete','secure','enabled_accessibility_services');await wait(500);};
const api=async()=>{for(let i=0;;i++)try{const r=await fetch(cfg.url+'/v1/status',{headers:{Authorization:'Bearer '+cfg.androidToken}});assert.equal(r.status,200);return r.json();}catch(e){if(i===2)throw e;await wait(500);}};
function mutate(sql){execFileSync('ssh',['-o','BatchMode=yes','zmanga','python3','-'],{input:`import sqlite3\nwith sqlite3.connect('/var/lib/bir-nefes/qa.sqlite3') as db:\n db.executescript(${JSON.stringify(sql)})\n`});}
const checks=[];const check=(ok,text)=>{assert.ok(ok,text);checks.push(text);console.log('PASS: '+text);};
mkdirSync('artifacts/qa',{recursive:true});
const shot=n=>writeFileSync(`artifacts/qa/home-${n}.png`,execFileSync(adb,['-s',serial,'exec-out','screencap','-p'],{maxBuffer:8*1024*1024}));
const oldServices=shell('settings','get','secure','enabled_accessibility_services').trim();
const oldEnabled=shell('settings','get','secure','accessibility_enabled').trim();
const restore=(k,v)=>v==='null'?shell('settings','delete','secure',k):shell('settings','put','secure',k,v);
try{
 await home();await disable();call('install','-r',apk);shell('pm','clear',pkg);
 mutate("UPDATE quota SET day='',used=0,night=0,end=0,kind='normal'; DELETE FROM attempts; UPDATE emergency SET month='',used=0; DELETE FROM emergency_requests;");
 await api();shell('am','start','-n',pkg+'/.MainActivity');await wait(800);shot('setup');
 shell('settings','put','secure','enabled_accessibility_services',pkg+'/'+pkg+'.PauseService');shell('settings','put','secure','accessibility_enabled','1');await wait(900);await home();
 for(let iteration=1;iteration<=3;iteration++){
  await open();await wait(1800);check(focus().includes(pkg),`Waiting overlay blocks Chrome on entry ${iteration}`);shot(`waiting-${iteration}`);
  // Fixed1080x2400 screenshot-observed WAITING overlay Home button.
  shell('input','tap','540',process.env.HOME_BUTTON_Y??'1450');
  await wait(650);check(isHome(),`Home button exits waiting overlay ${iteration}`);
  for(let sample=0;sample<6;sample++){await wait(400);assert.ok(isHome(),'No delayed overlay may return to launcher');}
  check(true,`Launcher remains clear for over 2 seconds after Home ${iteration}`);shot(`launcher-${iteration}`);
 }
 // Genuine immediate reentry must not be suppressed by Home recovery logic.
 await open();await wait(500);shell('input','tap','540',process.env.HOME_BUTTON_Y??'1450');await wait(350);await open();
 check(focus().includes(pkg),'Quick genuine reentry remains blocked');
 check((await api()).used===0,'Leaving unfinished countdown consumes no daily use');
 await wait(32000);let normal=await api();
 for(let i=0;i<8&&focus().includes(pkg);i++){await wait(1000);normal=await api();}
 check(focus().includes('com.android.chrome')&&normal.used===1&&normal.sessionKind==='normal','Real 30-second countdown grants a normal window');
 check(normal.expiresAt-normal.serverNow>1160000&&normal.expiresAt-normal.serverNow<=1200000,'Normal phone window lasts 20 minutes');
 await home();await open();let repeated=await api();
 check(focus().includes('com.android.chrome')&&repeated.expiresAt===normal.expiresAt&&repeated.used===1,'Normal reentry preserves original 20-minute deadline');
 await home();mutate('UPDATE quota SET end=0,used=5;');await open();await wait(1200);shot('exhausted');
 // Exhausted overlay has less content than WAITING; observed emergency button y1420.
 shell('input','tap','540','1420');await wait(500);check((await api()).emergencyRemaining===4,'First emergency click only requests confirmation');shot('emergency-confirm');
 shell('input','tap','540','1420');let emergency;
 for(let i=0;i<15;i++){await wait(1000);emergency=await api();if(emergency.state==='allowed'&&!focus().includes(pkg))break;}
 check(focus().includes('com.android.chrome')&&emergency.sessionKind==='emergency'&&emergency.emergencyRemaining===3&&emergency.used===5,'Emergency opens immediately and only consumes monthly quota');
 check(emergency.expiresAt-emergency.serverNow>1160000&&emergency.expiresAt-emergency.serverNow<=1200000,'Emergency phone window lasts 20 minutes');
 mutate("UPDATE quota SET end=CAST(strftime('%s','now') AS INTEGER)*1000+2000;");await wait(8000);
 check(focus().includes(pkg),'Fixed emergency expiry blocks foreground app');shot('expired');
 shell('input','keyevent','4');await wait(2600);check(isHome(),'Expiry Back leaves launcher clear after 2 seconds');
 writeFileSync('artifacts/qa/home-result.json',JSON.stringify({version:'1.3.1',apkSha256:createHash('sha256').update(readFileSync(apk)).digest('hex'),platform:'Android 16 API 36 emulator',fixtureSetup:'Dedicated QA database reset and expired-session fixture; actual Chrome; real 30-second countdown; screenshot-observed coordinates; no production quota mutation',limits:'No physical user device or OEM-specific background accessibility event sequence was available',checks,result:'passed'},null,2));
}finally{await home();await disable();restore('enabled_accessibility_services',oldServices);restore('accessibility_enabled',oldEnabled);}
