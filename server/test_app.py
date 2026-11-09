import concurrent.futures
import json
import threading
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
import tempfile
import sqlite3
import re
import unittest
from unittest import mock
from datetime import datetime
import app
from app import Store, Handler, Mailer, ZONE, digest

def at(value):
    return int(datetime.fromisoformat(value).replace(tzinfo=ZONE).timestamp()*1000)

class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(self.temp.name+'/usage.db')
        self.t = at('2026-10-01T10:00:00')
    def tearDown(self):
        self.temp.cleanup()
    def start(self, device, t, attempt='attempt-0000000001'):
        wait = self.store.request(1,device,'enter',attempt,t)
        self.assertEqual(wait['state'],'waiting')
        early = self.store.request(1,device,'complete',attempt,t+29999)
        self.assertEqual(early['state'],'waiting')
        return self.store.request(1,device,'complete',attempt,t+30000)
    def test_shared_window_and_no_extension(self):
        first = self.start('phone',self.t)
        second = self.store.request(1,'chrome','enter','attempt-0000000002',self.t+180000)
        self.assertEqual(first['expiresAt'],second['expiresAt'])
        self.assertEqual(second['used'],1)
        self.assertEqual(second['state'],'allowed')
    def test_atomic_simultaneous_completion(self):
        for d in ('phone','chrome'):
            self.store.request(1,d,'enter','attempt-0000000001',self.t)
        with concurrent.futures.ThreadPoolExecutor() as pool:
            results = list(pool.map(lambda d:self.store.request(1,d,'complete','attempt-0000000001',self.t+30000),('phone','chrome')))
        self.assertEqual([r['used'] for r in results],[1,1])
        self.assertEqual(results[0]['session'],results[1]['session'])
    def test_five_uses_and_replay(self):
        for n in range(5):
            r=self.start('phone',self.t+n*1231000,f'attempt-00000000{n:02}')
            self.assertEqual(r['used'],n+1)
        r=self.store.request(1,'chrome','enter','attempt-0000000009',r['expiresAt'])
        self.assertEqual(r['state'],'exhausted')
    def test_replay_does_not_allocate(self):
        r=self.start('phone',self.t)
        r=self.store.request(1,'phone','complete','attempt-0000000001',r['expiresAt'])
        self.assertEqual(r['state'],'expired')
        self.assertEqual(r['used'],1)
    def test_late_window_is_full_five_minutes(self):
        t=at('2026-10-01T23:58:00')
        r=self.start('phone',t)
        self.assertEqual(r['expiresAt'],t+330000)
        r=self.store.request(1,'chrome','status',now=t+180000)
        self.assertEqual(r['state'],'allowed')
        r=self.store.request(1,'chrome','enter','attempt-0000000002',t+330000)
        self.assertEqual(r['state'],'exhausted')
    def test_cutoff_crossing_and_absence(self):
        r=self.start('phone',at('2026-10-01T22:27:00'))
        self.assertEqual(r['expiresAt'],at('2026-10-01T22:35:00'))
        r=self.store.request(1,'chrome','enter','attempt-0000000002',at('2026-10-02T00:01:00'))
        self.assertEqual(r['state'],'exhausted')
    def test_renewal_0300_and_restart(self):
        self.start('phone',at('2026-10-01T23:00:00'))
        self.store=Store(self.store.path)
        r=self.store.request(1,'chrome','status',now=at('2026-10-02T02:59:59'))
        self.assertEqual(r['state'],'exhausted')
        r=self.store.request(1,'chrome','status',now=at('2026-10-02T03:00:00'))
        self.assertEqual((r['state'],r['remaining'],r['used']),('idle',5,0))
    def test_active_cap_renewal(self):
        r=self.start('phone',at('2026-10-02T02:58:00'))
        self.assertEqual(r['expiresAt'],at('2026-10-02T03:00:00'))
    def test_no_challenge_no_allocation(self):
        r=self.store.request(1,'phone','complete','attempt-0000000001',self.t)
        self.assertEqual((r['state'],r['used']),('expired',0))
    def test_other_device_cannot_complete_challenge(self):
        self.store.request(1,'phone','enter','attempt-0000000001',self.t)
        r=self.store.request(1,'chrome','complete','attempt-0000000001',self.t+30000)
        self.assertEqual(r['state'],'expired')

    def test_emergency_bypasses_wait_without_daily_use(self):
        self.store.request(1,'phone','enter','normal-0000000001',self.t)
        r=self.store.request(1,'phone','emergency','urgent-0000000001',self.t+1000)
        self.assertEqual((r['state'],r['used'],r['emergencyRemaining'],r['sessionKind']),('allowed',0,3,'emergency'))
        self.assertEqual(r['expiresAt'],self.t+1201000)
        other=self.store.request(1,'chrome','enter','normal-0000000002',self.t+5000)
        self.assertEqual((r['expiresAt'],r['session']),(other['expiresAt'],other['session']))

    def test_four_emergencies_shared_and_replay_safe(self):
        for n in range(4):
            r=self.store.request(1,'phone' if n%2 else 'chrome','emergency',f'urgent-00000000{n:02}',self.t+n*1200001)
            self.assertEqual(r['emergencyRemaining'],3-n)
        r=self.store.request(1,'phone','emergency','urgent-0000000099',r['expiresAt'])
        self.assertEqual((r['state'],r['used']),('emergency_exhausted',0))
        r=self.store.request(1,'chrome','emergency','urgent-0000000000',self.t+5000000)
        self.assertEqual(r['state'],'expired')

    def test_emergency_idempotent_after_restart(self):
        first=self.store.request(1,'phone','emergency','urgent-0000000001',self.t)
        self.store=Store(self.store.path)
        second=self.store.request(1,'phone','emergency','urgent-0000000001',self.t+500)
        self.assertEqual(first['expiresAt'],second['expiresAt'])
        self.assertEqual(second['emergencyRemaining'],3)
        expired=self.store.request(1,'phone','emergency','urgent-0000000001',first['expiresAt'])
        self.assertEqual((expired['state'],expired['emergencyRemaining']),('expired',3))

    def test_simultaneous_emergency_allocates_once(self):
        with concurrent.futures.ThreadPoolExecutor() as pool:
            results=list(pool.map(lambda d:self.store.request(1,d,'emergency','urgent-0000000001',self.t),('phone','chrome')))
        self.assertEqual([r['emergencyRemaining'] for r in results],[3,3])
        self.assertEqual(results[0]['session'],results[1]['session'])

    def test_emergency_during_normal_does_not_charge_or_extend(self):
        first=self.start('phone',self.t)
        second=self.store.request(1,'chrome','emergency','urgent-0000000001',self.t+60000)
        self.assertEqual(second['emergencyRemaining'],4)
        self.assertEqual(first['expiresAt'],second['expiresAt'])

    def test_night_emergency_full_duration_and_daily_unchanged(self):
        t=at('2026-10-01T22:29:00')
        first=self.store.request(1,'phone','emergency','urgent-0000000001',t)
        r=self.store.request(1,'phone','status',now=t+360000)
        self.assertEqual((r['state'],r['remaining'],r['used']),('allowed',1,0))
        self.assertEqual(r['expiresAt'],t+1200000)
        daily=self.start('phone',first['expiresAt'])
        self.assertEqual((daily['used'],daily['remaining'],daily['emergencyRemaining']),(1,0,3))
        urgent=self.store.request(1,'phone','emergency','urgent-0000000002',daily['expiresAt'])
        self.assertEqual((urgent['state'],urgent['used'],urgent['remaining']),('allowed',1,0))

    def test_month_resets_at_0300_not_midnight_and_active_survives(self):
        t=at('2026-10-31T23:59:00')
        self.store.request(1,'phone','emergency','urgent-0000000001',t)
        r=self.store.request(1,'phone','status',now=at('2026-11-01T00:01:00'))
        self.assertEqual((r['emergencyRemaining'],r['emergencyMonth']),(3,'2026-10'))
        first=self.store.request(1,'phone','emergency','urgent-0000000002',at('2026-11-01T02:58:00'))
        r=self.store.request(1,'chrome','status',now=at('2026-11-01T03:00:00'))
        self.assertEqual((r['emergencyRemaining'],r['emergencyMonth'],r['remaining']),(4,'2026-11',5))
        self.assertEqual(r['expiresAt'],first['expiresAt'])
        self.assertEqual(r['state'],'allowed')

    def test_upgrade_preserves_legacy_quota(self):
        path=self.temp.name+'/legacy.db'
        with sqlite3.connect(path) as db:
            db.executescript("CREATE TABLE quota(id INTEGER PRIMARY KEY,day TEXT,used INTEGER,night INTEGER,end INTEGER,session INTEGER); INSERT INTO quota VALUES(1,'2026-10-01',3,0,0,9);")
        upgraded=Store(path)
        r=upgraded.request(1,'phone','status',now=self.t)
        self.assertEqual((r['used'],r['remaining'],r['session'],r['emergencyRemaining']),(3,2,9,4))
        upgraded=Store(path)
        self.assertEqual(upgraded.request(1,'phone','status',now=self.t)['used'],3)

    def test_extra_all_blocks_rights_urgent_and_open_window(self):
        t=at('2026-10-01T10:00:00')
        self.store.request(1,'phone','enter','attempt-000000000001',t)
        r=self.store.request(1,'phone','complete','attempt-000000000001',t+30_000)
        self.assertEqual(r['state'],'allowed')
        code,view=self.store.start_extra(1,'all',60,t+60_000)
        self.assertEqual((code,view['extraAllUntil']),(200,t+60_000+3_600_000))
        r=self.store.request(1,'phone','status',now=t+61_000)
        self.assertEqual((r['state'],r['extraAllUntil']),('exhausted',t+60_000+3_600_000))
        r=self.store.request(1,'phone','enter','attempt-000000000002',t+62_000)
        self.assertEqual(r['state'],'exhausted')
        r=self.store.request(1,'chrome','video_enter','video-0000000000a1',t+62_500)
        self.assertEqual((r['state'],r['mode']),('exhausted','limited'))
        # The urgent pass still works and opens phone and video for its 20 minutes.
        r=self.store.request(1,'phone','emergency','attempt-000000000003',t+63_000)
        self.assertEqual((r['state'],r['emergencyRemaining'],r['expiresAt']),('allowed',3,t+63_000+1_200_000))
        r=self.store.request(1,'chrome','video_enter','video-0000000000a1',t+64_000)
        self.assertEqual((r['state'],r['mode']),('allowed','emergency'))
        self.assertEqual(self.store.request(1,'phone','status',now=t+63_000+1_200_001)['state'],'exhausted')
        # A shorter request never shortens it; after it ends the normal rules return.
        self.assertEqual(self.store.start_extra(1,'all',30,t+65_000)[1]['extraAllUntil'],t+60_000+3_600_000)
        r=self.store.request(1,'phone','enter','attempt-000000000004',t+60_000+3_600_001)
        self.assertEqual((r['state'],r['used']),('waiting',1))

    def test_extra_video_blocks_only_video_until_the_day_ends(self):
        t=at('2026-10-03T12:00:00')
        code,view=self.store.start_extra(1,'video','day',t)
        self.assertEqual(view['extraVideoUntil'],at('2026-10-04T03:00:00'))
        r=self.store.request(1,'chrome','video_enter','video-0000000000b1',t+1000)
        self.assertEqual((r['state'],r['mode'],r['nextChangeAt']),('exhausted','limited',at('2026-10-04T03:00:00')))
        self.assertEqual(self.store.request(1,'phone','enter','attempt-0000000000b3',t+1500)['state'],'waiting')
        r=self.store.request(1,'chrome','video_emergency','video-0000000000b2',t+2000)
        self.assertEqual((r['state'],r['mode'],r['emergencyRemaining']),('allowed','emergency',3))

    def test_extra_ends_only_with_guardian_decision(self):
        mails=[]; self.store.mail=lambda *m: mails.append(m)
        t=at('2026-10-01T10:00:00')
        self.store.start_extra(1,'video',120,t)
        self.assertEqual(self.store.end_extra(1,'',t+1000),(409,{'error':'no_guardian'}))
        self.store.set_guardian(1,'k@example.com',t)
        token=re.search(r'approve#(\S+)',mails[-1][2]).group(1)
        self.store.decide(token,'confirm',t+2000)
        code,view=self.store.end_extra(1,'bitti',t+3000)
        self.assertEqual((code,view['extraEndPending']),(200,True))
        token=re.search(r'approve#(\S+)',mails[-1][2]).group(1)
        self.assertEqual(self.store.request_info(token,t+4000)['decisions'],['end','deny'])
        self.store.decide(token,'end',t+5000)
        r=self.store.request(1,'chrome','video_enter','video-0000000000c1',t+6000)
        self.assertEqual((r['mode'],r['extraVideoUntil']),('metered',0))

    def test_video_weekend_is_free_until_the_night(self):
        for moment, expected, mode in [('2026-10-02T12:00:00','allowed','metered'),('2026-10-03T02:00:00','exhausted','night'),('2026-10-03T04:59:59','exhausted','night'),('2026-10-03T05:00:00','allowed','free'),('2026-10-03T08:00:00','allowed','free'),('2026-10-03T12:00:00','allowed','free'),('2026-10-03T22:29:59','allowed','free'),('2026-10-03T22:30:00','exhausted','night'),('2026-10-04T02:00:00','exhausted','night'),('2026-10-04T12:00:00','allowed','free'),('2026-10-04T23:00:00','exhausted','night'),('2026-10-05T02:00:00','exhausted','night'),('2026-10-05T12:00:00','allowed','metered')]:
            r=self.store.request(1,'chrome','video_enter','video-0000000001',at(moment))
            self.assertEqual((r['state'],r['mode']),(expected,mode),moment)
        r=self.store.request(1,'chrome','video_enter','video-0000000001',at('2026-10-03T06:00:00'))
        self.assertEqual((r['expiresAt'],r['nextChangeAt']),(at('2026-10-03T22:30:00'),at('2026-10-03T22:30:00')))
        r=self.store.request(1,'chrome','video_tick','video-0000000001',at('2026-10-03T12:00:05'))
        self.assertEqual(r['videoRemainingMs'],600_000)

    def test_video_free_windows_and_night(self):
        for hour, expected, mode in [('04:59:59','exhausted','night'),('05:00:00','allowed','free'),('07:59:59','allowed','free'),('08:00:00','allowed','metered'),('16:59:59','allowed','metered'),('17:00:00','allowed','free'),('22:29:59','allowed','free'),('22:30:00','exhausted','night')]:
            r=self.store.request(1,'chrome','video_enter','video-0000000001',at('2026-10-01T'+hour))
            self.assertEqual((r['state'],r['mode']),(expected,mode),hour)
        daily=self.store.request(1,'phone','status',now=at('2026-10-01T22:30:01'))
        self.assertEqual(daily['used'],0)

    def test_video_three_second_visits_accumulate(self):
        for n in range(3):
            t=self.t+n*60000
            key=f'video-00000000{n:02}'
            self.store.request(1,'chrome','video_enter',key,t)
            r=self.store.request(1,'chrome','video_stop',key,t+3000)
            self.assertEqual(r['videoRemainingMs'],600000-(n+1)*3000)
        r=self.store.request(1,'chrome','video_status',now=self.t+300000)
        self.assertEqual(r['videoRemainingMs'],591000)
        self.assertEqual(self.store.request(1,'phone','status',now=self.t+300000)['used'],0)

    def test_video_twenty_minutes_then_free_at_17(self):
        self.store.request(1,'chrome','video_enter','video-0000000001',self.t)
        for n in range(1,601):
            r=self.store.request(1,'chrome','video_tick','video-0000000001',self.t+n*2000)
        self.assertEqual((r['state'],r['videoRemainingMs']),('exhausted',0))
        self.assertEqual(r['expiresAt'],self.t+1200000)
        r=self.store.request(1,'chrome','video_enter','video-0000000002',at('2026-10-01T17:00:00'))
        self.assertEqual((r['state'],r['mode']),('allowed','free'))

    def test_video_lost_stop_only_charges_last_lease(self):
        self.store.request(1,'chrome','video_enter','video-0000000001',self.t)
        r=self.store.request(1,'chrome','video_status',now=self.t+120000)
        self.assertEqual(r['videoRemainingMs'],595000)
        again=self.store.request(1,'chrome','video_status',now=self.t+240000)
        self.assertEqual(again['videoRemainingMs'],595000)

    def test_video_two_tabs_charge_union_and_stop_independently(self):
        self.store.request(1,'chrome','video_enter','video-0000000001',self.t)
        self.store.request(1,'chrome','video_enter','video-0000000002',self.t+1000)
        self.store.request(1,'chrome','video_stop','video-0000000001',self.t+3000)
        r=self.store.request(1,'chrome','video_stop','video-0000000002',self.t+4000)
        self.assertEqual(r['videoRemainingMs'],596000)
        replay=self.store.request(1,'chrome','video_tick','video-0000000001',self.t+5000)
        self.assertEqual(replay['state'],'expired')
        self.assertEqual(self.store.request(1,'chrome','video_status',now=self.t+20000)['videoRemainingMs'],596000)

    def test_video_lease_capped_at_17_and_small_remaining(self):
        t=at('2026-10-01T16:59:58')
        r=self.store.request(1,'chrome','video_enter','video-0000000001',t)
        self.assertEqual(r['expiresAt'],at('2026-10-01T17:00:00'))
        r=self.store.request(1,'chrome','video_status',now=t+10000)
        self.assertEqual(r['videoRemainingMs'],598000)
        self.store.request(1,'chrome','video_status',now=at('2026-10-02T10:00:00'))
        with self.store.connect() as db: db.execute('UPDATE video_quota SET spent=599001')
        r=self.store.request(1,'chrome','video_enter','video-0000000002',at('2026-10-02T10:00:00'))
        self.assertEqual(r['expiresAt']-r['serverNow'],999)

    def test_video_emergency_uses_shared_monthly_not_daily(self):
        t=at('2026-10-01T23:00:00')
        r=self.store.request(1,'chrome','video_emergency','urgent-0000000001',t)
        self.assertEqual((r['state'],r['mode'],r['emergencyRemaining']),('allowed','emergency',3))
        phone=self.store.request(1,'phone','enter','phone-0000000001',t+1000)
        self.assertEqual((phone['expiresAt'],phone['used']),(r['expiresAt'],0))
        r=self.store.request(1,'chrome','video_enter','video-0000000001',t+1200000)
        self.assertEqual((r['state'],r['mode']),('exhausted','night'))

    def test_normal_grant_does_not_unlock_video_night(self):
        t=at('2026-10-01T23:00:00')
        self.start('phone',t)
        r=self.store.request(1,'chrome','video_enter','video-0000000001',t+60000)
        self.assertEqual(r['state'],'exhausted')
        r=self.store.request(1,'chrome','video_emergency','urgent-0000000001',t+61000)
        self.assertEqual((r['state'],r['mode'],r['emergencyRemaining']),('allowed','emergency',3))

    def test_emergency_stops_video_metering_across_clients(self):
        self.store.request(1,'chrome','video_enter','video-0000000001',self.t)
        self.store.request(1,'phone','emergency','urgent-0000000001',self.t+1000)
        r=self.store.request(1,'chrome','video_tick','video-0000000001',self.t+3000)
        self.assertEqual((r['mode'],r['videoRemainingMs']),('emergency',599000))
        r=self.store.request(1,'chrome','video_tick','video-0000000001',self.t+6000)
        self.assertEqual(r['videoRemainingMs'],599000)

    def test_video_renewal_03_and_restart(self):
        self.store.request(1,'chrome','video_enter','video-0000000001',self.t)
        self.store.request(1,'chrome','video_stop','video-0000000001',self.t+3000)
        self.store=Store(self.store.path)
        r=self.store.request(1,'chrome','video_status',now=at('2026-10-02T02:59:59'))
        self.assertEqual(r['videoRemainingMs'],597000)
        r=self.store.request(1,'chrome','video_status',now=at('2026-10-02T03:00:00'))
        self.assertEqual(r['videoRemainingMs'],600000)

    def test_existing_grant_keeps_deadline_during_duration_upgrade(self):
        self.store.request(1,'phone','status',now=self.t)
        with self.store.connect() as db:
            db.execute("UPDATE quota SET day='2026-10-01',used=2,end=?,session=12,kind='normal'", (self.t+600000,))
        self.store=Store(self.store.path)
        r=self.store.request(1,'chrome','enter','upgrade-0000000001',self.t+120000)
        self.assertEqual((r['used'],r['expiresAt']),(2,self.t+600000))
        newer=self.start('phone',r['expiresAt']+1000)
        self.assertEqual(newer['expiresAt']-newer['serverNow'],1200000)

    def test_video_upgrade_keeps_already_spent_time(self):
        self.store.request(1,'chrome','video_status',now=self.t)
        with self.store.connect() as db:
            db.execute("UPDATE video_quota SET day='2026-10-01',spent=120123,accounted=?", (self.t,))
        self.store=Store(self.store.path)
        r=self.store.request(1,'chrome','video_status',now=self.t)
        self.assertEqual(r['videoRemainingMs'],479877)

