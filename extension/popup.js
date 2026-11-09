async function showQuota() {
(async () => {
  const status=document.getElementById('status'), detail=document.getElementById('detail');
  try {
    const r=await chrome.runtime.sendMessage({operation:'status'});
    if(!r || r.error) throw new Error('offline');
    if (r.rules) applyRules(r.rules);
    showExtra(r);
    const monthly = birNefesMonthlyRenewal(rules);
    document.getElementById('urgent').textContent = `${r.emergencyRemaining ?? '—'}/${rules.urgentCount} acele hakkı · ${monthly[0].toLocaleUpperCase('tr-TR')}${monthly.slice(1)} yenilenir`;
    const limited=r.extraAllUntil > r.serverNow;
    status.textContent=limited ? 'Ek kısıt açık' : r.state==='allowed' ? 'Ortak kullanımın açık' : `${r.remaining} hakkın kaldı`;
    detail.textContent=limited ? `${birNefesUntil(r.extraAllUntil)} kendi koyduğun kısıt sürüyor.` : r.state==='allowed' ? `Bitiş: ${new Date(r.expiresAt).toLocaleTimeString('tr-TR',{timeZone:'Europe/Istanbul',hour:'2-digit',minute:'2-digit'})}. Cihaz değiştirmen bu süreyi uzatmaz.` : r.remaining === 0 ? `Hakların her gün ${birNefesAt(rules.renewal)} yenilenir. Şimdilik mola zamanı.` : 'Yeni bir kullanımda önce kısa bir mola vereceksin.';
  } catch {$('extra').hidden=true;status.textContent='Sunucuya ulaşılamadı';detail.textContent='Bağlantı kurulmadan yeni hak açılamaz. İnternet bağlantını kontrol et.';}
})();
(async () => {
  const status=document.getElementById('video-status'), detail=document.getElementById('video-detail');
  try {
    const r=await chrome.runtime.sendMessage({operation:'video-status'});
    if(!r || r.error) throw new Error('offline');
    if (r.rules) applyRules(r.rules);
    showWork(r);
    const seconds=Math.floor(r.videoRemainingMs/1000);
    status.textContent=r.mode==='free' ? 'Şu an serbest' : r.mode==='emergency' ? 'Acele geçişin açık' : r.mode==='work' ? 'Çalışma geçişin açık' : r.mode==='night' ? `${birNefesAt(birNefesNightOpens(rules, r.serverNow ?? Date.now()))} açılır` : r.mode==='limited' ? 'Ek kısıt açık' : `${Math.floor(seconds/60)} dk ${seconds%60} sn kaldı`;
    detail.textContent='';
  } catch {workShown=false;$('work').hidden=true;status.textContent='Süre kontrol edilemedi';detail.textContent='Sunucu bağlantısını kontrol et.';}
})();
loadRules();
}

const $ = id => document.getElementById(id);
const send = message => chrome.runtime.sendMessage(message).catch(() => ({error:'offline'}));
const failures = {disabled:'Çalışma geçişi kurallarında kapalı.', no_guardian:'Kardeş ekli olmadığı için süre dolmadan bitmez.', mail_limit:'Bugünkü e-posta sınırı doldu. Yarın yeniden dene.', no_extra:'Açık bir ek kısıt yok.', invalid:'Seçimini kontrol et.', no_oath:'Yeminin içinde “yemin ederim” geçmeli.', chrome_only:'Çalışma geçişi yalnız bilgisayarda açılır.', active:'Zaten açık bir çalışma geçişin var.', offline:'Sunucuya ulaşılamadı. İnternet bağlantını kontrol et.', invalid_code:'Kod yanlış ya da süresi dolmuş. Telefondan yeni kod al.', locked:'Kardeş kilidi açıkken hesap değiştirilemez. Telefondan izin iste.', unpaired:'Bu bilgisayarın bağlantısı geçersiz. Başka hesaba bağlan.'};
// Messages that quote the account's rules are built when they are shown.
function fail(error) {
  if (error === 'not_metered') { const hours = birNefesMeteredText(rules); return hours ? `Geçiş yalnız sayaçlı saatlerde gerekir (${hours}). Şu an video serbest ya da kapalı.` : 'Sayaçlı video saati yok. Şu an geçiş gerekmez.'; }
  if (error === 'work_cap') { const d = birNefesDuration(rules.work.dailyMin); return `Bugünkü ${d}${d.endsWith('saat') ? 'lik' : 'lık'} çalışma süresi doldu.`; }
  return failures[error] || failures.offline;
}

