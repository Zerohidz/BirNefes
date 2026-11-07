import json,re,shutil,subprocess,tempfile,zipfile
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
# The signed package for the Chromium force-install. The key fixes the extension ID, so it must be kept.
key=Path('.build/extension.pem').resolve()
extension_id='fohapjjlilnlhnpakacmhbmebpodcoop'
with tempfile.TemporaryDirectory() as work:
 root=Path(work)/'BirNefes-Chrome'
 shutil.copytree(target,root)
 subprocess.run(['chromium','--headless=new','--no-message-box',f'--user-data-dir={work}/profile',f'--pack-extension={root}',f'--pack-extension-key={key}'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 shutil.move(f'{root}.crx',f'artifacts/BirNefes-Chrome-{version}.crx')
Path('artifacts/updates.xml').write_text(f"""<?xml version='1.0' encoding='UTF-8'?>
<gupdate xmlns='http://www.google.com/update2/response' protocol='2.0'>
  <app appid='{extension_id}'>
    <updatecheck codebase='https://birnefes.zerohidz.com/BirNefes-Chrome-{version}.crx' version='{version}' />
  </app>
</gupdate>
""")
print(f'Signed package in artifacts/BirNefes-Chrome-{version}.crx')
