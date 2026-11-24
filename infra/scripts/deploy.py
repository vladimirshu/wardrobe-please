#!/usr/bin/env python3
"""Deployment plumbing for deploy.sh. Uses AWS CLI; never reads secret values."""
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

INFRA = Path(__file__).resolve().parents[1]
STACK = 'WardrobeBackendHost'


# Run a checked subprocess, optionally capturing stdout, so failed commands stop
# the current deployment phase instead of letting it continue with invalid state.
def run(*args, capture=False, **kwargs):
    return subprocess.run(args, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, **kwargs).stdout


# Run an AWS CLI request and parse its JSON response so deployment steps can use
# structured data without repeating command execution and response parsing.
def aws(*args):
    return json.loads(run('aws', *args, '--output', 'json', capture=True))


# Read the backend stack, returning None only when it does not exist, so fresh
# deployments can proceed without hiding AWS permission or service errors.
def stack():
    result = subprocess.run(['aws', 'cloudformation', 'describe-stacks',
                             '--stack-name', STACK, '--output', 'json'],
                            capture_output=True, text=True)
    if result.returncode:
        if 'does not exist' in result.stderr and 'ValidationError' in result.stderr:
            return None
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout)['Stacks'][0]


# Look up a named stack output, returning None when absent, so deployment steps
# can discover resource identifiers without hard-coding them.
def output(current, name):
    return next((v['OutputValue'] for v in current.get('Outputs', [])
                 if v['OutputKey'] == name), None)


# Fetch the EC2 instance record so callers can inspect its current state and
# network attachments rather than relying on potentially stale deployment outputs.
def instance(instance_id):
    return aws('ec2', 'describe-instances', '--instance-ids', instance_id)[
        'Reservations'][0]['Instances'][0]


# Remove CIDR and prefix-list ingress from the existing host's single stack-owned
# security group so starting it for migration does not restore broad inbound access.
def close_old_ingress(instance_id):
    # Close historical public/prefix-list access before starting the old host.
    groups = instance(instance_id)['SecurityGroups']
    resources = aws('cloudformation', 'list-stack-resources', '--stack-name', STACK)['StackResourceSummaries']
    owned = {r['PhysicalResourceId'] for r in resources
             if r['ResourceType'] == 'AWS::EC2::SecurityGroup' and r.get('PhysicalResourceId')}
    if len(groups) != 1 or groups[0]['GroupId'] not in owned:
        raise RuntimeError('Expected one stack-owned host security group. Review the attached groups.')
    for group in groups:
        sg = aws('ec2', 'describe-security-groups', '--group-ids', group['GroupId'])['SecurityGroups'][0]
        for permission in sg['IpPermissions']:
            broad = {k: permission[k] for k in ('IpRanges', 'Ipv6Ranges', 'PrefixListIds')
                     if permission.get(k)}
            if broad:
                rule = {k: permission[k] for k in ('IpProtocol', 'FromPort', 'ToPort') if k in permission}
                rule.update(broad)
                aws('ec2', 'revoke-security-group-ingress', '--group-id', group['GroupId'],
                    '--ip-permissions', json.dumps([rule]))


# Require exactly one inbound rule allowing TCP 8080 from the CloudFront VPC-origin
# service group so the backend is not activated with unexpected network access.
def verify_ingress(instance_id):
    # Refuse to activate the filter-free backend unless ingress is exactly as designed.
    host = instance(instance_id)
    groups = aws('ec2', 'describe-security-groups', '--filters',
                 f"Name=vpc-id,Values={host['VpcId']}",
                 'Name=group-name,Values=CloudFront-VPCOrigins-Service-SG')['SecurityGroups']
    allowed = {g['GroupId'] for g in groups}
    rules = []
    for group in host['SecurityGroups']:
        rules.extend(aws('ec2', 'describe-security-groups', '--group-ids',
                         group['GroupId'])['SecurityGroups'][0]['IpPermissions'])
    if len(rules) != 1:
        raise RuntimeError('Expected exactly one inbound rule; backend was not activated.')
    rule = rules[0]
    sources = rule.get('UserIdGroupPairs', [])
    if (rule['IpProtocol'] != 'tcp' or rule.get('FromPort') != 8080
            or rule.get('ToPort') != 8080 or len(sources) != 1
            or sources[0]['GroupId'] not in allowed
            or any(rule.get(k) for k in ('IpRanges', 'Ipv6Ranges', 'PrefixListIds'))):
        raise RuntimeError('Unexpected inbound rule; backend was not activated.')


