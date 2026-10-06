#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
sdk="${ANDROID_HOME:?Set ANDROID_HOME to Android SDK}"
adb="$sdk/platform-tools/adb"
serial="${ANDROID_TEST_SERIAL:-emulator-5556}"
test "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 || { echo 'Select a dedicated test emulator.' >&2; exit 1; }
compose=(docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml -f compose.e2e.yaml)
trap '"$adb" -s "$serial" reverse --remove tcp:8444 || true; "${compose[@]}" down' EXIT
./scripts/local-tls.sh
"${compose[@]}" up --build -d --wait
curl --noproxy '*' --fail --silent --show-error --cacert ops/.runtime/tls/localhost.crt --retry 10 --retry-all-errors --retry-delay 1 https://localhost:8444/health/ready >/dev/null
make android-check
(cd android && ./gradlew assembleDebugAndroidTest assembleRelease)
"$adb" -s "$serial" reverse tcp:8444 tcp:8444
"$adb" -s "$serial" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p ops/.runtime/checks
rm -f ops/.runtime/checks/android-foundation-instrumentation.txt ops/.runtime/checks/android-api-auth-instrumentation.txt ops/.runtime/checks/android-ui-auth-instrumentation.txt ops/.runtime/checks/android-auth-runtime.json
run_suite() {
    local suite="$1" expected="$2" artifact="$3"
    "$adb" -s "$serial" shell am instrument -w -r -e class "$suite" -e api_origin https://localhost:8444 com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner > "$artifact"
    { rg -q "^OK \($expected tests?\)" "$artifact" && ! rg -q "INSTRUMENTATION_STATUS_CODE: -[1-4]" "$artifact"; } || { echo 'Android instrumentation failed; inspect restricted local artifact.' >&2; exit 1; }
}
run_suite 'com.capital.accounting.FoundationTest,com.capital.accounting.CommandStoreTest,com.capital.accounting.CredentialVaultTest,com.capital.accounting.SettingsScreenTest' 6 ops/.runtime/checks/android-foundation-instrumentation.txt
run_suite com.capital.accounting.ApiAuthTest 1 ops/.runtime/checks/android-api-auth-instrumentation.txt
run_suite com.capital.accounting.CommandApiTest 1 ops/.runtime/checks/android-command-api-instrumentation.txt
run_suite com.capital.accounting.AccountApiTest 1 ops/.runtime/checks/android-account-api-instrumentation.txt
run_suite com.capital.accounting.DevicesScreenTest 1 ops/.runtime/checks/android-devices-instrumentation.txt
run_suite com.capital.accounting.AuthScreenTest 1 ops/.runtime/checks/android-ui-auth-instrumentation.txt
python3 scripts/check-android-auth-runtime.py "$adb" "$serial"
python3 scripts/check-android-release-trust.py "$sdk"
echo 'PASS 11/11 real Android instrumentation tests and auth runtime acceptance'
