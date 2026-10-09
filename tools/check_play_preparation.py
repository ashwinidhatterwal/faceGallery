#!/usr/bin/env python3
"""Static publishing checks; --submission additionally requires owner-supplied assets."""
import argparse,json,struct,re,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--submission',action='store_true');args=p.parse_args()
errors=[];listing=json.loads((root/'publishing/store-listing.json').read_text())
for key,limit in [('title',30),('short_description',80),('full_description',4000),('release_notes',500)]:
 if not listing[key].strip() or len(listing[key])>limit:errors.append(f'Invalid {key} length')
def png(path):
 data=path.read_bytes()
 if data[:8]!=b'\x89PNG\r\n\x1a\n' or data[12:16]!=b'IHDR':raise ValueError('Invalid PNG: '+str(path))
 return struct.unpack('>IIBB',data[16:26])
for name,dimensions,color in [('play-icon-512.png',(512,512),6),('feature-graphic-1024x500.png',(1024,500),2)]:
 try:
  w,h,depth,ctype=png(root/'publishing/assets'/name)
  if (w,h)!=dimensions or depth!=8 or ctype!=color:errors.append('Invalid dimensions/format: '+name)
 except (OSError,ValueError) as e:errors.append(str(e))
config=(root/'app/build.gradle.kts').read_text()
if int(re.search(r'targetSdk\s*=\s*(\d+)',config).group(1))<36:errors.append('Target API below current Play requirement')
policy=(root/'app/src/main/assets/PRIVACY.txt').read_text()
if 'Face Gallery' not in policy or 'Retention and deletion' not in policy:errors.append('Incomplete privacy policy')
if not (root/'docs/privacy.html').is_file():errors.append('Public policy source missing')
if args.submission:
 if not re.fullmatch(r'[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+',listing['support_email']):errors.append('Public support email missing/invalid')
 if not listing['privacy_policy_url'].startswith('https://'):errors.append('Public HTTPS privacy URL missing')
 if 'Draft:' in (root/'docs/privacy.html').read_text():errors.append('Privacy page still draft')
 shots=list((root/'publishing/screenshots').glob('*.png'))+list((root/'publishing/screenshots').glob('*.jpg'))
 if len(shots)<2:errors.append('At least two real app screenshots needed')
 print('This checks supplied files only: verify public URL, signed build, Data safety, FGS video and physical acceptance separately.')
if errors:
 for error in errors:print('BLOCKED:',error)
 sys.exit(1)
print('Static publishing checks passed'+(' (owner assets supplied)' if args.submission else ' (technical preparation only)'))
