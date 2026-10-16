#!/usr/bin/env python3
"""Read-only production checks. Never print rendered environment values or secret contents."""
import argparse,base64,ipaddress,json,os,pathlib,stat,subprocess,sys,urllib.parse
ROOT=pathlib.Path(__file__).resolve().parent.parent

def compose_args(env_file=None):
    args=['docker','compose','--project-name','accounting-prod','-f',str(ROOT/'compose.yaml'),'-f',str(ROOT/'compose.prod.yaml')]
    if env_file:args+=['--env-file',str(pathlib.Path(env_file).resolve())]
    return args

def render(env_file=None):
    result=subprocess.run(compose_args(env_file)+['config','--format','json'],cwd=ROOT,capture_output=True,text=True)
    if result.returncode:raise RuntimeError('compose_configuration')
    return json.loads(result.stdout)

def command(args):
    result=subprocess.run(args,capture_output=True,env={k:v for k,v in os.environ.items() if k not in ('SSL_CERT_FILE','SSL_CERT_DIR')})
    if result.returncode:raise RuntimeError('certificate_validation')
    return result.stdout.strip()

def certificate(cert,key,hostname,ca=None):
    cert,key=pathlib.Path(cert),pathlib.Path(key)
    if not cert.is_file() or not key.is_file() or key.is_symlink():raise RuntimeError('certificate_files')
    if stat.S_IMODE(key.stat().st_mode)&0o077:raise RuntimeError('private_key_permissions')
    command(['openssl','x509','-checkend','604800','-noout','-in',str(cert)])
    public=command(['openssl','x509','-pubkey','-noout','-in',str(cert)])
    if public!=command(['openssl','pkey','-pubout','-in',str(key)]):raise RuntimeError('certificate_key_mismatch')
    try:ipaddress.ip_address(hostname);identity=['-verify_ip',hostname]
    except ValueError:identity=['-verify_hostname',hostname]
    args=['openssl','verify','-purpose','sslserver','-untrusted',str(cert)]+identity
    if ca:args+=['-CAfile',str(ca)]
    command(args+[str(cert)])

def bind(service,target):
    for volume in service.get('volumes',[]):
        if volume.get('target')==target and volume.get('type')=='bind' and volume.get('read_only'):
            return pathlib.Path(volume['source'])
    raise RuntimeError('readonly_tls_mount')

def check_config(config,web_test_ca=None):
    errors=[];checks=[]
    def check(name,fn):
        try:fn();checks.append(name)
        except Exception:errors.append(name)
    services=config.get('services',{});api=services.get('api',{});environment=api.get('environment',{});db=services.get('db',{}).get('environment',{})
    def application():
        assert environment.get('APP_ENV')=='production'
        origin=urllib.parse.urlsplit(environment['PUBLIC_ORIGIN'])
        assert origin.scheme=='https' and origin.hostname and origin.username is None and origin.path in ('','/') and not origin.query and not origin.fragment
        assert origin.hostname not in ('localhost','127.0.0.1','::1')
        for service in ('worker','migrate'):
            other=services[service]['environment']
            for key in ('APP_ENV','DATABASE_URL','PUBLIC_ORIGIN','SYNC_CURSOR_ACTIVE_KEY','SYNC_CURSOR_KEYS','AUTH_EMAIL_VERIFICATION','AUTH_EMAIL_KEY','REGISTRATION_ENABLED','SMTP_ADDRESS','SMTP_FROM','SMTP_MODE','SMTP_USERNAME','SMTP_PASSWORD'):
                assert other.get(key)==environment.get(key)
    check('production_environment',application)
    def database():
        url=urllib.parse.urlsplit(environment['DATABASE_URL']);query=urllib.parse.parse_qs(url.query)
        assert url.scheme in ('postgres','postgresql') and url.hostname=='db' and url.port in (None,5432)
        assert query.get('sslmode')==['verify-full'] and query.get('sslrootcert')==['/etc/accounting/db-tls/ca.crt']
        assert urllib.parse.unquote(url.username or '')==db['POSTGRES_USER']
        assert urllib.parse.unquote(url.password or '')==db['POSTGRES_PASSWORD']
        assert urllib.parse.unquote(url.path[1:])==db['POSTGRES_DB']
        assert len(db['POSTGRES_PASSWORD'])>=24 and 'replace_with' not in db['POSTGRES_PASSWORD']
    check('database_and_backup_target',database)
    def cursor_keys():
        keys=json.loads(environment['SYNC_CURSOR_KEYS']);assert environment['SYNC_CURSOR_ACTIVE_KEY'] in keys
        assert isinstance(keys,dict) and len(keys)>0
        for name,value in keys.items():assert name and len(name)<=64 and '.' not in name and len(base64.b64decode(value,validate=True))>=32
    check('persistent_cursor_keys',cursor_keys)
    def email():
        assert environment.get('AUTH_EMAIL_VERIFICATION','false').lower() in ('true','false')
        assert environment.get('REGISTRATION_ENABLED','false').lower() in ('true','false')
        enabled=environment.get('AUTH_EMAIL_VERIFICATION','false').lower()=='true'
        registration=environment.get('REGISTRATION_ENABLED','false').lower()=='true'
        assert not registration or enabled
        if enabled:
            assert len(base64.b64decode(environment['AUTH_EMAIL_KEY'],validate=True))==32
            assert environment.get('SMTP_MODE') in ('tls','starttls')
            assert ':' in environment.get('SMTP_ADDRESS','') and '@' in environment.get('SMTP_FROM','')
            assert bool(environment.get('SMTP_USERNAME'))==bool(environment.get('SMTP_PASSWORD'))
    check('email_verification_configuration',email)
    def database_tls():
        ca=bind(api,'/etc/accounting/db-tls')/'ca.crt';server=bind(services['db'],'/etc/postgresql/tls')
        certificate(server/'server.crt',server/'server.key','db',ca)
        for service in ('worker','migrate'):assert bind(services[service],'/etc/accounting/db-tls')==ca.parent
    check('database_tls_chain_and_key',database_tls)
    def web_tls():
        directory=bind(services['web'],'/etc/nginx/tls');origin=urllib.parse.urlsplit(environment['PUBLIC_ORIGIN'])
        certificate(directory/'localhost.crt',directory/'localhost.key',origin.hostname,web_test_ca)
    check('web_tls_chain_and_key',web_tls)
    return {'ready':not errors,'passed':checks,'failed':errors,'test_only_web_trust':web_test_ca is not None}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--env-file');args=parser.parse_args()
    try:result=check_config(render(args.env_file))
    except Exception:result={'ready':False,'passed':[],'failed':['compose_configuration'],'test_only_web_trust':False}
    print(json.dumps(result));return 0 if result['ready'] else 1
if __name__=='__main__':sys.exit(main())