INSTALL='a'*64

class AccountTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(self.temp.name+'/usage.db')
        self.t = at('2026-10-01T10:00:00')
    def tearDown(self):
        self.temp.cleanup()
    def call(self, token, operation, attempt='', now=None):
        account, device = self.store.authenticate(token)
        return self.store.request(account, device, operation, attempt, self.t if now is None else now)
    def start(self, token, t, attempt='attempt-0000000001'):
        self.call(token,'enter',attempt,t)
        return self.call(token,'complete',attempt,t+30000)

    def test_two_accounts_do_not_share_quota(self):
        a=self.store.register('android',INSTALL)
        b=self.store.register('android','b'*64)
        self.assertNotEqual(a['account'],b['account'])
        self.assertEqual(self.start(a['token'],self.t)['used'],1)
        r=self.call(b['token'],'status')
        self.assertEqual((r['state'],r['used']),('idle',0))
        self.call(a['token'],'emergency','urgent-0000000001',self.t+1300000)
        self.call(a['token'],'video_enter','video-0000000001',self.t+3000000)
        self.call(a['token'],'video_stop','video-0000000001',self.t+3010000)
        r=self.call(b['token'],'video_status',now=self.t+3020000)
        self.assertEqual((r['emergencyRemaining'],r['videoRemainingMs']),(4,600000))

    def test_paired_chrome_sees_same_quota(self):
        phone=self.store.register('android',INSTALL)
        code=self.store.pair_code(phone['account'],self.t)
        self.assertEqual(len(code['code']),8)
        self.assertEqual(code['expiresAt'],self.t+600000)
        chrome=self.store.pair(code['code'],'chrome',now=self.t)
        self.assertEqual(chrome['account'],phone['account'])
        first=self.start(phone['token'],self.t)
        r=self.call(chrome['token'],'enter','attempt-0000000002',self.t+60000)
        self.assertEqual((r['state'],r['expiresAt'],r['used']),('allowed',first['expiresAt'],1))

    def test_reinstall_keeps_account_and_revokes_old_token(self):
        old=self.store.register('android',INSTALL)
        self.start(old['token'],self.t)
        new=self.store.register('android',INSTALL)
        self.assertEqual(new['account'],old['account'])
        self.assertNotEqual(new['token'],old['token'])
        self.assertEqual(self.store.authenticate(old['token']),(None,None))
        self.assertEqual(self.call(new['token'],'status',now=self.t+60000)['used'],1)
        with self.store.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM devices').fetchone()[0],1)
            self.assertNotIn(new['token'],[r[0] for r in db.execute('SELECT token_hash FROM devices')])

    def test_pair_moves_android_to_other_account(self):
        chrome=self.store.register('chrome')
        phone=self.store.register('android',INSTALL)
        code=self.store.pair_code(chrome['account'],self.t)['code']
        moved=self.store.pair(code,'android',INSTALL,self.t)
        self.assertEqual(moved['account'],chrome['account'])
        self.assertEqual(self.store.authenticate(phone['token']),(None,None))
        self.assertEqual(self.store.register('android',INSTALL)['account'],chrome['account'])

    def test_code_single_use_expires_and_replaced(self):
        owner=self.store.register('chrome')
        code=self.store.pair_code(owner['account'],self.t)['code']
        self.assertTrue(self.store.pair(code,'chrome',now=self.t+1000))
        self.assertIsNone(self.store.pair(code,'chrome',now=self.t+2000))
        code=self.store.pair_code(owner['account'],self.t)['code']
        self.assertIsNone(self.store.pair(code,'chrome',now=self.t+600000))
        old=self.store.pair_code(owner['account'],self.t)['code']
        new=self.store.pair_code(owner['account'],self.t)['code']
        if old!=new:
            self.assertIsNone(self.store.pair(old,'chrome',now=self.t))
        self.assertTrue(self.store.pair(new,'chrome',now=self.t))
        self.assertIsNone(self.store.pair('WRONGCDE','chrome',now=self.t))

    def test_simultaneous_start_allocates_once_per_account(self):
        a=self.store.register('android',INSTALL)
        a2=self.store.pair(self.store.pair_code(a['account'],self.t)['code'],'chrome',now=self.t)
        b=self.store.register('chrome')
        tokens=[a['token'],a2['token'],b['token']]
        for token in tokens:
            self.call(token,'enter','attempt-0000000001',self.t)
        with concurrent.futures.ThreadPoolExecutor() as pool:
            results=list(pool.map(lambda token:self.call(token,'complete','attempt-0000000001',self.t+30000),tokens))
        self.assertEqual([r['used'] for r in results],[1,1,1])
        self.assertEqual(results[0]['session'],results[1]['session'])
        self.assertEqual(self.call(a['token'],'status',now=self.t+40000)['used'],1)
        self.assertEqual(self.call(b['token'],'status',now=self.t+40000)['used'],1)

    def test_legacy_db_and_devices_migrate_once(self):
        path=self.temp.name+'/legacy.db'
        devices=self.temp.name+'/devices.json'
        with open(devices,'w') as f:
            json.dump([dict(id='android',token_hash=digest('phone-token')),dict(id='chrome',token_hash=digest('chrome-token'))],f)
        db=sqlite3.connect(path)
        db.executescript('''
                CREATE TABLE quota (id INTEGER PRIMARY KEY CHECK(id=1), day TEXT NOT NULL, used INTEGER NOT NULL,
                    night INTEGER NOT NULL, end INTEGER NOT NULL, session INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'normal');
                INSERT INTO quota VALUES(1,'2026-10-01',2,0,%d,7,'normal');
                CREATE TABLE attempts (device TEXT PRIMARY KEY, attempt TEXT NOT NULL, ready INTEGER NOT NULL, expires INTEGER NOT NULL, consumed INTEGER NOT NULL);
                INSERT INTO attempts VALUES('chrome','attempt-0000000005',%d,%d,0);
                CREATE TABLE video_quota (id INTEGER PRIMARY KEY CHECK(id=1), day TEXT NOT NULL, spent INTEGER NOT NULL, accounted INTEGER NOT NULL);
                INSERT INTO video_quota VALUES(1,'2026-10-01',120000,%d);
                CREATE TABLE video_clients (device TEXT NOT NULL, attempt TEXT NOT NULL, end INTEGER NOT NULL, PRIMARY KEY(device,attempt));
                CREATE TABLE video_closed (device TEXT NOT NULL, attempt TEXT NOT NULL, day TEXT NOT NULL, PRIMARY KEY(device,attempt));
                INSERT INTO video_closed VALUES('chrome','video-0000000001','2026-10-01');
                CREATE TABLE emergency (id INTEGER PRIMARY KEY CHECK(id=1), month TEXT NOT NULL, used INTEGER NOT NULL);
                INSERT INTO emergency VALUES(1,'2026-10',3);
                CREATE TABLE emergency_requests (device TEXT NOT NULL, attempt TEXT NOT NULL, PRIMARY KEY(device,attempt));
                INSERT INTO emergency_requests VALUES('android','urgent-0000000001');
            ''' % (self.t+600000, self.t-1000, self.t+60000, self.t))
        db.close()
        for _ in range(2):
            self.store=Store(path,devices)
            phone=self.call('phone-token','status')
            self.assertEqual((phone['used'],phone['session'],phone['expiresAt'],phone['emergencyRemaining']),(2,7,self.t+600000,1))
            self.assertEqual(self.call('chrome-token','video_status')['videoRemainingMs'],480000)
            with self.store.connect() as db:
                self.assertEqual(db.execute('SELECT COUNT(*) FROM devices').fetchone()[0],2)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM accounts').fetchone()[0],1)
        # Pending challenge and replay markers follow the imported device rows.
        chrome=self.store.authenticate('chrome-token')[1]
        with self.store.connect() as db:
            self.assertEqual(db.execute('SELECT account_id,device FROM attempts').fetchone()[:],(1,chrome))
        self.assertEqual(self.call('phone-token','emergency','urgent-0000000001',self.t+2000000)['state'],'expired')
        self.assertEqual(self.call('chrome-token','video_enter','video-0000000001',self.t+2000000)['state'],'expired')
        fresh=self.store.register('android',INSTALL)
        self.assertNotEqual(fresh['account'],1)

