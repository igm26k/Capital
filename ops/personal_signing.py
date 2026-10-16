#!/usr/bin/env python3
"""Create/reuse a private local Android identity and build an APK. Never publishes."""
import argparse,contextlib,datetime,fcntl,hashlib,json,os,pathlib,re,secrets,shutil,stat,subprocess,sys,tempfile,uuid
ROOT=pathlib.Path(__file__).resolve().parents[1]

def protected(path,directory=False):
    path=pathlib.Path(path)
    if path.is_symlink() or (not path.is_dir() if directory else not path.is_file()) or stat.S_IMODE(path.stat().st_mode)&0o077:
        raise RuntimeError('Personal signing material must be private and must not be a symlink')
    return path

@contextlib.contextmanager
def locked(directory):
    directory=pathlib.Path(directory)
    if not directory.is_absolute():raise RuntimeError('Use an absolute private directory')
    directory.mkdir(mode=0o700,parents=True,exist_ok=True);protected(directory,True)
    fd=os.open(directory/'.personal-signing.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        yield directory

def run(args,environment=None,**kw):
    # Passwords are always environment references, never argv or tool output.
    try:return subprocess.run(args,env=environment,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw)
    except subprocess.CalledProcessError:raise RuntimeError('Personal signing operation failed; existing identity is preserved') from None

def identity_environment(directory):
    identity=protected(pathlib.Path(directory)/'identity',True)
    credentials=protected(identity/'credentials.json')
    key=protected(identity/'personal-release.p12')
    if credentials.stat().st_size>16384 or key.stat().st_size>1024*1024:raise RuntimeError('Invalid signing identity')
    body=json.loads(credentials.read_text())
    if set(body)!={'format','alias','store_password','key_password'} or body['format']!=1 or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,63}',body['alias']):raise RuntimeError('Invalid signing identity')
    if not isinstance(body['store_password'],str) or len(body['store_password'])<32 or body['key_password']!=body['store_password']:raise RuntimeError('Invalid signing credentials')
    environment=dict(os.environ)
    environment.update(ACCOUNTING_RELEASE_STORE_FILE=str(key),ACCOUNTING_RELEASE_STORE_PASSWORD=body['store_password'],ACCOUNTING_RELEASE_KEY_ALIAS=body['alias'],ACCOUNTING_RELEASE_KEY_PASSWORD=body['key_password'])
    return environment

