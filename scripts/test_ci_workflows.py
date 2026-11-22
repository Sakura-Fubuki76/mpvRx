#!/usr/bin/env python3
"""Local CI policy regressions; needs PyYAML and Bash for publication simulations."""

import hashlib
import importlib.util
import json
from pathlib import Path
import os
import subprocess
import shutil
import shlex
import sys
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
ABIS = ("universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64")
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("preview_manifest", ROOT / "scripts/build_preview_manifest.py")
MANIFEST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MANIFEST)


class WorkflowPolicyTest(unittest.TestCase):
    def setUp(self):
        self.text = {
            name: (ROOT / f".github/workflows/{name}.yml").read_text(encoding="utf-8-sig")
            for name in ("build", "release", "preview")
        }
        self.workflows = {name: yaml.load(text, Loader=yaml.BaseLoader) for name, text in self.text.items()}

    def test_only_standard_apks_and_no_pages_pipeline(self):
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                self.assertNotRegex(self.text[name], r"(?i)fongmi|no[-_ ]?vulkan|playstore|\.github\.io|pages:|id-token:")
                self.assertNotRegex(self.text[name], r"actions/(configure-pages|upload-pages-artifact|deploy-pages)")
                build = workflow["jobs"]["build"]
                self.assertNotIn("strategy", build)
                self.assertEqual(build["env"]["APK_DIR"], "standard")
                self.assertEqual(tuple(build["env"]["APK_ABIS"].split()), ABIS)
                expected_task = "assembleStandardPreview" if name == "preview" else "assembleStandardRelease"
                self.assertIn(expected_task, self.text[name])

    def test_native_builds_checkout_submodules_and_pinned_source(self):
        for name, workflow in self.workflows.items():
            with self.subTest(workflow=name):
                checkout = next(step for step in workflow["jobs"]["build"]["steps"]
                                if step.get("uses", "").startswith("actions/checkout@"))
                self.assertEqual(checkout["with"]["submodules"], "recursive")
                self.assertEqual(checkout["with"]["fetch-depth"], "0")
                if name != "build":
                    owner = "prepare" if name == "release" else "check"
                    self.assertEqual(checkout["with"]["ref"], "${{ needs." + owner + ".outputs.source_sha }}")

    def test_five_ci_artifacts_and_five_required_stable_assets(self):
        uploads = [step for step in self.workflows["build"]["jobs"]["build"]["steps"]
                   if step.get("uses", "").startswith("actions/upload-artifact@")]
        self.assertEqual(len(uploads), len(ABIS))
        for abi in ABIS:
            self.assertTrue(any(f"app-standard-{abi}-release" in step["with"]["path"] for step in uploads))
        self.assertIn("variants=(" + " ".join(ABIS) + ")", self.text["release"])
        self.assertIn("Missing expected release asset", self.text["release"])

    def test_public_releases_require_signing_and_verification(self):
        for name in ("release", "preview"):
            steps = self.workflows[name]["jobs"]["build"]["steps"]
            signing = next(step for step in steps if step["name"].startswith("Sign and stage"))
            self.assertEqual(set(signing["env"]), {
                "SIGNING_KEYSTORE", "SIGNING_KEY_ALIAS", "SIGNING_STORE_PASSWORD", "KEY_PASSWORD"
            })
            for secret in signing["env"]:
                self.assertIn(f'-z "${secret}"', signing["run"])
            self.assertIn('"$apksigner" verify --verbose', signing["run"])
            self.assertIn("exit 1", signing["run"])

    def test_preview_manifest_published_after_apks_without_changing_stable_tags(self):
        workflow = self.workflows["preview"]
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        publish = workflow["jobs"]["publish"]
        self.assertEqual(publish["permissions"], {"contents": "write"})
        script = next(step["run"] for step in publish["steps"] if step["name"] == "Publish rolling preview prerelease")
        self.assertLess(script.index("gh release upload preview downloads/*.apk"), script.index("gh release upload preview latest.json"))
        self.assertLess(script.index("gh release upload preview latest.json"), script.index("--method DELETE"))
        self.assertIn("git/refs/tags/preview", script)
        self.assertIn("--prerelease --latest=false", script)
        check_script = next(step["run"] for step in workflow["jobs"]["check"]["steps"] if step["name"] == "Check for unpublished app activity")
        self.assertIn("scripts/build_preview_manifest.py", check_script)
        self.assertNotIn("preview-site", check_script)
        self.assertIn("/releases/download/preview/latest.json", check_script)
        updater = (ROOT / "app/src/main/java/app/gyrolet/mpvrx/domain/update/UpdateManager.kt").read_text(encoding="utf-8")
        self.assertIn('PREVIEW_RELEASE_URL = "https://github.com/Sakura-Fubuki76/mpvRx/releases/download/preview/latest.json"', updater)
        self.assertIn('STABLE_RELEASE_URL = "https://api.github.com/repos/Sakura-Fubuki76/mpvRx/releases/latest"', updater)


class PreviewManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.downloads = Path(self.temp.name)
        self.metadata = {
            "GITHUB_REPOSITORY": "Sakura-Fubuki76/mpvRx", "COMMIT_COUNT": "2400",
            "SOURCE_SHA": "abcdef0" * 5 + "abcde", "COMMIT_TITLE": 'test "preview"',
            "PUBLISHED_AT": "2026-11-22T12:00:00Z", "SOURCE_FINGERPRINT": "1" * 64,
            "GITHUB_RUN_ID": "123", "GITHUB_RUN_NUMBER": "45", "GITHUB_REF_NAME": "master",
        }
        for abi in ABIS:
            (self.downloads / f"mpvRx-preview-r2400-{abi}.apk").write_bytes(f"test APK bytes for {abi}".encode())

    def test_manifest_has_five_artifact_hashes_and_github_urls(self):
        manifest = MANIFEST.build_manifest(self.downloads, self.metadata)
        self.assertEqual(manifest["channel"], "preview")
        self.assertEqual(manifest["commit_count"], 2400)
        self.assertEqual(manifest["tag_name"], "preview-r2400")
        self.assertEqual(tuple(asset["abi"] for asset in manifest["assets"]), ABIS)
        for asset in manifest["assets"]:
            data = (self.downloads / asset["name"]).read_bytes()
            self.assertEqual(asset["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(asset["size"], len(data))
            self.assertEqual(asset["browser_download_url"],
                             "https://github.com/Sakura-Fubuki76/mpvRx/releases/download/preview/" + asset["name"])
        self.assertEqual(json.loads(json.dumps(manifest))["commit_sha"], self.metadata["SOURCE_SHA"])
        self.assertNotIn("pages_url", manifest)

    def test_cli_writes_the_manifest_using_workflow_metadata(self):
        output = self.downloads / "latest.json"
        subprocess.run([
            sys.executable, str(ROOT / "scripts/build_preview_manifest.py"),
            "--downloads-dir", str(self.downloads), "--output", str(output),
        ], env={**os.environ, **self.metadata}, check=True)
        result = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(result["commit_count"], 2400)
        self.assertEqual(len(result["assets"]), 5)

    def test_nonpositive_build_number_cannot_publish_a_manifest(self):
        self.metadata["COMMIT_COUNT"] = "0"
        with self.assertRaisesRegex(ValueError, "positive"):
            MANIFEST.build_manifest(self.downloads, self.metadata)

    def test_missing_abi_cannot_publish_a_manifest(self):
        next(self.downloads.glob("*.apk")).unlink()
        with self.assertRaisesRegex(ValueError, "missing="):
            MANIFEST.build_manifest(self.downloads, self.metadata)

    def test_extra_variant_cannot_publish_a_manifest(self):
        (self.downloads / "mpvRx-preview-r2400-fongmi.apk").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "extra="):
            MANIFEST.build_manifest(self.downloads, self.metadata)

    def test_empty_apk_cannot_publish_a_manifest(self):
        next(self.downloads.glob("*.apk")).write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "empty"):
            MANIFEST.build_manifest(self.downloads, self.metadata)


