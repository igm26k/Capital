#!/usr/bin/env python3
"""Daily/monthly retention with verification, a process lock and optional encrypted offsite copies."""
import argparse,stat,datetime as dt,fcntl,hashlib,json,os,pathlib,re,subprocess,sys,tempfile,uuid
import backup_signing
UTC=dt.timezone.utc

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda:file.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def atomic_json(path,value):
    descriptor,name=tempfile.mkstemp(prefix='.status-',dir=path.parent)
    try:
        with os.fdopen(descriptor,'w') as file:
            json.dump(value,file);file.write('\n');file.flush();os.fsync(file.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name):os.unlink(name)

def verify(path):
    checksum=path.with_name(path.name+'.sha256')
    if path.is_symlink() or checksum.is_symlink() or not path.is_file() or not checksum.is_file():raise RuntimeError('Backup or checksum unavailable')
    if checksum.stat().st_size>8192:raise RuntimeError('Invalid backup checksum')
    expected=checksum.read_text().split()[0]
    if not re.fullmatch('[0-9a-f]{64}',expected) or digest(path)!=expected:raise RuntimeError('Backup integrity mismatch')
    return expected

def checksum_file(path,sha):
    target=path.with_name(path.name+'.sha256')
    descriptor,temporary=tempfile.mkstemp(prefix='.checksum-',dir=path.parent)
    try:
        with os.fdopen(descriptor,'w') as file:
            file.write(f'{sha}  {path.name}\n');file.flush();os.fsync(file.fileno())
        os.link(temporary,target)
    finally:pathlib.Path(temporary).unlink(missing_ok=True)

def monthly_copy(daily,monthly):
    checksum=monthly.with_name(monthly.name+'.sha256')
    if not monthly.exists() and not monthly.is_symlink():
        if checksum.exists() or checksum.is_symlink():raise RuntimeError('Orphan monthly checksum must be inspected')
        os.link(daily,monthly)
    if not checksum.exists() and not checksum.is_symlink():
        # Recover a crash between the two links only from a verified identical daily inode.
        for candidate in monthly.parent.iterdir():
            if candidate==monthly or candidate.is_symlink() or not candidate.name.startswith(monthly.name.split('-monthly-')[0]+'-daily-') or not candidate.name.endswith('.dump'):continue
            if candidate.is_file() and os.path.samefile(candidate,monthly):
                checksum_file(monthly,verify(candidate));break
    verify(monthly)

def encrypt_copy(source,destination,recipients,signing_key=None):
    descriptor,temporary=tempfile.mkstemp(prefix='.encrypted-',dir=destination.parent)
    try:
        with os.fdopen(descriptor,'wb') as file:
            subprocess.run(['age','-R',str(recipients),str(source)],check=True,stdout=file,stderr=subprocess.DEVNULL)
            file.flush();os.fsync(file.fileno())
        os.link(temporary,destination)
        checksum_file(destination,digest(destination))
        if signing_key:backup_signing.sign(destination,signing_key)
    finally:pathlib.Path(temporary).unlink(missing_ok=True)

def retain(directory,profile,now):
    # Only this cycle's exact daily/monthly naming scheme is eligible. Preserve unknown files and links.
    cutoff_daily=now-dt.timedelta(days=30)
    cutoff_month=now.year*12+now.month-1-11
    removed=0
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file():continue
        daily=re.fullmatch(re.escape(profile)+r'-daily-(\d{8}T\d{6}Z)-[0-9a-f]{8}\.dump(?:\.age)?',path.name)
        monthly=re.fullmatch(re.escape(profile)+r'-monthly-(\d{4})-(\d{2})\.dump(?:\.age)?',path.name)
        expired=False
        try:
            if daily:expired=dt.datetime.strptime(daily[1],'%Y%m%dT%H%M%SZ').replace(tzinfo=UTC)<cutoff_daily
            elif monthly:
                year,month=int(monthly[1]),int(monthly[2]);dt.date(year,month,1)
                expired=year*12+month-1<cutoff_month
        except ValueError:continue
        if expired:
            checksum=path.with_name(path.name+'.sha256')
            signature=checksum.with_name(checksum.name+'.sig')
            if checksum.is_symlink() or signature.is_symlink() or checksum.is_dir() or signature.is_dir():continue
            path.unlink();checksum.unlink(missing_ok=True);signature.unlink(missing_ok=True);removed+=1
    return removed

