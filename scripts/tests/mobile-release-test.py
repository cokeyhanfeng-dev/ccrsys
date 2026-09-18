"""临时目录发布回归：真实 shell/打包/HTTP；构建阶段使用合成产物，不代表业务构建。"""
import hashlib
import http.server
import os
import re
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]

class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ccr-release-test-')
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        for name in ['package-release.sh', 'deploy-release.sh']:
            shutil.copy(ROOT / 'scripts' / name, self.root / 'scripts' / name)
        shutil.copytree(ROOT / 'docs/deployment/mobile', self.root / 'docs/deployment/mobile')
        self.pc = self.root / 'pc'; self.pc.mkdir(); (self.pc / 'keep').write_text('电脑端保留')
        self.mobile = self.root / 'mobile'; self.mobile.mkdir(); (self.mobile / 'old').write_text('旧移动版本')
        self.backup = self.root / 'backup'
        dev = self.root / 'dev'
        dev.write_text('''#!/bin/bash
set -eu
cd "$(dirname "$0")"
if [[ "${FIXTURE_BUILD_FAIL:-}" == 1 ]]; then exit 77; fi
mkdir -p backend/ccr-admin/target frontend/dist frontend/dist-mobile/assets
printf 'synthetic jar' > backend/ccr-admin/target/ccr-admin.jar
printf 'pc' > frontend/dist/index.html
printf '<script src="/mobile/assets/main.js"></script>' > frontend/dist-mobile/index.html
printf 'mobile js' > frontend/dist-mobile/assets/main.js
'''); dev.chmod(0o755)
        self.env = {**os.environ, 'CCR_MOBILE_DIR': str(self.mobile), 'CCR_FRONTEND_DIR': str(self.pc), 'CCR_BACKUP_DIR': str(self.backup)}
        self.code = 401
        owner = self
        class Handler(http.server.SimpleHTTPRequestHandler):
            def do_GET(self):
                if self.path == '/mobile/api/mobile/session':
                    self.send_response(200); self.end_headers()
                    self.wfile.write(('{"code":%d}' % owner.code).encode())
                else:
                    super().do_GET()
            def translate_path(self, path):
                return str(owner.root / path.lstrip('/'))
            def log_message(self, *args): pass
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.env['CCR_MOBILE_HEALTH_URL'] = f'http://127.0.0.1:{self.server.server_port}/mobile/'

    def tearDown(self):
        for log in getattr(self, "install_logs", []):
            log.unlink(missing_ok=True)
            Path(str(log)+".entries").unlink(missing_ok=True)
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.temp.cleanup()

    def package(self, mode='3'):
        result = subprocess.run(['bash', str(self.root / 'scripts/package-release.sh'), mode], env=self.env, capture_output=True, text=True, errors="replace", timeout=90)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return max((self.root / 'release').glob('*.tar.gz'), key=lambda p:p.stat().st_mtime_ns)

    def deploy(self, archive=None, mode='3', answer='yes\n'):
        args = ['bash', str(self.root / 'scripts/deploy-release.sh'), mode]
        if archive is not None: args.append(str(archive))
        result = subprocess.run(args, input=answer, env=self.env, capture_output=True, text=True, errors="replace", timeout=90)
        logs = re.findall(r'^安装日志: (.+)$', result.stdout, re.MULTILINE)
        self.install_logs = getattr(self, 'install_logs', []) + [Path(p) for p in logs]
        return result

    def test_all_modes_package_only_selected_components_and_valid_hashes(self):
        for mode in ['1','2','3','12','13','23','123']:
            archive = self.package(mode)
            with tarfile.open(archive) as tar:
                files = {m.name.split('/',1)[1]:tar.extractfile(m).read() for m in tar.getmembers() if m.isfile()}
            self.assertEqual('backend/ccr-admin.jar' in files, '1' in mode)
            self.assertEqual('frontend/dist/index.html' in files, '2' in mode)
            self.assertEqual('frontend/dist-mobile/index.html' in files, '3' in mode)
            self.assertIn('deployment/compose.nginx.yml', files)
            for line in files['SHA256SUMS'].decode().splitlines():
                digest, path = line.split('  ', 1)
                self.assertEqual(hashlib.sha256(files[path]).hexdigest(), digest)

    def test_mobile_success_keeps_pc_and_backs_up_old_files(self):
        archive = self.package()
        result = self.deploy(archive)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('100% 全部部署检查完成', result.stdout)
        self.assertIn(str(archive.resolve()), result.stdout)
        self.assertIn('安装包全名: ' + archive.name, result.stdout)
        self.assertNotIn('main.js: OK', result.stdout)
        self.assertIn('main.js: OK', self.install_logs[-1].read_text())
        self.assertIn('tail -n 200 -f ' + str(self.install_logs[-1]), result.stdout)
        self.assertIn(str(next(self.backup.glob('*/mobile.tar.gz')).resolve()), result.stdout)
        self.assertIn('docker logs --tail 200 -f nginx-nextcloud', result.stdout)
        self.assertTrue((self.mobile/'index.html').exists()); self.assertFalse((self.mobile/'old').exists())
        self.assertEqual((self.pc/'keep').read_text(), '电脑端保留')
        with tarfile.open(next(self.backup.glob('*/mobile.tar.gz'))) as tar:
            self.assertIn('./old', tar.getnames())

    def test_api_failure_rolls_back_mobile(self):
        self.code = 500
        result = self.deploy(self.package())
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('100%', result.stdout)
        self.assertIn('移动端旧文件已恢复', result.stdout)
        self.assertIn('匿名会话校验失败', self.install_logs[-1].read_text())
        self.assertIn(str(next(self.backup.glob('*/mobile.tar.gz')).resolve()), result.stdout)
        self.assertEqual((self.mobile/'old').read_text(), '旧移动版本')
        self.assertFalse((self.mobile/'index.html').exists())
        self.assertEqual((self.pc/'keep').read_text(), '电脑端保留')

    def test_missing_mobile_payload_does_not_touch_files(self):
        self.assertNotEqual(self.deploy(self.package('2')).returncode, 0)
        self.assertTrue((self.mobile/'old').exists())

    def test_overlap_and_symlink_alias_are_rejected(self):
        archive = self.package()
        for target in [self.pc, self.pc/'child', self.root, Path('/')]:
            self.env['CCR_MOBILE_DIR'] = str(target)
            self.assertNotEqual(self.deploy(archive).returncode, 0)
        alias = self.root/'alias'; alias.symlink_to(self.pc, target_is_directory=True)
        self.env['CCR_MOBILE_DIR'] = str(alias)
        self.assertNotEqual(self.deploy(archive).returncode, 0)
        self.assertEqual((self.pc/'keep').read_text(), '电脑端保留')

    def test_existing_lock_refuses_second_deployment(self):
        Path(str(self.mobile)+'.release-lock').mkdir()
        self.assertNotEqual(self.deploy(self.package()).returncode, 0)
        self.assertTrue((self.mobile/'old').exists())
        self.assertTrue(Path(str(self.mobile)+'.release-lock').exists())

    def test_checksum_failure_does_not_touch_existing_site(self):
        archive = self.package()
        unpacked = self.root/'tampered'; unpacked.mkdir()
        with tarfile.open(archive) as tar: tar.extractall(unpacked, filter='data')
        bundle = next(unpacked.iterdir())
        (bundle/'frontend/dist-mobile/index.html').write_text('tampered')
        broken = self.root/'broken.tar.gz'
        with tarfile.open(broken, 'w:gz') as tar: tar.add(bundle, arcname=bundle.name)
        self.assertNotEqual(self.deploy(broken).returncode, 0)
        self.assertEqual((self.mobile/'old').read_text(), '旧移动版本')
        self.assertFalse(self.backup.exists())

    def test_pc_output_reports_only_created_backups(self):
        archive = self.package('2')
        result = self.deploy(os.path.relpath(archive), '2')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(str(archive.resolve()), result.stdout)
        self.assertIn(str(next(self.backup.glob('*/frontend.tar.gz')).resolve()), result.stdout)
        self.assertNotIn('app.jar.bak', result.stdout)
        self.assertNotIn('mobile.tar.gz', result.stdout)
        self.assertIn('100%', result.stdout)

    def test_auto_select_latest_compatible_package(self):
        older = self.package('3')
        newest = self.root/'release'/'ccr-release-newer-all.tar.gz'
        shutil.copy(older, newest)
        incompatible = self.package('2')
        os.utime(older, (1000, 1000)); os.utime(newest, (2000, 2000))
        os.utime(incompatible, (3000, 3000))
        self.env['CCR_RELEASE_DIR'] = str(self.root/'release')
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('待部署包: ' + newest.name, result.stdout)
        self.assertIn('确认使用以上发布包部署？', result.stdout)
        self.assertTrue((self.mobile/'index.html').exists())

    def test_auto_selection_does_not_read_older_archives(self):
        archive = self.package('3')
        newest = archive.parent/'ccr-release-zz newest.tar.gz'
        shutil.copy(archive, newest)
        os.utime(archive, (1000, 1000)); os.utime(newest, (3000, 3000))
        broken = archive.parent/'ccr-release-aa-broken.tar.gz'
        broken.write_text('unreadable old archive'); os.utime(broken, (2000, 2000))
        # 记录真实 tar 调用，确保确认前只读取最新匹配包。
        bindir = self.root/'bin'; bindir.mkdir()
        calls = self.root/'tar-calls'
        wrapper = bindir/'tar'
        import shlex
        wrapper.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> '+shlex.quote(str(calls))+'\nexec '+shlex.quote(shutil.which('tar'))+' "$@"\n')
        wrapper.chmod(0o755)
        self.env['PATH'] = str(bindir)+os.pathsep+self.env['PATH']
        self.env['CCR_RELEASE_DIR'] = str(archive.parent)
        result = self.deploy(answer='no\n')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertIn('待部署包: '+newest.name, result.stdout)
        self.assertEqual(calls.read_text().splitlines(), ['-tzf '+str(newest)])
        self.assertFalse(self.backup.exists())

    def test_cancel_blank_no_and_eof_never_touch_targets(self):
        archive = self.package()
        for answer in ['no\n', '\n', '', 'anything\n']:
            result = self.deploy(archive, answer=answer)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('已取消部署', result.stdout)
            self.assertIn(archive.name, result.stdout)
            self.assertFalse(self.backup.exists())
            self.assertFalse(Path(str(self.mobile)+'.release-lock').exists())
            self.assertEqual((self.mobile/'old').read_text(), '旧移动版本')

    def test_auto_selection_requires_every_selected_component(self):
        self.package('3')
        self.package('2')
        self.env['CCR_RELEASE_DIR'] = str(self.root/'release')
        result = self.deploy(mode='23')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('确认使用', result.stdout)
        self.assertFalse(self.backup.exists())

    def test_build_failure_does_not_publish_archive(self):
        self.env['FIXTURE_BUILD_FAIL'] = '1'
        result = subprocess.run(['bash', str(self.root/'scripts/package-release.sh'), '3'],env=self.env,capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list((self.root/'release').glob('*.tar.gz')), [])

if __name__ == '__main__': unittest.main(verbosity=2)
