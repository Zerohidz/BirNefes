// The account's quota rules come from the server in every answer. These are the
// built-in values an older server implies when its answer carries no `rules`.
const BIRNEFES_DEFAULT_RULES = Object.freeze({
  rights: 5, windowMin: 20, waitSec: 30,
  renewal: '03:00', nightStart: '22:30', nightWindowMin: 5,
  urgentCount: 4, urgentMin: 20,
  video: {
    weekday: {closedFrom: '22:30', closedUntil: '05:00', meteredFrom: '08:00', meteredUntil: '17:00', budgetMin: 10},
    weekend: {closedFrom: '22:30', closedUntil: '05:00', meteredFrom: null, meteredUntil: null, budgetMin: 0}
  },
  work: {enabled: true, minutes: [15, 30, 60, 90], dailyMin: 180, summary: true}
});

// Texts must not break on a malformed answer, so anything that does not look like
// a rule set falls back to the defaults as a whole.
function birNefesRules(value) {
  const time = v => typeof v === 'string' && /^\d\d:\d\d$/.test(v);
  const day = d => d && typeof d === 'object' && (d.closedFrom === null || time(d.closedFrom)) && time(d.closedUntil)
    && (d.meteredFrom === null || time(d.meteredFrom)) && (d.meteredUntil === null || time(d.meteredUntil)) && Number.isInteger(d.budgetMin);
  const ok = value && typeof value === 'object'
    && ['rights','windowMin','waitSec','nightWindowMin','urgentCount','urgentMin'].every(k => Number.isInteger(value[k]))
    && time(value.renewal) && time(value.nightStart)
    && value.video && day(value.video.weekday) && day(value.video.weekend)
    && value.work && typeof value.work.enabled === 'boolean' && Array.isArray(value.work.minutes)
    && value.work.minutes.length > 0 && value.work.minutes.every(Number.isInteger) && Number.isInteger(value.work.dailyMin);
  return ok ? value : BIRNEFES_DEFAULT_RULES;
}

// "08:00" -> "08.00"
function birNefesClock(hhmm) { return String(hhmm).replace(':', '.'); }
function birNefesMinutes(hhmm) { const [h, m] = String(hhmm).split(':').map(Number); return h * 60 + m; }

// Turkish case suffix after a clock time, chosen by how its last number is read:
// "03.00’te", "17.00’de", "22.30’da".
function birNefesAt(hhmm) {
  const [hh, mm] = birNefesClock(hhmm).split('.');
  const n = Number(mm === '00' ? hh : mm);
  const suffix = n === 0 ? 'da' : n % 10 ? 'de,de,te,te,te,da,de,de,da'.split(',')[n % 10 - 1] : 'da,de,da,ta,de'.split(',')[n / 10 - 1];
  return `${birNefesClock(hhmm)}’${suffix}`;
}

// 45 -> "45 dakika", 60 -> "1 saat", 90 -> "1,5 saat", 195 -> "3 saat 15 dakika"
function birNefesDuration(minutes) {
  if (minutes < 60) return `${minutes} dakika`;
  const h = Math.floor(minutes / 60), m = minutes % 60;
  return !m ? `${h} saat` : m === 30 ? `${h},5 saat` : `${h} saat ${m} dakika`;
}

// 30 -> "30 saniye", 60 -> "1 dakika", 90 -> "1 dakika 30 saniye"
function birNefesWait(seconds) {
  if (seconds < 60) return `${seconds} saniye`;
  const m = Math.floor(seconds / 60), s = seconds % 60;
  return s ? `${m} dakika ${s} saniye` : `${m} dakika`;
}
// 30 -> "30 saniyelik", 60 -> "1 dakikalık"
function birNefesWaitLabel(seconds) {
  return birNefesWait(seconds) + (seconds % 60 || seconds < 60 ? 'lik' : 'lık');
}

const birNefesSpan = (from, until) => `${birNefesClock(from)}–${birNefesClock(until)}`;

// "Günde 5 × 20 dakika · Önce 30 saniye"
function birNefesDailyLine(rules) {
  const r = birNefesRules(rules);
  return `Günde ${r.rights} × ${r.windowMin} dakika · ${r.waitSec ? `Önce ${birNefesWait(r.waitSec)}` : 'Beklemeden'}`;
}

// "22.30–03.00: kalan haklardan biri, 5 dakika"
function birNefesNightLine(rules) {
  const r = birNefesRules(rules);
  return `${birNefesSpan(r.nightStart, r.renewal)}: ${r.nightWindowMin ? `kalan haklardan biri, ${r.nightWindowMin} dakika` : 'yeni hak açılmaz'}`;
}

// "Acele geçişi · 20 dakika · 3/4 kaldı" (remaining < 0 means unknown)
function birNefesUrgentButton(rules, remaining) {
  const r = birNefesRules(rules);
  return `Acele geçişi · ${r.urgentMin} dakika${remaining >= 0 ? ` · ${remaining}/${r.urgentCount} kaldı` : ''}`;
}

// "Ayda 4 × 20 dakika · Beklemeden"
function birNefesUrgentLine(rules) {
  const r = birNefesRules(rules);
  return `Ayda ${r.urgentCount} × ${r.urgentMin} dakika · Beklemeden`;
}

function birNefesMonthlyRenewal(rules) {
  return `ayın 1’inde ${birNefesAt(birNefesRules(rules).renewal)}`;
}

