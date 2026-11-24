#!/usr/bin/python3
"""Host-only credential fetch. Never print provider responses or credentials."""
import json
import os
from pathlib import Path
import subprocess
import sys


def command(args, data=None):
    result = subprocess.run(args, input=data, capture_output=True, text=True, timeout=240)
    if result.returncode:
        raise RuntimeError('AWS/container preparation command failed')
    return result.stdout


def main():
    os.umask(0o077)
    config = json.loads(Path('/etc/wardrobe/config.json').read_text())
    run = Path('/run/wardrobe')
    run.mkdir(mode=0o700, exist_ok=True)
    aws = ['aws', '--region', config['region']]
    names = {'/ecom/prod/openai-api-key': 'OPENAI_API_KEY',
             '/ecom/prod/qdrant-api-key': 'QDRANT_API_KEY'}
    response = json.loads(command(aws + ['ssm', 'get-parameters', '--with-decryption',
                                        '--names', *names, '--output', 'json']))
    parameters = {p['Name']: p for p in response.get('Parameters', [])}
    if response.get('InvalidParameters') or parameters.keys() != names.keys():
        raise RuntimeError('Required parameters are missing')
    env = {'SPRING_PROFILES_ACTIVE': 'aws', 'QDRANT_HOST': config['qdrantHost'], 'QDRANT_USE_TLS': 'true'}
    for name, key in names.items():
        parameter = parameters[name]
        value = parameter['Value']
        if parameter['Type'] != 'SecureString' or not value or any(c in value for c in '\r\n\0'):
            raise RuntimeError('Required SecureString parameter is invalid')
        env[key] = value
    image = config['image'] + ':' + config['tag']
    registry = config['image'].split('/')[0]
    # Keep the Docker registry token in tmpfs and discard it after pulling.
    docker_config = run / 'docker'
    docker_config.mkdir(mode=0o700, exist_ok=True)
    try:
        token = command(aws + ['ecr', 'get-login-password'])
        command(['docker', '--config', str(docker_config), 'login', '--username', 'AWS',
                 '--password-stdin', registry], token)
        command(['docker', '--config', str(docker_config), 'pull', image])
    finally:
        (docker_config / 'config.json').unlink(missing_ok=True)
    (run / 'backend.env').write_text(''.join(f'{key}={value}\n' for key, value in env.items()))
    (run / 'image.env').write_text(f'BACKEND_IMAGE={image}\n')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Backend preparation failed; check image, network and parameter configuration.', file=sys.stderr)
        sys.exit(1)
