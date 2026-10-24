#!/usr/bin/env python3
"""
Automated Release Script for Mutqan Hadith
Handles Git status check, commit, tag detection, patch increment, and remote push.

Usage:
    python release.py ["commit message"] [--dry-run]

Any uncommitted work is staged and committed first (with the given message), the
next vX.Y.Z tag is derived from the highest existing tag, app/build.gradle.kts is
bumped to match, and the branch plus the new tag are pushed. The tag push is what
triggers the GitHub Actions workflow that builds, signs and publishes the APK.
--dry-run only prints the version that would be released and changes nothing.
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

GRADLE_FILE = "app/build.gradle.kts"
TAG_PATTERN = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def run_command(cmd, allow_failure=False):
    """Executes a command and returns stdout. Exits on failure unless allow_failure is True."""
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0 and not allow_failure:
        print(f"❌ Error running command: {' '.join(cmd)}")
        if result.stderr.strip():
            print(f"   Stderr: {result.stderr.strip()}")
        if result.stdout.strip():
            print(f"   Stdout: {result.stdout.strip()}")
        sys.exit(result.returncode)
    return result.stdout.strip()


def get_current_branch():
    """Gets the active git branch name."""
    branch = run_command(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    return branch if branch and branch != "HEAD" else "main"


def require_origin():
    """Stops before anything is committed or bumped when there is nowhere to push to."""
    if not run_command(["git", "remote", "get-url", "origin"], allow_failure=True):
        print("❌ No 'origin' remote is configured, so there is nowhere to push the release.")
        print("   Add one first:  git remote add origin <repository-url>")
        sys.exit(1)


def check_and_commit(commit_message):
    """Checks git status and commits uncommitted changes if any exist."""
    print("🔍 Checking repository status...")
    status = run_command(["git", "status", "--porcelain"])
    if status:
        print("✏️  Uncommitted changes detected. Staging and committing...")
        run_command(["git", "add", "-A"])
        run_command(["git", "commit", "-m", commit_message])
        print(f"✅ Committed changes: '{commit_message}'")
    else:
        print("✨ Working tree is clean. No uncommitted changes.")


def fetch_existing_tags():
    """Fetches existing tags from remote and returns list of tag names."""
    print("🌐 Fetching remote tags...")
    run_command(["git", "fetch", "--tags"], allow_failure=True)
    tags_output = run_command(["git", "tag", "-l"])
    return tags_output.splitlines() if tags_output else []


def get_next_version_tag(existing_tags):
    """Parses existing vX.Y.Z tags and computes the next patch tag."""
    parsed_versions = []

    for tag in existing_tags:
        match = TAG_PATTERN.match(tag.strip())
        if match:
            major, minor, patch = map(int, match.groups())
            parsed_versions.append((major, minor, patch))

    if not parsed_versions:
        next_tag = "v1.0.0"
    else:
        parsed_versions.sort()
        highest_major, highest_minor, highest_patch = parsed_versions[-1]
        next_tag = f"v{highest_major}.{highest_minor}.{highest_patch + 1}"

    while next_tag in existing_tags:
        match = TAG_PATTERN.match(next_tag)
        if match:
            maj, min_v, pat = map(int, match.groups())
            next_tag = f"v{maj}.{min_v}.{pat + 1}"

    return next_tag


def compute_version(content, next_tag):
    """Returns (versionCode, versionName) for next_tag given the current gradle file content."""
    match = TAG_PATTERN.match(next_tag)
    version_name = f"{match.group(1)}.{match.group(2)}.{match.group(3)}" if match else next_tag.lstrip("v")

    vc_match = re.search(r"versionCode\s*=\s*(\d+)", content)
    new_version_code = int(vc_match.group(1)) + 1 if vc_match else 1

    if match:
        major, minor, patch = map(int, match.groups())
        new_version_code = max(new_version_code, major * 10000 + minor * 100 + patch)

    return new_version_code, version_name


def update_build_gradle(next_tag):
    """Reads app/build.gradle.kts, increments versionCode and bumps versionName to match next_tag."""
    try:
        with open(GRADLE_FILE, "r", encoding="utf-8", newline="") as f:
            content = f.read()

        new_version_code, version_name = compute_version(content, next_tag)

        new_content = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {new_version_code}", content, count=1)
        new_content = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{version_name}"', new_content, count=1)

        with open(GRADLE_FILE, "w", encoding="utf-8", newline="") as f:
            f.write(new_content)

        print(f"📝 Updated {GRADLE_FILE}: versionCode = {new_version_code}, versionName = \"{version_name}\"")
    except Exception as e:
        print(f"❌ Failed to update {GRADLE_FILE}: {e}")
        sys.exit(1)


def publish_release(branch, new_tag):
    """Pushes commits and tag to remote repository."""
    print(f"🚀 Pushing branch '{branch}' to origin...")
    run_command(["git", "push", "origin", branch])

    print(f"🏷️  Creating local tag: '{new_tag}'...")
    run_command(["git", "tag", new_tag])

    print(f"📡 Pushing tag '{new_tag}' to origin...")
    run_command(["git", "push", "origin", new_tag])


def main():
    parser = argparse.ArgumentParser(description="Commit, tag and push a Mutqan Hadith release.")
    parser.add_argument("message", nargs="?", default="chore: release build", help="commit message for uncommitted changes")
    parser.add_argument("--dry-run", action="store_true", help="show the next version without changing anything")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    # Work from the repository root whatever directory the script was started from.
    os.chdir(Path(__file__).resolve().parent)

    print("========================================")
    print("🚀 Starting Automated Mutqan Hadith Release")
    print("========================================")

    branch = get_current_branch()
    print(f"📌 Active Branch: {branch}")

    if args.dry_run:
        next_tag = get_next_version_tag(fetch_existing_tags())
        with open(GRADLE_FILE, "r", encoding="utf-8") as f:
            code, name = compute_version(f.read(), next_tag)
        print(f"🎯 Next Tag: {next_tag} (versionCode = {code}, versionName = \"{name}\")")
        print("🧪 Dry run: nothing was committed, bumped or pushed.")
        return

    require_origin()
    check_and_commit(args.message)

    existing_tags = fetch_existing_tags()
    next_tag = get_next_version_tag(existing_tags)
    print(f"🎯 Calculated Next Tag: {next_tag}")

    update_build_gradle(next_tag)
    check_and_commit(f"chore: bump version to {next_tag}")

    publish_release(branch, next_tag)

    print("========================================")
    print(f"🎉 Success! Released {next_tag} on branch '{branch}'.")
    print("   GitHub Actions will now build and attach app-release.apk.")
    print("========================================")


if __name__ == "__main__":
    main()
