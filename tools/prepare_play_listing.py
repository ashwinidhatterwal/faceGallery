#!/usr/bin/env python3
"""Generate a public, self-contained privacy page from the shipped policy."""
import argparse, html, json, re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--email');p.add_argument('--developer');p.add_argument('--privacy-url');args=p.parse_args()
path=root/'publishing/store-listing.json';listing=json.loads(path.read_text())
if args.email:
    if not re.fullmatch(r'[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+',args.email):p.error('Provide a valid public support email')
    listing['support_email']=args.email
if args.developer:
    if not args.developer.strip():p.error('Developer display name cannot be blank')
    listing['developer_name']=args.developer.strip()
if args.privacy_url:
    from urllib.parse import urlparse
    url=urlparse(args.privacy_url)
    if url.scheme!='https' or not url.hostname or url.username or url.password:p.error('Use a public HTTPS privacy URL without credentials')
    listing['privacy_policy_url']=args.privacy_url
for key,limit in [('title',30),('short_description',80),('full_description',4000),('release_notes',500)]:
    if len(listing[key])>limit:p.error(f'{key} exceeds {limit} characters')
if any((args.email,args.developer,args.privacy_url)):
    path.write_text(json.dumps(listing,indent=2,ensure_ascii=False)+'\n')
policy=root/'app/src/main/assets/PRIVACY.txt'
text=policy.read_text();text=re.sub(r'^Developer: .*$', 'Developer: '+listing['developer_name'],text,flags=re.M)
email=listing['support_email']
if email:
    text=text.replace('For support or privacy questions, use the developer contact published on Face Gallery\'s Google Play listing.','For support or privacy questions, contact '+email+'.')
    # Keep the public and in-app policy aligned; build again after changing this file.
    policy.write_text(text)
paragraphs=text.split('\n\n');body=''
for i,block in enumerate(paragraphs):
    lines=block.splitlines();heading=lines[0]
    if i==0:body+='<h1>'+html.escape(heading)+'</h1><p>'+'<br>'.join(html.escape(x) for x in lines[1:])+'</p>'
    else:body+='<h2>'+html.escape(heading)+'</h2><p>'+html.escape('\n'.join(lines[1:]))+'</p>'
warning='' if email else '<aside>Draft: the developer must add a public support email before publishing this page.</aside>'
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Face Gallery Privacy Policy</title><style>body{margin:0;background:#f7f5ed;color:#163e40;font:17px/1.65 system-ui,sans-serif}main{max-width:780px;margin:auto;padding:48px 24px}h1{font-size:34px;line-height:1.2}h2{font-size:22px;margin-top:32px}p{white-space:pre-line}aside{padding:16px;border:1px solid #d2a449;background:#fff2cc}</style><main>'''+warning+body+'</main></html>\n'
(root/'docs').mkdir(exist_ok=True);(root/'docs/privacy.html').write_text(page)
print('Privacy page generated. Public support fields complete:',bool(email and listing['privacy_policy_url']))
print('Character counts:',{k:len(listing[k]) for k in ['title','short_description','full_description','release_notes']})
