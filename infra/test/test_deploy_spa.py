"""Verify release ordering and cache headers without AWS or a frontend build."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deploy_spa', Path(__file__).parents[1] / 'scripts/deploy_spa.py')
spa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(spa)


class SpaDeployTest(unittest.TestCase):
    def test_publish_dependencies_before_index_and_invalidate_both_entry_paths(self):
        calls = []

        def run(*args, **kwargs):
            calls.append(args)
            if args[:2] == ('aws', 'cloudformation'):
                return json.dumps({'Stacks': [{'Outputs': [
                    {'OutputKey': 'SpaBucketName', 'OutputValue': 'test-spa-bucket'},
                    {'OutputKey': 'DistributionId', 'OutputValue': 'EDEMO123'}]}]})

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build = root / 'wardrobe-fe/dist'
            (build / 'assets').mkdir(parents=True)
            (build / 'index.html').write_text('<html/>')
            with patch.object(spa, 'ROOT', root), patch.object(spa, 'run', side_effect=run):
                spa.deploy()
        uploads = [c for c in calls if c[:2] == ('aws', 's3')]
        self.assertEqual(len(uploads), 3)
        self.assertIn('public, max-age=31536000, immutable', uploads[0])
        self.assertIn('public, max-age=60', uploads[1])
        self.assertIn('assets/*', uploads[1])
        self.assertIn('index.html', uploads[1])
        self.assertEqual(uploads[2][2], 'cp')
        self.assertIn('s3://test-spa-bucket/index.html', uploads[2])
        self.assertEqual(calls[-1], ('aws', 'cloudfront', 'create-invalidation',
                                    '--distribution-id', 'EDEMO123', '--paths', '/index.html', '/'))
        self.assertFalse(any('--delete' in c for c in calls))

    def test_build_failure_does_not_publish_or_invalidate(self):
        calls = []

        def run(*args, **kwargs):
            calls.append(args)
            if args[:2] == ('aws', 'cloudformation'):
                return json.dumps({'Stacks': [{'Outputs': [
                    {'OutputKey': 'SpaBucketName', 'OutputValue': 'test-spa-bucket'},
                    {'OutputKey': 'DistributionId', 'OutputValue': 'EDEMO123'}]}]})
            if args == ('npm', 'run', 'build'):
                raise RuntimeError('build failed')

        with patch.object(spa, 'run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, 'build failed'):
                spa.deploy()
        self.assertFalse(any(c[:2] in [('aws', 's3'), ('aws', 'cloudfront')] for c in calls))
