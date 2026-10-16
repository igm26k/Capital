import contextlib,io,json,os,pathlib,sys,tempfile,unittest
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'ops'))
import personal_signing

class PersonalSigning(unittest.TestCase):
    def test_real_key_is_stable_and_passwords_are_not_returned(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory)
            first=personal_signing.ensure_identity(root)
            credentials=(root/'identity/credentials.json').read_bytes();key=(root/'identity/personal-release.p12').read_bytes()
            second=personal_signing.ensure_identity(root)
            self.assertEqual(first,second);self.assertEqual(credentials,(root/'identity/credentials.json').read_bytes());self.assertEqual(key,(root/'identity/personal-release.p12').read_bytes())
            secret=json.loads(credentials)['store_password'];self.assertNotIn(secret,json.dumps(first))
            self.assertEqual((root/'identity/credentials.json').stat().st_mode&0o777,0o600)
            self.assertEqual((root/'identity/personal-release.p12').stat().st_mode&0o777,0o600)
    def test_partial_identity_never_generates_another_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);(root/'identity').mkdir(mode=0o700)
            key=root/'identity/personal-release.p12';key.write_bytes(b'preserved');key.chmod(0o600)
            with mock.patch.object(personal_signing,'run') as command:
                with self.assertRaises(Exception):personal_signing.ensure_identity(root)
                command.assert_not_called()
            self.assertEqual(key.read_bytes(),b'preserved')
    def test_unsafe_permissions_and_symlink_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);root.chmod(0o755)
            with self.assertRaises(RuntimeError):personal_signing.ensure_identity(root)
            root.chmod(0o700);target=root/'target';target.mkdir(mode=0o700);(root/'identity').symlink_to(target)
            with self.assertRaises(RuntimeError):personal_signing.ensure_identity(root)
    def test_orphaned_first_creation_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);(root/'.identity-interrupted').mkdir(mode=0o700)
            with mock.patch.object(personal_signing,'run') as command:
                with self.assertRaises(RuntimeError):personal_signing.ensure_identity(root)
                command.assert_not_called()
    def test_read_only_plan_creates_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            target=pathlib.Path(directory)/'not-created'
            with mock.patch.object(sys,'argv',['personal_signing','--plan','--directory',str(target)]),contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(personal_signing.main(),0)
            self.assertFalse(target.exists());self.assertFalse(json.loads(output.getvalue())['mutations'])