def _run(profile,directory,offsite=None,recipients=None,signing_key=None,trusted_signing_keys=None):
    directory=pathlib.Path(directory)
    if not directory.is_absolute():raise RuntimeError('Use an absolute backup directory')
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    if directory.is_symlink():raise RuntimeError('Backup directory must not be a symlink')
    if stat.S_IMODE(directory.stat().st_mode)&0o077:raise RuntimeError('Backup directory must be private')
    lock_path=directory/'.accounting-backup.lock'
    descriptor=os.open(lock_path,os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(descriptor,'w') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise CycleRunning('Backup cycle already running')
        now=dt.datetime.now(UTC)
        daily=directory/f'{profile}-daily-{now:%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:8]}.dump'
        subprocess.run([str(pathlib.Path(__file__).parent/'backup.sh'),profile,str(daily)],check=True,stdout=subprocess.DEVNULL)
        sha=verify(daily)
        monthly=directory/f'{profile}-monthly-{now:%Y-%m}.dump'
        monthly_copy(daily,monthly)
        encrypted=False
        if offsite:
            if not recipients or not signing_key:raise RuntimeError('Offsite encryption recipients and signing key are required')
            destination=pathlib.Path(offsite)
            if not destination.is_absolute() or destination==directory or directory in destination.parents:raise RuntimeError('Offsite must be a separate absolute directory')
            destination.mkdir(mode=0o700,parents=True,exist_ok=True)
            if destination.is_symlink():raise RuntimeError('Offsite directory must not be a symlink')
            if stat.S_IMODE(destination.stat().st_mode)&0o077:raise RuntimeError('Offsite directory must be private')
            # Keep daily30days and monthly12calendar months in the encrypted destination too.
            encrypted_path=destination/(daily.name+'.age')
            encrypt_copy(daily,encrypted_path,recipients,signing_key)
            encrypted_month=destination/(monthly.name+'.age')
            if not encrypted_month.exists() and not encrypted_month.is_symlink():
                encrypt_copy(monthly,encrypted_month,recipients,signing_key)
            verify(encrypted_month)
            public=subprocess.run(['openssl','pkey','-in',str(signing_key),'-pubout'],capture_output=True,check=True).stdout
            descriptor,public_path=tempfile.mkstemp(prefix='.verification-',dir=directory)
            try:
                with os.fdopen(descriptor,'wb') as file:file.write(public)
                backup_signing.verify(encrypted_path,public_path)
                try:backup_signing.verify(encrypted_month,public_path)
                except RuntimeError:
                    if not trusted_signing_keys:raise
                    backup_signing.verify(encrypted_month,trusted_signing_keys)
            finally:pathlib.Path(public_path).unlink(missing_ok=True)
            retain(destination,profile,now)
            encrypted=True
        # Prune only after the current backup and requested offsite copy have succeeded.
        removed=retain(directory,profile,now)
        status={'profile':profile,'started_at':now.isoformat(),'completed_at':dt.datetime.now(UTC).isoformat(),'file':daily.name,'sha256':sha,'monthly_verified':True,'offsite_encrypted':encrypted,'offsite_signed':encrypted,'pruned_files':removed}
        (directory/f'{profile}-backup-error.json').unlink(missing_ok=True)
        atomic_json(directory/f'{profile}-backup-status.json',status)
        return status

class CycleRunning(RuntimeError):pass

def run(profile,directory,offsite=None,recipients=None,signing_key=None,trusted_signing_keys=None):
    try:return _run(profile,directory,offsite,recipients,signing_key,trusted_signing_keys)
    except CycleRunning:raise
    except Exception:
        target=pathlib.Path(directory)
        if target.is_absolute() and target.is_dir() and not target.is_symlink() and not stat.S_IMODE(target.stat().st_mode)&0o077:
            try:atomic_json(target/f'{profile}-backup-error.json',{'failed_at':dt.datetime.now(UTC).isoformat(),'failed':True})
            except Exception:pass
        raise

def main():
    parser=argparse.ArgumentParser();parser.add_argument('profile',choices=['test','local','prod']);parser.add_argument('--directory',required=True);parser.add_argument('--offsite');parser.add_argument('--recipients');parser.add_argument('--signing-key');parser.add_argument('--trusted-signing-keys');args=parser.parse_args()
    os.umask(0o077)
    try:print(json.dumps(run(args.profile,args.directory,args.offsite,args.recipients,args.signing_key,args.trusted_signing_keys)))
    except Exception:
        print('Backup cycle failed; previous copies and last-success status retained. Inspect configuration and protected backup files.',file=sys.stderr);return 1
    return 0
if __name__=='__main__':sys.exit(main())
