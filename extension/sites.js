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
