import unittest
from release_version import select_version


def release(tag, code):
    return {'tag_name': tag, 'body': f'<!-- colorcode-version-code: {code} -->'}


class ReleaseVersionTest(unittest.TestCase):
    def test_first_and_patch_release(self):
        self.assertEqual(('v0.1.0', 1), select_version([], 'a', {}))
        self.assertEqual(('v0.1.1', 2), select_version([release('v0.1.0', 1)], 'b', {}))

    def test_rerun_and_requested_version(self):
        releases = [release('v0.1.0', 1)]
        self.assertEqual(('v0.1.0', 1), select_version(releases, 'a', {'v0.1.0': 'a'}))
        self.assertEqual(('v2.0.0', 2), select_version(releases, 'b', {}, '2.0.0'))
        self.assertEqual(('v0.1.1', 2), select_version(releases, 'b', {}, '0.1.0'))

    def test_bad_history_fails_closed(self):
        with self.assertRaises(ValueError): select_version([{'tag_name': 'v0.1.0'}], 'a', {})
        with self.assertRaises(ValueError): select_version([release('v0.1.0', 2), release('v0.1.1', 1)], 'a', {})
        with self.assertRaises(ValueError): select_version([], 'a', {}, '01.0.0')