def token_of(mail):
    return re.search(r'/approve#(\S+)', mail[2]).group(1)

class GuardianTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.sent = []
        self.store = Store(self.temp.name+'/usage.db', mail=lambda *m: self.sent.append(m))
        self.account = self.store.register('android', INSTALL)['account']
        self.t = at('2026-10-01T10:00:00')
    def tearDown(self):
        self.temp.cleanup()
    def guard(self, now=None):
        return self.store.guard(self.account, self.t if now is None else now)
    def verified(self, email='abla@example.com'):
        self.store.set_guardian(self.account, email, self.t)
        self.assertEqual(self.store.decide(token_of(self.sent[-1]), 'confirm', self.t)[0], 200)
    def release(self, reason='', now=None):
        status, view = self.store.release(self.account, reason, self.t if now is None else now)
        self.assertEqual(status, 200)
        return token_of(self.sent[-1])

    def test_no_guardian_no_lock(self):
        self.assertEqual(self.guard(), dict(guardian=None, pendingGuardian=False, locked=False,
                                            releasedUntil=0, pendingRelease=False, mailError=False))
        self.assertEqual(self.store.release(self.account, '', self.t), (409, {'error': 'no_guardian'}))
        self.assertEqual(self.sent, [])

    def test_verify_flow(self):
        status, view = self.store.set_guardian(self.account, 'abla@example.com', self.t)
        self.assertEqual((status, view['pendingGuardian'], view['locked'], view['guardian']), (200, True, False, None))
        account, to, body = self.sent[-1]
        self.assertEqual((account, to), (self.account, 'abla@example.com'))
        self.assertIn('https://zmanga.zerohidz.com/bir-nefes/approve#', body)
        token = token_of(self.sent[-1])
        info = self.store.request_info(token, self.t)
        self.assertEqual((info['kind'], info['decisions']), ('verify', ['confirm']))
        with self.store.connect() as db:
            self.assertEqual(db.execute('SELECT token_hash FROM guard_requests').fetchone()[0], digest(token))
        self.assertEqual(self.store.decide(token, 'deny', self.t), (400, {'error': 'invalid_decision'}))
        self.assertEqual(self.store.decide(token, 'confirm', self.t)[0], 200)
        g = self.guard()
        self.assertEqual((g['guardian'], g['locked'], g['pendingGuardian']), ('a***@example.com', True, False))

    def test_release_decisions(self):
        self.verified()
        token = self.release('Annem arıyor')
        self.assertEqual(self.sent[-1][1], 'abla@example.com')
        self.assertIn('Gerekçe: Annem arıyor', self.sent[-1][2])
        self.assertEqual(self.store.request_info(token, self.t)['reason'], 'Annem arıyor')
        self.assertTrue(self.guard()['pendingRelease'])
        self.assertEqual(self.store.decide(token, '1h', self.t)[1]['releasedUntil'], self.t+3600000)
        g = self.guard(self.t+1000)
        self.assertEqual((g['locked'], g['releasedUntil'], g['pendingRelease']), (False, self.t+3600000, False))
        # The release window ends on its own.
        g = self.guard(self.t+3600000)
        self.assertEqual((g['locked'], g['releasedUntil']), (True, 0))
        self.store.decide(self.release(now=self.t+4000000), '24h', self.t+4000000)
        self.assertEqual(self.guard(self.t+4000000)['releasedUntil'], self.t+4000000+86400000)
        self.store.decide(self.release(now=self.t+4000000), 'deny', self.t+4000000)
        self.assertEqual(self.guard(self.t+4000000)['releasedUntil'], self.t+4000000+86400000)
        self.store.decide(self.release(now=self.t+4000000), 'permanent', self.t+4000000)
        g = self.guard(self.t+4000000)
        self.assertEqual((g['guardian'], g['locked'], g['releasedUntil']), (None, False, 0))
        self.assertEqual(self.store.release(self.account, '', self.t)[0], 409)

    def test_deny_keeps_lock(self):
        self.verified()
        self.assertEqual(self.store.decide(self.release(), 'deny', self.t)[0], 200)
        g = self.guard()
        self.assertEqual((g['locked'], g['pendingRelease']), (True, False))

    def test_change_needs_current_guardian(self):
        self.verified()
        self.store.set_guardian(self.account, 'kuzen@example.com', self.t)
        self.assertEqual(self.sent[-1][1], 'abla@example.com')
        token = token_of(self.sent[-1])
        info = self.store.request_info(token, self.t)
        self.assertEqual((info['kind'], info['email'], info['decisions']), ('change', 'k***@example.com', ['approve', 'deny']))
        self.assertEqual(self.guard()['guardian'], 'a***@example.com')
        self.store.decide(token, 'deny', self.t)
        self.assertEqual((self.guard()['guardian'], self.guard()['pendingGuardian'], len(self.sent)), ('a***@example.com', False, 2))
        self.store.set_guardian(self.account, 'kuzen@example.com', self.t)
        self.store.decide(token_of(self.sent[-1]), 'approve', self.t)
        self.assertEqual(self.sent[-1][1], 'kuzen@example.com')
        self.assertEqual((self.guard()['guardian'], self.guard()['pendingGuardian']), ('a***@example.com', True))
        self.store.decide(token_of(self.sent[-1]), 'confirm', self.t)
        g = self.guard()
        self.assertEqual((g['guardian'], g['locked']), ('k***@example.com', True))

    def test_token_single_use_expiry_and_replacement(self):
        self.verified()
        token = self.release()
        self.assertEqual(self.store.decide(token, '1h', self.t)[0], 200)
        self.assertEqual(self.store.decide(token, '24h', self.t), (404, {'error': 'invalid_token'}))
        self.assertIsNone(self.store.request_info(token, self.t))
        token = self.release()
        self.assertIsNone(self.store.request_info(token, self.t+86400000))
        self.assertEqual(self.store.decide(token, '1h', self.t+86400000)[0], 404)
        self.assertFalse(self.guard(self.t+86400000)['pendingRelease'])
        old, new = self.release(), self.release()
        self.assertEqual(self.store.decide(old, '1h', self.t)[0], 404)
        self.assertEqual(self.store.decide(new, '1h', self.t)[0], 200)

    def test_daily_mail_limit(self):
        for n in range(10):
            self.assertEqual(self.store.set_guardian(self.account, f'x{n}@example.com', self.t)[0], 200)
        self.assertEqual(self.store.set_guardian(self.account, 'y@example.com', self.t), (429, {'error': 'mail_limit'}))
        self.assertEqual(self.store.set_guardian(self.account, 'y@example.com', at('2026-10-02T03:00:00'))[0], 200)

    def test_mailer_ports_and_failure(self):
        config = self.temp.name+'/smtp.json'
        mailer = Mailer(self.store, config)
        for port, cls in ((465, 'SMTP_SSL'), (587, 'SMTP')):
            with open(config, 'w') as f:
                json.dump(dict(host='smtp.example.com', port=port, user='u', password='p', **{'from': 'bn@example.com'}), f)
            with mock.patch.object(app.smtplib, cls) as smtp:
                mailer(self.account, 'abla@example.com', 'gövde')
                mailer.queue.join()
            client = smtp.return_value
            self.assertEqual(smtp.call_args[0][:2], ('smtp.example.com', port))
            self.assertEqual(client.starttls.called, port == 587)
            client.login.assert_called_once_with('u', 'p')
            message = client.send_message.call_args[0][0]
            self.assertEqual((message['Subject'], message['To']), ('Bir Nefes: onayın isteniyor', 'abla@example.com'))
        self.assertFalse(self.guard()['mailError'])
        mailer.path = self.temp.name+'/missing.json'
        with mock.patch('builtins.print') as log:
            mailer(self.account, 'abla@example.com', 'gövde')
            mailer.queue.join()
        self.assertEqual(log.call_args[0], ('mail failed:', 'FileNotFoundError'))
        self.assertTrue(self.guard()['mailError'])

