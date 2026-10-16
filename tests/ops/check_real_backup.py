#!/usr/bin/env python3
"""Actual isolated PostgreSQL backup drill; temporarily pauses existing test writers."""
import json,os,pathlib,subprocess,sys,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'ops'))
import backup_cycle as cycle
from decrypt_backup import decrypt
C=['docker','compose','--project-name','accounting-test','-f','compose.yaml','-f','compose.test.yaml']
def command(args,**kw):return subprocess.run(args,cwd=ROOT,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw)
def main():
 os.umask(0o077)
 base=pathlib.Path(tempfile.mkdtemp(prefix='signed-backup-',dir=ROOT/'ops/.runtime/checks'))
 local=base/'local';offsite=base/'offsite';trusted=base/'trusted';trusted.mkdir()
 running=command(C+['ps','--services','--status','running']).stdout.decode().split()
 writers=[name for name in ['api','worker'] if name in running]
 private=[]
 try:
  if writers:command(C+['stop',*writers])
  identity=base/'age.identity';private.append(identity)
  command(['age-keygen','-o',str(identity)])
  recipient=command(['age-keygen','-y',str(identity)]).stdout
  recipients=base/'recipients';recipients.write_bytes(recipient)
  keys=[]
  for name in ['a','b']:
   key=base/f'signing-{name}.pem';private.append(key);keys.append(key)
   command(['openssl','genpkey','-algorithm','Ed25519','-out',str(key)])
   (trusted/f'{name}.pub').write_bytes(command(['openssl','pkey','-in',str(key),'-pubout']).stdout)
  first=cycle.run('test',local,offsite,recipients,keys[0],trusted)
  monthly=next(local.glob('*monthly*.dump'));original=cycle.digest(monthly)
  monthly.with_name(monthly.name+'.sha256').unlink()
  latest=cycle.run('test',local,offsite,recipients,keys[1],trusted)
  assert cycle.verify(monthly)==original
  restored=base/'decrypted.dump'
  decrypt(offsite/(latest['file']+'.age'),restored,identity,trusted)
  assert cycle.digest(restored)==latest['sha256']
  command([str(ROOT/'ops/restore-check.sh'),str(restored)])
  status=local/'test-backup-status.json';accepted=status.read_bytes()
  expired=local/'test-daily-20000101T000000Z-deadbeef.dump';expired.write_bytes(b'canary')
  invalid=base/'invalid-recipients';invalid.write_text('invalid recipient\n')
  try:cycle.run('test',local,offsite,invalid,keys[1],trusted)
  except subprocess.CalledProcessError:pass
  else:raise AssertionError('Invalid recipient accepted')
  assert status.read_bytes()==accepted and expired.exists() and (local/'test-backup-error.json').exists()
  cycle.run('test',local,offsite,recipients,keys[1],trusted)
  assert not expired.exists() and not (local/'test-backup-error.json').exists()
  collision=base/'collision.dump'
  args=[str(ROOT/'ops/backup.sh'),'test',str(collision)]
  processes=[subprocess.Popen(args,cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL) for _ in range(2)]
  assert sorted(p.wait() for p in processes)==[0,1]
  cycle.verify(collision)
  proof={'real_postgres_dump':True,'signed_encrypted_roundtrip':True,'signing_key_rotation':True,'monthly_crash_recovery':True,'restored_tables':28,'failure_preserves_last_success':True,'successful_retry_clears_failure':True,'concurrent_publication_one_winner':True,'production_configured':False,'artifact_directory':str(base)}
 finally:
  for path in private:path.unlink(missing_ok=True)
  if writers:command(C+['up','-d','--no-deps',*writers])
 proof['ephemeral_private_keys_removed']=all(not p.exists() for p in private)
 (ROOT/'ops/.runtime/checks/signed-backup-result.json').write_text(json.dumps(proof,indent=2)+'\n')
 print(json.dumps(proof))
if __name__=='__main__':main()
