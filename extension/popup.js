async function showQuota() {
(async () => {
  const status=document.getElementById('status'), detail=document.getElementById('detail');
  try {
    const r=await chrome.runtime.sendMessage({operation:'status'});
    if(!r || r.error) throw new Error('offline');
    document.getElementById('urgent').textContent = `${r.emergencyRemaining ?? '—'}/4 acele hakkı · Ayın 1’inde 03.00’te yenilenir`;
    status.textContent=r.state==='allowed' ? 'Ortak kullanımın açık' : `${r.remaining} hakkın kaldı`;
    detail.textContent=r.state==='allowed' ? `Bitiş: ${new Date(r.expiresAt).toLocaleTimeString('tr-TR',{timeZone:'Europe/Istanbul',hour:'2-digit',minute:'2-digit'})}. Cihaz değiştirmen bu süreyi uzatmaz.` : r.remaining === 0 ? 'Hakların her gün 03.00’te yenilenir. Şimdilik mola zamanı.' : 'Yeni bir kullanımda önce kısa bir mola vereceksin.';
  } catch {status.textContent='Sunucuya ulaşılamadı';detail.textContent='Bağlantı kurulmadan yeni hak açılamaz. İnternet bağlantını kontrol et.';}
})();
(async () => {
  const status=document.getElementById('video-status'), detail=document.getElementById('video-detail');
  try {
    const r=await chrome.runtime.sendMessage({operation:'video-status'});
    if(!r || r.error) throw new Error('offline');
    const seconds=Math.floor(r.videoRemainingMs/1000);
    status.textContent=r.mode==='free' ? 'Şu an serbest' : r.mode==='emergency' ? 'Acele geçişin açık' : r.mode==='night' ? 'Sabah 05.00’te açılır' : `${Math.floor(seconds/60)} dk ${seconds%60} sn kaldı`;
    detail.textContent='Bu süre mesajlaşma haklarından ayrıdır. Sayaçlı saatlerde sekmeden ayrılınca video durur, serbest saatlerde arka planda da çalar.';
  } catch {status.textContent='Süre kontrol edilemedi';detail.textContent='Sunucu bağlantısını kontrol et.';}
})();
}

const $ = id => document.getElementById(id);
const send = message => chrome.runtime.sendMessage(message).catch(() => ({error:'offline'}));
const failures = {offline:'Sunucuya ulaşılamadı. İnternet bağlantını kontrol et.', invalid_code:'Kod yanlış ya da süresi dolmuş. Telefondan yeni kod al.', locked:'Kardeş kilidi açıkken hesap değiştirilemez. Telefondan izin iste.', unpaired:'Bu bilgisayarın bağlantısı geçersiz. Başka hesaba bağlan.'};

async function joined(button, message) {
  const status = $('join-status');
  button.disabled = true; status.textContent = '';
  const r = await send(message);
  button.disabled = false;
  if (r?.paired) return show(true);
  status.textContent = failures[r?.error] || failures.offline;
}

let timer;
$('link-phone').addEventListener('click', async () => {
  const button = $('link-phone');
  button.disabled = true; $('link-status').textContent = '';
  const r = await send({operation:'pair-code'});
  button.disabled = false;
  if (!r?.code) { $('link-status').textContent = failures[r?.error] || failures.offline; return; }
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
  if (r?.paired !== false) { $('link-status').textContent = failures[r?.error] || failures.offline; return; }
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
