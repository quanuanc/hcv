#!/usr/bin/env python3
"""Local Postgres.app / Vault static-password rotation learning demo."""
import argparse
import datetime
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import time
import urllib.error
import urllib.request

PSQL = '/Applications/Postgres.app/Contents/Versions/17/bin/psql'
DB = 'hcv_demo'
APP = 'hcv_demo_app'
MANAGER = 'hcv_vault_manager'
ROLE_PATH = 'database/static-roles/hcv-demo'
CREDS_PATH = 'database/static-creds/hcv-demo'
VAULT_ADDR = os.environ.get('VAULT_ADDR', 'https://127.0.0.1:8200').rstrip('/')


def vault(path, data=None):
    token = os.environ.get('VAULT_TOKEN')
    if not token:
        raise RuntimeError('Set VAULT_TOKEN first.')
    request = urllib.request.Request(
        f'{VAULT_ADDR}/v1/{path}',
        data=None if data is None else json.dumps(data).encode(),
        headers={'X-Vault-Token': token, 'Content-Type': 'application/json'},
    )
    handlers = [urllib.request.ProxyHandler({})]
    if VAULT_ADDR.startswith('https://'):
        ca = os.environ.get('VAULT_CACERT', str(Path(__file__).parent / '.local/server-tls/vault-ca.pem'))
        handlers.append(urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca)))
    opener = urllib.request.build_opener(*handlers)
    try:
        with opener.open(request, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'Vault {path}: {error.read().decode()}') from None


def psql(sql=None, *, user='postgres', database='postgres', password=None,
         interactive=False, check=True):
    env = os.environ.copy()
    for name in ('PGPASSWORD', 'PGSERVICE', 'PGSERVICEFILE', 'PGOPTIONS'):
        env.pop(name, None)
    if password is not None:
        env['PGPASSWORD'] = password
    command = [PSQL, '-X', '-w', '-h', '127.0.0.1', '-p', '5432',
               '-U', user, '-d', database, '-v', 'ON_ERROR_STOP=1']
    if interactive:
        return subprocess.run(command, env=env).returncode
    result = subprocess.run(command + ['-At'], input=sql, text=True,
                            capture_output=True, env=env)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result


def configure_auth():
    hba = Path(psql('SHOW hba_file;').stdout.strip())
    original = hba.read_text()
    marker = '# BEGIN hcv_demo Vault learning rules'
    if marker in original:
        return
    # These accounts may connect only to the demo DB, and must use a password.
    users = f'{APP},{MANAGER}'
    rules = f'''{marker}
local {DB} {users} scram-sha-256
local all {users} reject
host {DB} {users} 127.0.0.1/32 scram-sha-256
host all {users} 127.0.0.1/32 reject
host {DB} {users} ::1/128 scram-sha-256
host all {users} ::1/128 reject
# END hcv_demo Vault learning rules

'''
    suffix = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup = hba.with_name(hba.name + '.hcv-demo-' + suffix + '.bak')
    backup.write_text(original)
    backup.chmod(0o600)
    try:
        hba.write_text(rules + original)
        errors = psql('SELECT error FROM pg_hba_file_rules WHERE error IS NOT NULL;').stdout.strip()
        if errors:
            raise RuntimeError(errors)
        if psql('SELECT pg_reload_conf();').stdout.strip() != 't':
            raise RuntimeError('PostgreSQL config reload failed')
    except Exception:
        hba.write_text(original)
        psql('SELECT pg_reload_conf();')
        raise
    print(f'Added demo-only password authentication; backup: {backup}')
    time.sleep(1)


