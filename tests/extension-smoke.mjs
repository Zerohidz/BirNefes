const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
import { readFileSync, writeFileSync, cpSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.build/qa-sync-config.json','utf8'));
assert.ok(config.url.endsWith('/bir-nefes-qa'),'Only isolated QA authority allowed');
const dir=resolve('.build/extension-qa');mkdirSync(dir,{recursive:true});cpSync('extension',dir,{recursive:true});
writeFileSync(`${dir}/config.js`,`const BIRNEFES_CONFIG = ${JSON.stringify({url:config.url,token:config.chromeToken})};\n`);
const checks=[];const check=(value,name)=>{assert.ok(value,name);checks.push(name);console.log('PASS:',name)};
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function status(){
 for(let attempt=0;attempt<3;attempt++){
  try{return await (await fetch(config.url+'/v1/status',{headers:{Authorization:'Bearer '+config.chromeToken},signal:AbortSignal.timeout(12000)})).json()}
  catch(error){if(attempt===2)throw error;await wait(500)}
 }
}
function seed(used,end=0,night=0) {
 execFileSync('ssh',['-o','BatchMode=yes','zmanga','python3 -'],{input:`import sqlite3,sys\nsys.path.insert(0,'/opt/bir-nefes')\nfrom app import bounds\nimport time\nday=bounds(int(time.time()*1000))[0]\nwith sqlite3.connect('/var/lib/bir-nefes/qa.sqlite3') as db:\n db.execute('UPDATE quota SET day=?,used=?,night=?,end=?,session=session+1 WHERE id=1',(day,${used},${night},${end}))\n db.execute('DELETE FROM attempts')\n`,encoding:'utf8'});
}
const context=await chromium.launchPersistentContext('',{channel:'chromium',headless:true,args:[`--disable-extensions-except=${dir}`,`--load-extension=${dir}`],viewport:{width:1200,height:800}});
const errors=[];
context.on('page',page=>page.on('pageerror',e=>errors.push(e.message)));
await context.route('https://web.whatsapp.com/**',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><html lang="tr"><head><title>WhatsApp QA surrogate</title></head><body><h1>QA WhatsApp surrogate — no real messages</h1><button onclick="this.textContent=\'clicked\'" id="target">Underlying target</button></body></html>'}));
let worker=context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
const extensionId=worker.url().split('/')[2];
const page=await context.newPage();
const allowed=()=>page.locator('html').getAttribute('data-birnefes-allowed').then(v=>v!==null);
const screenshot=name=>page.screenshot({path:`artifacts/qa/${name}.png`});
const ax=async()=>{const c=await context.newCDPSession(page);try{return (await c.send('Accessibility.getFullAXTree')).nodes.map(n=>n.name?.value??'').join('\n')}finally{await c.detach()}};
try {
 seed(0);
 await page.goto('https://web.whatsapp.com/');
 await wait(2000);
 check(!(await allowed()) && await page.locator('body').evaluate(b=>b.inert),'Initial mask and inert body block target');
 check((await ax()).includes('Bir nefes al.'),'Real extension countdown shown');
 await screenshot('chrome-countdown');
 await wait(10500);check(!(await allowed()),'Former 10-second boundary stays blocked');
 await page.waitForFunction(()=>document.documentElement.hasAttribute('data-birnefes-allowed'),{},{timeout:30000});
 let first=await status();check(first.used===1 && first.state==='allowed','Real 30-second server wait grants exactly one right');
 await page.locator('#target').click();check(await page.locator('#target').textContent()==='clicked','Underlying page becomes interactive');
 await screenshot('chrome-allowed');
 await page.reload();await page.waitForFunction(()=>document.documentElement.hasAttribute('data-birnefes-allowed'));
 check((await status()).expiresAt===first.expiresAt,'Reload does not extend shared deadline');
 const android=await (await fetch(config.url+'/v1/status',{headers:{Authorization:'Bearer '+config.androidToken}})).json();
 check(android.expiresAt===first.expiresAt && android.used===1,'Android credential sees same Chrome-created right');
 // Shorten the existing QA lease, never production timing constants.
 execFileSync('ssh',['-o','BatchMode=yes','zmanga','python3 -'],{input:"import sqlite3,time\nwith sqlite3.connect('/var/lib/bir-nefes/qa.sqlite3') as db: db.execute('UPDATE quota SET end=? WHERE id=1',(int(time.time()*1000)+6500,))\n",encoding:'utf8'});
 await wait(8500);
 check(!(await allowed()) && (await ax()).includes('Kullanım süren doldu.'),'Foreground deadline shows blocking expiry');
 await screenshot('chrome-expired');
 await page.setViewportSize({width:390,height:844});await screenshot('chrome-expired-mobile');
 // Home is the only button; focus traversal verifies keyboard-accessible exit in closed shadow DOM.
 await page.keyboard.press('Tab');await page.keyboard.press('Enter');
 await page.waitForURL(/^chrome:\/\/(newtab|new-tab-page)\//);check(true,'Home control exits to new tab');
 seed(5);
 await page.goto('https://web.whatsapp.com/');await wait(1500);
 check(!(await allowed()) && (await ax()).includes('Bugünkü hakkın bitti.'),'Exhausted quota blocks Chrome');
 await screenshot('chrome-exhausted');
 const popup=await context.newPage();await popup.goto(`chrome-extension://${extensionId}/popup.html`);
 await popup.locator('#status').filter({hasText:'0 hakkın kaldı'}).waitFor();
 check(await popup.locator('a').getAttribute('href')==='https://web.whatsapp.com/','Popup reflects shared allowance and links to WhatsApp');
 await popup.screenshot({path:'artifacts/qa/chrome-popup.png'});await popup.close();
 await page.bringToFront();await context.setOffline(true);await page.reload().catch(()=>{});
 // Reload while fully offline cannot load even the fixture; restore document via routed navigation.
 await page.goto('https://web.whatsapp.com/').catch(()=>{});await wait(7000);
 check(!(await allowed()),'Offline cannot grant access');
 await screenshot('chrome-offline');await context.setOffline(false);
 check(errors.length===0,`No page exceptions (${errors.length})`);
 writeFileSync('artifacts/qa/extension-result.json',JSON.stringify({version:'1.2.0',checks,result:'passed',scope:'Real MV3 extension in isolated Chromium; mocked WhatsApp document, dedicated live HTTPS QA authority; seeded quota/shortened lease for boundaries'},null,2));
} finally {await context.setOffline(false);await context.close()}