async function joined(button, message) {
  const status = $('join-status');
  button.disabled = true; status.textContent = '';
  const r = await send(message);
  button.disabled = false;
  if (r?.paired) return show(true);
  status.textContent = fail(r?.error);
}

let timer;
$('link-phone').addEventListener('click', async () => {
  const button = $('link-phone');
  button.disabled = true; $('link-status').textContent = '';
  const r = await send({operation:'pair-code'});
  button.disabled = false;
  if (!r?.code) { $('link-status').textContent = fail(r?.error); return; }
  const end = Date.now() + r.ttl;
  $('code-value').textContent = r.code.length === 8 ? `${r.code.slice(0,4)}-${r.code.slice(4)}` : r.code;
  $('pair-code').hidden = false; button.hidden = true; $('pair-code').scrollIntoView({block:'center'});
  clearInterval(timer);
  const tick = () => {
    const left = Math.max(0, Math.ceil((end - Date.now())/1000));
    $('code-time').textContent = `${Math.floor(left/60)}:${String(left%60).padStart(2,'0')} içinde geçerli · Tek kullanımlık`;
    if (!left) { clearInterval(timer); $('pair-code').hidden = true; button.hidden = false; button.textContent = 'Yeni kod al'; }
  };
  tick(); timer = setInterval(tick, 1000);
});
$('switch').addEventListener('click', async () => {
  if (!confirm('Bu bilgisayar mevcut hesaptan çıkacak. Telefonun bu hesapta kalır. Devam edilsin mi?')) return;
  const r = await send({operation:'unpair'});
  if (r?.paired !== false) { $('link-status').textContent = fail(r?.error); return; }
  show(false);
});
$('pair-form').addEventListener('submit', event => {
  event.preventDefault();
  const code = $('code').value.toUpperCase().replace(/[^A-Z]/g, '');
  if (code.length !== 8) { $('join-status').textContent = 'Kod 8 harften oluşur.'; return; }
  joined($('pair'), {operation:'pair', code});
});

function show(paired) {
  $('join').hidden = paired; $('quota').hidden = !paired;
  clearInterval(timer); $('pair-code').hidden = true; $('link-phone').hidden = false; $('link-status').textContent = '';
  if (paired) showQuota(); else $('code').focus();
}
send({operation:'account'}).then(r => show(!!r?.paired));

// Tabs: arrow keys, Home and End move between them; only the selected one is in the tab order.
const tabs = [...document.querySelectorAll('[role=tab]')];
function selectTab(tab, focus) {
  for (const t of tabs) {
    const on = t === tab;
    t.setAttribute('aria-selected', String(on)); t.tabIndex = on ? 0 : -1;
    $(t.getAttribute('aria-controls')).hidden = !on;
  }
  if (focus) tab.focus();
}
for (const tab of tabs) {
  tab.addEventListener('click', () => selectTab(tab, false));
  tab.addEventListener('keydown', event => {
    const i = tabs.indexOf(tab);
    const next = {ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1}[event.key];
    if (next === undefined) return;
    event.preventDefault();
    selectTab(tabs[(next + tabs.length) % tabs.length], true);
  });
}

