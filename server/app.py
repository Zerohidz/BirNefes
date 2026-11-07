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
WAIT = 30_000
DAY_WINDOW = 1_200_000
NIGHT_WINDOW = 300_000
EMERGENCY_WINDOW = 1_200_000
VIDEO_BUDGET = 1_200_000
VIDEO_LEASE = 5_000

def bounds(now):
    local = datetime.fromtimestamp(now / 1000, ZONE)
    date = local.date() - (timedelta(days=1) if local.time() < daytime(3) else timedelta())
    cutoff = int(datetime.combine(date, daytime(22, 30), ZONE).timestamp() * 1000)
    renewal = int(datetime.combine(date + timedelta(days=1), daytime(3), ZONE).timestamp() * 1000)
    return str(date), cutoff, renewal

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
        account_id INTEGER PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('verify','change','release')),
        token_hash TEXT NOT NULL UNIQUE, email TEXT, reason TEXT NOT NULL, expires INTEGER NOT NULL)""",
]
ACCOUNT_COLUMNS = {
    'guardian_email': 'TEXT',
    'released_until': 'INTEGER NOT NULL DEFAULT 0',
    'mail_day': "TEXT NOT NULL DEFAULT ''",
    'mail_count': 'INTEGER NOT NULL DEFAULT 0',
    'mail_error': 'INTEGER NOT NULL DEFAULT 0',
}
CODE_LETTERS = 'ACDEFGHJKMNPQRTUVWXY'
CODE_LIFETIME = 600_000
GUARD_LIFETIME = 86_400_000
MAIL_LIMIT = 10
DECISIONS = {'verify': ('confirm',), 'change': ('approve', 'deny'), 'release': ('1h', '24h', 'permanent', 'deny')}
RELEASES = {'1h': 3_600_000, '24h': 86_400_000}
PUBLIC_URL = os.environ.get('BIRNEFES_PUBLIC_URL', 'https://zmanga.zerohidz.com/bir-nefes').rstrip('/')
SUBJECT = 'Bir Nefes: onayın isteniyor'
KIND_TEXT = {'verify': 'Seni onaylayıcı (kardeş) olarak eklemek istiyor.',
             'change': 'Onaylayıcı adresini değiştirmek istiyor.',
             'release': 'Kilidin açılmasına izin vermeni istiyor.'}

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
            for name, declaration in ACCOUNT_COLUMNS.items():
                if name not in columns('accounts'):
                    db.execute(f'ALTER TABLE accounts ADD COLUMN {name} {declaration}')
            for table in legacy:
                kind = 'kind' if 'kind' in columns('old_quota') else "'normal'"
                db.execute(f'INSERT INTO {table} ' + OLD_TABLES[table].format(kind=kind))
                db.execute(f'DROP TABLE old_{table}')
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
            db.execute('DELETE FROM pair_codes WHERE account_id=?', (row[0],))
            return self.attach(db, row[0], kind, install, now)

    def authenticate(self, token):
        with self.connect() as db:
            row = db.execute('SELECT id, account_id FROM devices WHERE token_hash=?', (digest(token),)).fetchone()
        return (row['account_id'], str(row['id'])) if row else (None, None)

    def ensure(self, db, account):
        db.execute("INSERT OR IGNORE INTO quota VALUES(?,'',0,0,0,0,'normal')", (account,))
        db.execute("INSERT OR IGNORE INTO video_quota VALUES(?,'',0,0)", (account,))
        db.execute("INSERT OR IGNORE INTO emergency VALUES(?,'',0)", (account,))

    def request(self, account, device, operation, attempt='', now=None):
        now = int(time.time() * 1000) if now is None else now
        if operation.startswith('video_'):
            return self.video_request(account, device, operation, attempt, now)
        day, cutoff, renewal = bounds(now)
        late = now >= cutoff
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            self.ensure(db, account)
            q = dict(db.execute('SELECT * FROM quota WHERE account_id=?', (account,)).fetchone())
            if q['day'] != day:
                q.update(day=day, used=0, night=0, end=q['end'] if q['kind'] == 'emergency' else 0)
            if late and q['kind'] == 'normal' and q['end'] > cutoff and not q['night']:
                q['night'] = 1
                q['end'] = min(q['end'], cutoff + NIGHT_WINDOW)
            allowed = q['end'] > now
            available = q['used'] < 5 and (not late or not q['night'])
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
                elif emergency['used'] >= 4:
                    state = 'emergency_exhausted'
                else:
                    emergency['used'] += 1
                    q.update(end=now + EMERGENCY_WINDOW, session=q['session'] + 1, kind='emergency')
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
                        ready, state = now + WAIT, 'waiting'
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
                    q['end'] = min(now + (NIGHT_WINDOW if late else DAY_WINDOW), renewal)
                    # Return the final deadline upfront, so offline clients also
                    # obey the 22:30 transition without needing a push message.
                    if not late and q['end'] > cutoff:
                        q['end'] = min(q['end'], cutoff + NIGHT_WINDOW)
                    db.execute('UPDATE attempts SET consumed=1 WHERE account_id=? AND device=?', (account, device))
                    state = 'allowed'
            db.execute('UPDATE quota SET day=:day,used=:used,night=:night,end=:end,session=:session,kind=:kind WHERE account_id=:account_id', q)
            db.execute('UPDATE emergency SET month=:month,used=:used WHERE account_id=:account_id', emergency)
            return dict(state=state, serverNow=now, readyAt=ready, expiresAt=q['end'],
                        session=q['session'], used=q['used'],
                        remaining=0 if late and q['night'] else min(5-q['used'], 1 if late else 5),
                        renewalAt=renewal, budgetDay=day, sessionKind=q['kind'],
                        emergencyRemaining=4-emergency['used'], emergencyMonth=month)

    def video_request(self, account, device, operation, attempt, now):
        # Emergency allocation stays under the same atomic monthly ledger.
        if operation == 'video_emergency':
            self.request(account, device, 'emergency_video', attempt, now)
        day, cutoff, renewal = bounds(now)
        date = datetime.fromisoformat(day).date()
        stamp = lambda hour: int(datetime.combine(date, daytime(hour), ZONE).timestamp()*1000)
        five, eight, seventeen = stamp(5), stamp(8), stamp(17)
        # Saturday and Sunday have no metered hours; the night stays closed.
        weekend = date.weekday() >= 5
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
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
            charge_end = min(now, grant['end'] - EMERGENCY_WINDOW) if urgent else now
            elapsed = max(0, min(charge_end, lease_end, seventeen) - max(q['accounted'], eight))
            q['spent'] = min(VIDEO_BUDGET, q['spent'] + elapsed)
            q['accounted'] = now
            db.execute('DELETE FROM video_clients WHERE account_id=? AND end<=?', (account, now))
            if operation == 'video_stop':
                db.execute('INSERT OR IGNORE INTO video_closed VALUES(?,?,?,?)', (account, device, attempt, day))
                db.execute('DELETE FROM video_clients WHERE account_id=? AND device=? AND attempt=?', (account, device, attempt))
            emergency = db.execute('SELECT * FROM emergency WHERE account_id=?', (account,)).fetchone()
            emergency_left = 4 - emergency['used'] if emergency['month'] == day[:7] else 4
            closed = db.execute('SELECT 1 FROM video_closed WHERE account_id=? AND device=? AND attempt=?', (account, device, attempt)).fetchone()
            if urgent:
                db.execute('DELETE FROM video_clients WHERE account_id=?', (account,))
                state, mode, end = 'allowed', 'emergency', grant['end']
            elif five <= now < eight or seventeen <= now < cutoff or (weekend and five <= now < cutoff):
                state, mode, end = 'allowed', 'free', eight if now < eight and not weekend else cutoff
            elif eight <= now < seventeen and q['spent'] < VIDEO_BUDGET:
                state, mode = 'allowed', 'metered'
                end = min(now + VIDEO_LEASE, now + VIDEO_BUDGET - q['spent'], seventeen)
                if operation in ('video_enter', 'video_tick') and not closed:
                    db.execute('INSERT OR REPLACE INTO video_clients VALUES(?,?,?,?)', (account, device, attempt, end))
            else:
                state, mode, end = 'exhausted', 'night' if now < five or now >= cutoff else 'exhausted', now
            if closed and operation in ('video_enter', 'video_tick'):
                state, end = 'expired', now
            # Switching to a free/emergency window stops this client's metering.
            if mode != 'metered':
                db.execute('DELETE FROM video_clients WHERE account_id=? AND device=? AND attempt=?', (account, device, attempt))
            db.execute('UPDATE video_quota SET day=:day,spent=:spent,accounted=:accounted WHERE account_id=:account_id', q)
            return dict(state=state, mode=mode, serverNow=now, expiresAt=end,
                        session=grant['session'] if urgent else 0, sessionKind=mode,
                        videoRemainingMs=VIDEO_BUDGET-q['spent'], emergencyRemaining=emergency_left,
                        budgetDay=day, renewalAt=renewal, readyAt=0, remaining=0,
                        nextChangeAt=(five if now < five else cutoff if weekend and now < cutoff else eight if now < eight else seventeen if now < seventeen else cutoff if now < cutoff else renewal+2*3900_000))

    def guard_view(self, db, account, now):
        a = db.execute('SELECT guardian_email, released_until, mail_error FROM accounts WHERE id=?', (account,)).fetchone()
        r = db.execute('SELECT kind FROM guard_requests WHERE account_id=? AND expires>?', (account, now)).fetchone()
        kind = r['kind'] if r else None
        guardian = a['guardian_email'] if a else None
        released = a['released_until'] if guardian and a['released_until'] > now else 0
        return dict(guardian=mask(guardian), pendingGuardian=kind in ('verify', 'change'),
                    locked=bool(guardian) and not released, releasedUntil=released,
                    pendingRelease=kind == 'release', mailError=bool(a and a['mail_error']))

    def guard(self, account, now=None):
        now = int(time.time() * 1000) if now is None else now
        with self.connect() as db:
            return self.guard_view(db, account, now)

    def issue(self, db, account, kind, email, reason, to, now, limited=True):
        # Replaces any pending request and returns the mail to send after commit.
        day = bounds(now)[0]
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
    def __call__(self, account, to, body):
        self.queue.put((account, to, body))
    def deliver(self, to, body):
        # Read on every send, so smtp.json can be added without a restart.
        with open(self.path) as source:
            config = json.load(source)
        message = EmailMessage()
        message['Subject'] = SUBJECT
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
            account, to, body = self.queue.get()
            try:
                self.deliver(to, body)
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
  release: 'Kullanıcı kilidin açılmasını istiyor.'
};
const LABEL = {confirm: 'Onaylıyorum', approve: 'İzin ver', deny: 'Reddet',
  '1h': '1 saatliğine aç', '24h': '24 saatliğine aç', permanent: 'Kilidi kalıcı kaldır (onaylayıcılıktan çık)'};
function done(r) {
  buttons.textContent = '';
  if (r.decision === 'deny') result.textContent = 'İstek reddedildi.';
  else if (r.decision === 'confirm') result.textContent = 'Onaylayıcı oldun. Kilit başladı.';
  else if (r.decision === 'approve') result.textContent = 'Değişikliğe izin verildi. Yeni adrese doğrulama maili gönderildi.';
  else if (r.decision === 'permanent') result.textContent = 'Kilit kalıcı olarak kaldırıldı. Artık onaylayıcı değilsin.';
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
        return self.reply(200, result) if result else self.reply(403, {'error': 'invalid_code'})
    def do_GET(self):
        self.handle_request()
    def do_POST(self):
        self.handle_request()

if __name__ == '__main__':
    store = Store(os.environ.get('BIRNEFES_DB', '/var/lib/bir-nefes/usage.sqlite3'),
                  os.environ.get('BIRNEFES_DEVICES', '/etc/bir-nefes/devices.json'))
    server = ThreadingHTTPServer(('127.0.0.1', int(os.environ.get('BIRNEFES_PORT', '8765'))), Handler)
    store.mail = Mailer(store)
    server.store = store
    server.serve_forever()
