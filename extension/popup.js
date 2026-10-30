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
    detail.textContent='Bu süre mesajlaşma haklarından ayrıdır. Sekmeden ayrılınca sayaç durur.';
  } catch {status.textContent='Süre kontrol edilemedi';detail.textContent='Sunucu bağlantısını kontrol et.';}
})();
