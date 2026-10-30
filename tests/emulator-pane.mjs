import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const adb=process.env.ADB??'adb',serial=process.env.ANDROID_SERIAL;
assert.ok(serial?.startsWith('emulator-'),'Disposable emulator only');
const pkg='com.zerohidz.whatsapppause';
const call=(...a)=>execFileSync(adb,['-s',serial,...a],{encoding:'utf8',maxBuffer:8*1024*1024});
const shell=(...a)=>call('shell',...a),wait=ms=>new Promise(r=>setTimeout(r,ms));
const focus=()=>shell('dumpsys','window').split('\n').find(l=>l.includes('mCurrentFocus='))??'';
const shot=n=>writeFileSync(`artifacts/qa/home-pane-${n}.png`,execFileSync(adb,['-s',serial,'exec-out','screencap','-p'],{maxBuffer:8*1024*1024}));
const oldServices=shell('settings','get','secure','enabled_accessibility_services').trim();
const oldEnabled=shell('settings','get','secure','accessibility_enabled').trim();
const checks=[],check=(v,t)=>{assert.ok(v,t);checks.push(t);console.log('PASS: '+t);};
const restore=(k,v)=>v==='null'?shell('settings','delete','secure',k):shell('settings','put','secure',k,v);
try{
 call('install','-r','.build/pane-fixture/target.apk');
 shell('settings','put','secure','enabled_accessibility_services',pkg+'/'+pkg+'.PauseService');shell('settings','put','secure','accessibility_enabled','1');await wait(1000);
 shell('input','keyevent','3');await wait(400);
 for(const [label,component] of [
 ['Photos','com.google.android.apps.photos/.home.HomeActivity'],
 ['YouTube','com.google.android.youtube/.app.honeycomb.Shell$HomeActivity'],
 ['pane fixture','com.birnefes.qa.pane/qa.pane.PaneActivity']]){
  shell('am','start','-S','-n',"'"+component+"'");await wait(2500);
  check(focus().includes(pkg),`Fresh ${label} launch is blocked despite activity metadata filtering`);
  if(label==='pane fixture')shot('blocked');
  shell('input','keyevent','3');await wait(3000);
  check(!focus().includes(pkg)&&/launcher|nexuslauncher/.test(focus()),`Home remains clear after leaving ${label}`);
 }
 const logs=call('logcat','-d','-s','BirNefesPaneQA:I','*:S');writeFileSync('artifacts/qa/home-pane-events.log',logs);
 check(logs.includes('delay=500ms')&&logs.includes('delay=1000ms')&&!logs.includes('send failed'),'Fixture emitted late pane and generic FrameLayout window events');shot('launcher');
 writeFileSync('artifacts/qa/home-pane-result.json',JSON.stringify({version:'1.3.1',checks,result:'passed',limits:'Synthetic emulator event; sendAccessibilityEvent returned successfully but no old QA APK or physical OEM event trace was available. No production quota mutation.'},null,2));
}finally{shell('input','keyevent','3');shell('settings','delete','secure','enabled_accessibility_services');await wait(500);restore('enabled_accessibility_services',oldServices);restore('accessibility_enabled',oldEnabled);}
