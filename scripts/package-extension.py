import json,re,shutil,zipfile
from pathlib import Path
# The package carries no credential: each install pairs from the popup, so it can be shared.
source=Path('extension/config.js').read_text()
url=re.search(r"url: '([^']+)'",source).group(1)
target=Path('artifacts/BirNefes-Chrome')
shutil.rmtree(target,ignore_errors=True)
target.mkdir(parents=True)
for p in Path('extension').iterdir():
 if p.is_file(): shutil.copy2(p,target/p.name)
(target/'config.js').write_text('const BIRNEFES_CONFIG = '+json.dumps(dict(url=url,token=''))+';\n')
version=json.loads(Path('extension/manifest.json').read_text())['version']
with zipfile.ZipFile(f'artifacts/BirNefes-Chrome-{version}.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in target.iterdir(): z.write(p,p.name)
print(f'Extension packaged in artifacts/BirNefes-Chrome-{version}.zip')