def setup():
    vault('sys/health')
    version = psql('SHOW server_version_num;').stdout.strip()
    if not 170000 <= int(version) < 180000:
        raise RuntimeError(f'Expected PostgreSQL 17, found {version}')
    # Re-running after a dev Vault restart resets only the dedicated manager.
    manager_password = secrets.token_urlsafe(36)
    psql(f'''
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='{APP}') THEN
    CREATE ROLE {APP} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='{MANAGER}') THEN
    CREATE ROLE {MANAGER} LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION;
  END IF;
END $$;
SET password_encryption = 'scram-sha-256';
ALTER ROLE {MANAGER} PASSWORD '{manager_password}';
GRANT {APP} TO {MANAGER} WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
''')
    exists = psql(f"SELECT 1 FROM pg_database WHERE datname='{DB}';").stdout.strip()
    if not exists:
        psql(f'CREATE DATABASE {DB} OWNER {APP};')
    owner = psql(f"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='{DB}';").stdout.strip()
    if owner != APP:
        raise RuntimeError(f'{DB} already exists with unexpected owner {owner}; not changing ownership.')
    psql(f'REVOKE ALL ON DATABASE {DB} FROM PUBLIC; GRANT CONNECT ON DATABASE {DB} TO {MANAGER};')
    configure_auth()
    mounts = vault('sys/mounts')['data']
    if 'database/' not in mounts:
        vault('sys/mounts/database', {'type': 'database'})
    elif mounts['database/']['type'] != 'database':
        raise RuntimeError('database/ is occupied by a different engine')
    vault('database/config/hcv-demo', {
        'plugin_name': 'postgresql-database-plugin',
        'allowed_roles': ['hcv-demo'],
        'connection_url': 'postgresql://{{username}}:{{password}}@127.0.0.1:5432/hcv_demo?sslmode=disable',
        'username': MANAGER,
        'password': manager_password,
        'verify_connection': True,
        'max_open_connections': 2,
    })
    vault(ROLE_PATH, {
        'db_name': 'hcv-demo', 'username': APP, 'rotation_period': '10s',
        'rotation_statements': ['ALTER ROLE "{{name}}" WITH PASSWORD \'{{password}}\';'],
    })
    policy = Path(__file__).with_name('hcv-demo-read.hcl').read_text()
    vault('sys/policies/acl/hcv-demo-read', {'policy': policy})
    print('Created hcv_demo; Vault manages hcv_demo_app with a 10-second rotation period.')
    status()


def creds():
    return vault(CREDS_PATH)['data']


def set_period(period):
    return vault(ROLE_PATH, {'db_name': 'hcv-demo', 'username': APP,
                             'rotation_period': period})


def status():
    data = creds()
    print(json.dumps({k: v for k, v in data.items() if k != 'password'}, indent=2))


def connect():
    data = creds()
    raise SystemExit(psql(user=data['username'], database=DB,
                          password=data['password'], interactive=True))


def verify():
    """Exercise a scheduled rotation, restore the original period, then test manual rotation."""
    before = creds()
    psql('SELECT current_user, current_database();', user=APP,
         database=DB, password=before['password'])
    wrong = psql('SELECT 1;', user=APP, database=DB,
                 password='intentionally-wrong-' + secrets.token_hex(16), check=False)
    if wrong.returncode == 0 or 'password authentication failed' not in wrong.stderr:
        raise RuntimeError('Expected password authentication to reject the wrong password')
    print('PASS: current credential connects; wrong password is rejected.', flush=True)
    try:
        set_period('10s')
        # Reset the timer once; the NEXT change must come from Vault's scheduler.
        vault('database/rotate-role/hcv-demo', {})
        baseline = creds()
        deadline = time.monotonic() + 55
        while time.monotonic() < deadline:
            time.sleep(2)
            latest = creds()
            if latest['password'] != baseline['password']:
                psql('SELECT 1;', user=APP, database=DB, password=latest['password'])
                old = psql('SELECT 1;', user=APP, database=DB,
                            password=baseline['password'], check=False)
                if old.returncode == 0 or 'password authentication failed' not in old.stderr:
                    raise RuntimeError('Previous password should no longer authenticate')
                print('PASS: scheduled rotation ran automatically; old password rejected, new password works.', flush=True)
                break
        else:
            raise RuntimeError('No automatic rotation observed within 55 seconds')
    finally:
        set_period(f"{before['rotation_period']}s")
        vault('database/rotate-role/hcv-demo', {})
    current = creds()
    assert current['rotation_period'] == before['rotation_period']
    result = psql('CREATE TABLE IF NOT EXISTS public.vault_learning (id integer PRIMARY KEY, message text); '
                 "INSERT INTO public.vault_learning VALUES (1, 'hello from Vault') "
                 'ON CONFLICT (id) DO UPDATE SET message=EXCLUDED.message; '
                 'SELECT current_user, current_database(), message FROM public.vault_learning;',
                 user=APP, database=DB, password=current['password'])
    print('PASS: restored original rotation period and reset timer; application can create/write/read its demo table.')
    print(result.stdout.strip())
    status()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['setup', 'status', 'connect', 'rotate', 'verify'])
    args = parser.parse_args()
    try:
        if args.action == 'rotate':
            vault('database/rotate-role/hcv-demo', {})
            status()
        else:
            globals()[args.action]()
    except (RuntimeError, OSError, urllib.error.URLError) as error:
        parser.exit(1, f'Error: {error}\n')
