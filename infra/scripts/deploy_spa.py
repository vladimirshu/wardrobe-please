"""Build and upload the SPA to the stack-owned bucket. No AWS SDK or secrets."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def run(*args, capture=False, cwd=None):
    result = subprocess.run(args, cwd=cwd, check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout if capture else None


def deploy():
    # Resolve destinations from CloudFormation rather than accepting arbitrary buckets.
    stack = json.loads(run('aws', 'cloudformation', 'describe-stacks', '--stack-name',
                          'WardrobeEdgeHosting', '--output', 'json', capture=True))['Stacks'][0]
    outputs = {item['OutputKey']: item['OutputValue'] for item in stack['Outputs']}
    bucket, distribution = outputs['SpaBucketName'], outputs['DistributionId']
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', bucket) or not re.fullmatch(r'E[A-Z0-9]+', distribution):
        raise RuntimeError('Invalid edge stack outputs')
    frontend = ROOT / 'wardrobe-fe'
    run('npm', 'ci', cwd=frontend)
    run('npm', 'run', 'build', cwd=frontend)
    build = frontend / 'dist'
    if not (build / 'index.html').is_file() or not (build / 'assets').is_dir():
        raise RuntimeError('Expected Vite dist/index.html and dist/assets')
    # Publish dependencies first and index last. Keep old hashed assets for open tabs
    # and rollback; no --delete. Retired releases can be cleaned up deliberately later.
    run('aws', 's3', 'sync', str(build / 'assets'), f's3://{bucket}/assets/',
        '--cache-control', 'public, max-age=31536000, immutable', '--only-show-errors')
    run('aws', 's3', 'sync', str(build), f's3://{bucket}/', '--exclude', 'assets/*',
        '--exclude', 'index.html', '--cache-control', 'public, max-age=60', '--only-show-errors')
    run('aws', 's3', 'cp', str(build / 'index.html'), f's3://{bucket}/index.html',
        '--cache-control', 'public, max-age=60', '--content-type', 'text/html', '--only-show-errors')
    run('aws', 'cloudfront', 'create-invalidation', '--distribution-id', distribution,
        '--paths', '/index.html', '/')


if __name__ == '__main__':
    deploy()