# Update host scripts and non-secret settings through SSM, restart the backend,
# and await readiness so existing hosts receive changes and restart is not mistaken
# for successful application startup and catalog initialization.
def activate(instance_id, host_config):
    # Send only source files and non-secret settings; provider keys stay on the host.
    files = {str(target): base64.b64encode((INFRA / 'scripts' / source).read_bytes()).decode()
             for source, target in [('prepare.py', '/usr/local/sbin/wardrobe-prepare'),
                                    ('backend.service', '/etc/systemd/system/wardrobe-backend.service')]}
    install = f'''import base64, json, os
from pathlib import Path
os.umask(0o077)
for target, content in {files!r}.items():
    path = Path(target)
    path.write_bytes(base64.b64decode(content))
    path.chmod(0o700 if target.endswith('wardrobe-prepare') else 0o644)
path = Path('/etc/wardrobe/config.json')
config = json.loads(path.read_text())
config.update({host_config!r})
temp = path.with_suffix('.new')
temp.write_text(json.dumps(config))
temp.chmod(0o600)
temp.replace(path)
'''
    commands = ['set -eu', 'cloud-init status --wait >/dev/null',
                "python3 - <<'WARDROBE_DEPLOY'\n" + install + 'WARDROBE_DEPLOY',
                'systemctl daemon-reload', 'systemctl restart wardrobe-backend',
                # Readiness includes catalog initialization; never treat restart as success.
                "python3 - <<'WARDROBE_HEALTH'\nimport json, time, urllib.request\n"
                'for attempt in range(120):\n'
                '    try:\n'
                "        with urllib.request.urlopen('http://127.0.0.1:8080/api/v1/health', timeout=5) as r:\n"
                "            if r.status == 200 and json.load(r).get('status') == 'UP':\n"
                "                print('Backend readiness: UP'); break\n"
                '    except Exception:\n        pass\n    time.sleep(5)\n'
                "else:\n    raise SystemExit('Backend readiness timed out')\nWARDROBE_HEALTH"]
    # Wait for the agent on new/restarted instances, with a bounded timeout.
    for _ in range(120):
        info = aws('ssm', 'describe-instance-information', '--filters',
                   f'Key=InstanceIds,Values={instance_id}')['InstanceInformationList']
        if info and info[0]['PingStatus'] == 'Online':
            break
        time.sleep(5)
    else:
        raise RuntimeError('SSM agent did not become available within 10 minutes.')
    command = aws('ssm', 'send-command', '--instance-ids', instance_id,
                  '--document-name', 'AWS-RunShellScript', '--parameters',
                  json.dumps({'commands': commands, 'executionTimeout': ['1800']}))['Command']['CommandId']
    for _ in range(420):
        result = subprocess.run(['aws', 'ssm', 'get-command-invocation', '--command-id', command,
                                 '--instance-id', instance_id, '--output', 'json'], capture_output=True, text=True)
        if result.returncode:
            if 'InvocationDoesNotExist' not in result.stderr:
                raise RuntimeError(result.stderr)
        else:
            invocation = json.loads(result.stdout)
            if invocation['Status'] == 'Success':
                print('Backend readiness: UP', flush=True)
                return
            if invocation['Status'] not in ('Pending', 'InProgress', 'Delayed', 'Cancelling'):
                raise RuntimeError(f'SSM deployment {command}: {invocation["Status"]}. Inspect it in Systems Manager.')
        time.sleep(5)
    raise RuntimeError(f'SSM deployment {command} timed out; inspect it in Systems Manager.')


