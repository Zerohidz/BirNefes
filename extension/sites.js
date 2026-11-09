// Exact domain boundaries, including regional domains of the two named video sites.
function birNefesTarget(value) {
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol)) return false;
    const host = url.hostname.toLowerCase().replace(/\.$/, '');
    const domains = ['youtube.com', 'youtu.be', 'youtube-nocookie.com', 'whatsapp.com', 'telegram.org', 't.me'];
    return domains.some(d => host === d || host.endsWith('.' + d)) ||
      /(^|\.)(tranimeizle|fullhdfilmizlesene)\.[a-z0-9.-]+$/.test(host);
  } catch { return false; }
}

function birNefesCategory(value) {
  if (!birNefesTarget(value)) return null;
  const host = new URL(value).hostname.toLowerCase().replace(/\.$/, '');
  return ['whatsapp.com','telegram.org','t.me'].some(d => host === d || host.endsWith('.'+d)) ? 'messaging' : 'video';
}

// "18.30’a kadar" in Istanbul time; the suffix follows how the last number is read.
function birNefesUntil(ms) {
  const parts = new Intl.DateTimeFormat('tr-TR', {timeZone:'Europe/Istanbul', hour:'2-digit', minute:'2-digit', hourCycle:'h23'}).formatToParts(new Date(ms));
  const hh = parts.find(p => p.type === 'hour').value, mm = parts.find(p => p.type === 'minute').value;
  const n = Number(mm === '00' ? hh : mm);
  const suffix = n === 0 ? 'a' : n % 10 ? 'e,ye,e,e,e,ya,ye,e,a'.split(',')[n % 10 - 1] : 'a,ye,a,a,ye'.split(',')[n / 10 - 1];
  return `${hh}.${mm}’${suffix} kadar`;
}

// A small always-on-top pill with the time left in an open window; it never takes clicks.
function birNefesTimer() {
  const host = document.createElement('bir-nefes-timer');
  host.style.cssText = 'all:initial;position:fixed;right:16px;bottom:16px;z-index:2147483647;pointer-events:none;display:none';
  const shadow = host.attachShadow({mode: 'closed'});
  shadow.innerHTML = `<style>div{font:700 26px/1 system-ui,sans-serif;font-variant-numeric:tabular-nums;color:#ebf2ee;background:rgba(10,20,27,.88);border:2px solid #50b582;border-radius:12px;padding:10px 16px;box-shadow:0 4px 16px rgba(0,0,0,.4)}small{display:block;font:500 12px system-ui,sans-serif;color:#bacac4;margin-bottom:4px}</style><div><small>Kalan süre</small><span></span></div>`;
  const text = shadow.querySelector('span');
  return {
    show(ms) {
      if (!host.isConnected && document.documentElement) document.documentElement.append(host);
      const s = Math.max(0, Math.ceil(ms / 1000));
      const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = String(s % 60).padStart(2, '0');
      text.textContent = h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${String(m).padStart(2, '0')}:${sec}`;
      host.style.display = 'block';
    },
    hide() { host.style.display = 'none'; }
  };
}
