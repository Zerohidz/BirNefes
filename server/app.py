#!/usr/bin/env python3
"""Single-account quota authority. Bind only to loopback behind HTTPS nginx."""
import hashlib
import hmac
import json
import os
import sqlite3
import time
from contextlib import contextmanager
from datetime import datetime, timedelta, time as daytime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

ZONE = ZoneInfo('Europe/Istanbul')
WAIT = 30_000
DAY_WINDOW = 900_000
NIGHT_WINDOW = 300_000
EMERGENCY_WINDOW = 900_000
VIDEO_BUDGET = 900_000
VIDEO_LEASE = 5_000

def bounds(now):
    local = datetime.fromtimestamp(now / 1000, ZONE)
    date = local.date() - (timedelta(days=1) if local.time() < daytime(3) else timedelta())
    cutoff = int(datetime.combine(date, daytime(22, 30), ZONE).timestamp() * 1000)
    renewal = int(datetime.combine(date + timedelta(days=1), daytime(3), ZONE).timestamp() * 1000)
    return str(date), cutoff, renewal

class Store:
    def __init__(self, path):
        self.path = path
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS quota (
                    id INTEGER PRIMARY KEY CHECK(id=1), day TEXT NOT NULL,
                    used INTEGER NOT NULL, night INTEGER NOT NULL, end INTEGER NOT NULL,
                    session INTEGER NOT NULL);
                INSERT OR IGNORE INTO quota(id,day,used,night,end,session) VALUES(1,'',0,0,0,0);
                CREATE TABLE IF NOT EXISTS attempts (
                    device TEXT PRIMARY KEY, attempt TEXT NOT NULL,
                    ready INTEGER NOT NULL, expires INTEGER NOT NULL, consumed INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS video_quota (
                    id INTEGER PRIMARY KEY CHECK(id=1), day TEXT NOT NULL,
                    spent INTEGER NOT NULL, accounted INTEGER NOT NULL);
                INSERT OR IGNORE INTO video_quota VALUES(1,'',0,0);
                CREATE TABLE IF NOT EXISTS video_clients (
                    device TEXT NOT NULL, attempt TEXT NOT NULL, end INTEGER NOT NULL,
                    PRIMARY KEY(device,attempt));
                CREATE TABLE IF NOT EXISTS video_closed (
                    device TEXT NOT NULL, attempt TEXT NOT NULL, day TEXT NOT NULL,
                    PRIMARY KEY(device,attempt));
                CREATE TABLE IF NOT EXISTS emergency (
                    id INTEGER PRIMARY KEY CHECK(id=1), month TEXT NOT NULL, used INTEGER NOT NULL);
                INSERT OR IGNORE INTO emergency VALUES(1,'',0);
                CREATE TABLE IF NOT EXISTS emergency_requests (
                    device TEXT NOT NULL, attempt TEXT NOT NULL, PRIMARY KEY(device,attempt));
            ''')
            if 'kind' not in {row['name'] for row in db.execute('PRAGMA table_info(quota)')}:
                db.execute("ALTER TABLE quota ADD COLUMN kind TEXT NOT NULL DEFAULT 'normal'")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        finally:
            db.close()

    def request(self, device, operation, attempt='', now=None):
        now = int(time.time() * 1000) if now is None else now
        if operation.startswith('video_'):
            return self.video_request(device, operation, attempt, now)
        day, cutoff, renewal = bounds(now)
        late = now >= cutoff
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            q = dict(db.execute('SELECT * FROM quota WHERE id=1').fetchone())
            if q['day'] != day:
                q.update(day=day, used=0, night=0, end=q['end'] if q['kind'] == 'emergency' else 0)
            if late and q['kind'] == 'normal' and q['end'] > cutoff and not q['night']:
                q['night'] = 1
                q['end'] = min(q['end'], cutoff + NIGHT_WINDOW)
            allowed = q['end'] > now
            available = q['used'] < 5 and (not late or not q['night'])
            state = 'allowed' if allowed else 'idle' if available else 'exhausted'
            month = day[:7]
            emergency = dict(db.execute('SELECT * FROM emergency WHERE id=1').fetchone())
            if emergency['month'] != month:
                emergency.update(month=month, used=0)
            ready = 0
            if operation in ('emergency', 'emergency_video') and (not allowed or (operation == 'emergency_video' and q['kind'] != 'emergency')):
                seen = db.execute('SELECT 1 FROM emergency_requests WHERE device=? AND attempt=?',
                                  (device, attempt)).fetchone()
                if seen:
                    state = 'expired'
                elif emergency['used'] >= 4:
                    state = 'emergency_exhausted'
                else:
                    emergency['used'] += 1
                    q.update(end=now + EMERGENCY_WINDOW, session=q['session'] + 1, kind='emergency')
                    db.execute('INSERT INTO emergency_requests VALUES(?,?)', (device, attempt))
                    state = 'allowed'
            if operation in ('enter', 'complete') and state == 'idle':
                row = db.execute('SELECT * FROM attempts WHERE device=?', (device,)).fetchone()
                if operation == 'enter':
                    if row and row['attempt'] == attempt:
                        if row['consumed'] or row['expires'] <= now:
                            state = 'expired'
                        else:
                            ready, state = row['ready'], 'waiting'
                    else:
                        ready, state = now + WAIT, 'waiting'
                        db.execute('INSERT OR REPLACE INTO attempts VALUES(?,?,?,?,0)',
                                   (device, attempt, ready, now + 120_000))
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
                    db.execute('UPDATE attempts SET consumed=1 WHERE device=?', (device,))
                    state = 'allowed'
            db.execute('UPDATE quota SET day=:day,used=:used,night=:night,end=:end,session=:session,kind=:kind WHERE id=1', q)
            db.execute('UPDATE emergency SET month=:month,used=:used WHERE id=1', emergency)
            return dict(state=state, serverNow=now, readyAt=ready, expiresAt=q['end'],
                        session=q['session'], used=q['used'],
                        remaining=0 if late and q['night'] else min(5-q['used'], 1 if late else 5),
                        renewalAt=renewal, budgetDay=day, sessionKind=q['kind'],
                        emergencyRemaining=4-emergency['used'], emergencyMonth=month)

    def video_request(self, device, operation, attempt, now):
        # Emergency allocation stays under the same atomic monthly ledger.
        if operation == 'video_emergency':
            self.request(device, 'emergency_video', attempt, now)
        day, cutoff, renewal = bounds(now)
        date = datetime.fromisoformat(day).date()
        stamp = lambda hour: int(datetime.combine(date, daytime(hour), ZONE).timestamp()*1000)
        five, eight, seventeen = stamp(5), stamp(8), stamp(17)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            q = dict(db.execute('SELECT * FROM video_quota WHERE id=1').fetchone())
            if q['day'] != day:
                q.update(day=day, spent=0, accounted=now)
                db.execute('DELETE FROM video_clients')
                db.execute('DELETE FROM video_closed WHERE day<>?', (day,))
            lease_end = db.execute('SELECT COALESCE(MAX(end),0) FROM video_clients').fetchone()[0]
            # Account the union of active leases: multiple tabs do not multiply
            # consumption. No client clock/duration is accepted as authority.
            grant = db.execute('SELECT * FROM quota WHERE id=1').fetchone()
            urgent = grant['kind'] == 'emergency' and grant['end'] > now
            charge_end = min(now, grant['end'] - EMERGENCY_WINDOW) if urgent else now
            elapsed = max(0, min(charge_end, lease_end, seventeen) - max(q['accounted'], eight))
            q['spent'] = min(VIDEO_BUDGET, q['spent'] + elapsed)
            q['accounted'] = now
            db.execute('DELETE FROM video_clients WHERE end<=?', (now,))
            if operation == 'video_stop':
                db.execute('INSERT OR IGNORE INTO video_closed VALUES(?,?,?)', (device, attempt, day))
                db.execute('DELETE FROM video_clients WHERE device=? AND attempt=?', (device, attempt))
            emergency = db.execute('SELECT * FROM emergency WHERE id=1').fetchone()
            emergency_left = 4 - emergency['used'] if emergency['month'] == day[:7] else 4
            closed = db.execute('SELECT 1 FROM video_closed WHERE device=? AND attempt=?', (device, attempt)).fetchone()
            if urgent:
                db.execute('DELETE FROM video_clients')
                state, mode, end = 'allowed', 'emergency', grant['end']
            elif five <= now < eight or seventeen <= now < cutoff:
                state, mode, end = 'allowed', 'free', eight if now < eight else cutoff
            elif eight <= now < seventeen and q['spent'] < VIDEO_BUDGET:
                state, mode = 'allowed', 'metered'
                end = min(now + VIDEO_LEASE, now + VIDEO_BUDGET - q['spent'], seventeen)
                if operation in ('video_enter', 'video_tick') and not closed:
                    db.execute('INSERT OR REPLACE INTO video_clients VALUES(?,?,?)', (device, attempt, end))
            else:
                state, mode, end = 'exhausted', 'night' if now < five or now >= cutoff else 'exhausted', now
            if closed and operation in ('video_enter', 'video_tick'):
                state, end = 'expired', now
            # Switching to a free/emergency window stops this client's metering.
            if mode != 'metered':
                db.execute('DELETE FROM video_clients WHERE device=? AND attempt=?', (device, attempt))
            db.execute('UPDATE video_quota SET day=:day,spent=:spent,accounted=:accounted WHERE id=1', q)
            return dict(state=state, mode=mode, serverNow=now, expiresAt=end,
                        session=grant['session'] if urgent else 0, sessionKind=mode,
                        videoRemainingMs=VIDEO_BUDGET-q['spent'], emergencyRemaining=emergency_left,
                        budgetDay=day, renewalAt=renewal, readyAt=0, remaining=0,
                        nextChangeAt=(five if now < five else eight if now < eight else seventeen if now < seventeen else cutoff if now < cutoff else renewal+2*3900_000))

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
    def handle_request(self):
        if self.path == '/health' and self.command == 'GET':
            return self.reply(200, {'status': 'ok', 'version': '1.3.1'})
        authorization = self.headers.get('Authorization', '')
        hashed = hashlib.sha256(authorization.removeprefix('Bearer ').encode()).hexdigest()
        device = next((d['id'] for d in self.server.devices
                       if authorization.startswith('Bearer ') and hmac.compare_digest(d['token_hash'], hashed)), None)
        if device is None:
            return self.reply(401, {'error': 'unauthorized'})
        operation = {'/v1/status': 'status', '/v1/enter': 'enter', '/v1/complete': 'complete', '/v1/emergency': 'emergency',
                     '/v1/video-status': 'video_status', '/v1/video-enter': 'video_enter',
                     '/v1/video-tick': 'video_tick', '/v1/video-stop': 'video_stop',
                     '/v1/video-emergency': 'video_emergency'}.get(self.path)
        if operation is None or self.command != ('GET' if operation in ('status', 'video_status') else 'POST'):
            return self.reply(404, {'error': 'not_found'})
        attempt = ''
        if self.command == 'POST':
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 512:
                    return self.reply(400, {'error': 'invalid_body'})
                payload = json.loads(self.rfile.read(length))
                attempt = payload.get('attempt', '')
                if not isinstance(attempt, str) or not 16 <= len(attempt) <= 80 or not all(c.isalnum() or c == '-' for c in attempt):
                    return self.reply(400, {'error': 'invalid_attempt'})
            except (ValueError, AttributeError):
                return self.reply(400, {'error': 'invalid_body'})
        try:
            return self.reply(200, self.server.store.request(device, operation, attempt))
        except sqlite3.Error:
            return self.reply(503, {'error': 'storage_unavailable'})
    def do_GET(self):
        self.handle_request()
    def do_POST(self):
        self.handle_request()

if __name__ == '__main__':
    store = Store(os.environ.get('BIRNEFES_DB', '/var/lib/bir-nefes/usage.sqlite3'))
    with open(os.environ.get('BIRNEFES_DEVICES', '/etc/bir-nefes/devices.json')) as source:
        devices = json.load(source)
    server = ThreadingHTTPServer(('127.0.0.1', int(os.environ.get('BIRNEFES_PORT', '8765'))), Handler)
    server.store, server.devices = store, devices
    server.serve_forever()
