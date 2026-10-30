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
