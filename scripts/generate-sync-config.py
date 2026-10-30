import json
import os
from pathlib import Path
source=Path(os.environ.get('BIRNEFES_SYNC_CONFIG','.build/sync-config.json'))
config=json.loads(source.read_text()) if source.exists() else {}
url=config.get('url','')
token=config.get('androidToken','')
if url and not url.startswith('https://'):
    raise ValueError('Sync requires HTTPS')
Path('.build/generated/SyncConfig.java').write_text('''package com.zerohidz.whatsapppause;
public final class SyncConfig {
 public static final boolean ENABLED = %s;
 public static final String URL = %s;
 public static final String TOKEN = %s;
}
''' % (str(bool(url and token)).lower(),json.dumps(url),json.dumps(token)))
