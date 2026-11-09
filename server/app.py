#!/usr/bin/env python3
"""Multi-account quota authority. Bind only to loopback behind HTTPS nginx."""
import hashlib
import json
import os
import re
import queue
import secrets
import smtplib
import sqlite3
import ssl
import threading
import time
from email.message import EmailMessage
from urllib.parse import parse_qs
from contextlib import contextmanager
from datetime import datetime, timedelta, time as daytime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

ZONE = ZoneInfo('Europe/Istanbul')
# Today's policy; an account without rule_sets rows uses it. Every policy number lives here.
DEFAULT_RULES = {
    'rights': 5, 'windowMin': 20, 'waitSec': 30,
    'renewal': '03:00', 'nightStart': '22:30', 'nightWindowMin': 5,
    'urgentCount': 4, 'urgentMin': 20,
    'video': {
        'weekday': {'closedFrom': '22:30', 'closedUntil': '05:00', 'meteredFrom': '08:00', 'meteredUntil': '17:00', 'budgetMin': 10},
        'weekend': {'closedFrom': '22:30', 'closedUntil': '05:00', 'meteredFrom': None, 'meteredUntil': None, 'budgetMin': 0},
    },
    'work': {'enabled': True, 'minutes': [15, 30, 60, 90], 'dailyMin': 180, 'summary': True},
}
VIDEO_LEASE = 5_000
# An urgent pass row written before quota.started existed was always twenty minutes long.
LEGACY_URGENT = 1_200_000
# A study pass opens metered video on the computer only, after a typed oath; the guardian gets a daily summary.
WORK_OPTIONS = (15, 30, 45, 60, 90, 120)
WORK_OATH = 'yemin ederim'
WORK_SUBJECT = 'Bir Nefes: bugünkü çalışma geçişleri'
CLOCK = re.compile('([01][0-9]|2[0-3]):[03]0')

def defaults():
    return json.loads(json.dumps(DEFAULT_RULES))

def minutes_of(text):
    hour, minute = text.split(':')
    return int(hour) * 60 + int(minute)

def offset(text, rules):
    # Minutes after the renewal, so a budget day runs from 0 to 1440.
    return (minutes_of(text) - minutes_of(rules['renewal'])) % 1440

