(() => {
  if (birNefesCategory(location.href) !== 'messaging') return;
  let state = 'connecting', attempt = '', generation = 0, deadline = 0;
  let lastMuted;
  let emergencyRemaining = -1, confirming = false;
  let wallDeadline = 0, ready = 0, session = 0, busy = false, pollAt = 0;
  const host = document.createElement('bir-nefes-blocker');
  host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;display:block;visibility:visible;';
  const shadow = host.attachShadow({mode: 'closed'});
  shadow.innerHTML = `<style>
    :host{color-scheme:dark}*{box-sizing:border-box}main{position:fixed;inset:0;background:#0a141b;color:#ebf2ee;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;padding:32px;overflow:auto}
    section{max-width:520px;width:100%;text-align:center}h1{font-size:clamp(28px,4vw,40px);line-height:1.2;font-weight:650;margin:0 0 20px}p{font-size:18px;line-height:1.6;color:#bacac4;margin:12px 0}
    output{display:block;font-size:88px;font-weight:600;line-height:1.3;font-variant-numeric:tabular-nums;color:#50b582;margin:20px 0}
    button{font:600 16px system-ui;background:#50b582;color:#0a141b;border:0;border-radius:10px;padding:16px 28px;margin-top:28px;cursor:pointer;min-height:52px}button:focus-visible{outline:3px solid white;outline-offset:5px}
    small{display:block;color:#93aaa0;font-size:14px;margin-top:24px;line-height:1.6}[hidden]{display:none!important}
    </style><main role="dialog" aria-modal="true" aria-labelledby="title"><section><h1 id="title"></h1><p id="detail"></p><output id="count" aria-hidden="true" hidden></output><p id="note"></p><button id="home">Ana sayfaya dön</button><button id="urgent" hidden></button><p id="urgent-note" hidden></p><small>Bir Nefes · Telefon + Chrome<br>Haklar 03.00’te yenilenir · İstanbul saati</small></section></main>`;
  const $ = id => shadow.getElementById(id);
  function attach() {
    if (!host.isConnected && document.documentElement) document.documentElement.append(host);
  }
  function render(next) {
    if (state !== next) confirming = false;
    state = next;
    attach();
    const allowed = state === 'allowed';
    document.documentElement.toggleAttribute('data-birnefes-allowed', allowed);
    host.style.display = allowed ? 'none' : 'block';
    if (lastMuted !== !allowed) {
      lastMuted = !allowed;
      chrome.runtime.sendMessage({operation:'audio',blocked:!allowed}).catch(() => { lastMuted = undefined; });
    }
    if (document.body) document.body.inert = !allowed;
    const titles = {connecting:'Hakların kontrol ediliyor…', waiting:'Bir nefes al.', expired:'Kullanım süren doldu.', exhausted:'Bugünkü hakkın bitti.', error:'Sunucuya ulaşılamadı.', unpaired:'Bir Nefes’i eşleştir.'};
    $('title').textContent = titles[state] || '';
    $('detail').textContent = state === 'waiting' ? 'Şu an bu siteye neden giriyorsun?' : state === 'connecting' ? 'Telefon ve bilgisayar aynı hakkı paylaşır.' : state === 'error' ? 'Bağlantını kontrol edip yeniden gir.' : state === 'unpaired' ? 'Eklenti simgesine tıkla.' : 'Ekrandan ayrılma zamanı.';
    $('note').textContent = state === 'waiting' ? '30 saniyelik mola bitince otomatik devam edeceksin.' : state === 'expired' ? 'Ana sayfaya dön. Kalan hakkın varsa yeniden girişte yeni mola başlar.' : state === 'exhausted' ? 'Hakların her gün 03.00’te yenilenir.' : state === 'error' ? 'Bağlantı kurulmadan yeni kullanım hakkı açılamaz.' : state === 'unpaired' ? 'Telefondaki kodu gir. Sonra bu sayfayı yenile.' : 'Birazdan devam edeceksin.';
    $('count').hidden = state !== 'waiting';
    $('urgent').hidden = allowed || state === 'connecting' || state === 'unpaired' || emergencyRemaining === 0;
    $('urgent').textContent = confirming ? 'Aylık 1 hakkı kullan ve aç' : `Acele geçişi · 20 dakika${emergencyRemaining >= 0 ? ` · ${emergencyRemaining}/4 kaldı` : ''}`;
    $('urgent-note').hidden = $('urgent').hidden;
    $('urgent-note').textContent = 'Günlük hakkından düşmez. Ayda 4 kez; ayın 1’inde 03.00’te yenilenir. İnternet gerekir.';
    if (!allowed) stopMedia();
  }
  $('home').addEventListener('click', () => chrome.runtime.sendMessage({operation:'home'}).catch(() => location.replace('about:blank')));
  $('urgent').addEventListener('click', () => {
    if (busy) return;
    if (!confirming) { confirming = true; render(state); return; }
    attempt = crypto.randomUUID(); render('connecting'); request('emergency');
  });
  function stopMedia() {
    for (const media of document.querySelectorAll('video,audio')) media.pause();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
  }
  document.addEventListener('play', event => {
    if (state !== 'allowed' && event.target instanceof HTMLMediaElement) event.target.pause();
  }, true);
  function expired() { return performance.now() >= deadline || Date.now() >= wallDeadline; }
  async function request(operation) {
    const current = generation;
    busy = true;
    try {
      const result = await chrome.runtime.sendMessage({operation, attempt});
      if (current !== generation) return;
      pollAt = performance.now() + 5000;
      if (operation === 'status' && (state !== 'allowed' || expired())) { render('expired'); return; }
      if (!result || result.error) { if (state !== 'allowed') render(result?.error === 'unpaired' ? 'unpaired' : 'error'); return; }
      emergencyRemaining = Number.isInteger(result.emergencyRemaining) ? result.emergencyRemaining : -1;
      if (result.state === 'allowed') {
        if (operation === 'status' && result.session !== session) { render('expired'); return; }
        const left = Math.max(0, result.expiresAt - result.serverNow - result.transitMs);
        deadline = operation === 'status' ? Math.min(deadline, performance.now()+left) : performance.now()+left;
        wallDeadline = operation === 'status' ? Math.min(wallDeadline, Date.now()+left) : Date.now()+left;
        session = result.session;
        render(left > 0 ? 'allowed' : 'expired');
      } else if (result.state === 'waiting') {
        ready = performance.now() + Math.max(0, result.readyAt - result.serverNow);
        render('waiting');
      } else render(['exhausted','emergency_exhausted'].includes(result.state) ? 'exhausted' : 'expired');
    } catch { if (current === generation && state !== 'allowed') render('error'); }
    finally { if (current === generation) busy = false; }
  }
  function enter() {
    generation++; busy=false; attempt=crypto.randomUUID();
    render('connecting'); request('enter');
  }
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') enter();
    else { generation++; busy=false; if(state !== 'allowed') render('connecting'); }
  });
  // Keep the mask above the app even when the app replaces its root during load.
  const observer = new MutationObserver(() => {
  if (birNefesCategory(location.href) !== 'messaging') return; attach(); if(document.body) document.body.inert = state !== 'allowed'; });
  observer.observe(document.documentElement, {childList:true});
  setInterval(() => {
  if (birNefesCategory(location.href) !== 'messaging') return;
    if (state === 'allowed' && expired()) render('expired');
    if (document.visibilityState !== 'visible') return;
    if (state === 'waiting') {
      $('count').textContent = String(Math.max(0, Math.ceil((ready-performance.now())/1000)));
      if (performance.now() >= ready && !busy) {render('connecting');request('complete');}
    } else if (state === 'allowed' && performance.now() >= pollAt && !busy) request('status');
  }, 100);
  render('connecting');
  if (document.visibilityState === 'visible') enter();
})();
