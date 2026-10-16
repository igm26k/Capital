#!/usr/bin/env python3
"""Deploy the base Compose release after read-only preflight; failures leave ingress closed."""
import argparse,datetime,json,os,pathlib,subprocess,sys,time,fcntl,uuid
from preflight import ROOT,check_config,compose_args,render
from smoke import check as smoke
STEPS=['preflight','build_images','pause_ingress_and_writers','database_ready','backup_before_migration','migrate','start_api_worker','internal_readiness','start_ingress','verified_https_smoke']

def _deploy(env_file,directory):
    config=render(env_file)
    if not check_config(config)['ready']:raise RuntimeError('preflight')
    directory=pathlib.Path(directory)
    if not directory.is_absolute() or directory.is_symlink() or not directory.is_dir() or not os.access(directory,os.W_OK) or directory.stat().st_mode&0o077:raise RuntimeError('private_backup_directory')
    args=compose_args(env_file);environment=dict(os.environ)
    if env_file:environment['ACCOUNTING_ENV_FILE']=str(pathlib.Path(env_file).resolve())
    identifier=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
    log_path=directory/('deployment-'+identifier+'.log')
    descriptor=os.open(log_path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(descriptor,'w') as log:
        def run(command,stage):
            log.write(json.dumps({'stage':stage})+'\n');log.flush()
            return subprocess.run(command,cwd=ROOT,env=environment,check=True,stdout=log,stderr=subprocess.STDOUT)
        run(args+['build','api','worker','migrate','web'],'build_images')
        paused=False
        try:
            paused=True
            run(args+['stop','web','api','worker'],'pause_ingress_and_writers')
            run(args+['up','-d','--wait','db'],'database_ready')
            backup=directory/('prod-before-migration-'+identifier+'.dump')
            run([str(ROOT/'ops/backup.sh'),'prod',str(backup)],'backup_before_migration')
            run(args+['run','--rm','--no-deps','migrate'],'migrate')
            run(args+['up','-d','--no-deps','api','worker'],'start_api_worker')
            deadline=time.monotonic()+60;ready=False
            while time.monotonic()<deadline:
                probe=subprocess.run(args+['run','--rm','--no-deps','--entrypoint','wget','web','-q','-O','-','http://api:8080/health/ready'],cwd=ROOT,env=environment,capture_output=True,timeout=10)
                if probe.returncode==0:
                    try:ready=json.loads(probe.stdout)['status']=='ready'
                    except Exception:ready=False
                if ready:break
                time.sleep(1)
            if not ready:raise RuntimeError('internal_readiness')
            run(args+['up','-d','--no-deps','web'],'start_ingress')
            smoke(config['services']['api']['environment']['PUBLIC_ORIGIN'])
            return {'deployed':True,'verified_https_smoke':True,'backup':backup.name,'protected_log':log_path.name,'bank_features':False,'release_acceptance_complete':False}
        except Exception:
            if paused:subprocess.run(args+['stop','web','api','worker'],cwd=ROOT,env=environment,stdout=log,stderr=subprocess.STDOUT)
            raise RuntimeError('Deployment failed; ingress/writers stopped, database and backup preserved. Inspect protected deployment log and recovery runbook.')

def deploy(env_file,directory):
    lock_directory=ROOT/'ops/.runtime/locks';lock_directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    descriptor=os.open(lock_directory/'deploy-prod.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(descriptor,'w') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError('Deployment already running')
        return _deploy(env_file,directory)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--env-file');parser.add_argument('--backup-directory',required=True);parser.add_argument('--plan',action='store_true');args=parser.parse_args()
    if args.plan:print(json.dumps({'steps':STEPS,'mutations':False,'project':'accounting-prod'}));return 0
    os.umask(0o077)
    try:print(json.dumps(deploy(args.env_file,args.backup_directory)));return 0
    except Exception:print(json.dumps({'deployed':False,'message':'Preflight or deployment failed. Existing database is preserved; inspect configuration and recovery runbook.'}));return 1
if __name__=='__main__':sys.exit(main())
