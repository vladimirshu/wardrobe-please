"""Offline deployment checks: no real AWS/Docker commands are executed."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deploy', Path(__file__).parents[1] / 'scripts/deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

HOST = {'VpcId': 'vpc-test', 'State': {'Name': 'stopped'},
        'SecurityGroups': [{'GroupId': 'sg-host'}]}
RESOURCE = {'ResourceType': 'AWS::EC2::SecurityGroup', 'PhysicalResourceId': 'sg-host'}
RULE = {'IpProtocol': 'tcp', 'FromPort': 8080, 'ToPort': 8080,
        'UserIdGroupPairs': [{'GroupId': 'sg-managed'}]}


class DeployTest(unittest.TestCase):
    def test_only_revoke_stack_owned_broad_sources(self):
        rule = dict(RULE, PrefixListIds=[{'PrefixListId': 'pl-old'}], IpRanges=[{'CidrIp': '0.0.0.0/0'}])
        responses = [{'StackResourceSummaries': [RESOURCE]},
                     {'SecurityGroups': [{'IpPermissions': [rule]}]}, {}]
        with patch.object(deploy, 'instance', return_value=HOST), patch.object(deploy, 'aws', side_effect=responses) as aws:
            deploy.close_old_ingress('i-host')
        revoked = json.loads(aws.call_args.args[-1])[0]
        self.assertEqual(revoked['PrefixListIds'], rule['PrefixListIds'])
        self.assertEqual(revoked['IpRanges'], rule['IpRanges'])
        self.assertNotIn('UserIdGroupPairs', revoked)

    def test_refuse_to_modify_unowned_group(self):
        with patch.object(deploy, 'instance', return_value=HOST), patch.object(deploy, 'aws',
                          return_value={'StackResourceSummaries': []}) as aws:
            with self.assertRaisesRegex(RuntimeError, 'stack-owned'):
                deploy.close_old_ingress('i-host')
        self.assertEqual(aws.call_count, 1)

    def test_final_ingress_requires_only_managed_group(self):
        for rule, accepted in [(RULE, True), (dict(RULE, IpRanges=[{'CidrIp': '0.0.0.0/0'}]), False),
                               (dict(RULE, UserIdGroupPairs=[{'GroupId': 'sg-foreign'}]), False)]:
            with self.subTest(rule=rule), patch.object(deploy, 'instance', return_value=HOST), patch.object(
                    deploy, 'aws', side_effect=[{'SecurityGroups': [{'GroupId': 'sg-managed'}]},
                                               {'SecurityGroups': [{'IpPermissions': [rule]}]}]):
                if accepted:
                    deploy.verify_ingress('i-host')
                else:
                    with self.assertRaises(RuntimeError):
                        deploy.verify_ingress('i-host')

    def test_ssm_payload_is_executable_and_contains_no_secret_values(self):
        sent = []

        def aws(*args):
            if args[1] == 'describe-instance-information':
                return {'InstanceInformationList': [{'PingStatus': 'Online'}]}
            sent.append(json.loads(args[-1]))
            return {'Command': {'CommandId': 'cmd-test'}}

        result = subprocess.CompletedProcess([], 0, json.dumps({'Status': 'Success'}), '')
        with patch.object(deploy, 'aws', side_effect=aws), patch.object(deploy.subprocess, 'run', return_value=result):
            deploy.activate('i-host', {'tag': 'release-test', 'qdrantHost': 'q.example.com'})
        commands = sent[0]['commands']
        self.assertLess(commands.index('cloud-init status --wait >/dev/null'), commands.index('systemctl restart wardrobe-backend'))
        # Compile both embedded Python programs without running host mutations.
        for command in commands:
            if command.startswith("python3 - <<'"):
                compile(command.split('\n', 1)[1].rsplit('\n', 1)[0], '<remote>', 'exec')
        self.assertNotIn('--with-decryption', json.dumps(sent))
        self.assertNotIn('get-parameters', json.dumps(sent))

    def exercise_deploy(self, fresh=False, failure=False, stop=True):
        events = []
        current = {'Parameters': [{'ParameterKey': 'QdrantHost', 'ParameterValue': 'q.example.com'}],
                   'Outputs': [{'OutputKey': 'InstanceId', 'OutputValue': 'i-host'},
                               {'OutputKey': 'RepositoryUri', 'OutputValue': 'registry.example.com/backend'}]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = {'budget_email': 'owner@example.com', 'qdrant_host': 'q.example.com' if fresh else '',
                      'stop_after_deploy': stop}
            (root / 'deploy.config.example.json').write_text(json.dumps({
                'profile': 'wardrobe', 'account_id': '708564192046', 'region': 'eu-central-1', 'budget_usd': 8}))
            (root / 'config.json').write_text(json.dumps(config))
            (root / '.deploy-image-tag').write_text('release-test')

            def aws(*args):
                events.append(args)
                if args[:2] == ('sts', 'get-caller-identity'):
                    return {'Account': '708564192046'}
                if args[:2] == ('cloudformation', 'list-stack-resources'):
                    return {'StackResourceSummaries': []}
                return {}

            def run(*args, **kwargs):
                events.append(args)
                if args[:3] == ('node', '-p', 'process.versions.node.split(".")[0]'):
                    return '22'
                if args[:3] == ('npx', 'cdk', 'deploy') and failure:
                    raise RuntimeError('simulated deployment failure')
                if args[:3] == ('aws', 'ecr', 'get-login-password'):
                    return 'test-token'
                return ''

            with patch.object(deploy, 'INFRA', root), patch.object(deploy.sys, 'argv', ['deploy.py', str(root / 'config.json'), 'deploy']), \
                    patch.object(deploy, 'aws', side_effect=aws), patch.object(deploy, 'run', side_effect=run), \
                    patch.object(deploy.shutil, 'which', return_value='/mock/tool'), patch.dict(os.environ), \
                    patch.object(deploy, 'stack', side_effect=[None if fresh else current, current, current]), \
                    patch.object(deploy, 'instance', return_value=dict(HOST, State={'Name': 'running'})), \
                    patch.object(deploy, 'close_old_ingress', side_effect=lambda _: events.append(('close-ingress',))), \
                    patch.object(deploy, 'verify_ingress', side_effect=lambda _: events.append(('verify-ingress',))), \
                    patch.object(deploy, 'activate', side_effect=lambda *_: events.append(('activate',))):
                previous = Path.cwd()
                try:
                    if failure:
                        with self.assertRaisesRegex(RuntimeError, 'simulated'):
                            deploy.main()
                    else:
                        deploy.main()
                finally:
                    os.chdir(previous)
        return events

    def test_existing_host_sequence_and_default_stop(self):
        events = self.exercise_deploy()
        start = next(i for i, e in enumerate(events) if e[:2] == ('ec2', 'start-instances'))
        self.assertLess(events.index(('close-ingress',)), start)
        push = next(i for i, e in enumerate(events) if 'push' in e)
        self.assertLess(events.index(('verify-ingress',)), push)
        self.assertLess(push, events.index(('activate',)))
        self.assertTrue(any(e[:2] == ('ec2', 'stop-instances') for e in events))

    def test_new_stack_does_not_start_missing_host(self):
        events = self.exercise_deploy(fresh=True)
        self.assertNotIn(('close-ingress',), events)
        self.assertIn(('activate',), events)

    def test_failure_stops_host_without_activating(self):
        events = self.exercise_deploy(failure=True)
        self.assertNotIn(('activate',), events)
        self.assertTrue(any(e[:2] == ('ec2', 'stop-instances') for e in events))

    def test_explicit_keep_running(self):
        events = self.exercise_deploy(stop=False)
        self.assertFalse(any(e[:2] == ('ec2', 'stop-instances') for e in events))


if __name__ == '__main__':
    unittest.main()
