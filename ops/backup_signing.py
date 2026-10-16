"""Detached signatures authenticate the backup source independently of the offsite store."""
import os,pathlib,stat,subprocess,tempfile

def sign(backup,key):
    backup,key=pathlib.Path(backup),pathlib.Path(key)
    if not key.is_file() or key.is_symlink() or stat.S_IMODE(key.stat().st_mode)&0o077:raise RuntimeError('Protected backup signing key required')
    checksum=backup.with_name(backup.name+'.sha256');target=checksum.with_name(checksum.name+'.sig')
    descriptor,temporary=tempfile.mkstemp(prefix='.signature-',dir=backup.parent);os.close(descriptor)
    try:
        subprocess.run(['openssl','pkeyutl','-sign','-rawin','-inkey',str(key),'-in',str(checksum),'-out',temporary],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        with open(temporary,'rb') as file:os.fsync(file.fileno())
        os.link(temporary,target)
    finally:pathlib.Path(temporary).unlink(missing_ok=True)

def verify(backup,public_key):
    backup=pathlib.Path(backup);checksum=backup.with_name(backup.name+'.sha256');signature=checksum.with_name(checksum.name+'.sig')
    if not signature.is_file() or signature.is_symlink() or signature.stat().st_size>8192:raise RuntimeError('Backup signature unavailable')
    source=pathlib.Path(public_key)
    if source.is_symlink() or stat.S_IMODE(source.stat().st_mode)&0o022:raise RuntimeError('Untrusted verification key path')
    keys=sorted(source.glob('*.pub')) if source.is_dir() else [source]
    if not 1<=len(keys)<=32:raise RuntimeError('Trusted backup verification keys unavailable')
    for key in keys:
        if key.is_symlink() or not key.is_file() or stat.S_IMODE(key.stat().st_mode)&0o022:continue
        result=subprocess.run(['openssl','pkeyutl','-verify','-rawin','-pubin','-inkey',str(key),'-in',str(checksum),'-sigfile',str(signature)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if result.returncode==0:return
    raise RuntimeError('Backup source signature invalid')