// The account's rules. Every answer that carries them replaces these; an older server sends none.
let rules = birNefesRules(null), workShown = false, rulesView = null;
const SITES = [['Telefon + mesajlaşma', 'WhatsApp · Telegram'], ['Video / film', 'YouTube · tranimeizle · fullhdfilmizlesene']];
function applyRules(value) {
  rules = birNefesRules(value);
  const select = $('work-minutes'), before = Number(select.value);
  const minutes = rules.work.minutes;
  const chosen = minutes.includes(before) ? before : minutes.includes(60) ? 60 : minutes[0];
  select.replaceChildren(...minutes.map(m => new Option(birNefesDuration(m), String(m), false, m === chosen)));
  const hours = birNefesMeteredText(rules);
  $('work-hint').textContent = [hours ? `${hours} arasında iş ya da ders videosu için.` : 'Sayaçlı video saati yok; geçiş gerekmez.',
    `Günde en fazla ${birNefesDuration(rules.work.dailyMin)}.`, rules.work.summary ? 'Gün sonunda kardeşine özet e-posta gider.' : ''].filter(Boolean).join(' ');
  $('work').hidden = !workShown || !rules.work.enabled;
  $('extra-day').textContent = `Gün sonuna kadar (${birNefesClock(rules.renewal)})`;
  if (!rulesView) renderRules(rules, null);
}
function group(title, lines, extra) {
  const section = document.createElement('section'), h = document.createElement('h2'), list = document.createElement('ul');
  h.textContent = title; list.className = 'lines';
  for (const line of lines) { const li = document.createElement('li'); li.textContent = line; list.append(li); }
  if (extra) { const li = document.createElement('li'); li.className = 'sites'; li.textContent = extra; list.append(li); }
  section.append(h, list);
  return section;
}
function renderRules(current, pending) {
  const summary = birNefesSummary(current);
  $('rules-current').replaceChildren(...summary.map(([title, lines]) => group(title, lines, SITES.find(s => s[0] === title)?.[1])));
  $('rules-pending').hidden = !pending;
  if (!pending) return;
  // Only the lines that change are listed, under the group they belong to.
  const before = new Set(summary.flatMap(([, lines]) => lines));
  const changed = birNefesSummary(pending.rules).flatMap(([title, lines]) => lines.filter(l => !before.has(l)).map(l => `${title}: ${l}`));
  const day = new Date(pending.effectiveAt).toLocaleDateString('tr-TR', {timeZone: 'Europe/Istanbul', day: 'numeric', month: 'long'});
  $('rules-pending-when').textContent = `${day} ${birNefesAt(clock(pending.effectiveAt))} yürürlüğe girer${pending.looser ? ' · gevşetme' : ''}`;
  $('rules-pending-lines').replaceChildren(...(changed.length ? changed : ['Aynı kurallar']).map(line => { const li = document.createElement('li'); li.textContent = line; return li; }));
}
async function loadRules() {
  const r = await send({operation:'rules'});
  if (!r?.current?.rules) return;
  rulesView = r;
  applyRules(r.current.rules);
  renderRules(rules, r.pending?.rules && Number.isFinite(r.pending.effectiveAt) ? r.pending : null);
}
applyRules(null);