class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.sent = []
        self.server.store = Store(self.temp.name+'/usage.db', mail=lambda *m: self.sent.append(m))
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()
    def post(self, path, body=None, token=None, method='POST'):
        data=None if method=='GET' else json.dumps(body or {}, ensure_ascii=False).encode()
        request=urllib.request.Request(f'http://127.0.0.1:{self.server.server_port}{path}',data,method=method)
        if token: request.add_header('Authorization','Bearer '+token)
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.load(error)
    def test_register_pair_and_use(self):
        status, phone = self.post('/v1/register',dict(kind='android',install=INSTALL))
        self.assertEqual((status,sorted(phone)),(200,['account','token']))
        self.assertEqual(self.post('/v1/register',dict(kind='chrome',install=INSTALL))[0],400)
        self.assertEqual(self.post('/v1/register',dict(kind='android',install='xyz'))[0],400)
        self.assertEqual(self.post('/v1/pair-code')[0],401)
        status, code = self.post('/v1/pair-code',token=phone['token'])
        self.assertEqual((status,sorted(code)),(200,['code','expiresAt']))
        self.assertEqual(self.post('/v1/pair',dict(code='AAAAAAAA',kind='chrome')),(403,{'error':'invalid_code'}))
        pretty=code['code'][:4].lower()+'-'+code['code'][4:]
        status, chrome = self.post('/v1/pair',dict(code=pretty,kind='chrome'))
        self.assertEqual((status,chrome['account']),(200,phone['account']))
        status, r = self.post('/v1/emergency',dict(attempt='urgent-0000000001'),chrome['token'])
        self.assertEqual((status,r['emergencyRemaining']),(200,3))
        self.assertEqual(self.post('/v1/emergency',dict(attempt='urgent-0000000001'),'wrong')[0],401)

    def test_guardian_over_http(self):
        phone = self.post('/v1/register',dict(kind='android',install=INSTALL))[1]['token']
        status, r = self.post('/v1/status',token=phone,method='GET')
        self.assertEqual((status,r['guard']['guardian'],r['guard']['locked']),(200,None,False))
        self.assertIn('used',r)
        self.assertEqual(self.post('/v1/guardian',dict(email='abla@example.com'))[0],401)
        self.assertEqual(self.post('/v1/guardian',dict(email='bozuk'),phone),(400,{'error':'invalid_email'}))
        self.assertEqual(self.post('/v1/release',{},phone),(409,{'error':'no_guardian'}))
        status, g = self.post('/v1/guardian',dict(email=' Abla@Example.com '),phone)
        self.assertEqual((status,g['pendingGuardian']),(200,True))
        self.assertEqual(self.sent[-1][1],'abla@example.com')
        token = token_of(self.sent[-1])
        self.assertEqual(self.post('/v1/request-info?token='+token,method='GET')[1]['kind'],'verify')
        self.assertEqual(self.post('/v1/request-info?token=yanlis',method='GET')[0],404)
        self.assertEqual(self.post('/v1/decide',dict(token=token,decision='confirm')),(200,{'kind':'verify','decision':'confirm','releasedUntil':0}))
        self.assertEqual(self.post('/v1/decide',dict(token=token,decision='confirm'))[0],404)
        self.assertEqual(self.post('/v1/release',dict(reason='x'*201),phone)[0],400)
        status, g = self.post('/v1/release',dict(reason='ş'*200),phone)
        self.assertEqual((status,g['pendingRelease'],g['locked']),(200,True,True))
        token = token_of(self.sent[-1])
        info = self.post('/v1/request-info?token='+token,method='GET')[1]
        self.assertEqual((info['reason'],info['decisions']),('ş'*200,['1h','24h','permanent','deny']))
        self.assertEqual(self.post('/v1/decide',dict(token=token,decision='1h'))[0],200)
        guard = self.post('/v1/status',token=phone,method='GET')[1]['guard']
        self.assertEqual((guard['guardian'],guard['locked'],guard['pendingRelease']),('a***@example.com',False,False))
        self.assertGreater(guard['releasedUntil'],0)

    def test_approve_page(self):
        with urllib.request.urlopen(f'http://127.0.0.1:{self.server.server_port}/approve') as response:
            page = response.read().decode()
            self.assertEqual((response.status,response.headers['Content-Type']),(200,'text/html; charset=utf-8'))
        self.assertIn('lang="tr"',page)
        self.assertIn("fetch('v1/decide'",page)

