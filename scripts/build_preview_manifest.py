#!/usr/bin/env python3
"""Build the Standard-only preview manifest served from a GitHub prerelease asset."""

import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import quote


STANDARD_ABIS = ("universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def build_manifest(downloads_dir: Path, metadata: dict[str, str]) -> dict:
    repository = metadata["GITHUB_REPOSITORY"]
    commit_count = int(metadata["COMMIT_COUNT"])
    if commit_count < 1:
        raise ValueError("Preview commit count must be positive")
    expected = {f"mpvRx-preview-r{commit_count}-{abi}.apk" for abi in STANDARD_ABIS}
    actual = {apk.name for apk in downloads_dir.glob("*.apk")}
    if actual != expected:
        raise ValueError(
            f"Unexpected preview APK set; missing={sorted(expected - actual)}, "
            f"extra={sorted(actual - expected)}"
        )
    base_url = f"https://github.com/{repository}/releases/download/preview"
    assets = []
    for abi in STANDARD_ABIS:
        name = f"mpvRx-preview-r{commit_count}-{abi}.apk"
        apk = downloads_dir / name
        size = apk.stat().st_size
        if size == 0:
            raise ValueError(f"Preview APK is empty: {name}")
        digest = hashlib.sha256()
        with apk.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        assets.append({
            "abi": abi,
            "browser_download_url": f"{base_url}/{quote(name)}",
            "name": name,
            "size": size,
            "content_type": "application/vnd.android.package-archive",
            "sha256": digest.hexdigest(),
        })
    source_sha = metadata["SOURCE_SHA"]
    commit_title = metadata["COMMIT_TITLE"]
    run_id = metadata["GITHUB_RUN_ID"]
    run_number = metadata["GITHUB_RUN_NUMBER"]
    run_url = f"{metadata.get('GITHUB_SERVER_URL', 'https://github.com')}/{repository}/actions/runs/{run_id}"
    return {
        "tag_name": f"preview-r{commit_count}",
        "name": f"mpvRx Preview r{commit_count}",
        "body": f"Commit: {commit_title}\n\nStandard APK preview from workflow run #{run_number}. "
                f"Source commit: {source_sha[:7]}.\n\n{run_url}",
        "published_at": metadata["PUBLISHED_AT"],
        "assets": assets,
        "channel": "preview",
        "commit_sha": source_sha,
        "commit_title": commit_title,
        "commit_count": commit_count,
        "source_fingerprint": metadata["SOURCE_FINGERPRINT"],
        "run_number": int(run_number),
        "run_id": int(run_id),
        "run_url": run_url,
        "source_branch": metadata["GITHUB_REF_NAME"],
        "source_workflow": "Preview Build",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--downloads-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = build_manifest(args.downloads_dir, dict(os.environ))
    args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
