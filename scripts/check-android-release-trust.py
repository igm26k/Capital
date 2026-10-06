#!/usr/bin/env python3
"""Inspect compiled release trust configuration, including optimized resource paths."""
import re
import subprocess
import sys
from pathlib import Path
aapt = Path(sys.argv[1]) / 'build-tools/36.0.0/aapt2'
apk = Path('android/app/build/outputs/apk/release/app-release-unsigned.apk')
def dump(*args):
    return subprocess.run([str(aapt), 'dump', *args, str(apk)], check=True, capture_output=True, text=True).stdout
resources = dump('resources')
assert 'raw/local_ca' not in resources
match = re.search(r'xml/network_security_config\n\s+\(\) \(file\) (res/\S+) type=XML', resources)
assert match, 'Release network config resource absent'
config = dump('xmltree', '--file', match[1])
assert 'E: domain-config' not in config
assert 'A: cleartextTrafficPermitted=false' in config
assert config.count('E: certificates') == 1 and 'A: src="system"' in config
print('PASS compiled release trust: system CA only, cleartext disabled, no local debug CA')