class WorkPassTests(unittest.TestCase):
    setUp, tearDown, verified = GuardianTests.setUp, GuardianTests.tearDown, GuardianTests.verified
    OATH = 'İş dışında 5 dakika bile kullanmayacağıma YEMİN EDERİM'
    def chrome(self):
        token = self.store.pair(self.store.pair_code(self.account, self.t)['code'], 'chrome', now=self.t)['token']
        return self.store.authenticate(token)[1]

    def test_oath_window_device_and_mode(self):
        device = self.chrome()
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', 'söz veriyorum', self.t), (400, {'error': 'no_oath'}))
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', self.OATH, at('2026-10-01T18:00:00'))[1], {'error': 'not_metered'})
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', self.OATH, at('2026-10-03T10:00:00'))[1], {'error': 'not_metered'})
        phone = self.store.authenticate(self.store.register('android', INSTALL, self.t)['token'])[1]
        self.assertEqual(self.store.start_work(self.account, phone, 60, 'Ders', self.OATH, self.t)[1], {'error': 'chrome_only'})
        status, view = self.store.start_work(self.account, device, 60, 'Ders', self.OATH, self.t)
        self.assertEqual((status, view['workUntil']), (200, self.t + 3_600_000))
        self.assertEqual(self.store.start_work(self.account, device, 30, 'Ders', self.OATH, self.t + 1000)[1], {'error': 'active'})
        r = self.store.request(self.account, device, 'video_enter', 'video-0000000001', self.t + 60_000)
        self.assertEqual((r['state'], r['mode'], r['expiresAt']), ('allowed', 'work', self.t + 3_600_000))
        # The pass does not spend the metered budget.
        self.assertEqual(r['videoRemainingMs'], 600_000)
        self.store.end_work(self.account, self.t + 120_000)
        r = self.store.request(self.account, device, 'video_tick', 'video-0000000001', self.t + 121_000)
        self.assertEqual(r['mode'], 'metered')

    def test_daily_cap_and_extra_limit(self):
        device = self.chrome()
        for n in range(2):
            self.assertEqual(self.store.start_work(self.account, device, 90, 'Ders', self.OATH, self.t + n * 5_400_000)[0], 200)
        self.assertEqual(self.store.start_work(self.account, device, 15, 'Ders', self.OATH, self.t + 10_800_000)[1], {'error': 'work_cap'})
        self.store.start_extra(self.account, 'video', 30, at('2026-10-02T10:00:00'))
        self.assertEqual(self.store.start_work(self.account, device, 15, 'Ders', self.OATH, at('2026-10-02T10:01:00'))[1], {'error': 'limited'})

    def test_summary_mailed_once_after_night_starts(self):
        self.verified()
        device = self.chrome()
        self.sent.clear()
        self.store.start_work(self.account, device, 30, 'Algoritma dersi', self.OATH, self.t)
        self.store.end_work(self.account, self.t + 600_000)
        self.assertEqual(self.store.work_reports(at('2026-10-01T22:00:00')), 0)
        self.assertEqual(self.store.work_reports(at('2026-10-01T22:30:00')), 1)
        account, to, body, subject = self.sent[-1]
        self.assertEqual((to, subject), ('abla@example.com', app.WORK_SUBJECT))
        self.assertIn('10.00–10.10 (10 dk): Algoritma dersi', body)
        self.assertIn('YEMİN EDERİM', body)
        self.assertEqual(self.store.work_reports(at('2026-10-02T09:00:00')), 0)

