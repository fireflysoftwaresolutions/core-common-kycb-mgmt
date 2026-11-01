"""Owned hosted-only disposable database; never connect to an existing service."""
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.qualification'
IMAGE = 'postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5'
LABEL = 'org.firefly.reproofing.fixture'


def command(arguments, **kwargs):
    return subprocess.run(arguments, check=True, capture_output=True, **kwargs)


def require_hosted():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        raise RuntimeError('This fixture is restricted to its hosted ephemeral runner')
    if any(os.environ.get(name) for name in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG',
                                            'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')):
        raise RuntimeError('Docker daemon-selection overrides are not admitted')
    endpoint = command(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'], text=True).stdout
    if not json.loads(endpoint).startswith('unix://'):
        raise RuntimeError('Only the local hosted Docker daemon is admitted')


def psql(container, content, extra=()):
    result = subprocess.run(['docker', 'exec', '-i', container, 'psql', '-X', '-U', 'postgres', '-d', 'reproofing',
                             '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', *extra], input=content,
                            capture_output=True)
    with (STATE / 'fixture-private.log').open('ab') as log:
        log.write(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError('Owned fixture SQL failed; raw diagnostic remains private')


def prepare():
    os.umask(0o077)
    STATE.mkdir(exist_ok=True)
    marker = STATE / 'postgres-ownership.json'
    if marker.exists():
        raise RuntimeError('Refusing to overwrite fixture ownership')
    run = os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
    if not re.fullmatch(r'\d+-\d+', run):
        raise RuntimeError('Unexpected hosted run identity')
    container = 'reproofing-' + run
    password = secrets.token_hex(32)
    runtime_password = secrets.token_hex(32)
    environment = STATE / 'postgres.env'
    environment.write_text(f'POSTGRES_PASSWORD={password}\nPOSTGRES_DB=reproofing\n')
    marker.write_text(json.dumps({'container': container, 'run': run, 'image': IMAGE}) + '\n')
    command(['docker', 'run', '--detach', '--rm', '--name', container, '--label', f'{LABEL}={run}',
             '--publish', '127.0.0.1:5432:5432', '--env-file', str(environment), IMAGE])
    for _ in range(60):
        ready = subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'postgres', '-d', 'reproofing'],
                               capture_output=True)
        if ready.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('Owned PostgreSQL fixture did not become ready')
    roles = f"""CREATE ROLE reproofing_owner NOLOGIN;
CREATE ROLE reproofing_runtime LOGIN PASSWORD '{runtime_password}';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO reproofing_owner;
"""
    psql(container, roles.encode())
    psql(container, (ROOT / 'ops/reproofing-installer/install.sql').read_bytes(),
         ('-v', 'reproofing_owner=reproofing_owner', '-v', 'reproofing_runtime=reproofing_runtime'))
    print('::add-mask::' + runtime_password)
    with Path(os.environ['GITHUB_ENV']).open('a') as output:
        output.write('REPROOFING_TEST_R2DBC_URL=r2dbc:postgresql://localhost:5432/reproofing\n')
        output.write('REPROOFING_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/reproofing\n')
        output.write('REPROOFING_TEST_USER=reproofing_runtime\n')
        output.write('REPROOFING_TEST_PASSWORD=' + runtime_password + '\n')
    print('Owned empty hosted fixture: optional reproofing installer; restricted runtime role ready.')


def stop():
    marker = STATE / 'postgres-ownership.json'
    if not marker.exists():
        return
    ownership = json.loads(marker.read_text())
    inspection = command(['docker', 'inspect', ownership['container']], text=True).stdout
    actual = json.loads(inspection)[0]
    if actual['Config']['Labels'].get(LABEL) != ownership['run'] or actual['Config']['Image'] != IMAGE:
        raise RuntimeError('Fixture ownership mismatch; refusing to stop it')
    command(['docker', 'stop', ownership['container']])
    print('Stopped only the labelled disposable hosted fixture; no external service was contacted.')


if __name__ == '__main__':
    try:
        require_hosted()
        if sys.argv[1:] == ['stop']:
            stop()
        elif not sys.argv[1:]:
            prepare()
        else:
            raise RuntimeError('Unsupported fixture operation')
    except Exception:
        print('Hosted recovery fixture operation failed; no raw error, credential or database log is emitted.', file=sys.stderr)
        raise SystemExit(1)