def stamp(date, minutes):
    # Wall-clock instant `minutes` after midnight of `date` (may run into the next days).
    days, minutes = divmod(minutes, 1440)
    return int(datetime.combine(date + timedelta(days=days), daytime(minutes // 60, minutes % 60), ZONE).timestamp() * 1000)

def bounds(now, rules):
    renewal = minutes_of(rules['renewal'])
    local = datetime.fromtimestamp(now / 1000, ZONE)
    date = local.date() - (timedelta(days=1) if local.hour * 60 + local.minute < renewal else timedelta())
    cutoff = stamp(date, renewal + offset(rules['nightStart'], rules))
    return str(date), cutoff, stamp(date, renewal + 1440)

def schedule(date, rules):
    """One budget day's video calendar: (opens, meteredFrom, meteredUntil, closes, budget ms).

    Closed before `opens` and from `closes`; metered in [meteredFrom, meteredUntil) when set; free otherwise.
    """
    day = rules['video']['weekend' if date.weekday() >= 5 else 'weekday']
    renewal = minutes_of(rules['renewal'])
    at = lambda text: stamp(date, renewal + offset(text, rules))
    closes = stamp(date, renewal + 1440) if day['closedFrom'] is None else at(day['closedFrom'])
    if day['meteredFrom'] is None:
        return at(day['closedUntil']), None, None, closes, 0
    return at(day['closedUntil']), at(day['meteredFrom']), at(day['meteredUntil']), closes, day['budgetMin'] * 60_000

def rules_at(db, account, now):
    # The highest version already in force, else the defaults (version 0).
    row = db.execute('SELECT version, effective_at, rules FROM rule_sets WHERE account_id=? AND effective_at<=? '
                     'ORDER BY version DESC LIMIT 1', (account, now)).fetchone()
    return (row['version'], row['effective_at'], json.loads(row['rules'])) if row else (0, 0, defaults())

def check_rules(rules):
    """Returns the path of the first invalid field, or None."""
    def shape(value, template, path):
        if not isinstance(value, dict):
            return path or 'rules'
        for key in list(template) + list(value):
            if key not in template or key not in value:
                return (path + '.' if path else '') + str(key)
        return None
    number = lambda value, low, high, step=1: type(value) is int and low <= value <= high and value % step == 0
    clock = lambda value: isinstance(value, str) and CLOCK.fullmatch(value) is not None
    bad = shape(rules, DEFAULT_RULES, '')
    if bad:
        return bad
    for key, low, high, step in (('rights', 0, 10, 1), ('windowMin', 5, 60, 5), ('waitSec', 0, 300, 5)):
        if not number(rules[key], low, high, step):
            return key
    if not clock(rules['renewal']) or minutes_of(rules['renewal']) > 360:
        return 'renewal'
    off = lambda text: offset(text, rules)
    # Night start sits 12 to 23 hours after the renewal, so a late renewal cannot remove the night rule.
    if not clock(rules['nightStart']) or not 720 <= off(rules['nightStart']) <= 1380:
        return 'nightStart'
    for key, low, high, step in (('nightWindowMin', 0, 20, 5), ('urgentCount', 0, 8, 1), ('urgentMin', 5, 30, 5)):
        if not number(rules[key], low, high, step):
            return key
    bad = shape(rules['video'], DEFAULT_RULES['video'], 'video')
    if bad:
        return bad
    for kind in ('weekday', 'weekend'):
        path, day = f'video.{kind}.', rules['video'][kind]
        bad = shape(day, DEFAULT_RULES['video']['weekday'], path[:-1])
        if bad:
            return bad
        closed_from, closed_until = day['closedFrom'], day['closedUntil']
        if closed_from is not None and (not clock(closed_from) or off(closed_from) < 720):
            return path + 'closedFrom'
        closes = 1440 if closed_from is None else off(closed_from)
        if not clock(closed_until) or off(closed_until) >= 720:
            return path + 'closedUntil'
        start, end = day['meteredFrom'], day['meteredUntil']
        if (start is None) != (end is None):
            return path + ('meteredUntil' if end is None else 'meteredFrom')
        if start is not None:
            if not clock(start) or off(start) < off(closed_until):
                return path + 'meteredFrom'
            if not clock(end) or not off(start) < off(end) <= closes:
                return path + 'meteredUntil'
        if not number(day['budgetMin'], 0, 120, 5) or (start is None and day['budgetMin']):
            return path + 'budgetMin'
    work = rules['work']
    bad = shape(work, DEFAULT_RULES['work'], 'work')
    if bad:
        return bad
    if type(work['enabled']) is not bool:
        return 'work.enabled'
    minutes = work['minutes']
    if (not isinstance(minutes, list) or not minutes or any(type(m) is not int or m not in WORK_OPTIONS for m in minutes)
            or minutes != sorted(set(minutes))):
        return 'work.minutes'
    if not number(work['dailyMin'], 15, 480, 15):
        return 'work.dailyMin'
    if type(work['summary']) is not bool:
        return 'work.summary'
    return None

def leaves(rules, template=DEFAULT_RULES, path=''):
    # Leaf paths in the fixed DEFAULT_RULES order.
    for key, value in template.items():
        if isinstance(value, dict):
            yield from leaves(rules[key], value, path + key + '.')
        else:
            yield path + key, rules[key]

def changes(new, current):
    return [path for (path, a), (_, b) in zip(leaves(new), leaves(current)) if a != b]

def rank(rules, kind, minute):
    # Video access of one 30-minute slice: closed 0, metered 1, free 2.
    day = rules['video'][kind]
    off = lambda text: offset(text, rules)
    closes = 1440 if day['closedFrom'] is None else off(day['closedFrom'])
    if minute < off(day['closedUntil']) or minute >= closes:
        return 0
    if day['meteredFrom'] is not None and off(day['meteredFrom']) <= minute < off(day['meteredUntil']):
        return 1
    return 2

def looser(new, current):
    """True when any part of `new` gives more than `current`; a mixed change counts as looser."""
    if new['renewal'] != current['renewal']:
        return True
    more = lambda key: new[key] > current[key]
    if any(more(k) for k in ('rights', 'windowMin', 'nightWindowMin', 'urgentCount', 'urgentMin')):
        return True
    if new['waitSec'] < current['waitSec'] or offset(new['nightStart'], new) > offset(current['nightStart'], current):
        return True
    for kind in ('weekday', 'weekend'):
        if new['video'][kind]['budgetMin'] > current['video'][kind]['budgetMin']:
            return True
        if any(rank(new, kind, m) > rank(current, kind, m) for m in range(0, 1440, 30)):
            return True
    a, b = new['work'], current['work']
    return ((a['enabled'] and not b['enabled']) or max(a['minutes']) > max(b['minutes'])
            or a['dailyMin'] > b['dailyMin'] or (b['summary'] and not a['summary']))

OLD_TABLES = {
    # Single-row legacy tables keep only row id=1; the others copy every row.
    'quota': 'SELECT 1,day,used,night,end,session,{kind} FROM old_quota WHERE id=1',
    'attempts': 'SELECT 1,device,attempt,ready,expires,consumed FROM old_attempts',
    'video_quota': 'SELECT 1,day,spent,accounted FROM old_video_quota WHERE id=1',
    'video_clients': 'SELECT 1,device,attempt,end FROM old_video_clients',
    'video_closed': 'SELECT 1,device,attempt,day FROM old_video_closed',
    'emergency': 'SELECT 1,month,used FROM old_emergency WHERE id=1',
    'emergency_requests': 'SELECT 1,device,attempt FROM old_emergency_requests',
}
SCHEMA = [
    'CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY, created INTEGER NOT NULL)',
    """CREATE TABLE IF NOT EXISTS devices (
        id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL,
        kind TEXT NOT NULL CHECK(kind IN ('android','chrome')),
        token_hash TEXT NOT NULL UNIQUE, install_hash TEXT UNIQUE, created INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS pair_codes (
        account_id INTEGER PRIMARY KEY, code TEXT NOT NULL UNIQUE, expires INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS quota (
        account_id INTEGER PRIMARY KEY, day TEXT NOT NULL, used INTEGER NOT NULL,
        night INTEGER NOT NULL, end INTEGER NOT NULL, session INTEGER NOT NULL,
        kind TEXT NOT NULL DEFAULT 'normal')""",
    """CREATE TABLE IF NOT EXISTS attempts (
        account_id INTEGER NOT NULL, device TEXT NOT NULL, attempt TEXT NOT NULL,
        ready INTEGER NOT NULL, expires INTEGER NOT NULL, consumed INTEGER NOT NULL,
        PRIMARY KEY(account_id,device))""",
    """CREATE TABLE IF NOT EXISTS video_quota (
        account_id INTEGER PRIMARY KEY, day TEXT NOT NULL,
        spent INTEGER NOT NULL, accounted INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS video_clients (
        account_id INTEGER NOT NULL, device TEXT NOT NULL, attempt TEXT NOT NULL,
        end INTEGER NOT NULL, PRIMARY KEY(account_id,device,attempt))""",
    """CREATE TABLE IF NOT EXISTS video_closed (
        account_id INTEGER NOT NULL, device TEXT NOT NULL, attempt TEXT NOT NULL,
        day TEXT NOT NULL, PRIMARY KEY(account_id,device,attempt))""",
    """CREATE TABLE IF NOT EXISTS emergency (
        account_id INTEGER PRIMARY KEY, month TEXT NOT NULL, used INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS emergency_requests (
        account_id INTEGER NOT NULL, device TEXT NOT NULL, attempt TEXT NOT NULL,
        PRIMARY KEY(account_id,device,attempt))""",
    # One pending guardian request per account; a new one replaces the old.
    """CREATE TABLE IF NOT EXISTS guard_requests (
        account_id INTEGER PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('verify','change','release','extra')),
        token_hash TEXT NOT NULL UNIQUE, email TEXT, reason TEXT NOT NULL, expires INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS work_passes (
        id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, device TEXT NOT NULL, day TEXT NOT NULL,
        started INTEGER NOT NULL, end INTEGER NOT NULL, purpose TEXT NOT NULL, oath TEXT NOT NULL,
        reported INTEGER NOT NULL DEFAULT 0)""",
    # Versioned per-account rules; at most one row has effective_at in the future (the pending set).
    """CREATE TABLE IF NOT EXISTS rule_sets (
        account_id INTEGER NOT NULL, version INTEGER NOT NULL, effective_at INTEGER NOT NULL,
        rules TEXT NOT NULL, created INTEGER NOT NULL, device TEXT, PRIMARY KEY(account_id,version))""",
]
ACCOUNT_COLUMNS = {
    'guardian_email': 'TEXT',
    'released_until': 'INTEGER NOT NULL DEFAULT 0',
    'mail_day': "TEXT NOT NULL DEFAULT ''",
    'mail_count': 'INTEGER NOT NULL DEFAULT 0',
    'mail_error': 'INTEGER NOT NULL DEFAULT 0',
    # Extra limits the user starts on top of the normal rules; 0 means none.
    'extra_video_until': 'INTEGER NOT NULL DEFAULT 0',
    'extra_all_until': 'INTEGER NOT NULL DEFAULT 0',
}
# Added after the legacy copy, which inserts quota rows by position.
QUOTA_COLUMNS = {
    # When the current urgent pass started; 0 on rows written before this column.
    'started': 'INTEGER NOT NULL DEFAULT 0',
}
EXTRA_SCOPES = {'video': 'extra_video_until', 'all': 'extra_all_until'}
EXTRA_MINUTES = (30, 60, 120, 240)
CODE_LETTERS = 'ACDEFGHJKMNPQRTUVWXY'
CODE_LIFETIME = 600_000
GUARD_LIFETIME = 86_400_000
MAIL_LIMIT = 10
DECISIONS = {'verify': ('confirm',), 'change': ('approve', 'deny'), 'release': ('1h', '24h', 'permanent', 'deny'),
             'extra': ('end', 'deny')}
RELEASES = {'1h': 3_600_000, '24h': 86_400_000}
PUBLIC_URL = os.environ.get('BIRNEFES_PUBLIC_URL', 'https://zmanga.zerohidz.com/bir-nefes').rstrip('/')
SUBJECT = 'Bir Nefes: onayın isteniyor'
KIND_TEXT = {'verify': 'Seni onaylayıcı (kardeş) olarak eklemek istiyor.',
             'change': 'Onaylayıcı adresini değiştirmek istiyor.',
             'release': 'Kilidin açılmasına izin vermeni istiyor.',
             'extra': 'Kendi başlattığı ek kısıtı erken bitirmek istiyor.'}

def mask(email):
    if not email:
        return None
    local, _, domain = email.partition('@')
    return local[:1] + '***@' + domain

def mail_body(kind, email, reason, token):
    lines = ['Merhaba,', '', 'Bir Nefes kullanıcısı ' + KIND_TEXT[kind]]
    if kind == 'change':
        lines.append('Yeni adres: ' + mask(email))
    if reason:
        lines.append('Gerekçe: ' + reason)
    lines += ['', 'Karar vermek için bağlantıyı aç (24 saat geçerli, tek kullanımlık):',
              f'{PUBLIC_URL}/approve#{token}', '', 'Bu isteği beklemiyorsan görmezden gelebilirsin.']
    return '\n'.join(lines) + '\n'

def clock(ms):
    return datetime.fromtimestamp(ms / 1000, ZONE).strftime('%H.%M')

def has_oath(text):
    # Turkish casing: İ→i and I→ı before lowering, so "YEMİN EDERİM" counts.
    folded = ' '.join(text.replace('İ', 'i').replace('I', 'ı').lower().split())
    return WORK_OATH in folded

def work_body(day, rows):
    total = sum(r['end'] - r['started'] for r in rows) // 60_000
    lines = ['Merhaba,', '', f'Bir Nefes kullanıcısı {day} günü bilgisayarda {len(rows)} çalışma geçişi açtı, toplam {total} dakika.', '']
    for r in rows:
        lines.append(f"{clock(r['started'])}–{clock(r['end'])} ({(r['end'] - r['started']) // 60_000} dk): {r['purpose']}")
        lines.append(f"  Yemini: {r['oath']}")
    lines += ['', 'Bu e-posta bilgi içindir, bir şey yapman gerekmiyor.']
    return '\n'.join(lines) + '\n'

def digest(secret):
    return hashlib.sha256(secret.encode()).hexdigest()

class Store:
    def __init__(self, path, legacy_devices=None, mail=None):
        self.path = path
        # mail(account, to, body) queues one message; None drops it.
        self.mail = mail
        now = int(time.time() * 1000)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            columns = lambda table: {row['name'] for row in db.execute(f'PRAGMA table_info({table})')}
            # Legacy single-account tables become account 1, in one transaction.
            legacy = [t for t in OLD_TABLES if columns(t) and 'account_id' not in columns(t)]
            for table in legacy:
                db.execute(f'ALTER TABLE {table} RENAME TO old_{table}')
            for statement in SCHEMA:
                db.execute(statement)
            # Older databases have a kind CHECK without 'extra'; pending requests are copied over.
            sql = db.execute("SELECT sql FROM sqlite_master WHERE name='guard_requests'").fetchone()[0]
            if "'extra'" not in sql:
                db.execute('ALTER TABLE guard_requests RENAME TO old_guard_requests')
                db.execute(next(s for s in SCHEMA if 'guard_requests' in s))
                db.execute('INSERT INTO guard_requests SELECT * FROM old_guard_requests')
                db.execute('DROP TABLE old_guard_requests')
            for name, declaration in ACCOUNT_COLUMNS.items():
                if name not in columns('accounts'):
                    db.execute(f'ALTER TABLE accounts ADD COLUMN {name} {declaration}')
            for table in legacy:
                kind = 'kind' if 'kind' in columns('old_quota') else "'normal'"
                db.execute(f'INSERT INTO {table} ' + OLD_TABLES[table].format(kind=kind))
                db.execute(f'DROP TABLE old_{table}')
            for name, declaration in QUOTA_COLUMNS.items():
                if name not in columns('quota'):
                    db.execute(f'ALTER TABLE quota ADD COLUMN {name} {declaration}')
            if legacy:
                db.execute('INSERT OR IGNORE INTO accounts(id,created) VALUES(1,?)', (now,))
            # The old devices.json tokens join account 1 exactly once.
            if legacy_devices and os.path.exists(legacy_devices) and not db.execute('PRAGMA user_version').fetchone()[0]:
                with open(legacy_devices) as source:
                    devices = json.load(source)
                db.execute('INSERT OR IGNORE INTO accounts(id,created) VALUES(1,?)', (now,))
                for d in devices:
                    kind = d['id'] if d['id'] in ('android', 'chrome') else 'android'
                    cursor = db.execute('INSERT OR IGNORE INTO devices(account_id,kind,token_hash,created) VALUES(1,?,?,?)',
                                        (kind, d['token_hash'], now))
                    if cursor.rowcount:
                        for table in ('attempts', 'video_clients', 'video_closed', 'emergency_requests'):
                            db.execute(f'UPDATE {table} SET device=? WHERE account_id=1 AND device=?',
                                       (str(cursor.lastrowid), d['id']))
                db.execute('PRAGMA user_version=1')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        finally:
            db.close()

    def attach(self, db, account, kind, install, now):
        # A known install moves to this account and its previous token stops working.
        token = secrets.token_urlsafe(32)
        db.execute('''INSERT INTO devices(account_id,kind,token_hash,install_hash,created) VALUES(?,?,?,?,?)
                      ON CONFLICT(install_hash) DO UPDATE SET account_id=excluded.account_id,
                      kind=excluded.kind, token_hash=excluded.token_hash''',
                   (account, kind, digest(token), install, now))
        return dict(token=token, account=account)

    def register(self, kind, install=None, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            known = install and db.execute('SELECT account_id FROM devices WHERE install_hash=?', (install,)).fetchone()
            account = known[0] if known else db.execute('INSERT INTO accounts(created) VALUES(?)', (now,)).lastrowid
            return self.attach(db, account, kind, install, now)

    def pair_code(self, account, now=None):
        now = int(time.time() * 1000) if now is None else now
        code = ''.join(secrets.choice(CODE_LETTERS) for _ in range(8))
        with self.connect() as db:
            db.execute('INSERT OR REPLACE INTO pair_codes VALUES(?,?,?)', (account, code, now + CODE_LIFETIME))
        return dict(code=code, expiresAt=now + CODE_LIFETIME)

    def pair(self, code, kind, install=None, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT account_id FROM pair_codes WHERE code=? AND expires>?', (code, now)).fetchone()
            if not row:
                return None
            # A phone cannot leave an account while its guardian lock holds; the code stays usable.
            known = install and db.execute('SELECT account_id FROM devices WHERE install_hash=?', (install,)).fetchone()
            if known and known[0] != row[0] and self.guard_view(db, known[0], now)['locked']:
                return dict(error='locked')
            db.execute('DELETE FROM pair_codes WHERE account_id=?', (row[0],))
            return self.attach(db, row[0], kind, install, now)

    def authenticate(self, token):
        with self.connect() as db:
            row = db.execute('SELECT id, account_id FROM devices WHERE token_hash=?', (digest(token),)).fetchone()
        return (row['account_id'], str(row['id'])) if row else (None, None)

    def ensure(self, db, account):
        db.execute("INSERT OR IGNORE INTO quota(account_id,day,used,night,end,session,kind) VALUES(?,'',0,0,0,0,'normal')", (account,))
        db.execute("INSERT OR IGNORE INTO video_quota VALUES(?,'',0,0)", (account,))
        db.execute("INSERT OR IGNORE INTO emergency VALUES(?,'',0)", (account,))

    def request(self, account, device, operation, attempt='', now=None):
        now = int(time.time() * 1000) if now is None else now
        if operation.startswith('video_'):
            return self.video_request(account, device, operation, attempt, now)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            version, _, rules = rules_at(db, account, now)
            day, cutoff, renewal = bounds(now, rules)
            late = now >= cutoff
            rights, night_window = rules['rights'], rules['nightWindowMin'] * 60_000
            self.ensure(db, account)
            q = dict(db.execute('SELECT * FROM quota WHERE account_id=?', (account,)).fetchone())
            if q['day'] != day:
                q.update(day=day, used=0, night=0, end=q['end'] if q['kind'] == 'emergency' else 0)
            if late and q['kind'] == 'normal' and q['end'] > cutoff and not q['night']:
                q['night'] = 1
                q['end'] = min(q['end'], cutoff + night_window)
            extra = self.extra_view(db, account, now)
            limited = extra['extraAllUntil'] > 0
            # An urgent pass still opens during an extra limit; normal rights do not.
            allowed = q['end'] > now and (not limited or q['kind'] == 'emergency')
            # A zero night window means no right at all after the night start.
            night_spent = lambda: late and (q['night'] or not night_window)
            available = q['used'] < rights and not night_spent() and not limited
            state = 'allowed' if allowed else 'idle' if available else 'exhausted'
            month = day[:7]
            emergency = dict(db.execute('SELECT * FROM emergency WHERE account_id=?', (account,)).fetchone())
            if emergency['month'] != month:
                emergency.update(month=month, used=0)
            ready = 0
            if operation in ('emergency', 'emergency_video') and (not allowed or (operation == 'emergency_video' and q['kind'] != 'emergency')):
                seen = db.execute('SELECT 1 FROM emergency_requests WHERE account_id=? AND device=? AND attempt=?',
                                  (account, device, attempt)).fetchone()
                if seen:
                    state = 'expired'
                elif emergency['used'] >= rules['urgentCount']:
                    state = 'emergency_exhausted'
                else:
                    emergency['used'] += 1
                    q.update(end=now + rules['urgentMin'] * 60_000, session=q['session'] + 1, kind='emergency', started=now)
                    db.execute('INSERT INTO emergency_requests VALUES(?,?,?)', (account, device, attempt))
                    state = 'allowed'
            if operation in ('enter', 'complete') and state == 'idle':
                row = db.execute('SELECT * FROM attempts WHERE account_id=? AND device=?', (account, device)).fetchone()
                if operation == 'enter':
                    if row and row['attempt'] == attempt:
                        if row['consumed'] or row['expires'] <= now:
                            state = 'expired'
                        else:
                            ready, state = row['ready'], 'waiting'
                    else:
                        ready, state = now + rules['waitSec'] * 1000, 'waiting'
                        db.execute('INSERT OR REPLACE INTO attempts VALUES(?,?,?,?,?,0)',
                                   (account, device, attempt, ready, now + 120_000))
                elif not row or row['attempt'] != attempt or row['consumed'] or row['expires'] <= now:
                    state = 'expired'
                elif now < row['ready']:
                    ready, state = row['ready'], 'waiting'
                else:
                    q['kind'] = 'normal'
                    q['used'] += 1
                    q['night'] = int(late)
                    q['session'] += 1
                    q['end'] = min(now + (night_window if late else rules['windowMin'] * 60_000), renewal)
                    # Return the final deadline upfront, so offline clients also
                    # obey the night start without needing a push message.
                    if not late and q['end'] > cutoff:
                        q['end'] = min(q['end'], cutoff + night_window)
                    db.execute('UPDATE attempts SET consumed=1 WHERE account_id=? AND device=?', (account, device))
                    state = 'allowed'
            db.execute('UPDATE quota SET day=:day,used=:used,night=:night,end=:end,session=:session,kind=:kind,started=:started WHERE account_id=:account_id', q)
            db.execute('UPDATE emergency SET month=:month,used=:used WHERE account_id=:account_id', emergency)
            return dict(state=state, serverNow=now, readyAt=ready, expiresAt=q['end'],
                        session=q['session'], used=q['used'],
                        remaining=0 if night_spent() else max(0, min(rights - q['used'], 1 if late else rights)),
                        renewalAt=renewal, budgetDay=day, sessionKind=q['kind'],
                        emergencyRemaining=max(0, rules['urgentCount'] - emergency['used']), emergencyMonth=month,
                        rulesVersion=version, rules=rules, **extra)

    def video_request(self, account, device, operation, attempt, now):
        # Emergency allocation stays under the same atomic monthly ledger.
        with self.connect() as db:
            extra = self.extra_view(db, account, now)
        limit_until = max(extra['extraVideoUntil'], extra['extraAllUntil'])
        if operation == 'video_emergency':
            self.request(account, device, 'emergency_video', attempt, now)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            version, _, rules = rules_at(db, account, now)
            day, _, renewal = bounds(now, rules)
            date = datetime.fromisoformat(day).date()
            opens, metered_from, metered_until, closes, budget = schedule(date, rules)
            metered = metered_from is not None
            if not metered:
                # A day without metered hours reports the largest daily budget, as before.
                budget = max(d['budgetMin'] for d in rules['video'].values()) * 60_000
            self.ensure(db, account)
            q = dict(db.execute('SELECT * FROM video_quota WHERE account_id=?', (account,)).fetchone())
            if q['day'] != day:
                q.update(day=day, spent=0, accounted=now)
                db.execute('DELETE FROM video_clients WHERE account_id=?', (account,))
                db.execute('DELETE FROM video_closed WHERE account_id=? AND day<>?', (account, day))
            lease_end = db.execute('SELECT COALESCE(MAX(end),0) FROM video_clients WHERE account_id=?', (account,)).fetchone()[0]
            # Account the union of active leases: multiple tabs do not multiply
            # consumption. No client clock/duration is accepted as authority.
            grant = db.execute('SELECT * FROM quota WHERE account_id=?', (account,)).fetchone()
            urgent = grant['kind'] == 'emergency' and grant['end'] > now
            # Metering stops when the urgent pass started, whatever its length under today's rules.
            started = grant['started'] or grant['end'] - LEGACY_URGENT
            charge_end = min(now, started) if urgent else now
            elapsed = max(0, min(charge_end, lease_end, metered_until) - max(q['accounted'], metered_from)) if metered else 0
            q['spent'] = min(budget, q['spent'] + elapsed)
            q['accounted'] = now
            db.execute('DELETE FROM video_clients WHERE account_id=? AND end<=?', (account, now))
            if operation == 'video_stop':
                db.execute('INSERT OR IGNORE INTO video_closed VALUES(?,?,?,?)', (account, device, attempt, day))
                db.execute('DELETE FROM video_clients WHERE account_id=? AND device=? AND attempt=?', (account, device, attempt))
            emergency = db.execute('SELECT * FROM emergency WHERE account_id=?', (account,)).fetchone()
            emergency_left = max(0, rules['urgentCount'] - emergency['used']) if emergency['month'] == day[:7] else rules['urgentCount']
            closed = db.execute('SELECT 1 FROM video_closed WHERE account_id=? AND device=? AND attempt=?', (account, device, attempt)).fetchone()
            work = self.work_view(db, account, now, rules)
            night = now < opens or now >= closes
            in_metered = metered and metered_from <= now < metered_until
            if urgent:
                db.execute('DELETE FROM video_clients WHERE account_id=?', (account,))
                state, mode, end = 'allowed', 'emergency', grant['end']
            elif limit_until:
                state, mode, end = 'exhausted', 'limited', now
            elif not night and not in_metered:
                state, mode, end = 'allowed', 'free', metered_from if metered and now < metered_from else closes
            elif work['workUntil']:
                state, mode, end = 'allowed', 'work', work['workUntil']
            elif in_metered and q['spent'] < budget:
                state, mode = 'allowed', 'metered'
                end = min(now + VIDEO_LEASE, now + budget - q['spent'], metered_until)
                if operation in ('video_enter', 'video_tick') and not closed:
                    db.execute('INSERT OR REPLACE INTO video_clients VALUES(?,?,?,?)', (account, device, attempt, end))
            else:
                state, mode, end = 'exhausted', 'night' if night else 'exhausted', now
            if closed and operation in ('video_enter', 'video_tick'):
                state, end = 'expired', now
            # Switching to a free/emergency window stops this client's metering.
            if mode != 'metered':
                db.execute('DELETE FROM video_clients WHERE account_id=? AND device=? AND attempt=?', (account, device, attempt))
            db.execute('UPDATE video_quota SET day=:day,spent=:spent,accounted=:accounted WHERE account_id=:account_id', q)
            # After tonight's close the next change is the next budget day's opening, under the rules then in force.
            tomorrow = schedule(date + timedelta(days=1), rules_at(db, account, renewal)[2])[0]
            change = next(t for t in (opens, metered_from, metered_until, closes, tomorrow) if t is not None and t > now)
            return dict(state=state, mode=mode, serverNow=now, expiresAt=end,
                        session=grant['session'] if urgent else 0, sessionKind=mode,
                        videoRemainingMs=budget-q['spent'], emergencyRemaining=emergency_left,
                        budgetDay=day, renewalAt=renewal, readyAt=0, remaining=0,
                        **extra, **work, rulesVersion=version, rules=rules,
                        nextChangeAt=(limit_until if not urgent else 0) or change)

    def work_view(self, db, account, now, rules=None):
        rules = rules or rules_at(db, account, now)[2]
        day = bounds(now, rules)[0]
        rows = db.execute('SELECT started, end, purpose FROM work_passes WHERE account_id=? AND day=? ORDER BY started',
                          (account, day)).fetchall()
        active = next((r['end'] for r in rows if r['started'] <= now < r['end']), 0)
        used = sum(r['end'] - r['started'] for r in rows)
        return dict(workUntil=active, workLeftMs=max(0, rules['work']['dailyMin'] * 60_000 - used),
                    workToday=[dict(started=r['started'], end=r['end'], purpose=r['purpose']) for r in rows])

    def start_work(self, account, device, minutes, purpose, oath, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            rules = rules_at(db, account, now)[2]
            if not rules['work']['enabled']:
                return 409, {'error': 'disabled'}
            if minutes not in rules['work']['minutes']:
                return 400, {'error': 'invalid_work'}
            if not has_oath(oath):
                return 400, {'error': 'no_oath'}
            day = bounds(now, rules)[0]
            _, metered_from, metered_until, _, _ = schedule(datetime.fromisoformat(day).date(), rules)
            # Only the metered hours need it; free hours are open and the night stays closed.
            if metered_from is None or not metered_from <= now < metered_until:
                return 409, {'error': 'not_metered'}
            kind = db.execute('SELECT kind FROM devices WHERE id=? AND account_id=?', (device, account)).fetchone()
            if not kind or kind['kind'] != 'chrome':
                return 403, {'error': 'chrome_only'}
            extra = self.extra_view(db, account, now)
            if extra['extraVideoUntil'] or extra['extraAllUntil']:
                return 409, {'error': 'limited'}
            view = self.work_view(db, account, now, rules)
            if view['workUntil']:
                return 409, {'error': 'active'}
            length = min(minutes * 60_000, view['workLeftMs'])
            if length < 60_000:
                return 409, {'error': 'work_cap'}
            db.execute('INSERT INTO work_passes(account_id,device,day,started,end,purpose,oath) VALUES(?,?,?,?,?,?,?)',
                       (account, device, day, now, now + length, purpose, oath))
            return 200, self.work_view(db, account, now, rules)

    def end_work(self, account, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('UPDATE work_passes SET end=? WHERE account_id=? AND started<=? AND end>?', (now, account, now, now))
            return 200, self.work_view(db, account, now)

    def work_reports(self, now=None):
        # A day's passes are final once that day's video night starts (or its renewal, without one); each day is mailed once.
        now = int(time.time() * 1000) if now is None else now
        messages = []
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            groups = {}
            for r in db.execute('SELECT * FROM work_passes WHERE reported=0 ORDER BY account_id, day, started'):
                groups.setdefault((r['account_id'], r['day']), []).append(r)
            done = []
            for (account, pass_day), rows in groups.items():
                # The rules of that day: the ones in force when its first pass started.
                rules = rules_at(db, account, rows[0]['started'])[2]
                if now < schedule(datetime.fromisoformat(pass_day).date(), rules)[3]:
                    continue
                done += [r['id'] for r in rows]
                to = db.execute('SELECT guardian_email FROM accounts WHERE id=?', (account,)).fetchone()
                if rules['work']['summary'] and to and to[0]:
                    messages.append((account, to[0], work_body(pass_day, rows), WORK_SUBJECT))
            if done:
                db.execute('UPDATE work_passes SET reported=1 WHERE id IN (%s)' % ','.join(str(i) for i in done))
        for message in messages:
            self.send(message)
        return len(messages)

    def extra_view(self, db, account, now):
        a = db.execute('SELECT extra_video_until, extra_all_until FROM accounts WHERE id=?', (account,)).fetchone()
        video = a['extra_video_until'] if a and a['extra_video_until'] > now else 0
        every = a['extra_all_until'] if a and a['extra_all_until'] > now else 0
        pending = db.execute("SELECT 1 FROM guard_requests WHERE account_id=? AND kind='extra' AND expires>?", (account, now)).fetchone()
        return dict(extraVideoUntil=video, extraAllUntil=every, extraEndPending=bool(pending))

    def start_extra(self, account, scope, minutes, now=None):
        # Only lengthens: a running limit is never shortened from a device.
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            until = bounds(now, rules_at(db, account, now)[2])[2] if minutes == 'day' else now + minutes * 60_000
            self.ensure(db, account)
            db.execute('INSERT OR IGNORE INTO accounts(id,created) VALUES(?,?)', (account, now))
            db.execute(f'UPDATE accounts SET {EXTRA_SCOPES[scope]}=MAX({EXTRA_SCOPES[scope]},?) WHERE id=?', (until, account))
            if scope == 'all':
                # An open shared window or urgent pass closes now.
                db.execute('UPDATE quota SET end=MIN(end,?) WHERE account_id=?', (now, account))
            return 200, self.extra_view(db, account, now)

    def end_extra(self, account, reason, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            current = db.execute('SELECT guardian_email FROM accounts WHERE id=?', (account,)).fetchone()[0]
            view = self.extra_view(db, account, now)
            if not view['extraVideoUntil'] and not view['extraAllUntil']:
                return 409, {'error': 'no_extra'}
            if not current:
                return 409, {'error': 'no_guardian'}
            message = self.issue(db, account, 'extra', None, reason, current, now)
            if message is None:
                return 429, {'error': 'mail_limit'}
            view = self.extra_view(db, account, now)
        self.send(message)
        return 200, view

    def guard_view(self, db, account, now):
        a = db.execute('SELECT guardian_email, released_until, mail_error FROM accounts WHERE id=?', (account,)).fetchone()
        r = db.execute('SELECT kind FROM guard_requests WHERE account_id=? AND expires>?', (account, now)).fetchone()
        kind = r['kind'] if r else None
        guardian = a['guardian_email'] if a else None
        released = a['released_until'] if guardian and a['released_until'] > now else 0
        return dict(guardian=mask(guardian), pendingGuardian=kind in ('verify', 'change'),
                    locked=bool(guardian) and not released, releasedUntil=released,
                    pendingRelease=kind == 'release', mailError=bool(a and a['mail_error']))

    def rules_view(self, db, account, now):
        version, effective, current = rules_at(db, account, now)
        row = db.execute('SELECT version, effective_at, rules FROM rule_sets WHERE account_id=? AND effective_at>? '
                         'ORDER BY version DESC LIMIT 1', (account, now)).fetchone()
        pending = None
        if row:
            rules = json.loads(row['rules'])
            pending = dict(version=row['version'], effectiveAt=row['effective_at'], rules=rules, looser=looser(rules, current))
        return dict(current=dict(version=version, effectiveAt=effective, rules=current), pending=pending,
                    defaults=defaults(), locked=self.guard_view(db, account, now)['locked'], serverNow=now)

    def rules(self, account, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            return self.rules_view(db, account, now)

    def save_rules(self, account, rules, base, dry_run=False, device=None, now=None):
        """Stores `rules` as the one pending set, in force from the current rules' next renewal.

        While the guardian lock holds, a set looser than the current one is refused (strict mode).
        """
        now = int(time.time() * 1000) if now is None else now
        field = check_rules(rules)
        if field:
            return 400, {'error': 'invalid_rules', 'field': field}
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            version, _, current = rules_at(db, account, now)
            effective = bounds(now, current)[2]
            loose = looser(rules, current)
            locked = self.guard_view(db, account, now)['locked']
            if dry_run:
                return 200, dict(looser=loose, changes=changes(rules, current), effectiveAt=effective,
                                 allowed=not (locked and loose))
            seen = db.execute('SELECT COALESCE(MAX(version),0) FROM rule_sets WHERE account_id=?', (account,)).fetchone()[0]
            if base != seen:
                return 409, {'error': 'stale'}
            if locked and loose:
                return 409, {'error': 'locked'}
            db.execute('DELETE FROM rule_sets WHERE account_id=? AND effective_at>?', (account, now))
            db.execute('INSERT INTO rule_sets VALUES(?,?,?,?,?,?)',
                       (account, seen + 1, effective, json.dumps(rules, separators=(',', ':')), now, device))
            return 200, self.rules_view(db, account, now)

    def cancel_rules(self, account, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM rule_sets WHERE account_id=? AND effective_at>?', (account, now))
            return self.rules_view(db, account, now)

    def guard(self, account, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            return self.guard_view(db, account, now)

    def issue(self, db, account, kind, email, reason, to, now, limited=True):
        # Replaces any pending request and returns the mail to send after commit.
        day = bounds(now, rules_at(db, account, now)[2])[0]
        a = db.execute('SELECT mail_day, mail_count FROM accounts WHERE id=?', (account,)).fetchone()
        count = a['mail_count'] if a['mail_day'] == day else 0
        if limited and count >= MAIL_LIMIT:
            return None
        db.execute('UPDATE accounts SET mail_day=?, mail_count=? WHERE id=?', (day, count + 1, account))
        token = secrets.token_urlsafe(32)
        db.execute('INSERT OR REPLACE INTO guard_requests VALUES(?,?,?,?,?,?)',
                   (account, kind, digest(token), email, reason, now + GUARD_LIFETIME))
        return (account, to, mail_body(kind, email, reason, token))

    def send(self, message):
        if message and self.mail:
            self.mail(*message)

    def set_guardian(self, account, email, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            current = db.execute('SELECT guardian_email FROM accounts WHERE id=?', (account,)).fetchone()[0]
            # An existing guardian approves the change before the new address is verified.
            message = self.issue(db, account, 'change' if current else 'verify', email, '', current or email, now)
            if message is None:
                return 429, {'error': 'mail_limit'}
            view = self.guard_view(db, account, now)
        self.send(message)
        return 200, view

    def release(self, account, reason, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            current = db.execute('SELECT guardian_email FROM accounts WHERE id=?', (account,)).fetchone()[0]
            if not current:
                return 409, {'error': 'no_guardian'}
            message = self.issue(db, account, 'release', None, reason, current, now)
            if message is None:
                return 429, {'error': 'mail_limit'}
            view = self.guard_view(db, account, now)
        self.send(message)
        return 200, view

    def request_info(self, token, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            row = db.execute('SELECT * FROM guard_requests WHERE token_hash=? AND expires>?', (digest(token), now)).fetchone()
        if not row:
            return None
        return dict(kind=row['kind'], reason=row['reason'], email=mask(row['email']),
                    decisions=list(DECISIONS[row['kind']]), expiresAt=row['expires'])

    def decide(self, token, decision, now=None):
        now = int(time.time() * 1000) if now is None else now
        message = None
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM guard_requests WHERE token_hash=? AND expires>?', (digest(token), now)).fetchone()
            if not row:
                return 404, {'error': 'invalid_token'}
            kind, account = row['kind'], row['account_id']
            if decision not in DECISIONS[kind]:
                return 400, {'error': 'invalid_decision'}
            db.execute('DELETE FROM guard_requests WHERE account_id=?', (account,))
            if kind == 'verify':
                db.execute('UPDATE accounts SET guardian_email=?, released_until=0 WHERE id=?', (row['email'], account))
            elif kind == 'change' and decision == 'approve':
                # The guardian already approved, so the daily mail cap does not block it.
                message = self.issue(db, account, 'verify', row['email'], '', row['email'], now, limited=False)
            elif kind == 'extra' and decision == 'end':
                db.execute('UPDATE accounts SET extra_video_until=0, extra_all_until=0 WHERE id=?', (account,))
            elif decision in RELEASES:
                db.execute('UPDATE accounts SET released_until=MAX(released_until,?) WHERE id=?',
                           (now + RELEASES[decision], account))
            elif decision == 'permanent':
                db.execute('UPDATE accounts SET guardian_email=NULL, released_until=0 WHERE id=?', (account,))
            view = self.guard_view(db, account, now)
        self.send(message)
        return 200, dict(kind=kind, decision=decision, releasedUntil=view['releasedUntil'])

    def mail_result(self, account, ok):
        with self.connect() as db:
            db.execute('UPDATE accounts SET mail_error=? WHERE id=?', (0 if ok else 1, account))

class Mailer:
    """Sends queued mail on one background thread so requests never wait on SMTP."""
    def __init__(self, store, path=None):
        self.store = store
        self.path = path or os.environ.get('BIRNEFES_SMTP', '/etc/bir-nefes/smtp.json')
        self.queue = queue.Queue()
        threading.Thread(target=self.run, daemon=True).start()
    def __call__(self, account, to, body, subject=SUBJECT):
        self.queue.put((account, to, body, subject))
    def deliver(self, to, body, subject=SUBJECT):
        # Read on every send, so smtp.json can be added without a restart.
        with open(self.path) as source:
            config = json.load(source)
        message = EmailMessage()
        message['Subject'] = subject
        message['From'] = config['from']
        message['To'] = to
        message.set_content(body)
        port = int(config.get('port', 465))
        context = ssl.create_default_context()
        if port == 465:
            smtp = smtplib.SMTP_SSL(config['host'], port, timeout=20, context=context)
        else:
            smtp = smtplib.SMTP(config['host'], port, timeout=20)
        with smtp:
            if port != 465:
                smtp.starttls(context=context)
            smtp.login(config['user'], config['password'])
            smtp.send_message(message)
    def run(self):
        while True:
            account, to, body, subject = self.queue.get()
            try:
                self.deliver(to, body, subject)
                ok = True
            except Exception as error:
                # The type only: SMTP errors can carry addresses.
                print('mail failed:', type(error).__name__, flush=True)
                ok = False
            try:
                self.store.mail_result(account, ok)
            except sqlite3.Error:
                pass
            self.queue.task_done()

APPROVE_PAGE = '''<!doctype html>
<html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>Bir Nefes onayı</title>
<style>
body{font:17px/1.5 system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem;color:#222;background:#fafafa}
h1{font-size:1.4rem}#reason{white-space:pre-wrap;background:#fff;border-left:3px solid #888;padding:.5rem .8rem}
button{font:inherit;display:block;width:100%;margin:.5rem 0;padding:.7rem;border:1px solid #444;border-radius:6px;background:#fff;cursor:pointer}
button.deny{border-color:#a33;color:#a33}#result{font-weight:600}
</style></head><body>
<h1>Bir Nefes</h1>
<p id="text">Yükleniyor…</p>
<p id="reason" hidden></p>
<div id="buttons"></div>
<p id="result"></p>
<script>
const token = location.hash.slice(1);
const text = document.getElementById('text'), reason = document.getElementById('reason');
const buttons = document.getElementById('buttons'), result = document.getElementById('result');
const KIND = {
  verify: 'Bir Nefes kullanıcısı seni onaylayıcı (kardeş) olarak eklemek istiyor. Onaylarsan uygulamayı kapatmak için senin iznin gerekecek.',
  change: 'Kullanıcı onaylayıcı adresini değiştirmek istiyor. Yeni adres: ',
  release: 'Kullanıcı kilidin açılmasını istiyor.',
  extra: 'Kullanıcı kendi başlattığı ek kısıtı erken bitirmek istiyor.'
};
const LABEL = {confirm: 'Onaylıyorum', approve: 'İzin ver', deny: 'Reddet',
  end: 'Ek kısıtı bitir', '1h': '1 saatliğine aç', '24h': '24 saatliğine aç', permanent: 'Kilidi kalıcı kaldır (onaylayıcılıktan çık)'};
function done(r) {
  buttons.textContent = '';
  if (r.decision === 'deny') result.textContent = 'İstek reddedildi.';
  else if (r.decision === 'confirm') result.textContent = 'Onaylayıcı oldun. Kilit başladı.';
  else if (r.decision === 'approve') result.textContent = 'Değişikliğe izin verildi. Yeni adrese doğrulama maili gönderildi.';
  else if (r.decision === 'permanent') result.textContent = 'Kilit kalıcı olarak kaldırıldı. Artık onaylayıcı değilsin.';
  else if (r.decision === 'end') result.textContent = 'Ek kısıt bitirildi.';
  else result.textContent = 'Kilit açıldı: ' + new Date(r.releasedUntil).toLocaleString('tr-TR') + ' saatine kadar.';
}
function decide(decision) {
  for (const b of buttons.children) b.disabled = true;
  fetch('v1/decide', {method: 'POST', headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify({token, decision})})
    .then(r => r.json().then(v => r.ok ? done(v) : Promise.reject(v)))
    .catch(() => { result.textContent = 'Karar kaydedilemedi. Bağlantı kullanılmış ya da süresi dolmuş olabilir.'; });
}
fetch('v1/request-info?token=' + encodeURIComponent(token))
  .then(r => r.ok ? r.json() : Promise.reject())
  .then(info => {
    text.textContent = KIND[info.kind] + (info.kind === 'change' ? info.email : '');
    if (info.reason) { reason.hidden = false; reason.textContent = 'Gerekçe: ' + info.reason; }
    for (const d of info.decisions) {
      const b = document.createElement('button');
      b.textContent = LABEL[d];
      if (d === 'deny') b.className = 'deny';
      b.onclick = () => decide(d);
      buttons.appendChild(b);
    }
  })
  .catch(() => { text.textContent = 'Bu bağlantı geçersiz, kullanılmış ya da süresi dolmuş.'; });
</script></body></html>
'''
TOKEN = re.compile('[A-Za-z0-9_-]{20,100}')
EMAIL = re.compile(r'[^@\s]+@[^@\s]+\.[^@\s]+')

class Handler(BaseHTTPRequestHandler):
    server_version = 'BirNefes'
    def log_message(self, fmt, *args):
        # Never log Authorization headers or request bodies.
        pass
    def reply(self, status, value):
        data = json.dumps(value, separators=(',', ':')).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def page(self):
        data = APPROVE_PAGE.encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def body(self, limit=512):
        length = int(self.headers.get('Content-Length', '0') or 0)
        if not 0 <= length <= limit:
            raise ValueError
        payload = json.loads(self.rfile.read(length)) if length else {}
        if not isinstance(payload, dict):
            raise ValueError
        return payload
    def handle_request(self):
        path, _, query = self.path.partition('?')
        if path == '/health' and self.command == 'GET':
            return self.reply(200, {'status': 'ok', 'version': '2.0.0'})
        if path == '/approve' and self.command == 'GET':
            return self.page()
        try:
            if self.command == 'POST' and path in ('/v1/register', '/v1/pair'):
                return self.join()
            if path == '/v1/request-info' and self.command == 'GET':
                token = parse_qs(query).get('token', [''])[0]
                info = TOKEN.fullmatch(token) and self.server.store.request_info(token)
                return self.reply(200, info) if info else self.reply(404, {'error': 'invalid_token'})
            if path == '/v1/decide' and self.command == 'POST':
                try:
                    payload = self.body()
                except ValueError:
                    return self.reply(400, {'error': 'invalid_body'})
                token, decision = payload.get('token'), payload.get('decision')
                if not isinstance(token, str) or not TOKEN.fullmatch(token):
                    return self.reply(404, {'error': 'invalid_token'})
                if not isinstance(decision, str):
                    return self.reply(400, {'error': 'invalid_decision'})
                return self.reply(*self.server.store.decide(token, decision))
            authorization = self.headers.get('Authorization', '')
            account, device = self.server.store.authenticate(authorization.removeprefix('Bearer '))
            if account is None or not authorization.startswith('Bearer '):
                return self.reply(401, {'error': 'unauthorized'})
            if path == '/v1/pair-code' and self.command == 'POST':
                return self.reply(200, self.server.store.pair_code(account))
            if path in ('/v1/guardian', '/v1/release') and self.command == 'POST':
                return self.guardian(account, path)
            if path == '/v1/extra' and self.command == 'POST':
                try:
                    payload = self.body()
                except ValueError:
                    return self.reply(400, {'error': 'invalid_body'})
                scope, minutes = payload.get('scope'), payload.get('minutes')
                if scope not in EXTRA_SCOPES or not (minutes == 'day' or (type(minutes) is int and minutes in EXTRA_MINUTES)):
                    return self.reply(400, {'error': 'invalid_extra'})
                return self.reply(*self.server.store.start_extra(account, scope, minutes))
            if path == '/v1/rules' and self.command == 'GET':
                return self.reply(200, self.server.store.rules(account))
            if path == '/v1/rules' and self.command == 'POST':
                try:
                    payload = self.body(4096)
                except ValueError:
                    return self.reply(400, {'error': 'invalid_body'})
                base, dry_run = payload.get('baseVersion'), payload.get('dryRun', False)
                if set(payload) - {'rules', 'baseVersion', 'dryRun'} or type(base) is not int or type(dry_run) is not bool:
                    return self.reply(400, {'error': 'invalid_body'})
                return self.reply(*self.server.store.save_rules(account, payload.get('rules'), base, dry_run, device))
            if path == '/v1/rules-cancel' and self.command == 'POST':
                return self.reply(200, self.server.store.cancel_rules(account))
            if path == '/v1/video-work' and self.command == 'POST':
                try:
                    payload = self.body(4096)
                except ValueError:
                    return self.reply(400, {'error': 'invalid_body'})
                minutes, purpose, oath = payload.get('minutes'), payload.get('purpose'), payload.get('oath')
                clean = lambda text: ' '.join(''.join(c if c.isprintable() else ' ' for c in text).split())
                if type(minutes) is not int or minutes not in WORK_OPTIONS or not isinstance(purpose, str) or not isinstance(oath, str):
                    return self.reply(400, {'error': 'invalid_work'})
                purpose, oath = clean(purpose), clean(oath)
                if not 3 <= len(purpose) <= 120 or len(oath) > 300:
                    return self.reply(400, {'error': 'invalid_work'})
                return self.reply(*self.server.store.start_work(account, device, minutes, purpose, oath))
            if path == '/v1/video-work-end' and self.command == 'POST':
                return self.reply(*self.server.store.end_work(account))
            if path == '/v1/extra-end' and self.command == 'POST':
                try:
                    reason = self.body(2048).get('reason') or ''
                except ValueError:
                    return self.reply(400, {'error': 'invalid_body'})
                if not isinstance(reason, str) or len(reason) > 200:
                    return self.reply(400, {'error': 'invalid_reason'})
                reason = ''.join(c if c.isprintable() else ' ' for c in reason).strip()
                return self.reply(*self.server.store.end_extra(account, reason))
            operation = {'/v1/status': 'status', '/v1/enter': 'enter', '/v1/complete': 'complete', '/v1/emergency': 'emergency',
                         '/v1/video-status': 'video_status', '/v1/video-enter': 'video_enter',
                         '/v1/video-tick': 'video_tick', '/v1/video-stop': 'video_stop',
                         '/v1/video-emergency': 'video_emergency'}.get(path)
            if operation is None or self.command != ('GET' if operation in ('status', 'video_status') else 'POST'):
                return self.reply(404, {'error': 'not_found'})
            attempt = ''
            if self.command == 'POST':
                try:
                    attempt = self.body().get('attempt', '')
                except ValueError:
                    return self.reply(400, {'error': 'invalid_body'})
                if not isinstance(attempt, str) or not 16 <= len(attempt) <= 80 or not all(c.isalnum() or c == '-' for c in attempt):
                    return self.reply(400, {'error': 'invalid_attempt'})
            result = self.server.store.request(account, device, operation, attempt)
            if operation == 'status':
                result['guard'] = self.server.store.guard(account)
            return self.reply(200, result)
        except sqlite3.Error:
            return self.reply(503, {'error': 'storage_unavailable'})
    def guardian(self, account, path):
        try:
            # A 200-character Turkish reason can exceed 512 bytes once JSON-escaped.
            payload = self.body(2048)
        except ValueError:
            return self.reply(400, {'error': 'invalid_body'})
        if path == '/v1/guardian':
            email = payload.get('email')
            email = email.strip().lower() if isinstance(email, str) else ''
            if len(email) > 254 or not EMAIL.fullmatch(email):
                return self.reply(400, {'error': 'invalid_email'})
            return self.reply(*self.server.store.set_guardian(account, email))
        reason = payload.get('reason', '')
        if reason is None:
            reason = ''
        if not isinstance(reason, str) or len(reason) > 200:
            return self.reply(400, {'error': 'invalid_reason'})
        reason = ''.join(c if c.isprintable() else ' ' for c in reason).strip()
        return self.reply(*self.server.store.release(account, reason))
    def join(self):
        try:
            payload = self.body()
        except ValueError:
            return self.reply(400, {'error': 'invalid_body'})
        kind, install = payload.get('kind'), payload.get('install')
        if kind not in ('android', 'chrome'):
            return self.reply(400, {'error': 'invalid_kind'})
        if install is not None and (kind != 'android' or not isinstance(install, str) or not re.fullmatch('[0-9a-f]{64}', install)):
            return self.reply(400, {'error': 'invalid_install'})
        if self.path == '/v1/register':
            return self.reply(200, self.server.store.register(kind, install))
        code = payload.get('code')
        code = re.sub('[^A-Z]', '', code.upper()) if isinstance(code, str) else ''
        result = self.server.store.pair(code, kind, install)
        if not result:
            return self.reply(403, {'error': 'invalid_code'})
        return self.reply(403, result) if 'error' in result else self.reply(200, result)
    def do_GET(self):
        self.handle_request()
    def do_POST(self):
        self.handle_request()

if __name__ == '__main__':
    store = Store(os.environ.get('BIRNEFES_DB', '/var/lib/bir-nefes/usage.sqlite3'),
                  os.environ.get('BIRNEFES_DEVICES', '/etc/bir-nefes/devices.json'))
    server = ThreadingHTTPServer(('127.0.0.1', int(os.environ.get('BIRNEFES_PORT', '8765'))), Handler)
    store.mail = Mailer(store)
    def report():
        while True:
            try:
                store.work_reports()
            except sqlite3.Error as error:
                print('work report failed:', type(error).__name__, flush=True)
            time.sleep(60)
    threading.Thread(target=report, daemon=True).start()
    server.store = store
    server.serve_forever()