def ruleset(changes=None):
    rules = app.defaults()
    for path, value in (changes or {}).items():
        *parents, key = path.split('.')
        node = rules
        for name in parents:
            node = node[name]
        node[key] = value
    return rules

class RulesPolicyTests(unittest.TestCase):
    setUp, tearDown, start = PolicyTests.setUp, PolicyTests.tearDown, PolicyTests.start
    def save(self, changes, account=1, base=0, now=None):
        status, body = self.store.save_rules(account, ruleset(changes), base, now=self.t if now is None else now)
        self.assertEqual(status, 200, body)
        return body

    def test_ac2_rights_change_waits_for_renewal(self):
        body = self.save({'rights': 3})
        self.assertEqual((body['current']['version'], body['pending']['version'], body['pending']['effectiveAt']),
                         (0, 1, at('2026-10-02T03:00:00')))
        for n in range(4):
            r = self.start('phone', self.t+n*1231000, f'attempt-00000000{n:02}')
        self.assertEqual((r['state'], r['used'], r['rulesVersion']), ('allowed', 4, 0))
        t = at('2026-10-02T10:00:00')
        for n in range(3):
            r = self.start('phone', t+n*1231000, f'attempt-00000001{n:02}')
        self.assertEqual((r['used'], r['remaining'], r['rulesVersion']), (3, 0, 1))
        r = self.store.request(1, 'phone', 'enter', 'attempt-0000000199', r['expiresAt'])
        self.assertEqual(r['state'], 'exhausted')

    def test_ac3_window_and_wait(self):
        self.save({'windowMin': 10, 'waitSec': 60})
        t = at('2026-10-02T10:00:00')
        r = self.store.request(1, 'phone', 'enter', 'attempt-0000000001', t)
        self.assertEqual((r['state'], r['readyAt']), ('waiting', t+60000))
        self.assertEqual(self.store.request(1, 'phone', 'complete', 'attempt-0000000001', t+59999)['state'], 'waiting')
        r = self.store.request(1, 'phone', 'complete', 'attempt-0000000001', t+60000)
        self.assertEqual((r['state'], r['expiresAt']), ('allowed', t+60000+600000))

    def test_ac4_night_start_and_window(self):
        self.save({'nightStart': '21:00', 'nightWindowMin': 10})
        r = self.start('phone', at('2026-10-02T20:54:30'))
        self.assertEqual(r['expiresAt'], at('2026-10-02T21:10:00'))
        t = at('2026-10-03T21:30:00')
        r = self.start('phone', t, 'attempt-0000000002')
        self.assertEqual((r['state'], r['expiresAt'], r['remaining']), ('allowed', t+30000+600000, 0))

    def test_ac4_zero_night_window_gives_no_night_right(self):
        self.save({'nightWindowMin': 0})
        r = self.store.request(1, 'phone', 'enter', 'attempt-0000000001', at('2026-10-02T23:00:00'))
        self.assertEqual((r['state'], r['remaining']), ('exhausted', 0))
        r = self.start('phone', at('2026-10-02T22:20:00'), 'attempt-0000000002')
        self.assertEqual(r['expiresAt'], at('2026-10-02T22:30:00'))

    def test_ac5_later_renewal_keeps_yesterday_until_it(self):
        self.save({'renewal': '05:00'})
        self.start('phone', self.t)
        r = self.store.request(1, 'phone', 'status', now=at('2026-10-02T02:00:00'))
        self.assertEqual((r['rulesVersion'], r['renewalAt']), (0, at('2026-10-02T03:00:00')))
        r = self.store.request(1, 'phone', 'status', now=at('2026-10-02T04:00:00'))
        self.assertEqual((r['rulesVersion'], r['budgetDay'], r['used'], r['renewalAt']),
                         (1, '2026-10-01', 1, at('2026-10-02T05:00:00')))
        r = self.store.request(1, 'phone', 'status', now=at('2026-10-02T05:00:00'))
        self.assertEqual((r['budgetDay'], r['used'], r['remaining']), ('2026-10-02', 0, 5))

    def test_ac6_validation(self):
        cases = [({'renewal': '23:00', 'nightStart': '22:30'}, 'renewal'), ({'nightStart': '13:00'}, 'nightStart'),
                 ({'nightStart': '22:15'}, 'nightStart'), ({'rights': 11}, 'rights'), ({'rights': True}, 'rights'),
                 ({'windowMin': 7}, 'windowMin'), ({'waitSec': 301}, 'waitSec'), ({'nightWindowMin': 25}, 'nightWindowMin'),
                 ({'urgentCount': 9}, 'urgentCount'), ({'urgentMin': 35}, 'urgentMin'),
                 ({'video.weekday.closedFrom': '14:00'}, 'video.weekday.closedFrom'),
                 ({'video.weekday.closedUntil': '15:00'}, 'video.weekday.closedUntil'),
                 ({'video.weekday.meteredFrom': '04:00'}, 'video.weekday.meteredFrom'),
                 ({'video.weekday.meteredUntil': '23:00'}, 'video.weekday.meteredUntil'),
                 ({'video.weekday.meteredUntil': None}, 'video.weekday.meteredUntil'),
                 ({'video.weekend.budgetMin': 5}, 'video.weekend.budgetMin'),
                 ({'video.weekday.budgetMin': 125}, 'video.weekday.budgetMin'),
                 ({'work.minutes': [30, 15]}, 'work.minutes'), ({'work.minutes': []}, 'work.minutes'),
                 ({'work.minutes': [20]}, 'work.minutes'), ({'work.dailyMin': 20}, 'work.dailyMin'),
                 ({'work.enabled': 1}, 'work.enabled'), ({'work.summary': None}, 'work.summary'),
                 ({'extra': 1}, 'extra'), ({'video.weekday.other': 1}, 'video.weekday.other'), ({'video': []}, 'video')]
        for changes, field in cases:
            self.assertEqual(self.store.save_rules(1, ruleset(changes), 0, now=self.t), (400, {'error': 'invalid_rules', 'field': field}), changes)
        missing = ruleset(); del missing['work']
        self.assertEqual(self.store.save_rules(1, missing, 0, now=self.t)[1]['field'], 'work')
        self.assertEqual(self.store.save_rules(1, None, 0, now=self.t)[1]['field'], 'rules')
        # Edges that are allowed: no night video close, midnight renewal, night start an hour before renewal.
        for changes in ({'video.weekend.closedFrom': None}, {'renewal': '00:00', 'video.weekday.closedUntil': '00:00'},
                        {'nightStart': '02:00'}, {'video.weekday.meteredFrom': '05:00', 'video.weekday.meteredUntil': '22:30'}):
            self.assertEqual(self.store.save_rules(1, ruleset(changes), 0, dry_run=True, now=self.t)[0], 200, changes)

    def test_ac7_weekday_metered_hours(self):
        self.save({'video.weekday.meteredFrom': '09:00', 'video.weekday.meteredUntil': '12:00', 'video.weekday.budgetMin': 30})
        r = self.store.request(1, 'chrome', 'video_status', now=at('2026-10-02T08:30:00'))
        self.assertEqual((r['state'], r['mode'], r['nextChangeAt']), ('allowed', 'free', at('2026-10-02T09:00:00')))
        self.assertEqual(r['expiresAt'], at('2026-10-02T09:00:00'))
        r = self.store.request(1, 'chrome', 'video_status', now=at('2026-10-02T10:00:00'))
        self.assertEqual((r['state'], r['mode'], r['videoRemainingMs']), ('allowed', 'metered', 1_800_000))
        r = self.store.request(1, 'chrome', 'video_status', now=at('2026-10-02T12:30:00'))
        self.assertEqual((r['state'], r['mode'], r['nextChangeAt']), ('allowed', 'free', at('2026-10-02T22:30:00')))
        # After the close the next change is the next day's opening.
        r = self.store.request(1, 'chrome', 'video_status', now=at('2026-10-02T23:00:00'))
        self.assertEqual((r['mode'], r['nextChangeAt']), ('night', at('2026-10-03T05:00:00')))

    def test_ac8_weekend_metered_hours(self):
        self.save({'video.weekend.meteredFrom': '10:00', 'video.weekend.meteredUntil': '12:00', 'video.weekend.budgetMin': 20})
        r = self.store.request(1, 'chrome', 'video_enter', 'video-0000000001', at('2026-10-03T11:00:00'))
        self.assertEqual((r['state'], r['mode'], r['videoRemainingMs']), ('allowed', 'metered', 1_200_000))
        r = self.store.request(1, 'chrome', 'video_status', now=at('2026-10-03T09:00:00'))
        self.assertEqual(r['mode'], 'free')
        r = self.store.request(1, 'chrome', 'video_status', now=at('2026-10-04T22:29:00'))
        self.assertEqual(r['mode'], 'free')
        r = self.store.request(1, 'chrome', 'video_status', now=at('2026-10-04T22:30:00'))
        self.assertEqual((r['state'], r['mode']), ('exhausted', 'night'))

    def test_ac18_lower_urgent_count(self):
        for n in range(3):
            self.store.request(1, 'phone', 'emergency', f'urgent-00000000{n:02}', self.t+n*1200001)
        self.save({'urgentCount': 2})
        t = at('2026-10-02T10:00:00')
        r = self.store.request(1, 'phone', 'status', now=t)
        self.assertEqual(r['emergencyRemaining'], 0)
        r = self.store.request(1, 'phone', 'emergency', 'urgent-0000000099', t)
        self.assertEqual((r['state'], r['emergencyRemaining']), ('emergency_exhausted', 0))
        self.assertEqual(self.store.request(1, 'chrome', 'video_status', now=t)['emergencyRemaining'], 0)

    def test_ac18_urgent_minutes(self):
        self.save({'urgentMin': 30})
        t = at('2026-10-02T23:00:00')
        r = self.store.request(1, 'phone', 'emergency', 'urgent-0000000001', t)
        self.assertEqual(r['expiresAt'], t+1_800_000)

    def test_ac19_urgent_charge_uses_started(self):
        t = at('2026-10-02T10:00:00')
        for account in (1, 2, 3):
            self.save({'urgentMin': 30}, account)
            self.store.request(account, 'chrome', 'video_enter', 'video-0000000001', t)
        # 1: a pass issued under today's 30 minutes.
        self.assertEqual(self.store.request(1, 'phone', 'emergency', 'urgent-0000000001', t+3000)['expiresAt'], t+3000+1_800_000)
        with self.store.connect() as db:
            # 2: a pass issued under yesterday's 20 minutes; 3: a row from before the started column.
            db.execute("UPDATE quota SET kind='emergency', end=?, started=?, session=1 WHERE account_id=2", (t+3000+1_200_000, t+3000))
            db.execute("UPDATE quota SET kind='emergency', end=?, started=0, session=1 WHERE account_id=3", (t+3000+1_200_000,))
        for account in (1, 2, 3):
            r = self.store.request(account, 'chrome', 'video_tick', 'video-0000000001', t+4000)
            self.assertEqual((r['mode'], r['videoRemainingMs']), ('emergency', 597_000), account)
            with self.store.connect() as db:
                self.assertEqual(db.execute('SELECT spent FROM video_quota WHERE account_id=?', (account,)).fetchone()[0], 3000)

    def test_ac19_started_column_migrates(self):
        path = self.temp.name+'/old.db'
        with sqlite3.connect(path) as db:
            db.execute("CREATE TABLE quota (account_id INTEGER PRIMARY KEY, day TEXT NOT NULL, used INTEGER NOT NULL, night INTEGER NOT NULL, end INTEGER NOT NULL, session INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'normal')")
            db.execute("INSERT INTO quota VALUES(1,'2026-10-01',2,0,0,4,'normal')")
        store = Store(path)
        self.assertEqual(store.request(1, 'phone', 'status', now=self.t)['used'], 2)
        with store.connect() as db:
            self.assertEqual(db.execute('SELECT started FROM quota').fetchone()[0], 0)

    def test_ac16_stale_single_pending_and_cancel(self):
        self.save({'rights': 4})
        self.assertEqual(self.store.save_rules(1, ruleset({'rights': 3}), 0, now=self.t), (409, {'error': 'stale'}))
        body = self.save({'rights': 3}, base=1, now=self.t+1000)
        self.assertEqual((body['pending']['version'], body['pending']['rules']['rights']), (2, 3))
        with self.store.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM rule_sets').fetchone()[0], 1)
        body = self.store.cancel_rules(1, self.t+2000)
        self.assertEqual((body['pending'], body['current']['version']), (None, 0))
        self.save({'rights': 2}, base=0, now=self.t+3000)
        # Once in force a set stays as history; a newer one is based on it.
        self.assertEqual(self.store.rules(1, at('2026-10-02T03:00:00'))['current']['rules']['rights'], 2)
        self.assertEqual(self.store.save_rules(1, ruleset({'rights': 1}), 0, now=at('2026-10-02T04:00:00'))[1], {'error': 'stale'})
        body = self.save({'rights': 1}, base=1, now=at('2026-10-02T04:00:00'))
        self.assertEqual((body['current']['version'], body['current']['effectiveAt'], body['pending']['version'], body['pending']['effectiveAt']),
                         (1, at('2026-10-02T03:00:00'), 2, at('2026-10-03T03:00:00')))

    def test_ac17_dry_run_writes_nothing(self):
        status, body = self.store.save_rules(1, ruleset({'video.weekday.budgetMin': 20, 'rights': 4}), 0, dry_run=True, now=self.t)
        self.assertEqual((status, body), (200, dict(looser=True, changes=['rights', 'video.weekday.budgetMin'],
                                                    effectiveAt=at('2026-10-02T03:00:00'), allowed=True)))
        with self.store.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM rule_sets').fetchone()[0], 0)
        self.assertIsNone(self.store.rules(1, self.t)['pending'])