class PreviewPublicationTest(unittest.TestCase):
    setUp = PreviewManifestTest.setUp

    def run_publication(self, exists=False, fail_apks=False):
        bash = shutil.which("bash")
        git_bash = Path("C:/Program Files/Git/bin/bash.exe")
        if sys.platform == "win32" and git_bash.is_file():
            bash = str(git_bash)
        if not bash:
            self.skipTest("Bash is needed to simulate publication")
        manifest = MANIFEST.build_manifest(self.downloads, self.metadata)
        (self.downloads / "latest.json").write_text(json.dumps(manifest), encoding="utf-8")
        apks = self.downloads / "downloads"
        apks.mkdir()
        for apk in self.downloads.glob("*.apk"):
            apk.rename(apks / apk.name)
        workflow = yaml.load((ROOT / ".github/workflows/preview.yml").read_text(), Loader=yaml.BaseLoader)
        script = next(step["run"] for step in workflow["jobs"]["publish"]["steps"]
                      if step["name"] == "Publish rolling preview prerelease")
        # Intercept every GitHub CLI invocation; this test never accesses GitHub.
        prefix = r'''
          gh() {
            printf '%s\n' "$*" >> "$COMMAND_LOG"
            if [[ "$1 $2" == "release view" ]]; then
              [[ "$RELEASE_EXISTS" == true ]]; return
            fi
            if [[ "$1 $2 $3" == "release upload preview" && "$4" == downloads/* && "$FAIL_APKS" == true ]]; then
              return 9
            fi
            if [[ "$1 $2" == "api repos/$GITHUB_REPOSITORY/releases/tags/preview" ]]; then
              printf '100\tmpvRx-preview-r2400-universal.apk\n200\tmpvRx-preview-r2300-universal.apk\n'
            fi
          }
          python3() { PYTHON_EXE "$@"; }
        '''.replace("PYTHON_EXE", shlex.quote(Path(sys.executable).as_posix()))
        log = self.downloads / "commands.log"
        result = subprocess.run([bash, "--noprofile", "--norc", "-c", prefix + script],
                                cwd=self.downloads, env={**os.environ, **self.metadata,
                                "COMMAND_LOG": log.as_posix(), "RELEASE_EXISTS": str(exists).lower(),
                                "FAIL_APKS": str(fail_apks).lower()}, capture_output=True, text=True)
        if "NtCreateDirectoryObject" in result.stderr and "0xC0000022" in result.stderr:
            self.skipTest("Windows sandbox denied Git Bash IPC; rerun with permission or on Linux")
        self.assertTrue(log.exists(), result.stderr)
        return result, log.read_text()

    def test_first_publication_creates_prerelease_and_prunes_only_old_apks(self):
        result, commands = self.run_publication()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("release create preview", commands)
        self.assertIn("--prerelease --latest=false", commands)
        self.assertIn("--method DELETE repos/Sakura-Fubuki76/mpvRx/releases/assets/200", commands)
        self.assertNotIn("releases/assets/100", commands)
        self.assertIn("git/refs/tags/preview", commands)
        self.assertNotIn("git/refs/tags/v", commands)

    def test_existing_prerelease_is_updated_not_recreated(self):
        result, commands = self.run_publication(exists=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("release create", commands)
        self.assertIn("release edit preview", commands)

    def test_failed_apk_upload_cannot_replace_manifest_or_prune_old_assets(self):
        result, commands = self.run_publication(exists=True, fail_apks=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("release upload preview latest.json", commands)
        self.assertNotIn("--method DELETE", commands)


if __name__ == "__main__":
    unittest.main(verbosity=2)
