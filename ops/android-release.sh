#!/usr/bin/env bash
# Build locally with deployment-provided signing credentials; does not publish.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Provide Android SDK path}"
: "${ACCOUNTING_RELEASE_STORE_FILE:?Provide external release keystore path}"
: "${ACCOUNTING_RELEASE_STORE_PASSWORD:?Provide release keystore password}"
: "${ACCOUNTING_RELEASE_KEY_ALIAS:?Provide release signing alias}"
: "${ACCOUNTING_RELEASE_KEY_PASSWORD:?Provide release key password}"
(cd android && ./gradlew --no-daemon -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' --max-workers=1 assembleRelease bundleRelease lintRelease)
apk=android/app/build/outputs/apk/release/app-release.apk
bundle=android/app/build/outputs/bundle/release/app-release.aab
test -s "$apk" && test -s "$bundle"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$apk"
jarsigner -verify "$bundle"
python3 - "$bundle" <<'PY'
import re,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    assert any(re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC)',n) for n in archive.namelist()), 'Bundle signature missing'
PY
python3 scripts/check-android-release-trust.py "$ANDROID_HOME" "$apk"
sha256sum "$apk" "$bundle"
echo 'Signed artifacts built and checked locally; store upload not performed.'
