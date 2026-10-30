#!/bin/sh
set -eu
cd "$(dirname "$0")"
umask 077
if lsof -nP -iTCP:8200 -sTCP:LISTEN >/dev/null 2>&1; then
  echo 'Port 8200 is already in use; stop the existing Vault before starting.' >&2
  exit 1
fi
mkdir -p .local/server-tls .local/client
chmod 700 .local .local/server-tls .local/client
if [ ! -s .local/admin-token ]; then
  python3 -c 'import secrets; print(secrets.token_urlsafe(48))' > .local/admin-token
fi
if [ ! -s .local/client/client.key ]; then
  openssl req -x509 -newkey rsa:3072 -noenc -sha256 -days 90 \
    -subj '/CN=spring-boot-demo' \
    -addext 'basicConstraints=critical,CA:FALSE' \
    -addext 'keyUsage=critical,digitalSignature' \
    -addext 'extendedKeyUsage=clientAuth' \
    -keyout .local/client/client.key -out .local/client/client.crt
fi
chmod 600 .local/admin-token .local/client/client.key
# dev-tls generates new server certificates on every restart.
rm -f .local/server-tls/vault-ca.pem .local/server-tls/vault-cert.pem .local/server-tls/vault-key.pem
export VAULT_DEV_ROOT_TOKEN_ID="$(cat .local/admin-token)"
exec /opt/homebrew/bin/vault server -dev -dev-tls \
  -dev-listen-address=127.0.0.1:8200 \
  -dev-tls-cert-dir="$PWD/.local/server-tls" -dev-no-store-token
