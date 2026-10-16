import base64,copy,datetime,fcntl,importlib.util,json,os,pathlib,subprocess,sys,tempfile,unittest
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'ops'))
import backup_cycle as backup
import preflight
import deploy

class BackupSafety(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.directory=pathlib.Path(self.temporary.name)
    def tearDown(self):self.temporary.cleanup()
    def file(self,name):
        path=self.directory/name;path.write_bytes(b'synthetic financial backup');backup.checksum_file(path,backup.digest(path));return path
    def test_retention_boundaries_and_foreign_files(self):
        now=datetime.datetime(2026,10,16,12,tzinfo=datetime.timezone.utc)
        expired=self.file('prod-daily-20260916T115959Z-aaaaaaaa.dump')
        boundary=self.file('prod-daily-20260916T120000Z-bbbbbbbb.dump')
        old_month=self.file('prod-monthly-2025-10.dump')
        retained_month=self.file('prod-monthly-2025-11.dump')
        foreign=self.file('manual-important.dump')
        other=self.file('test-daily-20200101T000000Z-cccccccc.dump')
        link=self.directory/'prod-daily-20200101T000000Z-dddddddd.dump';link.symlink_to(foreign)
        self.assertEqual(backup.retain(self.directory,'prod',now),2)
        for path in (boundary,retained_month,foreign,other,link):self.assertTrue(path.exists())
        self.assertFalse(expired.exists());self.assertFalse(old_month.exists())
    def test_monthly_link_crash_recovery_and_corruption(self):
        daily=self.file('prod-daily-20261016T000000Z-aaaaaaaa.dump');monthly=self.directory/'prod-monthly-2026-10.dump'
        os.link(daily,monthly)
        backup.monthly_copy(daily,monthly)
        self.assertEqual(backup.verify(monthly),backup.verify(daily))
        monthly.write_bytes(b'corrupted')
        with self.assertRaises(RuntimeError):backup.verify(monthly)
    def test_orphan_checksum_symlink_not_overwritten(self):
        daily=self.file('prod-daily-20261016T000000Z-aaaaaaaa.dump');monthly=self.directory/'prod-monthly-2026-10.dump'
        victim=self.directory/'important';victim.write_text('keep')
        monthly.with_name(monthly.name+'.sha256').symlink_to(victim)
        with self.assertRaises(RuntimeError):backup.monthly_copy(daily,monthly)
        self.assertEqual(victim.read_text(),'keep');self.assertFalse(monthly.exists())
    def test_failed_backup_preserves_previous_success_and_expired_copies(self):
        old=self.file('prod-daily-20200101T000000Z-aaaaaaaa.dump');status=self.directory/'prod-backup-status.json';status.write_text('previous success')
        with mock.patch.object(backup.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['backup'])):
            with self.assertRaises(subprocess.CalledProcessError):backup.run('prod',self.directory)
        self.assertTrue(old.exists());self.assertEqual(status.read_text(),'previous success')
    def test_overlapping_cycle_rejected_before_dump(self):
        with (self.directory/'.accounting-backup.lock').open('w') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with mock.patch.object(backup.subprocess,'run') as command:
                with self.assertRaises(RuntimeError):backup.run('prod',self.directory)
                command.assert_not_called()
    def test_real_age_encryption_decrypts_exactly(self):
        key=self.directory/'private.age';subprocess.run(['age-keygen','-o',str(key)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        recipient=subprocess.check_output(['age-keygen','-y',str(key)])
        public=self.directory/'recipients';public.write_bytes(recipient)
        source=self.file('prod-daily-20261016T000000Z-aaaaaaaa.dump');encrypted=self.directory/'copy.age'
        backup.encrypt_copy(source,encrypted,public)
        self.assertNotIn(source.read_bytes(),encrypted.read_bytes());backup.verify(encrypted)
        clear=subprocess.check_output(['age','-d','-i',str(key),str(encrypted)])
        self.assertEqual(clear,source.read_bytes())
    def test_backup_refuses_existing_dump_and_orphan_checksum(self):
        for suffix in ('','.sha256'):
            path=self.directory/('existing'+suffix);path.write_text('keep')
            output=self.directory/'existing'
            result=subprocess.run([str(ROOT/'ops/backup.sh'),'test',str(output)],capture_output=True)
            self.assertNotEqual(result.returncode,0);self.assertEqual(path.read_text(),'keep')
            path.unlink()

class ProductionPreflight(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary=tempfile.TemporaryDirectory();cls.root=pathlib.Path(cls.temporary.name)
        cls.ca=cls.root/'ca.crt';cls.ca_key=cls.root/'ca.key'
        def run(args):subprocess.run(['openssl']+args,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        run(['req','-x509','-newkey','rsa:2048','-nodes','-days','30','-subj','/CN=Accounting test CA','-addext','basicConstraints=critical,CA:TRUE','-keyout',str(cls.ca_key),'-out',str(cls.ca)])
        cls.paths={}
        for name,hostname,filename in [('db','db','server'),('web','accounting.example.test','localhost')]:
            folder=cls.root/name;folder.mkdir();key=folder/(filename+'.key');csr=folder/'request.csr';cert=folder/(filename+'.crt');ext=folder/'extensions'
            run(['req','-newkey','rsa:2048','-nodes','-subj','/CN='+hostname,'-keyout',str(key),'-out',str(csr)])
            ext.write_text('subjectAltName=DNS:'+hostname+'\nextendedKeyUsage=serverAuth\n')
            run(['x509','-req','-days','20','-in',str(csr),'-CA',str(cls.ca),'-CAkey',str(cls.ca_key),'-CAcreateserial','-out',str(cert),'-extfile',str(ext)])
            key.chmod(0o600);cls.paths[name]=folder
        cls.secret='synthetic-private-password-that-must-not-be-logged'
        environment={'APP_ENV':'production','PUBLIC_ORIGIN':'https://accounting.example.test','DATABASE_URL':'postgres://accounting:'+cls.secret+'@db:5432/accounting?sslmode=verify-full&sslrootcert=/etc/accounting/db-tls/ca.crt','SYNC_CURSOR_ACTIVE_KEY':'v1','SYNC_CURSOR_KEYS':json.dumps({'v1':base64.b64encode(b'x'*32).decode()}),'REGISTRATION_ENABLED':'false','AUTH_EMAIL_VERIFICATION':'false'}
        def mount(source,target):return {'source':str(source),'target':target,'type':'bind','read_only':True}
        cls.config={'services':{name:{'environment':copy.deepcopy(environment),'volumes':[mount(cls.root,'/etc/accounting/db-tls')]} for name in ('api','worker','migrate')}}
        cls.config['services']['db']={'environment':{'POSTGRES_USER':'accounting','POSTGRES_PASSWORD':cls.secret,'POSTGRES_DB':'accounting'},'volumes':[mount(cls.paths['db'],'/etc/postgresql/tls')]}
        cls.config['services']['web']={'volumes':[mount(cls.paths['web'],'/etc/nginx/tls')]}
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()
    def test_real_certificates_and_default_public_trust(self):
        result=preflight.check_config(copy.deepcopy(self.config),web_test_ca=self.ca)
        self.assertTrue(result['ready'],result);self.assertTrue(result['test_only_web_trust'])
        default=preflight.check_config(copy.deepcopy(self.config))
        self.assertFalse(default['ready']);self.assertIn('web_tls_chain_and_key',default['failed'])
    def test_mismatched_database_would_backup_wrong_target(self):
        config=copy.deepcopy(self.config);config['services']['api']['environment']['DATABASE_URL']=config['services']['api']['environment']['DATABASE_URL'].replace('@db:', '@elsewhere:')
        result=preflight.check_config(config,web_test_ca=self.ca)
        self.assertFalse(result['ready']);self.assertIn('database_and_backup_target',result['failed']);self.assertNotIn(self.secret,json.dumps(result))
    def test_insecure_registration_and_private_key_permissions(self):
        config=copy.deepcopy(self.config);config['services']['api']['environment']['REGISTRATION_ENABLED']='true'
        result=preflight.check_config(config,web_test_ca=self.ca);self.assertIn('email_verification_configuration',result['failed'])
        key=self.paths['web']/'localhost.key';key.chmod(0o644)
        try:self.assertIn('web_tls_chain_and_key',preflight.check_config(copy.deepcopy(self.config),web_test_ca=self.ca)['failed'])
        finally:key.chmod(0o600)

class DeploymentSafety(unittest.TestCase):
    def test_plan_never_calls_docker_or_creates_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            target=pathlib.Path(directory)/'not-created'
            result=subprocess.run([sys.executable,str(ROOT/'ops/deploy.py'),'--plan','--backup-directory',str(target)],capture_output=True,text=True,env={'PATH':os.environ['PATH']})
            self.assertEqual(result.returncode,0,result.stderr);self.assertFalse(json.loads(result.stdout)['mutations']);self.assertFalse(target.exists())
    def test_migration_failure_keeps_ingress_closed_and_never_restores_in_place(self):
        calls=[]
        def run(args,**kwargs):
            calls.append(args)
            if args[-4:]==['run','--rm','--no-deps','migrate']:raise subprocess.CalledProcessError(1,args)
            return subprocess.CompletedProcess(args,0,stdout=b'{"status":"ready"}')
        config={'services':{'api':{'environment':{'PUBLIC_ORIGIN':'https://accounting.example.test'}}}}
        with tempfile.TemporaryDirectory() as directory,mock.patch.object(deploy,'render',return_value=config),mock.patch.object(deploy,'check_config',return_value={'ready':True}),mock.patch.object(deploy.subprocess,'run',side_effect=run):
            with self.assertRaises(RuntimeError):deploy.deploy(None,directory)
        self.assertEqual(calls[-1][-4:],['stop','web','api','worker'])
        flattened=[' '.join(c) for c in calls]
        self.assertTrue(any('backup.sh' in c for c in flattened));self.assertFalse(any('pg_restore' in c or 'down' in c for c in flattened))
    def test_success_orders_backup_before_migration_and_checks_https(self):
        calls=[]
        def run(args,**kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args,0,stdout=b'{"status":"ready"}')
        config={'services':{'api':{'environment':{'PUBLIC_ORIGIN':'https://accounting.example.test'}}}}
        with tempfile.TemporaryDirectory() as directory,mock.patch.object(deploy,'render',return_value=config),mock.patch.object(deploy,'check_config',return_value={'ready':True}),mock.patch.object(deploy.subprocess,'run',side_effect=run),mock.patch.object(deploy,'smoke') as smoke:
            result=deploy.deploy(None,directory)
            self.assertTrue(result['deployed']);self.assertFalse(result['release_acceptance_complete'])
            self.assertEqual((pathlib.Path(directory)/result['protected_log']).stat().st_mode&0o777,0o600)
            smoke.assert_called_once_with('https://accounting.example.test')
        backup_index=next(i for i,c in enumerate(calls) if 'backup.sh' in c[0])
        migrate_index=next(i for i,c in enumerate(calls) if c[-4:]==['run','--rm','--no-deps','migrate'])
        self.assertLess(backup_index,migrate_index)

    def test_failed_preflight_does_not_stop_existing_services(self):
        with tempfile.TemporaryDirectory() as directory,mock.patch.object(deploy,'render',return_value={}),mock.patch.object(deploy,'check_config',return_value={'ready':False}),mock.patch.object(deploy.subprocess,'run') as command:
            with self.assertRaises(RuntimeError):deploy.deploy(None,directory)
            command.assert_not_called()


class BackupMonitoring(unittest.TestCase):
    def test_failure_and_stale_backup_never_report_healthy(self):
        import health_check
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);file=root/'test-daily-20261016T000000Z-aaaaaaaa.dump';file.write_bytes(b'test');sha=backup.digest(file);backup.checksum_file(file,sha)
            now=datetime.datetime.now(datetime.timezone.utc)
            status={'profile':'test','completed_at':now.isoformat(),'file':file.name,'sha256':sha,'monthly_verified':True,'offsite_encrypted':False}
            path=root/'test-backup-status.json';backup.atomic_json(path,status)
            self.assertEqual(health_check.backup_health(root,'test',now),[])
            backup.atomic_json(root/'test-backup-error.json',{'failed_at':(now+datetime.timedelta(seconds=1)).isoformat(),'failed':True})
            self.assertIn('latest_backup_cycle_failed',health_check.backup_health(root,'test',now))
            (root/'test-backup-error.json').unlink();status['completed_at']=(now-datetime.timedelta(hours=27)).isoformat();backup.atomic_json(path,status)
            self.assertIn('backup_missing_stale_or_invalid',health_check.backup_health(root,'test',now))
    def test_source_queue_failure_and_age_are_alerted(self):
        import health_check
        metrics={key:0 for key in ('email_failed','csv_failed','source_failed','snapshot_cleanup_overdue','email_oldest_pending_seconds','oldest_pending_seconds','source_oldest_pending_seconds')}
        with mock.patch.object(health_check,'backup_health',return_value=[]),mock.patch.object(health_check,'smoke'),mock.patch.object(health_check.subprocess,'run') as command:
            command.return_value.stdout=json.dumps(metrics)
            self.assertTrue(health_check.check('test','https://example.invalid','/private')['healthy'])
            metrics['source_failed']=1;metrics['source_oldest_pending_seconds']=301
            command.return_value.stdout=json.dumps(metrics)
            failed=health_check.check('test','https://example.invalid','/private')['failed']
            self.assertIn('source_failed',failed);self.assertIn('source_oldest_pending_seconds',failed)

    def test_symlink_metadata_is_not_trusted(self):
        import health_check
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);(root/'test-backup-status.json').symlink_to('/etc/passwd')
            self.assertIn('backup_missing_stale_or_invalid',health_check.backup_health(root,'test'))


class BackupAuthenticity(unittest.TestCase):
    def test_valid_encryption_cannot_replace_a_signed_backup(self):
        import backup_signing,decrypt_backup
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);identity=root/'identity';key=root/'signing.key';public_key=root/'signing.pub';recipients=root/'recipients'
            subprocess.run(['age-keygen','-o',str(identity)],check=True,stderr=subprocess.DEVNULL)
            recipients.write_bytes(subprocess.check_output(['age-keygen','-y',str(identity)]))
            subprocess.run(['openssl','genpkey','-algorithm','ED25519','-out',str(key)],check=True,stderr=subprocess.DEVNULL);key.chmod(0o600)
            public_key.write_bytes(subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout']));public_key.chmod(0o600)
            source=root/'original.dump';source.write_bytes(b'original financial backup');encrypted=root/'original.dump.age'
            backup.encrypt_copy(source,encrypted,recipients,key);backup_signing.verify(encrypted,public_key)
            destination=root/'restored.dump';decrypt_backup.decrypt(encrypted,destination,identity,public_key)
            self.assertEqual(destination.read_bytes(),source.read_bytes())
            with self.assertRaises(RuntimeError):decrypt_backup.decrypt(encrypted,destination,identity,public_key)
            # An attacker with the public recipient can create valid age encryption and a new checksum.
            evil=root/'evil.dump';evil.write_bytes(b'forged financial backup');replacement=root/'replacement.age';backup.encrypt_copy(evil,replacement,recipients)
            encrypted.write_bytes(replacement.read_bytes());encrypted.with_name(encrypted.name+'.sha256').unlink();backup.checksum_file(encrypted,backup.digest(encrypted))
            rejected=root/'must-not-exist.dump'
            with self.assertRaises(RuntimeError):decrypt_backup.decrypt(encrypted,rejected,identity,public_key)
            self.assertFalse(rejected.exists())

if __name__=='__main__':unittest.main()
