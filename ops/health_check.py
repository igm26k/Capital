#!/usr/bin/env python3
"""Read-only aggregate checks for a timer or external monitoring collector."""
import argparse,datetime as dt,json,pathlib,re,subprocess,sys
from smoke import check as smoke
ROOT=pathlib.Path(__file__).resolve().parent.parent

def backup_health(directory,profile,now=None):
    now=now or dt.datetime.now(dt.timezone.utc);directory=pathlib.Path(directory);failed=[]
    try:
        status=directory/f'{profile}-backup-status.json'
        assert not status.is_symlink() and status.stat().st_size<1024*1024
        body=json.loads(status.read_text());completed=dt.datetime.fromisoformat(body['completed_at'])
        assert completed.tzinfo is not None and body['profile']==profile
        assert -300<=(now-completed).total_seconds()<=26*3600
        filename=body['file'];assert re.fullmatch(re.escape(profile)+r'-daily-\d{8}T\d{6}Z-[0-9a-f]{8}\.dump',filename)
        file=directory/filename;checksum=directory/(filename+'.sha256')
        assert file.is_file() and not file.is_symlink() and checksum.is_file() and not checksum.is_symlink()
        assert checksum.stat().st_size<8192
        assert body['sha256']==checksum.read_text().split()[0] and body['monthly_verified']
        if profile=='prod' and (body.get('offsite_encrypted') is not True or body.get('offsite_signed') is not True):failed.append('signed_encrypted_offsite_not_configured')
        error=directory/f'{profile}-backup-error.json'
        if error.exists():
            assert not error.is_symlink() and error.stat().st_size<1024*1024
            failed_at=dt.datetime.fromisoformat(json.loads(error.read_text())['failed_at'])
            if failed_at>completed:failed.append('latest_backup_cycle_failed')
    except Exception:failed.append('backup_missing_stale_or_invalid')
    return failed

def check(profile,origin,directory):
    failed=backup_health(directory,profile)
    try:smoke(origin,timeout=10)
    except Exception:failed.append('verified_https_readiness')
    metrics={}
    try:
        result=subprocess.run([str(ROOT/'ops/monitor.sh'),profile],capture_output=True,text=True,check=True,timeout=15)
        metrics=json.loads(result.stdout)
        for key in ('email_failed','csv_failed','snapshot_cleanup_overdue'):
            if metrics[key]>0:failed.append(key)
        for key in ('email_oldest_pending_seconds','oldest_pending_seconds'):
            if metrics[key]>300:failed.append(key)
    except Exception:failed.append('database_metrics')
    return {'healthy':not failed,'failed':failed,'metrics':metrics}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('profile',choices=['test','local','prod']);parser.add_argument('--origin',required=True);parser.add_argument('--backup-directory',required=True);args=parser.parse_args()
    result=check(args.profile,args.origin,args.backup_directory);print(json.dumps(result));return 0 if result['healthy'] else 1
if __name__=='__main__':sys.exit(main())
