#!/usr/bin/env bash
# Exercise the release procedure with a disposable test key, then restore unsigned artifacts.
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Provide Android SDK path}"
for name in ACCOUNTING_RELEASE_STORE_FILE ACCOUNTING_RELEASE_STORE_PASSWORD ACCOUNTING_RELEASE_KEY_ALIAS ACCOUNTING_RELEASE_KEY_PASSWORD; do
 test -z "${!name:-}" || { echo 'Run signing self-test without owner signing variables.' >&2; exit 1; }
done
apk=android/app/build/outputs/apk/release/app-release.apk
bundle=android/app/build/outputs/bundle/release/app-release.aab
test ! -e "$apk" || { echo 'Existing signed APK must be preserved; use a separate checkout for this test.' >&2; exit 1; }
mkdir -p ops/.runtime/checks
scratch=$(mktemp -d "$PWD/ops/.runtime/checks/signing-test.XXXXXX")
restored=false
cleanup(){
 local result=$?
 unset ACCOUNTING_RELEASE_STORE_FILE ACCOUNTING_RELEASE_STORE_PASSWORD ACCOUNTING_RELEASE_KEY_ALIAS ACCOUNTING_RELEASE_KEY_PASSWORD
 rm -f -- "$apk"
 if [[ "$restored" != true ]]; then
  rm -f -- "$bundle"
  (cd android && ./gradlew --no-daemon -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' --max-workers=1 assembleRelease bundleRelease) > ops/.runtime/checks/signing-test-unsigned-recovery.log 2>&1 || true
 fi
 rm -rf -- "$scratch"
 if [[ "$result" == 0 && "$restored" == true ]]; then
  python3 - "$scratch" <<'PYCLEAN'
import json,pathlib,sys
assert not pathlib.Path(sys.argv[1]).exists(), 'Temporary key directory remains'
p=pathlib.Path('ops/.runtime/checks/android-test-signing.json')
data=json.loads(p.read_text());data['test_key_removed']=True;p.write_text(json.dumps(data)+'\n')
PYCLEAN
  echo 'PASS disposable test-key signing; owner key not used, test key removed, unsigned artifacts restored.'
 fi
 return "$result"
}
trap cleanup EXIT
export ACCOUNTING_RELEASE_STORE_FILE="$scratch/test-only.p12"
export ACCOUNTING_RELEASE_STORE_PASSWORD="$(openssl rand -hex 24)"
export ACCOUNTING_RELEASE_KEY_ALIAS=accounting-signing-self-test
export ACCOUNTING_RELEASE_KEY_PASSWORD="$ACCOUNTING_RELEASE_STORE_PASSWORD"
keytool -genkeypair -noprompt -alias "$ACCOUNTING_RELEASE_KEY_ALIAS" -dname 'CN=Accounting signing test only' -keyalg RSA -keysize 2048 -validity 1 -storetype PKCS12 -keystore "$ACCOUNTING_RELEASE_STORE_FILE" -storepass:env ACCOUNTING_RELEASE_STORE_PASSWORD -keypass:env ACCOUNTING_RELEASE_KEY_PASSWORD > "$scratch/key-generation.log" 2>&1
ops/android-release.sh
printf '%s\n' '{"test_key_only":true,"owner_signing":false,"apk_signature_verified":true,"bundle_signature_verified":true,"release_system_trust_verified":true}' > ops/.runtime/checks/android-test-signing.json
unset ACCOUNTING_RELEASE_STORE_FILE ACCOUNTING_RELEASE_STORE_PASSWORD ACCOUNTING_RELEASE_KEY_ALIAS ACCOUNTING_RELEASE_KEY_PASSWORD
(cd android && ./gradlew --no-daemon -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' --max-workers=1 assembleRelease bundleRelease)
python3 scripts/check-android-release-trust.py "$ANDROID_HOME"
python3 - "$ANDROID_HOME" "$bundle" <<'PYVERIFY'
import pathlib,re,subprocess,sys,zipfile,json
apk=pathlib.Path('android/app/build/outputs/apk/release/app-release-unsigned.apk')
assert subprocess.run([sys.argv[1]+'/build-tools/36.0.0/apksigner','verify',str(apk)],capture_output=True).returncode!=0, 'Unsigned APK still signed'
with zipfile.ZipFile(sys.argv[2]) as archive:
    assert not any(re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC)',n) for n in archive.namelist()), 'Test signature remains in bundle'
p=pathlib.Path('ops/.runtime/checks/android-test-signing.json')
data=json.loads(p.read_text());data['unsigned_artifacts_restored']=True;p.write_text(json.dumps(data)+'\n')
PYVERIFY
restored=true
