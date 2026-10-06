#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p ops/.runtime/tls
if [ -e ops/.runtime/tls/localhost.key ] || [ -e ops/.runtime/tls/localhost.crt ]; then
  test -s ops/.runtime/tls/localhost.key && test -s ops/.runtime/tls/localhost.crt
  exit 0
fi
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' -keyout ops/.runtime/tls/localhost.key -out ops/.runtime/tls/localhost.crt