class RulesGuardianTests(unittest.TestCase):
    setUp, tearDown, verified, release = GuardianTests.setUp, GuardianTests.tearDown, GuardianTests.verified, GuardianTests.release
    chrome = WorkPassTests.chrome
    OATH = WorkPassTests.OATH
    def save(self, changes, base=0, now=None, dry_run=False):
        return self.store.save_rules(self.account, ruleset(changes), base, dry_run, now=self.t if now is None else now)
    def count(self):
        with self.store.connect() as db:
            return db.execute('SELECT COUNT(*) FROM rule_sets').fetchone()[0]

    def test_ac9_work_disabled_and_minutes(self):
        self.assertEqual(self.save({'work.enabled': False})[0], 200)
        device = self.chrome()
        t = at('2026-10-02T10:00:00')
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', self.OATH, t), (409, {'error': 'disabled'}))
        self.assertEqual(self.save({'work.minutes': [15, 45]}, base=1, now=t)[0], 200)
        t = at('2026-10-05T10:00:00')
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', self.OATH, t), (400, {'error': 'invalid_work'}))
        status, view = self.store.start_work(self.account, device, 45, 'Ders', self.OATH, t)
        self.assertEqual((status, view['workUntil']), (200, t+2_700_000))

    def test_ac9_daily_minutes(self):
        self.assertEqual(self.save({'work.dailyMin': 60})[0], 200)
        device = self.chrome()
        t = at('2026-10-02T10:00:00')
        status, view = self.store.start_work(self.account, device, 90, 'Ders', self.OATH, t)
        self.assertEqual((status, view['workUntil'], view['workLeftMs']), (200, t+3_600_000, 0))
        self.assertEqual(self.store.start_work(self.account, device, 15, 'Ders', self.OATH, t+3_600_000)[1], {'error': 'work_cap'})

    def test_ac10_work_only_in_metered_hours(self):
        self.assertEqual(self.save({'video.weekday.meteredFrom': '09:00', 'video.weekday.meteredUntil': '12:00', 'video.weekday.budgetMin': 30})[0], 200)
        device = self.chrome()
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', self.OATH, at('2026-10-02T13:00:00')), (409, {'error': 'not_metered'}))
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', self.OATH, at('2026-10-02T08:30:00')), (409, {'error': 'not_metered'}))
        self.assertEqual(self.store.start_work(self.account, device, 60, 'Ders', self.OATH, at('2026-10-02T10:00:00'))[0], 200)

    def test_ac11_no_summary(self):
        self.assertEqual(self.save({'work.summary': False})[0], 200)
        self.verified()
        device = self.chrome()
        self.sent.clear()
        t = at('2026-10-02T10:00:00')
        self.store.start_work(self.account, device, 30, 'Ders', self.OATH, t)
        self.assertEqual(self.store.work_reports(at('2026-10-02T22:30:00')), 0)
        self.assertEqual(self.sent, [])
        with self.store.connect() as db:
            self.assertEqual(db.execute('SELECT reported FROM work_passes').fetchone()[0], 1)

    def test_ac11_summary_follows_that_days_close(self):
        self.verified()
        self.assertEqual(self.save({'video.weekday.closedFrom': '21:00', 'nightStart': '21:00'})[0], 200)
        device = self.chrome()
        self.sent.clear()
        self.store.start_work(self.account, device, 30, 'Ders', self.OATH, at('2026-10-02T10:00:00'))
        self.assertEqual(self.store.work_reports(at('2026-10-02T20:59:00')), 0)
        self.assertEqual(self.store.work_reports(at('2026-10-02T21:00:00')), 1)
        self.assertEqual(self.sent[-1][3], app.WORK_SUBJECT)

    def test_ac12_locked_rejects_loosening(self):
        self.verified()
        self.assertEqual(self.save({'rights': 6}), (409, {'error': 'locked'}))
        self.assertEqual(self.count(), 0)
        status, body = self.save({'rights': 4})
        self.assertEqual((status, body['pending']['version'], body['pending']['looser'], body['locked']), (200, 1, False, True))

    def test_ac13_release_allows_loosening(self):
        self.verified()
        self.store.decide(self.release(), '1h', self.t)
        status, body = self.save({'rights': 6}, now=self.t+1000)
        self.assertEqual((status, body['pending']['looser'], body['locked']), (200, True, False))
        self.assertTrue(self.store.guard(self.account, self.t+3_600_000)['locked'])
        r = self.store.request(self.account, 'phone', 'status', now=at('2026-10-02T03:00:00'))
        self.assertEqual((r['rulesVersion'], r['remaining'], r['rules']['rights']), (1, 6, 6))

    def test_ac14_mixed_change_is_looser(self):
        self.verified()
        self.assertEqual(self.save({'rights': 4, 'video.weekday.budgetMin': 20}), (409, {'error': 'locked'}))
        # A free slice where it was metered, or a shorter study day turned off and on again.
        self.assertEqual(self.save({'video.weekday.meteredUntil': '16:00'}), (409, {'error': 'locked'}))
        self.assertEqual(self.save({'video.weekend.closedFrom': None}), (409, {'error': 'locked'}))
        self.assertEqual(self.save({'work.summary': False}), (409, {'error': 'locked'}))
        self.assertEqual(self.save({'work.minutes': [15, 30, 60, 120]}), (409, {'error': 'locked'}))
        self.assertEqual(self.save({'nightStart': '23:00'}), (409, {'error': 'locked'}))
        self.assertEqual(self.save({'waitSec': 25}), (409, {'error': 'locked'}))
        self.assertEqual(self.save({'rights': 4, 'video.weekday.budgetMin': 20}, dry_run=True)[1]['allowed'], False)
        self.assertEqual(self.count(), 0)
        # Tighter on every axis is accepted.
        tight = {'rights': 4, 'waitSec': 60, 'nightStart': '22:00', 'video.weekday.meteredFrom': '07:00',
                 'video.weekday.closedFrom': '21:00', 'work.minutes': [15, 30], 'work.enabled': False}
        status, body = self.save(tight)
        self.assertEqual((status, body['pending']['looser']), (200, False))

    def test_ac15_locked_renewal_change(self):
        self.verified()
        self.assertEqual(self.save({'renewal': '02:00'}), (409, {'error': 'locked'}))
        self.assertEqual(self.save({'renewal': '04:00'}), (409, {'error': 'locked'}))
        self.assertEqual(self.count(), 0)

    def test_ac15_pending_loosening_survives_lock(self):
        self.assertEqual(self.save({'rights': 6})[0], 200)
        self.verified()
        self.assertTrue(self.store.guard(self.account, at('2026-10-02T03:00:00'))['locked'])
        r = self.store.request(self.account, 'phone', 'status', now=at('2026-10-02T03:00:00'))
        self.assertEqual(r['remaining'], 6)
        # Cancelling is always allowed, also while locked.
        self.assertEqual(self.save({'rights': 4}, base=1, now=at('2026-10-02T04:00:00'))[0], 200)
        self.assertIsNone(self.store.cancel_rules(self.account, at('2026-10-02T04:00:00'))['pending'])

    def test_ac20_locked_install_cannot_move(self):
        self.verified()
        other = self.store.register('chrome')
        code = self.store.pair_code(other['account'], self.t)['code']
        self.assertEqual(self.store.pair(code, 'android', INSTALL, self.t), {'error': 'locked'})
        self.assertEqual(self.store.register('android', INSTALL)['account'], self.account)
        # Same account while locked is fine; after a release the move works and the code is still valid.
        own = self.store.pair_code(self.account, self.t)['code']
        self.assertEqual(self.store.pair(own, 'android', INSTALL, self.t)['account'], self.account)
        self.store.decide(self.release(), '1h', self.t)
        self.assertEqual(self.store.pair(code, 'android', INSTALL, self.t+1000)['account'], other['account'])

