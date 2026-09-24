#!/usr/bin/env python3
"""
Scrape apkmirror for the latest official Hevy Wear OS APK version.

If the scraped (version_name, version_code) differs from both
api-versions/active.json AND api-versions/candidate.json, this script:
  - writes api-versions/candidate.json with the new values
  - appends an entry to api-versions/history.json
  - downloads the .apkm into $ARTIFACTS_DIR/hevy.apkm
  - extracts base.apk into $ARTIFACTS_DIR/base.apk
  - runs `apktool d` and writes $ARTIFACTS_DIR/smali.tar.gz

If nothing changed, exits 0 silently and writes no candidate.json. The
workflow keys off the presence of candidate.json to decide whether to
create a release, open an issue, and commit.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin

from playwright.sync_api import sync_playwright

REPO_ROOT = Path(__file__).resolve().parents[2]
API_VERSIONS_DIR = REPO_ROOT / "api-versions"
ACTIVE_FILE = API_VERSIONS_DIR / "active.json"
CANDIDATE_FILE = API_VERSIONS_DIR / "candidate.json"
HISTORY_FILE = API_VERSIONS_DIR / "history.json"
ARTIFACTS_DIR = Path(os.environ.get("ARTIFACTS_DIR", REPO_ROOT / ".hevy-archive"))

VARIANT_URL = (
    "https://www.apkmirror.com/apk/hevy-gym-workout-tracker/"
    "hevy-gym-log-workout-tracker-wear-os/"
    "variant-%7B%22arches_slug%22%3A%5B%22armeabi-v7a%22%5D%2C%22dpis_slug%22%3A%5B%22320%22%5D%7D/"
)

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

VERSION_RE = re.compile(r"(\d+\.\d+(?:\.\d+)?)\s*\((\d+)\)")


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_json(path: Path, default):
    if not path.exists():
        return default
    return json.loads(path.read_text())


def emit_output(**values: str) -> None:
    """Publish step outputs for the workflow; a no-op outside GitHub Actions.

    The workflow used to infer "did anything change?" from the mere presence
    of api-versions/candidate.json. That file stays committed until someone
    promotes it, so every run while a candidate was pending re-reported a
    change and then died on `git commit` with nothing staged. Only this
    script knows whether it actually wrote anything, so it says so directly.
    """
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        for key, value in values.items():
            fh.write(f"{key}={value}\n")


# apkmirror renders one release as a wrapper <div> holding a `div.appRow`
# (title, badges, per-release links) immediately followed by a sibling
# `div.infoSlide` carrying the "Version: X.Y.Z(BUILD)" row. The detail link
# lives in the appRow, under `a.infoLink` (the (i) icon) and `a.fontBlack`
# (the title); both point at the same release page. It used to be
# `a.downloadLink`, which apkmirror has since dropped — hence the pairing
# below rather than a bare page-wide selector, so a future rename fails on
# the version we actually parsed instead of silently returning a link from
# some other release's row.
DETAIL_LINK_XPATH = (
    "xpath=preceding-sibling::div[contains(concat(' ', normalize-space(@class), ' '), ' appRow ')][1]"
    "//a[contains(concat(' ', normalize-space(@class), ' '), ' infoLink ')"
    " or contains(concat(' ', normalize-space(@class), ' '), ' fontBlack ')]"
)


def scrape_latest(page) -> tuple[str, str, str]:
    """Return (version_name, version_code, release_detail_url).

    Walks the release list top-down (apkmirror lists newest first) and returns
    the first entry whose infoSlide carries an X.Y.Z(BUILD) version, together
    with the detail-page link from that same entry's appRow.
    """
    page.goto(VARIANT_URL, wait_until="domcontentloaded", timeout=60_000)
    page.wait_for_selector(".infoSlide-value", timeout=60_000)

    for slide in page.locator("div.infoSlide").all():
        version_name: str | None = None
        version_code: str | None = None
        for span in slide.locator(".infoSlide-value").all():
            m = VERSION_RE.search(span.inner_text().strip())
            if m:
                version_name = m.group(1)
                version_code = m.group(2)
                break
        if version_name is None or version_code is None:
            continue

        detail_links = slide.locator(DETAIL_LINK_XPATH)
        if detail_links.count() == 0:
            raise SystemExit(
                f"Found version {version_name} ({version_code}) but its appRow "
                "has no a.infoLink / a.fontBlack detail link. apkmirror page "
                "structure may have changed."
            )
        detail_href = detail_links.first.get_attribute("href")
        if not detail_href:
            raise SystemExit(
                f"Detail link for {version_name} ({version_code}) has no href. "
                "apkmirror page structure may have changed."
            )
        return version_name, version_code, urljoin(VARIANT_URL, detail_href)

    raise SystemExit(
        "Could not locate a version row matching X.Y.Z(BUILD) in any "
        ".infoSlide-value span. apkmirror page structure may have changed."
    )


def download_apkm(context, detail_url: str, target: Path) -> None:
    """Walk apkmirror's two-step download flow and save the .apkm."""
    page = context.new_page()
    page.goto(detail_url, wait_until="domcontentloaded", timeout=60_000)

    step1 = page.locator("a.downloadButton, a:has-text('Download APK Bundle')").first
    step1_href = step1.get_attribute("href")
    if not step1_href:
        raise SystemExit("No step-1 download button on detail page.")
    page.goto(urljoin(detail_url, step1_href), wait_until="domcontentloaded", timeout=60_000)

    with page.expect_download(timeout=180_000) as dl_info:
        page.locator("a:has-text('click here')").first.click()
    dl = dl_info.value
    target.parent.mkdir(parents=True, exist_ok=True)
    dl.save_as(str(target))


