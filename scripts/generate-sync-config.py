import json
import os
from pathlib import Path
# BIRNEFES_LOCAL_ONLY=1, or BIRNEFES_SYNC_CONFIG naming a missing file, builds a local-only APK.
# An APK never carries a token: each install registers and keeps its token in app storage.
# BIRNEFES_TEST_HTTP=1 allows an http:// URL and the config's token, for a local test server only.
DEFAULT_URL='https://zmanga.zerohidz.com/bir-nefes'
explicit=os.environ.get('BIRNEFES_SYNC_CONFIG')
source=Path(explicit or '.build/sync-config.json')
local=os.environ.get('BIRNEFES_LOCAL_ONLY')=='1' or (explicit and not source.exists())
config={} if local or not source.exists() else json.loads(source.read_text())
url='' if local else config.get('url') or DEFAULT_URL
token=config.get('androidToken','') if os.environ.get('BIRNEFES_TEST_HTTP')=='1' and not local else ''
if url and not url.startswith('https://') and not (os.environ.get('BIRNEFES_TEST_HTTP')=='1' and url.startswith('http://')):
    raise ValueError('Sync requires HTTPS')
Path('.build/generated/SyncConfig.java').write_text('''package com.zerohidz.whatsapppause;
public final class SyncConfig {
 public static final boolean ENABLED = %s;
 public static final String URL = %s;
 public static final String TOKEN = %s;
}
''' % (str(bool(url)).lower(),json.dumps(url.rstrip('/')),json.dumps(token)))
