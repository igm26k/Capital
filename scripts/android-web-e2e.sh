#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
command -v npm >/dev/null || { echo "Node/npm required for native-to-Chrome acceptance." >&2; exit 1; }
test -x "${CHROME_PATH:-/usr/bin/google-chrome}"
sdk="${ANDROID_HOME:?Set ANDROID_HOME}"
adb="$sdk/platform-tools/adb"
serial="${ANDROID_TEST_SERIAL:-emulator-5556}"
test "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
compose=(docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml -f compose.e2e.yaml)
trap '"$adb" -s "$serial" reverse --remove tcp:8444 || true; "${compose[@]}" down' EXIT
./scripts/local-tls.sh
"${compose[@]}" up --build -d --wait
curl --noproxy '*' --fail --silent --show-error --cacert ops/.runtime/tls/localhost.crt --retry 10 --retry-all-errors --retry-delay 1 https://localhost:8444/health/ready >/dev/null
make android-check
(cd android && ./gradlew assembleDebugAndroidTest)
"$adb" -s "$serial" reverse tcp:8444 tcp:8444
"$adb" -s "$serial" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p ops/.runtime/checks
proof="$PWD/ops/.runtime/checks/android-web-proof.json"
result="$PWD/ops/.runtime/checks/android-web-result.json"
artifact=ops/.runtime/checks/android-web-instrumentation.txt
rm -f "$proof" "$result"
"$adb" -s "$serial" shell am instrument -w -r -e class com.capital.accounting.AndroidWebScreenTest -e api_origin https://localhost:8444 com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner > "$artifact"
rg -q '^OK \(1 test\)' "$artifact"
! rg -q 'INSTRUMENTATION_STATUS_CODE: -[1-4]' "$artifact"
"$adb" -s "$serial" exec-out run-as com.capital.accounting cat files/android-web-proof.json > "$proof"
(cd web && npm ci && ANDROID_WEB_PROOF="$proof" ANDROID_WEB_RESULT="$result" npm exec playwright test -- --config playwright.android.config.ts)
test -s "$result"
echo 'PASS native Android forms -> real API/PostgreSQL -> Chrome UI and readonly reload'