def extract_and_decompile(apkm: Path, out_dir: Path) -> None:
    work = out_dir / "_work"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    with zipfile.ZipFile(apkm) as zf:
        zf.extractall(work)

    base_apk = work / "base.apk"
    if not base_apk.exists():
        apks = sorted(work.glob("*.apk"), key=lambda p: p.stat().st_size, reverse=True)
        if not apks:
            raise SystemExit("No .apk found inside .apkm bundle.")
        base_apk = apks[0]
    shutil.copy2(base_apk, out_dir / "base.apk")

    smali_out = out_dir / "smali"
    if smali_out.exists():
        shutil.rmtree(smali_out)
    subprocess.run(
        ["apktool", "d", "-f", "-o", str(smali_out), str(out_dir / "base.apk")],
        check=True,
    )

    tar_path = out_dir / "smali.tar.gz"
    with tarfile.open(tar_path, "w:gz") as tar:
        tar.add(smali_out, arcname="smali")
    shutil.rmtree(smali_out)
    shutil.rmtree(work)


def matches(snapshot: dict, version_name: str, version_code: str) -> bool:
    return (
        snapshot.get("version_name") == version_name
        and snapshot.get("version_code") == version_code
    )


def main() -> int:
    active = load_json(ACTIVE_FILE, {})
    candidate = load_json(CANDIDATE_FILE, {})
    ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=True,
            args=["--disable-blink-features=AutomationControlled"],
        )
        context = browser.new_context(user_agent=USER_AGENT)
        page = context.new_page()
        version_name, version_code, detail_url = scrape_latest(page)
        print(f"apkmirror latest: {version_name} ({version_code})")
        print(f"active:           {active.get('version_name')} ({active.get('version_code')})")
        print(f"candidate:        {candidate.get('version_name')} ({candidate.get('version_code')})")

        if matches(active, version_name, version_code) or matches(candidate, version_name, version_code):
            print("No change vs. active or candidate — exiting.")
            emit_output(changed="false")
            browser.close()
            return 0

        print("Change detected — downloading .apkm.")
        apkm_path = ARTIFACTS_DIR / "hevy.apkm"
        download_apkm(context, detail_url, apkm_path)
        browser.close()

    extract_and_decompile(apkm_path, ARTIFACTS_DIR)

    detected_at = now_iso()
    CANDIDATE_FILE.write_text(json.dumps({
        "version_name": version_name,
        "version_code": version_code,
        "detected_at": detected_at,
        "source_url": VARIANT_URL,
    }, indent=2) + "\n")

    history = load_json(HISTORY_FILE, {"entries": []})
    history.setdefault("entries", []).append({
        "version_name": version_name,
        "version_code": version_code,
        "first_seen_at": detected_at,
    })
    HISTORY_FILE.write_text(json.dumps(history, indent=2) + "\n")

    emit_output(changed="true", version=version_name, build=version_code)
    print("Wrote candidate + history. Archive ready at:", ARTIFACTS_DIR)
    return 0


if __name__ == "__main__":
    sys.exit(main())