class RulesHttpTests(unittest.TestCase):
    setUp, tearDown, post = HttpTests.setUp, HttpTests.tearDown, HttpTests.post
    def raw(self, path, data, token):
        request = urllib.request.Request(f'http://127.0.0.1:{self.server.server_port}{path}', data, method='POST')
        request.add_header('Authorization', 'Bearer '+token)
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_ac21_answers_carry_rules(self):
        phone = self.post('/v1/register', dict(kind='android', install=INSTALL))[1]['token']
        for path, method, body in (('/v1/status', 'GET', None), ('/v1/video-status', 'GET', None),
                                   ('/v1/enter', 'POST', dict(attempt='attempt-0000000001')),
                                   ('/v1/video-enter', 'POST', dict(attempt='video-0000000001'))):
            status, r = self.post(path, body, phone, method)
            self.assertEqual((status, r['rulesVersion'], r['rules']), (200, 0, app.DEFAULT_RULES), path)
            for key in ('state', 'serverNow', 'expiresAt', 'emergencyRemaining', 'budgetDay', 'renewalAt', 'remaining'):
                self.assertIn(key, r)
        status, view = self.post('/v1/rules', None, phone, 'GET')
        self.assertEqual((status, view['current'], view['pending'], view['defaults'], view['locked']),
                         (200, dict(version=0, effectiveAt=0, rules=app.DEFAULT_RULES), None, app.DEFAULT_RULES, False))
        status, body = self.post('/v1/rules', dict(rules=ruleset({'rights': 4}), baseVersion=0, dryRun=True), phone)
        self.assertEqual((status, body['changes'], body['looser'], body['allowed']), (200, ['rights'], False, True))
        status, body = self.post('/v1/rules', dict(rules=ruleset({'rights': 4}), baseVersion=0), phone)
        self.assertEqual((status, body['pending']['version'], body['pending']['rules']['rights']), (200, 1, 4))
        self.assertEqual(self.post('/v1/rules', dict(rules=ruleset({'rights': 3}), baseVersion=0), phone), (409, {'error': 'stale'}))
        status, body = self.post('/v1/rules-cancel', {}, phone)
        self.assertEqual((status, body['pending']), (200, None))

    def test_ac20_pair_locked_over_http(self):
        phone = self.post('/v1/register', dict(kind='android', install=INSTALL))[1]
        store = self.server.store
        store.set_guardian(phone['account'], 'abla@example.com')
        store.decide(token_of(self.sent[-1]), 'confirm')
        other = self.post('/v1/register', dict(kind='android', install='b'*64))[1]
        code = self.post('/v1/pair-code', token=other['token'])[1]['code']
        self.assertEqual(self.post('/v1/pair', dict(code=code, kind='android', install=INSTALL)), (403, {'error': 'locked'}))
        self.assertEqual(self.post('/v1/pair', dict(code=code, kind='chrome'))[0], 200)

    def test_ac22_bad_bodies(self):
        phone = self.post('/v1/register', dict(kind='android', install=INSTALL))[1]['token']
        self.assertEqual(self.raw('/v1/rules', b'{"rules":', phone), (400, {'error': 'invalid_body'}))
        self.assertEqual(self.raw('/v1/rules', b'[]', phone), (400, {'error': 'invalid_body'}))
        self.assertEqual(self.post('/v1/rules', dict(rules=ruleset(), baseVersion=0, extra=1), phone), (400, {'error': 'invalid_body'}))
        self.assertEqual(self.post('/v1/rules', dict(rules=ruleset(), baseVersion='0'), phone), (400, {'error': 'invalid_body'}))
        self.assertEqual(self.post('/v1/rules', dict(rules=ruleset(), baseVersion=0, dryRun=1), phone), (400, {'error': 'invalid_body'}))
        self.assertEqual(self.post('/v1/rules', dict(rules=ruleset({'extra': 1}), baseVersion=0), phone),
                         (400, {'error': 'invalid_rules', 'field': 'extra'}))
        self.assertEqual(self.post('/v1/rules', dict(rules=ruleset({'renewal': '23:00'}), baseVersion=0), phone),
                         (400, {'error': 'invalid_rules', 'field': 'renewal'}))
        big = json.dumps(dict(rules=ruleset(), baseVersion=0, pad='x'*4100)).encode()
        self.assertEqual(self.raw('/v1/rules', big, phone), (400, {'error': 'invalid_body'}))
        self.assertEqual(self.post('/v1/rules', None, None, 'GET')[0], 401)

    def test_ac23_approve_page_knows_extra(self):
        with urllib.request.urlopen(f'http://127.0.0.1:{self.server.server_port}/approve') as response:
            page = response.read().decode()
        kinds = re.search(r'const KIND = \{(.*?)\n\};', page, re.S).group(1)
        self.assertRegex(kinds, r"\n  extra: '[^']+'")
        for kind in app.DECISIONS:
            self.assertIn(f'\n  {kind}: ', kinds)

if __name__=='__main__': unittest.main()
