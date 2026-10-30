const {chromium}=await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const dir=resolve('.build/extension-qa');
const context=await chromium.launchPersistentContext('',{channel:'chromium',headless:true,args:[`--disable-extensions-except=${dir}`,`--load-extension=${dir}`],viewport:{width:1200,height:800}});
const checks=[];
const page=await context.newPage();
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function ax(){const c=await context.newCDPSession(page);try{return(await c.send('Accessibility.getFullAXTree')).nodes.map(n=>n.name?.value??'').join('\n')}finally{await c.detach()}}
const check=(ok,name)=>{assert.ok(ok,name);checks.push(name);console.log('PASS:',name)};
try{
 // Exhausted QA account left by the full extension smoke; public real WhatsApp,
 // fresh profile without login/messages. Observe only our own blocker.
 await page.goto('https://web.whatsapp.com/',{waitUntil:'domcontentloaded',timeout:45000});
 await wait(4000);
 check((await ax()).includes('Bugünkü hakkın bitti.'),'Real logged-out WhatsApp Web displays our blocker');
 check(await page.locator('body').evaluate(b=>b.inert),'Real WhatsApp Web body is inert');
 await page.screenshot({path:'artifacts/qa/chrome-real-whatsapp-blocked.png'});
 await context.route('https://web.whatsapp.com/**',r=>r.fulfill({headers:{'Content-Security-Policy':"default-src 'none'; script-src 'none'; style-src 'none'; require-trusted-types-for 'script'"},contentType:'text/html',body:'<!doctype html><html><body><h1>Strict CSP surrogate</h1></body></html>'}));
 await page.reload({waitUntil:'domcontentloaded'});await wait(2000);
 check((await ax()).includes('Bugünkü hakkın bitti.'),'Strict CSP and Trusted Types do not break blocker');
 await page.screenshot({path:'artifacts/qa/chrome-strict-csp.png'});
 execFileSync('ssh',['-o','BatchMode=yes','zmanga','python3 -'],{encoding:'utf8',input:"import sqlite3,time\nwith sqlite3.connect('/var/lib/bir-nefes/qa.sqlite3') as db: db.execute('UPDATE quota SET used=1,night=0,end=?,session=session+1 WHERE id=1',(int(time.time()*1000)+18000,))\n"});
 await page.reload({waitUntil:'domcontentloaded'});
 await page.waitForFunction(()=>document.documentElement.hasAttribute('data-birnefes-allowed'));
 await context.setOffline(true);await wait(3000);
 check(await page.locator('html').getAttribute('data-birnefes-allowed')!==null,'Already granted screen remains usable briefly offline');
 await page.waitForFunction(()=>!document.documentElement.hasAttribute('data-birnefes-allowed'),{},{timeout:25000});
 check((await ax()).includes('Kullanım süren doldu.'),'Offline cached grant still expires locally');
 await page.screenshot({path:'artifacts/qa/chrome-offline-expired.png'});
 writeFileSync('artifacts/qa/extension-edge-result.json',JSON.stringify({result:'passed',checks,scope:'Fresh Chromium profile; real logged-out WhatsApp Web and strict-CSP surrogate; dedicated QA lease shortened for offline expiry'},null,2));
}finally{await context.setOffline(false);await context.close()}
