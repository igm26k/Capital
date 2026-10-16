#!/usr/bin/env python3
"""Decrypt an authenticated offsite copy into a private, never-overwritten dump."""
import argparse,os,pathlib,subprocess,sys,tempfile
from backup_cycle import checksum_file,digest,verify
import backup_signing

def decrypt(source,destination,identity,verification_key):
    source,destination=pathlib.Path(source),pathlib.Path(destination)
    verify(source)
    backup_signing.verify(source,verification_key)
    if destination.exists() or destination.is_symlink() or destination.with_name(destination.name+'.sha256').exists() or destination.with_name(destination.name+'.sha256').is_symlink():raise RuntimeError('Destination exists')
    destination.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    descriptor,temporary=tempfile.mkstemp(prefix='.decrypted-',dir=destination.parent)
    try:
        with os.fdopen(descriptor,'wb') as file:
            subprocess.run(['age','-d','-i',str(identity),str(source)],check=True,stdout=file,stderr=subprocess.DEVNULL)
            file.flush();os.fsync(file.fileno())
        if pathlib.Path(temporary).stat().st_size==0:raise RuntimeError('Empty backup')
        os.link(temporary,destination)
        checksum_file(destination,digest(destination))
    finally:pathlib.Path(temporary).unlink(missing_ok=True)
    return {'decrypted':True,'checksum_verified':True,'source_signature_verified':True,'overwritten':False}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('source');parser.add_argument('destination');parser.add_argument('--identity',required=True);parser.add_argument('--verification-key',required=True);args=parser.parse_args();os.umask(0o077)
    try:print(__import__('json').dumps(decrypt(args.source,args.destination,args.identity,args.verification_key)));return 0
    except Exception:print('Decryption failed; no destination is overwritten. Check protected key and backup integrity.',file=sys.stderr);return 1
if __name__=='__main__':sys.exit(main())
