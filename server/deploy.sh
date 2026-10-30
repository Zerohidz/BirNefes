#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import hashlib,json,pathlib,secrets,os
p=pathlib.Path('.build/sync-config.json')
if not p.exists():
 p.write_text(json.dumps(dict(url='https://zmanga.zerohidz.com/bir-nefes',androidToken=secrets.token_urlsafe(32),chromeToken=secrets.token_urlsafe(32))))
 p.chmod(0o600)
c=json.loads(p.read_text())
h=pathlib.Path('.build/devices.json')
h.write_text(json.dumps([dict(id=d,token_hash=hashlib.sha256(c[k].encode()).hexdigest()) for d,k in [('android','androidToken'),('chrome','chromeToken')]]))
h.chmod(0o600)
PY
ssh -o BatchMode=yes zmanga 'install -d -m 0755 /opt/bir-nefes; id bir-nefes >/dev/null 2>&1 || useradd --system --home-dir /var/lib/bir-nefes --shell /usr/sbin/nologin bir-nefes; install -d -m 0750 -o root -g bir-nefes /etc/bir-nefes'
scp -q server/app.py server/bir-nefes.service zmanga:/opt/bir-nefes/
scp -q .build/devices.json zmanga:/etc/bir-nefes/devices.json
ssh -o BatchMode=yes zmanga 'bash -s' <<'REMOTE'
set -euo pipefail
chown root:bir-nefes /etc/bir-nefes/devices.json
chmod 0640 /etc/bir-nefes/devices.json
install -m 0644 /opt/bir-nefes/bir-nefes.service /etc/systemd/system/bir-nefes.service
python3 - <<'PY'
import pathlib,shutil,time
p=pathlib.Path('/etc/nginx/sites-available/zmanga')
# Use the real target of sites-enabled, which may not be sites-available.
p=pathlib.Path('/etc/nginx/sites-enabled/zmanga').resolve()
s=p.read_text()
if 'location ^~ /bir-nefes/' not in s:
 backup=str(p)+'.bir-nefes-backup-'+str(int(time.time()))
 shutil.copy2(p,backup)
 marker='location / {'
 assert marker in s, 'Expected existing root proxy location'
 block='''location ^~ /bir-nefes/ {
          limit_req zone=bir_nefes burst=15 nodelay;
          client_max_body_size 1k;
          proxy_pass http://127.0.0.1:8765/;
          proxy_set_header Host $host;
          proxy_connect_timeout 3s;
          proxy_read_timeout 15s;
      }
      '''
 p.write_text(s.replace(marker,block+marker,1))
pathlib.Path('/etc/nginx/conf.d/bir-nefes-rate.conf').write_text('limit_req_zone $binary_remote_addr zone=bir_nefes:1m rate=3r/s;\n')
PY
nginx -t
systemctl daemon-reload
systemctl enable --now bir-nefes
systemctl restart bir-nefes
systemctl reload nginx
systemctl is-active bir-nefes
REMOTE
