(() => {
  if (birNefesCategory(location.href) !== 'video') return;
  let generation = 0, attempt = '', busy = false, state = 'connecting', mode = '';
  let deadline = 0, wallDeadline = 0, pollAt = 0, emergencyRemaining = -1;
  let confirming = false, lastMuted, limitUntil = 0;
  const host = document.createElement('bir-nefes-blocker');
  host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;display:block;visibility:visible';
  const shadow = host.attachShadow({mode:'closed'});
  shadow.innerHTML = `<style>
    :host{color-scheme:dark}*{box-sizing:border-box}main{position:fixed;inset:0;background:#0a141b;color:#ebf2ee;font:18px/1.6 system-ui,sans-serif;display:flex;align-items:center;justify-content:center;padding:32px;overflow:auto}
    section{max-width:560px;text-align:center}h1{font-size:clamp(28px,4vw,40px);line-height:1.2;margin:0 0 20px}p{color:#bacac4}button{font:600 16px system-ui;background:#50b582;color:#0a141b;border:0;border-radius:10px;padding:16px 24px;margin:12px 4px;min-height:52px;cursor:pointer}button:focus-visible{outline:3px solid white;outline-offset:4px}small{display:block;color:#93aaa0;font-size:14px}[hidden]{display:none!important}
    </style><main role="dialog" aria-modal="true" aria-labelledby="title"><section><h1 id="title"></h1><p id="detail"></p><button id="home">Ana sayfaya dön</button><button id="urgent" hidden></button><p id="note"></p><small>Video / film · İstanbul saati<br>Hafta içi 05.00–08.00 ve 17.00–22.30 serbest<br>Hafta içi 08.00–17.00 toplam 20 dakika<br>Cumartesi ve pazar 05.00–22.30 serbest<br>22.30–05.00 kapalı</small></section></main>`;
  const $ = id => shadow.getElementById(id);
  const timer = birNefesTimer();
  const foreground = () => document.visibilityState === 'visible' && document.hasFocus();
  function attach() { if (!host.isConnected && document.documentElement) document.documentElement.append(host); }
  function stopMedia() {
    for (const media of document.querySelectorAll('video,audio')) media.pause();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
  }
  function render(next) {
    if (state !== next) confirming = false;
    state = next; attach();
    const allowed = state === 'allowed';
    document.documentElement.toggleAttribute('data-birnefes-allowed', allowed);
    host.style.display = allowed ? 'none' : 'block';
    if (document.body) document.body.inert = !allowed;
    if (lastMuted !== !allowed) {
      lastMuted = !allowed;
      chrome.runtime.sendMessage({operation:'audio', blocked:!allowed}).catch(() => { lastMuted=undefined; });
    }
    const limited = state === 'exhausted' && mode === 'limited';
    $('title').textContent = limited ? 'Ek kısıt açık.' : state === 'unpaired' ? 'Bir Nefes’i eşleştir.' : state === 'connecting' ? 'Video hakkın kontrol ediliyor…' : state === 'paused' ? 'Video süren duraklatıldı.' : state === 'error' ? 'Sunucuya ulaşılamadı.' : mode === 'night' ? 'Şimdi ekranı bırakma zamanı.' : '20 dakikalık video hakkın bitti.';
    $('detail').textContent = limited ? (limitUntil > 0 ? `${birNefesUntil(limitUntil)} kendi koyduğun kısıt sürüyor.` : 'Kendi koyduğun kısıt sürüyor.') : state === 'unpaired' ? 'Eklenti simgesine tıkla. Telefondaki kodu gir, sonra bu sayfayı yenile.' : state === 'paused' ? 'Bu sekmeye dönünce kaldığın süreden devam edersin.' : state === 'error' ? 'Bağlantı gelince kalan süren kontrol edilir.' : state === 'connecting' ? 'Video süresi mesajlaşma haklarından ayrıdır.' : mode === 'night' ? 'Video ve film siteleri 05.00’te açılacak.' : '17.00’de serbest kullanım başlayacak.';
    $('urgent').hidden = allowed || ['connecting','paused','unpaired'].includes(state) || emergencyRemaining === 0;
    $('urgent').textContent = confirming ? 'Aylık 1 hakkı kullan ve aç' : `Acele geçişi · 20 dakika${emergencyRemaining >= 0 ? ` · ${emergencyRemaining}/4 kaldı` : ''}`;
    $('note').textContent = limited ? 'Acele geçişi açılabilir. Erken bitirmek için kardeşinden izin iste.' : $('urgent').hidden ? '' : 'Ayda 4 ortak acele hakkı. Günlük video süresinden düşmez.';
    if (!allowed) stopMedia();
  }
  document.addEventListener('play', e => { if(state !== 'allowed' && e.target instanceof HTMLMediaElement) e.target.pause(); }, true);
  $('home').addEventListener('click', () => chrome.runtime.sendMessage({operation:'home'}).catch(() => location.replace('about:blank')));
  $('urgent').addEventListener('click', () => {
    if(busy) return;
    if(!confirming) { confirming=true; render(state); return; }
    render('connecting'); request('video-emergency');
  });
  function expired() { return performance.now() >= deadline || Date.now() >= wallDeadline; }
  async function request(operation) {
    const current = generation; busy=true;
    try {
      const result = await chrome.runtime.sendMessage({operation,attempt});
      if(current !== generation || !foreground()) return;
      pollAt = performance.now() + (result?.mode === 'metered' ? 2000 : 5000);
      if(!result || result.error) {
        if(state !== 'allowed' || expired()) render(result?.error === 'unpaired' ? 'unpaired' : 'error');
        return;
      }
      mode = result.mode;
      limitUntil = Number.isFinite(result.nextChangeAt) && result.nextChangeAt > result.serverNow ? result.nextChangeAt
        : Math.max(Number(result.extraAllUntil) || 0, Number(result.extraVideoUntil) || 0);
      emergencyRemaining = result.emergencyRemaining;
      if(result.state === 'allowed') {
        const left = Math.max(0,result.expiresAt-result.serverNow-result.transitMs);
        deadline = performance.now()+left; wallDeadline=Date.now()+left;
        render(left > 0 ? 'allowed' : 'connecting');
      } else render('exhausted');
    } catch { if(current === generation) { pollAt=performance.now()+5000; if(state !== 'allowed' || expired()) render('error'); } }
    finally { if(current === generation) busy=false; }
  }
  function enter() {
    if(!foreground()) return;
    generation++; busy=false; attempt=crypto.randomUUID();
    render('connecting'); request('video-enter');
  }
  function leave() {
    // Free and urgent time is not metered, so it may keep playing in the background
    // until its server deadline; only the metered budget stops with the tab.
    if (state === 'allowed' && mode !== 'metered' && !expired()) return;
    const previous = attempt; attempt=''; generation++; busy=false;
    render('paused');
    if(previous) chrome.runtime.sendMessage({operation:'video-stop',attempt:previous}).catch(() => {});
  }
  window.addEventListener('focus', () => { if(!attempt) enter(); });
  window.addEventListener('blur', leave);
  window.addEventListener('pagehide', leave);
  window.addEventListener('pageshow', () => { if(!attempt) enter(); });
  document.addEventListener('visibilitychange', () => {
    if(foreground()) { if(!attempt) enter(); } else leave();
  });
  const observer = new MutationObserver(() => { attach(); if(document.body) document.body.inert=state!=='allowed'; });
  observer.observe(document.documentElement, {childList:true});
  setInterval(() => {
    if(state === 'allowed' && expired()) render('connecting');
    // Free hours need no clock; metered and urgent time does.
    if(state === 'allowed' && mode !== 'free') timer.show(deadline - performance.now()); else timer.hide();
    if(foreground() && !busy && performance.now() >= pollAt) { if(!attempt) enter(); else request('video-tick'); }
  },100);
  render('connecting'); if(foreground()) enter(); else render('paused');
})();
