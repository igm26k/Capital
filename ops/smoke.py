#!/usr/bin/env python3
"""Anonymous production smoke with verified HTTPS and bounded retries; no financial writes."""
import argparse,json,ssl,time,urllib.error,urllib.parse,urllib.request,sys
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def check(origin,timeout=60):
    parsed=urllib.parse.urlsplit(origin)
    if parsed.scheme!='https' or not parsed.hostname or parsed.username is not None or parsed.query or parsed.fragment or parsed.path not in ('','/'):
        raise ValueError('Invalid HTTPS origin')
    opener=urllib.request.build_opener(NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    deadline=time.monotonic()+timeout;last=None
    while time.monotonic()<deadline:
        try:
            results=[]
            for path,expected in [('/health/ready',200),('/',200),('/api/v1/auth/session',401),('/api/v1/me/export',401)]:
                try:response=opener.open(urllib.request.Request(origin.rstrip('/')+path,headers={'Cache-Control':'no-store'}),timeout=min(5,max(.1,deadline-time.monotonic())))
                except urllib.error.HTTPError as error:response=error
                with response:
                    assert response.status==expected
                    data=response.read(1024*1024+1);assert len(data)<=1024*1024
                    if path=='/':
                        assert response.headers.get('X-Frame-Options')=='DENY'
                        assert 'frame-ancestors' in response.headers.get('Content-Security-Policy','')
                    elif expected==401:
                        body=json.loads(data);assert body['code']=='unauthenticated' and body['field_errors']==[] and body['current_versions']==[]
                        assert response.headers.get('Cache-Control')=='no-store'
                    else:assert json.loads(data)['status']=='ready'
                    results.append(path)
            return {'ready':True,'checks':results,'verified_https':True,'financial_writes':False}
        except Exception:last='Production smoke failed';time.sleep(min(1,max(0,deadline-time.monotonic())))
    raise RuntimeError(last or 'Production smoke failed')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('origin');parser.add_argument('--timeout',type=int,default=60);args=parser.parse_args()
    try:print(json.dumps(check(args.origin,max(1,min(args.timeout,300)))));return 0
    except Exception:print(json.dumps({'ready':False,'failed':['verified_https_or_anonymous_routes']}));return 1
if __name__=='__main__':sys.exit(main())