// Parts of one budget day for a day type, in order from the renewal.
function birNefesDaySegments(rules, type) {
  const r = birNefesRules(rules), d = r.video[type];
  const end = d.closedFrom ?? r.renewal;
  const free = d.meteredFrom ? [[d.closedUntil, d.meteredFrom], [d.meteredUntil, end]] : [[d.closedUntil, end]];
  const off = t => (birNefesMinutes(t) - birNefesMinutes(r.renewal) + 1440) % 1440;
  return {
    free: free.filter(([a, b]) => off(a) < (off(b) || 1440)),
    metered: d.meteredFrom ? [d.meteredFrom, d.meteredUntil, d.budgetMin] : null,
    // The evening close runs through the renewal into the next morning's close.
    closed: d.closedFrom || d.closedUntil !== r.renewal ? [d.closedFrom ?? r.renewal, d.closedUntil] : null
  };
}

// The video schedule as short lines, weekday first.
function birNefesVideoLines(rules) {
  const days = [['weekday', 'Hafta içi'], ['weekend', 'Cumartesi ve pazar']];
  const parts = days.map(([type]) => birNefesDaySegments(rules, type));
  const lines = [];
  days.forEach(([, label], i) => {
    const p = parts[i];
    if (p.free.length) lines.push(`${label} ${p.free.map(([a, b]) => birNefesSpan(a, b)).join(' ve ')} serbest`);
    if (p.metered) lines.push(`${label} ${birNefesSpan(p.metered[0], p.metered[1])} ${p.metered[2] ? `toplam ${p.metered[2]} dakika` : 'kapalı'}`);
  });
  const closed = parts.map(p => p.closed && birNefesSpan(...p.closed));
  if (closed[0] === closed[1]) { if (closed[0]) lines.push(`${closed[0]} kapalı`); }
  else days.forEach(([, label], i) => { if (closed[i]) lines.push(`${label} ${closed[i]} kapalı`); });
  return lines;
}

// "Hafta içi 08.00–17.00" (and the weekend's metered hours when there are any); '' if none.
function birNefesMeteredText(rules) {
  const r = birNefesRules(rules), out = [];
  if (r.video.weekday.meteredFrom) out.push(`hafta içi ${birNefesSpan(r.video.weekday.meteredFrom, r.video.weekday.meteredUntil)}`);
  if (r.video.weekend.meteredFrom) out.push(`hafta sonu ${birNefesSpan(r.video.weekend.meteredFrom, r.video.weekend.meteredUntil)}`);
  const text = out.join(', ');
  return text && text[0].toLocaleUpperCase('tr-TR') + text.slice(1);
}

// Where `ms` falls: the budget day's type and minutes since its renewal, in Istanbul time.
function birNefesDayAt(rules, ms) {
  const r = birNefesRules(rules);
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {timeZone: 'Europe/Istanbul', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', hourCycle: 'h23'})
    .formatToParts(new Date(ms)).map(p => [p.type, Number(p.value)]));
  const minutes = parts.hour * 60 + parts.minute, renewal = birNefesMinutes(r.renewal);
  const weekday = d => new Date(Date.UTC(parts.year, parts.month - 1, parts.day + d)).getUTCDay();
  const type = d => [0, 6].includes(weekday(d)) ? 'weekend' : 'weekday';
  const back = minutes < renewal ? -1 : 0;
  return {type: type(back), nextType: type(back + 1), off: (minutes - renewal + 1440) % 1440};
}

// The clock time the night close ends, seen from `ms`.
function birNefesNightOpens(rules, ms) {
  const r = birNefesRules(rules), at = birNefesDayAt(r, ms), d = r.video[at.type];
  const off = t => (birNefesMinutes(t) - birNefesMinutes(r.renewal) + 1440) % 1440;
  return at.off < off(d.closedUntil) ? d.closedUntil : r.video[at.nextType].closedUntil;
}

// The end of the metered hours of the budget day around `ms`, or null.
function birNefesMeteredEnds(rules, ms) {
  const r = birNefesRules(rules);
  return r.video[birNefesDayAt(r, ms).type].meteredUntil;
}

// The rule summary for the popup's Kurallar tab, as titled groups of lines.
function birNefesSummary(rules) {
  const r = birNefesRules(rules);
  const work = r.work.enabled
    ? [`Açık · ${r.work.minutes.map(birNefesDuration).join(', ')}`, `Günde en fazla ${birNefesDuration(r.work.dailyMin)}`, r.work.summary ? 'Gün sonunda kardeşine özet e-posta gider' : 'Özet e-posta gitmez']
    : ['Kapalı'];
  return [
    ['Telefon + mesajlaşma', [birNefesDailyLine(r), birNefesNightLine(r), `Günlük yenileme ${birNefesClock(r.renewal)} · İstanbul saati`]],
    ['Acele geçişi', [birNefesUrgentLine(r), `Aylık yenileme: ayın 1’i, ${birNefesClock(r.renewal)}`]],
    ['Video / film', birNefesVideoLines(r)],
    ['Çalışma geçişi', work]
  ];
}

if (typeof module !== 'undefined') module.exports = {
  BIRNEFES_DEFAULT_RULES, birNefesRules, birNefesClock, birNefesAt, birNefesDuration, birNefesWait, birNefesWaitLabel,
  birNefesDailyLine, birNefesNightLine, birNefesUrgentButton, birNefesUrgentLine, birNefesMonthlyRenewal,
  birNefesVideoLines, birNefesMeteredText, birNefesDayAt, birNefesNightOpens, birNefesMeteredEnds, birNefesSummary
};
