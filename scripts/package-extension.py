import json,shutil,zipfile
from pathlib import Path
config=json.loads(Path('.build/sync-config.json').read_text())
target=Path('artifacts/BirNefes-Chrome')
target.mkdir(parents=True,exist_ok=True)
for p in Path('extension').iterdir():
 if p.is_file(): shutil.copy2(p,target/p.name)
(target/'config.js').write_text('const BIRNEFES_CONFIG = '+json.dumps(dict(url=config['url'],token=config['chromeToken']))+';\n')
(target/'config.js').chmod(0o600)
version=json.loads(Path('extension/manifest.json').read_text())['version']
with zipfile.ZipFile(f'artifacts/BirNefes-Chrome-{version}.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in target.iterdir(): z.write(p,p.name)
print('Private extension packaged in artifacts/BirNefes-Chrome')
