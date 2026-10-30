import concurrent.futures
import tempfile
import sqlite3
import unittest
from datetime import datetime
from app import Store, ZONE

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
        wait = self.store.request(device,'enter',attempt,t)
        self.assertEqual(wait['state'],'waiting')
        early = self.store.request(device,'complete',attempt,t+29999)
        self.assertEqual(early['state'],'waiting')
        return self.store.request(device,'complete',attempt,t+30000)
    def test_shared_window_and_no_extension(self):
        first = self.start('phone',self.t)
        second = self.store.request('chrome','enter','attempt-0000000002',self.t+180000)
        self.assertEqual(first['expiresAt'],second['expiresAt'])
        self.assertEqual(second['used'],1)
        self.assertEqual(second['state'],'allowed')
    def test_atomic_simultaneous_completion(self):
        for d in ('phone','chrome'):
            self.store.request(d,'enter','attempt-0000000001',self.t)
        with concurrent.futures.ThreadPoolExecutor() as pool:
            results = list(pool.map(lambda d:self.store.request(d,'complete','attempt-0000000001',self.t+30000),('phone','chrome')))
        self.assertEqual([r['used'] for r in results],[1,1])
        self.assertEqual(results[0]['session'],results[1]['session'])
    def test_five_uses_and_replay(self):
        for n in range(5):
            r=self.start('phone',self.t+n*931000,f'attempt-00000000{n:02}')
            self.assertEqual(r['used'],n+1)
        r=self.store.request('chrome','enter','attempt-0000000009',r['expiresAt'])
        self.assertEqual(r['state'],'exhausted')
    def test_replay_does_not_allocate(self):
        r=self.start('phone',self.t)
        r=self.store.request('phone','complete','attempt-0000000001',r['expiresAt'])
        self.assertEqual(r['state'],'expired')
        self.assertEqual(r['used'],1)
    def test_late_window_is_full_five_minutes(self):
        t=at('2026-10-01T23:58:00')
        r=self.start('phone',t)
        self.assertEqual(r['expiresAt'],t+330000)
        r=self.store.request('chrome','status',now=t+180000)
        self.assertEqual(r['state'],'allowed')
        r=self.store.request('chrome','enter','attempt-0000000002',t+330000)
        self.assertEqual(r['state'],'exhausted')
    def test_cutoff_crossing_and_absence(self):
        r=self.start('phone',at('2026-10-01T22:27:00'))
        self.assertEqual(r['expiresAt'],at('2026-10-01T22:35:00'))
        r=self.store.request('chrome','enter','attempt-0000000002',at('2026-10-02T00:01:00'))
        self.assertEqual(r['state'],'exhausted')
    def test_renewal_0300_and_restart(self):
        self.start('phone',at('2026-10-01T23:00:00'))
        self.store=Store(self.store.path)
        r=self.store.request('chrome','status',now=at('2026-10-02T02:59:59'))
        self.assertEqual(r['state'],'exhausted')
        r=self.store.request('chrome','status',now=at('2026-10-02T03:00:00'))
        self.assertEqual((r['state'],r['remaining'],r['used']),('idle',5,0))
    def test_active_cap_renewal(self):
        r=self.start('phone',at('2026-10-02T02:58:00'))
        self.assertEqual(r['expiresAt'],at('2026-10-02T03:00:00'))
    def test_no_challenge_no_allocation(self):
        r=self.store.request('phone','complete','attempt-0000000001',self.t)
        self.assertEqual((r['state'],r['used']),('expired',0))
    def test_other_device_cannot_complete_challenge(self):
        self.store.request('phone','enter','attempt-0000000001',self.t)
        r=self.store.request('chrome','complete','attempt-0000000001',self.t+30000)
        self.assertEqual(r['state'],'expired')

    def test_emergency_bypasses_wait_without_daily_use(self):
        self.store.request('phone','enter','normal-0000000001',self.t)
        r=self.store.request('phone','emergency','urgent-0000000001',self.t+1000)
        self.assertEqual((r['state'],r['used'],r['emergencyRemaining'],r['sessionKind']),('allowed',0,3,'emergency'))
        self.assertEqual(r['expiresAt'],self.t+901000)
        other=self.store.request('chrome','enter','normal-0000000002',self.t+5000)
        self.assertEqual((r['expiresAt'],r['session']),(other['expiresAt'],other['session']))

    def test_four_emergencies_shared_and_replay_safe(self):
        for n in range(4):
            r=self.store.request('phone' if n%2 else 'chrome','emergency',f'urgent-00000000{n:02}',self.t+n*900001)
            self.assertEqual(r['emergencyRemaining'],3-n)
        r=self.store.request('phone','emergency','urgent-0000000099',r['expiresAt'])
        self.assertEqual((r['state'],r['used']),('emergency_exhausted',0))
        r=self.store.request('chrome','emergency','urgent-0000000000',self.t+4000000)
        self.assertEqual(r['state'],'expired')

    def test_emergency_idempotent_after_restart(self):
        first=self.store.request('phone','emergency','urgent-0000000001',self.t)
        self.store=Store(self.store.path)
        second=self.store.request('phone','emergency','urgent-0000000001',self.t+500)
        self.assertEqual(first['expiresAt'],second['expiresAt'])
        self.assertEqual(second['emergencyRemaining'],3)
        expired=self.store.request('phone','emergency','urgent-0000000001',first['expiresAt'])
        self.assertEqual((expired['state'],expired['emergencyRemaining']),('expired',3))

    def test_simultaneous_emergency_allocates_once(self):
        with concurrent.futures.ThreadPoolExecutor() as pool:
            results=list(pool.map(lambda d:self.store.request(d,'emergency','urgent-0000000001',self.t),('phone','chrome')))
        self.assertEqual([r['emergencyRemaining'] for r in results],[3,3])
        self.assertEqual(results[0]['session'],results[1]['session'])

    def test_emergency_during_normal_does_not_charge_or_extend(self):
        first=self.start('phone',self.t)
        second=self.store.request('chrome','emergency','urgent-0000000001',self.t+60000)
        self.assertEqual(second['emergencyRemaining'],4)
        self.assertEqual(first['expiresAt'],second['expiresAt'])

    def test_night_emergency_full_duration_and_daily_unchanged(self):
        t=at('2026-10-01T22:29:00')
        first=self.store.request('phone','emergency','urgent-0000000001',t)
        r=self.store.request('phone','status',now=t+360000)
        self.assertEqual((r['state'],r['remaining'],r['used']),('allowed',1,0))
        self.assertEqual(r['expiresAt'],t+900000)
        daily=self.start('phone',first['expiresAt'])
        self.assertEqual((daily['used'],daily['remaining'],daily['emergencyRemaining']),(1,0,3))
        urgent=self.store.request('phone','emergency','urgent-0000000002',daily['expiresAt'])
        self.assertEqual((urgent['state'],urgent['used'],urgent['remaining']),('allowed',1,0))

    def test_month_resets_at_0300_not_midnight_and_active_survives(self):
        t=at('2026-10-31T23:59:00')
        self.store.request('phone','emergency','urgent-0000000001',t)
        r=self.store.request('phone','status',now=at('2026-11-01T00:01:00'))
        self.assertEqual((r['emergencyRemaining'],r['emergencyMonth']),(3,'2026-10'))
        first=self.store.request('phone','emergency','urgent-0000000002',at('2026-11-01T02:58:00'))
        r=self.store.request('chrome','status',now=at('2026-11-01T03:00:00'))
        self.assertEqual((r['emergencyRemaining'],r['emergencyMonth'],r['remaining']),(4,'2026-11',5))
        self.assertEqual(r['expiresAt'],first['expiresAt'])
        self.assertEqual(r['state'],'allowed')

    def test_upgrade_preserves_legacy_quota(self):
        path=self.temp.name+'/legacy.db'
        with sqlite3.connect(path) as db:
            db.executescript("CREATE TABLE quota(id INTEGER PRIMARY KEY,day TEXT,used INTEGER,night INTEGER,end INTEGER,session INTEGER); INSERT INTO quota VALUES(1,'2026-10-01',3,0,0,9);")
        upgraded=Store(path)
        r=upgraded.request('phone','status',now=self.t)
        self.assertEqual((r['used'],r['remaining'],r['session'],r['emergencyRemaining']),(3,2,9,4))
        upgraded=Store(path)
        self.assertEqual(upgraded.request('phone','status',now=self.t)['used'],3)

    def test_video_free_windows_and_night(self):
        for hour, expected, mode in [('04:59:59','exhausted','night'),('05:00:00','allowed','free'),('07:59:59','allowed','free'),('08:00:00','allowed','metered'),('16:59:59','allowed','metered'),('17:00:00','allowed','free'),('22:29:59','allowed','free'),('22:30:00','exhausted','night')]:
            r=self.store.request('chrome','video_enter','video-0000000001',at('2026-10-01T'+hour))
            self.assertEqual((r['state'],r['mode']),(expected,mode),hour)
        daily=self.store.request('phone','status',now=at('2026-10-01T22:30:01'))
        self.assertEqual(daily['used'],0)

    def test_video_three_second_visits_accumulate(self):
        for n in range(3):
            t=self.t+n*60000
            key=f'video-00000000{n:02}'
            self.store.request('chrome','video_enter',key,t)
            r=self.store.request('chrome','video_stop',key,t+3000)
            self.assertEqual(r['videoRemainingMs'],900000-(n+1)*3000)
        r=self.store.request('chrome','video_status',now=self.t+300000)
        self.assertEqual(r['videoRemainingMs'],891000)
        self.assertEqual(self.store.request('phone','status',now=self.t+300000)['used'],0)

    def test_video_fifteen_minutes_then_free_at_17(self):
        self.store.request('chrome','video_enter','video-0000000001',self.t)
        for n in range(1,451):
            r=self.store.request('chrome','video_tick','video-0000000001',self.t+n*2000)
        self.assertEqual((r['state'],r['videoRemainingMs']),('exhausted',0))
        self.assertEqual(r['expiresAt'],self.t+900000)
        r=self.store.request('chrome','video_enter','video-0000000002',at('2026-10-01T17:00:00'))
        self.assertEqual((r['state'],r['mode']),('allowed','free'))

    def test_video_lost_stop_only_charges_last_lease(self):
        self.store.request('chrome','video_enter','video-0000000001',self.t)
        r=self.store.request('chrome','video_status',now=self.t+120000)
        self.assertEqual(r['videoRemainingMs'],895000)
        again=self.store.request('chrome','video_status',now=self.t+240000)
        self.assertEqual(again['videoRemainingMs'],895000)

    def test_video_two_tabs_charge_union_and_stop_independently(self):
        self.store.request('chrome','video_enter','video-0000000001',self.t)
        self.store.request('chrome','video_enter','video-0000000002',self.t+1000)
        self.store.request('chrome','video_stop','video-0000000001',self.t+3000)
        r=self.store.request('chrome','video_stop','video-0000000002',self.t+4000)
        self.assertEqual(r['videoRemainingMs'],896000)
        replay=self.store.request('chrome','video_tick','video-0000000001',self.t+5000)
        self.assertEqual(replay['state'],'expired')
        self.assertEqual(self.store.request('chrome','video_status',now=self.t+20000)['videoRemainingMs'],896000)

    def test_video_lease_capped_at_17_and_small_remaining(self):
        t=at('2026-10-01T16:59:58')
        r=self.store.request('chrome','video_enter','video-0000000001',t)
        self.assertEqual(r['expiresAt'],at('2026-10-01T17:00:00'))
        r=self.store.request('chrome','video_status',now=t+10000)
        self.assertEqual(r['videoRemainingMs'],898000)
        self.store.request('chrome','video_status',now=at('2026-10-02T10:00:00'))
        with self.store.connect() as db: db.execute('UPDATE video_quota SET spent=899001')
        r=self.store.request('chrome','video_enter','video-0000000002',at('2026-10-02T10:00:00'))
        self.assertEqual(r['expiresAt']-r['serverNow'],999)

    def test_video_emergency_uses_shared_monthly_not_daily(self):
        t=at('2026-10-01T23:00:00')
        r=self.store.request('chrome','video_emergency','urgent-0000000001',t)
        self.assertEqual((r['state'],r['mode'],r['emergencyRemaining']),('allowed','emergency',3))
        phone=self.store.request('phone','enter','phone-0000000001',t+1000)
        self.assertEqual((phone['expiresAt'],phone['used']),(r['expiresAt'],0))
        r=self.store.request('chrome','video_enter','video-0000000001',t+900000)
        self.assertEqual((r['state'],r['mode']),('exhausted','night'))

    def test_normal_grant_does_not_unlock_video_night(self):
        t=at('2026-10-01T23:00:00')
        self.start('phone',t)
        r=self.store.request('chrome','video_enter','video-0000000001',t+60000)
        self.assertEqual(r['state'],'exhausted')
        r=self.store.request('chrome','video_emergency','urgent-0000000001',t+61000)
        self.assertEqual((r['state'],r['mode'],r['emergencyRemaining']),('allowed','emergency',3))

    def test_emergency_stops_video_metering_across_clients(self):
        self.store.request('chrome','video_enter','video-0000000001',self.t)
        self.store.request('phone','emergency','urgent-0000000001',self.t+1000)
        r=self.store.request('chrome','video_tick','video-0000000001',self.t+3000)
        self.assertEqual((r['mode'],r['videoRemainingMs']),('emergency',899000))
        r=self.store.request('chrome','video_tick','video-0000000001',self.t+6000)
        self.assertEqual(r['videoRemainingMs'],899000)

    def test_video_renewal_03_and_restart(self):
        self.store.request('chrome','video_enter','video-0000000001',self.t)
        self.store.request('chrome','video_stop','video-0000000001',self.t+3000)
        self.store=Store(self.store.path)
        r=self.store.request('chrome','video_status',now=at('2026-10-02T02:59:59'))
        self.assertEqual(r['videoRemainingMs'],897000)
        r=self.store.request('chrome','video_status',now=at('2026-10-02T03:00:00'))
        self.assertEqual(r['videoRemainingMs'],900000)

    def test_existing_grant_keeps_deadline_during_duration_upgrade(self):
        with self.store.connect() as db:
            db.execute("UPDATE quota SET day='2026-10-01',used=2,end=?,session=12,kind='normal'", (self.t+600000,))
        self.store=Store(self.store.path)
        r=self.store.request('chrome','enter','upgrade-0000000001',self.t+120000)
        self.assertEqual((r['used'],r['expiresAt']),(2,self.t+600000))
        newer=self.start('phone',r['expiresAt']+1000)
        self.assertEqual(newer['expiresAt']-newer['serverNow'],900000)

    def test_video_upgrade_keeps_already_spent_time(self):
        with self.store.connect() as db:
            db.execute("UPDATE video_quota SET day='2026-10-01',spent=120123,accounted=?", (self.t,))
        self.store=Store(self.store.path)
        r=self.store.request('chrome','video_status',now=self.t)
        self.assertEqual(r['videoRemainingMs'],779877)

if __name__=='__main__': unittest.main()