# Validate deployment inputs and dispatch preflight, image-build, or deployment
# work so the shell entry point shares one workflow for account checks, resource
# updates, activation, and default host shutdown after deployment or failure.
def main():
    os.chdir(INFRA)
    config = json.loads(Path(sys.argv[1]).read_text())
    # Defaults live in the example file and also apply to omitted optional fields.
    defaults = json.loads((INFRA / 'deploy.config.example.json').read_text())
    config = defaults | config
    if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', config['budget_email']):
        raise RuntimeError('Set budget_email in deploy.config.json before deployment.')
    if (not re.fullmatch(r'\d{12}', config['account_id'])
            or not isinstance(config['stop_after_deploy'], bool)
            or not isinstance(config['budget_usd'], (int, float)) or config['budget_usd'] <= 0):
        raise RuntimeError('Invalid account_id, stop_after_deploy or budget_usd.')
    os.environ.update(AWS_PROFILE=config['profile'], AWS_REGION=config['region'],
                      AWS_DEFAULT_REGION=config['region'], AWS_PAGER='', AWS_CLI_AUTO_PROMPT='off')
    for tool in ('aws', 'node', 'npm', 'npx', 'docker'):
        if not shutil.which(tool):
            raise RuntimeError(f'Required tool is missing: {tool}')
    if int(run('node', '-p', 'process.versions.node.split(".")[0]', capture=True).strip()) < 22:
        raise RuntimeError('Node 22+ is required.')
    if aws('sts', 'get-caller-identity')['Account'] != config['account_id']:
        raise RuntimeError('AWS credentials point to a different account. Nothing was deployed.')
    current = stack()
    qdrant = config['qdrant_host'] or next((p['ParameterValue'] for p in (current or {}).get('Parameters', [])
                                        if p['ParameterKey'] == 'QdrantHost'), '')
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9.-]+', qdrant):
        raise RuntimeError('Set qdrant_host to the Qdrant DNS hostname (required for a fresh stack).')
    action = sys.argv[2]
    if action == 'preflight':
        for name in ('openai-api-key', 'qdrant-api-key'):
            parameters = aws('ssm', 'describe-parameters', '--parameter-filters',
                             f'Key=Name,Option=Equals,Values=/ecom/prod/{name}')['Parameters']
            if len(parameters) != 1 or parameters[0]['Type'] != 'SecureString':
                raise RuntimeError(f'Missing SecureString: /ecom/prod/{name}')
        run('docker', 'info', capture=True)
        run('docker', 'buildx', 'version')
        return
    # Persist only a non-secret, collision-resistant local build tag between phases.
    tag_file = INFRA / '.deploy-image-tag'
    if action == 'build-image':
        tag = 'release-' + uuid.uuid4().hex
        run('docker', 'buildx', 'build', '--platform', 'linux/arm64', '--load',
            '-t', f'wardrobe-backend:{tag}', str(INFRA.parent / 'wardrobe-be'))
        tag_file.write_text(tag)
        return
    if action != 'deploy':
        raise RuntimeError('Unknown deployment action.')
    tag = tag_file.read_text().strip()
    context = ['-c', f'budgetEmail={config["budget_email"]}', '-c', f'budgetUsd={config["budget_usd"]}']
    # Bootstrap is repeatable; infrastructure itself is always deployed by CloudFormation.
    run('npx', 'cdk', 'bootstrap', f'aws://{config["account_id"]}/{config["region"]}')
    run('npx', 'cdk', 'synth', '--strict', *context, capture=True)
    run('npx', 'cdk', 'diff', *context)
    existing_id = output(current, 'InstanceId') if current else None
    try:
        if existing_id:
            close_old_ingress(existing_id)
            if instance(existing_id)['State']['Name'] == 'stopping':
                run('aws', 'ec2', 'wait', 'instance-stopped', '--instance-ids', existing_id)
            aws('ec2', 'start-instances', '--instance-ids', existing_id)
            run('aws', 'ec2', 'wait', 'instance-running', '--instance-ids', existing_id)
        run('npx', 'cdk', 'deploy', '--all', '--require-approval', 'never', *context,
            '--parameters', f'{STACK}:QdrantHost={qdrant}', '--parameters', f'{STACK}:ImageTag={tag}',
            '--outputs-file', 'outputs.json')
        deployed = stack()
        instance_id, repository = output(deployed, 'InstanceId'), output(deployed, 'RepositoryUri')
        verify_ingress(instance_id)
        # Keep Docker's temporary ECR credentials outside the user's persistent config.
        with tempfile.TemporaryDirectory(prefix='wardrobe-docker-') as directory:
            docker = ['docker', '--config', directory]
            token = run('aws', 'ecr', 'get-login-password', capture=True)
            run(*docker, 'login', '--username', 'AWS', '--password-stdin', repository.split('/')[0], input=token)
            run('docker', 'tag', f'wardrobe-backend:{tag}', f'{repository}:{tag}')
            run(*docker, 'push', f'{repository}:{tag}')
        activate(instance_id, {'region': config['region'], 'image': repository,
                               'tag': tag, 'qdrantHost': qdrant})
        edge = aws('cloudformation', 'describe-stacks', '--stack-name', 'WardrobeEdgeHosting')['Stacks'][0]
        run('bash', 'bind-control.sh', output(edge, 'DistributionId'))
        run('bash', 'deploy-spa.sh')
        print('Host verified, edge/control bound and SPA published. Live edge acceptance remains separate; schedules retain their configured state.', flush=True)
    finally:
        # Stop after verification/failure unless explicitly disabled. Control schedules default off.
        if config['stop_after_deploy']:
            latest = stack()
            ids = {i for i in (existing_id, output(latest, 'InstanceId') if latest else None) if i}
            # A failed initial create may have an instance before stack outputs exist.
            if latest:
                resources = aws('cloudformation', 'list-stack-resources', '--stack-name', STACK)['StackResourceSummaries']
                ids.update(r['PhysicalResourceId'] for r in resources
                           if r['ResourceType'] == 'AWS::EC2::Instance' and r.get('PhysicalResourceId')
                           and r['ResourceStatus'] != 'DELETE_COMPLETE')
            for host_id in ids:
                if instance(host_id)['State']['Name'] in ('running', 'pending'):
                    aws('ec2', 'stop-instances', '--instance-ids', host_id)
                    run('aws', 'ec2', 'wait', 'instance-stopped', '--instance-ids', host_id)
            print('EC2 stopped (stop_after_deploy=true).', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError) as error:
        print(f'Deployment failed: {error}', file=sys.stderr)
        sys.exit(1)