def _ensure_identity(directory):
    identity=directory/'identity'
    if not identity.exists() and not identity.is_symlink():
        # A crash during first creation must not silently replace a possibly retained key.
        if list(directory.glob('.identity-*')):raise RuntimeError('Incomplete signing identity; inspect protected staging directory')
        temporary=pathlib.Path(tempfile.mkdtemp(prefix='.identity-',dir=directory))
        try:
            password=secrets.token_urlsafe(48);alias='accounting-personal'
            environment=dict(os.environ,ACCOUNTING_RELEASE_STORE_PASSWORD=password,ACCOUNTING_RELEASE_KEY_PASSWORD=password)
            key=temporary/'personal-release.p12'
            run(['keytool','-genkeypair','-noprompt','-storetype','PKCS12','-keystore',str(key),'-storepass:env','ACCOUNTING_RELEASE_STORE_PASSWORD','-keypass:env','ACCOUNTING_RELEASE_KEY_PASSWORD','-alias',alias,'-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Accounting Personal'],environment)
            key.chmod(0o600)
            credentials=temporary/'credentials.json'
            descriptor=os.open(credentials,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(descriptor,'w') as file:
                json.dump({'format':1,'alias':alias,'store_password':password,'key_password':password},file)
                file.flush();os.fsync(file.fileno())
            with key.open('rb') as file:os.fsync(file.fileno())
            if identity.exists() or identity.is_symlink():raise RuntimeError('Signing identity already exists')
            os.rename(temporary,identity)
            fd=os.open(directory,os.O_DIRECTORY|os.O_RDONLY)
            try:os.fsync(fd)
            finally:os.close(fd)
        finally:
            if temporary.exists():shutil.rmtree(temporary)
    environment=identity_environment(directory)
    certificate=run(['keytool','-exportcert','-storetype','PKCS12','-keystore',environment['ACCOUNTING_RELEASE_STORE_FILE'],'-storepass:env','ACCOUNTING_RELEASE_STORE_PASSWORD','-alias',environment['ACCOUNTING_RELEASE_KEY_ALIAS']],environment).stdout
    return {'identity':str(identity),'certificate_sha256':hashlib.sha256(certificate).hexdigest()}

def ensure_identity(directory):
    with locked(directory) as target:return _ensure_identity(target)

def build(directory,sdk):
    with locked(directory) as target:
        identity=_ensure_identity(target);environment=identity_environment(target);environment['ANDROID_HOME']=str(pathlib.Path(sdk).resolve())
        stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
        artifacts=target/'artifacts';artifacts.mkdir(mode=0o700,exist_ok=True);protected(artifacts,True)
        log=target/f'build-{stamp}.log'
        descriptor=os.open(log,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(descriptor,'w') as output:
            try:
                subprocess.run([str(ROOT/'android/gradlew'),'--no-daemon','-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8','--max-workers=1','assembleRelease','lintRelease'],cwd=ROOT/'android',env=environment,stdout=output,stderr=subprocess.STDOUT,check=True)
                apk=ROOT/'android/app/build/outputs/apk/release/app-release.apk'
                verifier=pathlib.Path(sdk)/'build-tools/36.0.0/apksigner'
                result=run([str(verifier),'verify','--print-certs',str(apk)])
                match=re.search(rb'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})',result.stdout)
                if not match or match[1].decode().lower()!=identity['certificate_sha256']:raise RuntimeError('APK signer differs from private identity')
                subprocess.run([sys.executable,str(ROOT/'scripts/check-android-release-trust.py'),str(sdk),str(apk)],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,check=True)
                destination=artifacts/f'accounting-personal-{stamp}.apk'
                with apk.open('rb') as source,destination.open('xb') as copy:
                    shutil.copyfileobj(source,copy);copy.flush();os.fsync(copy.fileno())
            except Exception:raise RuntimeError('Personal APK build failed; inspect protected build log. Existing signing key is unchanged') from None
        head=run(['git','-C',str(ROOT),'rev-parse','HEAD']).stdout.decode().strip()
        dirty=bool(run(['git','-C',str(ROOT),'status','--porcelain']).stdout)
        proof={'apk':str(destination),'sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),'certificate_sha256':identity['certificate_sha256'],'identity':identity['identity'],'source_head':head,'source_dirty':dirty,'signature_verified':True,'system_tls_verified':True,'published':False,'device_runtime_tested':False,'protected_log':str(log)}
        file=artifacts/f'accounting-personal-{stamp}.json'
        with file.open('x') as output:json.dump(proof,output,indent=2);output.write('\n')
        return proof

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--directory',default=str(ROOT/'ops/.runtime/personal-android'));parser.add_argument('--sdk',default=os.environ.get('ANDROID_HOME'));parser.add_argument('--identity-only',action='store_true');parser.add_argument('--plan',action='store_true');args=parser.parse_args();os.umask(0o077)
    if args.plan:print(json.dumps({'publishes':False,'builds':'signed APK','identity_reused':True,'mutations':False}));return 0
    try:
        if args.identity_only:result=ensure_identity(args.directory)
        else:
            if not args.sdk:raise RuntimeError('Provide Android SDK path')
            result=build(args.directory,args.sdk)
        print(json.dumps(result));return 0
    except Exception:print('Personal signing failed. Existing identity is never replaced; inspect private directory and build log.',file=sys.stderr);return 1
if __name__=='__main__':sys.exit(main())
