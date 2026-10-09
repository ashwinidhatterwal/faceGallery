"""Fail release checks on network permission, debug flags, changed model, or bad native alignment."""
import argparse,hashlib,json,os,re,struct,subprocess,zipfile
from pathlib import Path
MODEL='35e2fc149c76efefefbc264fcb5153a467a1b490a82001aba19bd7fc432ec235'

def elf_segments(data):
    if data[:4]!=b'\x7fELF' or data[4]!=2:raise ValueError('Expected ELF64')
    endian={1:'<',2:'>'}.get(data[5])
    if not endian:raise ValueError('Invalid ELF byte order')
    offset=struct.unpack_from(endian+'Q',data,32)[0]
    size,count=struct.unpack_from(endian+'HH',data,54)
    if size<56 or not count or offset+size*count>len(data):raise ValueError('Invalid ELF program headers')
    return [struct.unpack_from(endian+'IIQQQQQQ',data,offset+i*size) for i in range(count)]

def verify_native(data):
    segments=elf_segments(data);loads=[s for s in segments if s[0]==1]
    if not loads:raise ValueError('No ELF LOAD segments')
    for _,_,offset,address,_,_,_,align in loads:
        if align<16384 or align&(align-1) or offset%align!=address%align:raise ValueError('Native LOAD segment is not 16 KB compatible')
    # Android rounds RELRO to pages. Padding is safe only when no writable data is covered.
    # A non-aligned RELRO end alone is insufficient to identify an unsafe binary.
    for segment in segments:
        if segment[0]!=0x6474e552:continue
        start,end=segment[3],segment[3]+segment[6]
        protected_start=start//16384*16384;protected_end=(end+16383)//16384*16384
        for load in loads:
            if not load[1]&2:continue
            overlap_start=max(protected_start,load[3]);overlap_end=min(protected_end,load[3]+load[6])
            if overlap_start<overlap_end and (overlap_start<start or overlap_end>end):
                raise ValueError('Rounded RELRO protection overlaps writable data on 16 KB pages')

def verify_archives(bundle,apk,signed=False):
    report={'model_sha256':MODEL,'native_libraries':[],'signed_required':signed}
    for path in (bundle,apk):
        with zipfile.ZipFile(path) as z:
            if z.testzip():raise ValueError('Corrupt archive')
            names=z.namelist()
            model=next(n for n in names if n.endswith('/mobilefacenet.tflite'))
            if hashlib.sha256(z.read(model)).hexdigest()!=MODEL:raise ValueError('Unexpected recognition model')
            if not any(n.endswith('/PRIVACY.txt') for n in names):raise ValueError('Missing offline privacy policy')
            libraries=[n for n in names if n.endswith('.so') and ('/arm64-v8a/' in n or '/x86_64/' in n)]
            if not libraries:raise ValueError('Missing 64-bit native libraries')
            for n in libraries:
                verify_native(z.read(n));report['native_libraries'].append(str(path)+':'+n)
            if signed and not any(n.startswith('META-INF/') and n.endswith(('.RSA','.EC','.DSA')) for n in names) and path.suffix=='.aab':raise ValueError('Unsigned Play bundle')
    sdk=Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))
    tools=sdk/'build-tools/35.0.0';aapt=tools/'aapt'
    badging=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
    for forbidden in ('android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','android.permission.MANAGE_EXTERNAL_STORAGE','application-debuggable'):
        if forbidden in badging:raise ValueError('Unexpected release manifest entry: '+forbidden)
    if "android.permission.READ_MEDIA_VIDEO" not in badging:raise ValueError("Missing video library permission")
    config=(Path(__file__).resolve().parents[1]/'app/build.gradle.kts').read_text()
    version=re.search(r'versionCode\s*=\s*(\d+)',config).group(1)
    if "name='com.mosaic.gallery'" not in badging or f"versionCode='{version}'" not in badging:raise ValueError('Unexpected application ID/version')
    subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(apk)],check=True,stdout=subprocess.DEVNULL)
    if signed:
        certificate=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(apk)],text=True)
        if '11bb3f4c3e4d2b7826b27ed89591e6b54adeadae4ab3745c812b63d978782f09' in certificate:
            raise ValueError('Release is signed with the public development key')
        subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin'/('jarsigner.exe' if os.name=='nt' else 'jarsigner')),'-verify',str(bundle)],check=True,stdout=subprocess.DEVNULL)
    report['apk_sha256']=hashlib.sha256(apk.read_bytes()).hexdigest();report['bundle_sha256']=hashlib.sha256(bundle.read_bytes()).hexdigest()
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('bundle',type=Path);p.add_argument('apk',type=Path);p.add_argument('--signed',action='store_true');args=p.parse_args()
    print(json.dumps(verify_archives(args.bundle,args.apk,args.signed),indent=2))
