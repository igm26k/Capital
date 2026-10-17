#!/bin/sh
# Certbot deploy hook. Mount /etc/accounting/web-tls into production nginx.
set -eu
[ "${RENEWED_LINEAGE:-}" = /etc/letsencrypt/live/195.133.21.189 ] || exit 0
install -d -m 0700 /etc/accounting/web-tls
openssl x509 -in "$RENEWED_LINEAGE/fullchain.pem" -noout -checkip 195.133.21.189
openssl verify -CApath /etc/ssl/certs -untrusted "$RENEWED_LINEAGE/chain.pem" "$RENEWED_LINEAGE/cert.pem"
install -m 0644 "$RENEWED_LINEAGE/fullchain.pem" /etc/accounting/web-tls/localhost.crt.new
install -m 0600 "$RENEWED_LINEAGE/privkey.pem" /etc/accounting/web-tls/localhost.key.new
mv /etc/accounting/web-tls/localhost.crt.new /etc/accounting/web-tls/localhost.crt
mv /etc/accounting/web-tls/localhost.key.new /etc/accounting/web-tls/localhost.key
# Only reload an existing personal production web container. Never expose tests.
for container in $(docker ps -q --filter label=com.docker.compose.project=accounting-prod --filter label=com.docker.compose.service=web); do
    docker exec "$container" nginx -t
    docker exec "$container" nginx -s reload
done