// Ek kısıt: the server only lengthens it, so the start button asks twice before sending.
let extraActive = false, extraConfirm = false, extraBefore = '';
function extraButton() {
  const verb = extraActive ? 'uzat' : 'başlat';
  $('extra-start').textContent = extraConfirm ? `Eminim, ${verb} · süre dolmadan bitiremezsin` : extraActive ? 'Uzat' : 'Başlat';
}
function showExtra(r) {
  const all = r.extraAllUntil > r.serverNow ? r.extraAllUntil : 0;
  const video = r.extraVideoUntil > r.serverNow ? r.extraVideoUntil : 0;
  extraActive = !!(all || video); extraConfirm = false;
  extraBefore = `${all}:${video}`;
  const lines = [];
  if (all) lines.push(`Her şey kısıtlı · ${birNefesUntil(all)}`);
  if (video > all) lines.push(`Video ve film kısıtlı · ${birNefesUntil(video)}`);
  $('extra-lines').textContent = lines.join('\n');
  $('extra-active').hidden = !extraActive;
  $('extra-pending').hidden = !(extraActive && r.extraEndPending === true);
  $('extra-hint').hidden = extraActive;
  $('extra-end-box').hidden = !extraActive;
  extraButton(); $('extra').hidden = false;
}
for (const id of ['extra-scope','extra-minutes']) $(id).addEventListener('change', () => { extraConfirm = false; extraButton(); });
$('extra-start').addEventListener('click', async () => {
  const button = $('extra-start');
  $('extra-status').textContent = '';
  if (!extraConfirm) { extraConfirm = true; extraButton(); return; }
  const value = $('extra-minutes').value;
  button.disabled = true;
  const r = await send({operation:'extra', scope:$('extra-scope').value, minutes: value === 'day' ? 'day' : Number(value)});
  button.disabled = false; extraConfirm = false; extraButton();
  if (!r || r.error) { $('extra-status').textContent = fail(r?.error); return; }
  const unchanged = `${r.extraAllUntil}:${r.extraVideoUntil}` === extraBefore;
  await showQuota();
  if (unchanged) $('extra-status').textContent = 'Açık kısıt zaten daha uzun. Süre değişmedi.';
});
$('extra-end').addEventListener('click', async () => {
  const button = $('extra-end');
  button.disabled = true; $('extra-status').textContent = '';
  const r = await send({operation:'extra-end', reason:$('extra-reason').value.trim().slice(0, 200)});
  button.disabled = false;
  if (!r || r.error) { $('extra-status').textContent = fail(r?.error); if (r?.error === 'no_extra') showQuota(); return; }
  $('extra-reason').value = '';
  $('extra-pending').hidden = !r.extraEndPending;
  $('extra-status').textContent = r.extraEndPending ? 'İstek kardeşine e-postayla gitti.' : '';
});

// Çalışma geçişi: the oath is typed, never prefilled; the server checks it again.
const clock = ms => new Date(ms).toLocaleTimeString('tr-TR', {timeZone:'Europe/Istanbul', hour:'2-digit', minute:'2-digit'}).replace(':', '.');
function showWork(r) {
  const now = r.serverNow ?? Date.now();
  const active = r.workUntil > now;
  $('work-active').hidden = !active; $('work-end').hidden = !active; $('work-form').hidden = active;
  if (active) $('work-active').textContent = `Açık · ${birNefesUntil(r.workUntil)}`;
  const today = Array.isArray(r.workToday) ? r.workToday : [];
  const lines = today.map(p => `${clock(p.started)}–${p.end > now ? 'şimdi' : clock(p.end)} · ${p.purpose}`);
  if (today.length) lines.push(`Bugün kalan: ${Math.floor((r.workLeftMs || 0) / 60000)} dakika`);
  $('work-log').textContent = lines.join('\n');
  workShown = true; $('work').hidden = !rules.work.enabled;
}
$('work-start').addEventListener('click', async () => {
  const purpose = $('work-purpose').value.trim(), oath = $('work-oath').value.trim();
  const folded = oath.replace(/İ/g, 'i').replace(/I/g, 'ı').toLocaleLowerCase('tr-TR').replace(/\s+/g, ' ');
  if (purpose.length < 3) { $('work-status').textContent = 'Ne izleyeceğini yaz.'; return; }
  if (!folded.includes('yemin ederim')) { $('work-status').textContent = failures.no_oath; return; }
  const button = $('work-start');
  button.disabled = true; $('work-status').textContent = '';
  const r = await send({operation:'video-work', minutes:Number($('work-minutes').value), purpose, oath});
  button.disabled = false;
  if (!r || r.error) { $('work-status').textContent = fail(r?.error); return; }
  $('work-purpose').value = ''; $('work-oath').value = '';
  showWork(r);
  $('video-status').textContent = 'Çalışma geçişin açık';
});
$('work-end').addEventListener('click', async () => {
  const r = await send({operation:'video-work-end'});
  if (!r || r.error) { $('work-status').textContent = fail(r?.error); return; }
  showQuota();
});
