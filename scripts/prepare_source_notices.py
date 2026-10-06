#!/usr/bin/env python3
"""Stage existing notices and a bounded acquisition index; never assert source completeness.

No downloads, dependency resolution, native transformation or publication occur here.
The allowlist deliberately excludes user data, build outputs, models and source snapshots.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
from urllib.parse import quote, urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "dj-thank/choplab-sampler"
INPUTS = (
    "LICENSE", "NOTICE.md", "docs/RELEASE.md",
    "licenses/onnxruntime-LICENSE.txt", "licenses/quickjs-LICENSE.txt",
    "licenses/Apache-2.0.txt", "licenses/LGPL-2.1.txt", "licenses/GPL-3.0.txt", "licenses/jna-NOTICE.txt",
    "config/source-notice-texts.json",
    "config/android-ffmpeg-audio.json", "scripts/android_runtime_pins.json",
    "config/newpipe-dependencies.json", "config/windows-ffmpeg-audio.json",
    "config/windows-onnxruntime.json", "config/windows-quickjs.json",
    "config/mac-media-tool-files.txt",
    "config/mac-media-tool-files-20261006.txt", "scripts/mac_tool_policy.py",
    "scripts/build_android_audio_runtime.py", "scripts/prepare_android_python_runtime.py",
    "scripts/build_windows_audio_runtime.py", "scripts/prepare_next_native_candidate.py",
    "scripts/prepare_windows_runtime.py", "scripts/prepare_media_tools.py",
    "scripts/prepare_mac_media_tools.py", "scripts/package_mac_app.py",
    "scripts/prepare_source_notices.py", ".github/workflows/next-native-candidate.yml",
)
INDEX = "SOURCE-INDEX.json"
README = "SOURCE-INDEX.md"
NIO_LICENSE = "licenses/desugar-configuration-LICENSE.txt"
ARCHIVE_ROOT = "source-notices/"
# These allowlisted recipes are reference documents, never package runtime code.
# Keep original source identities while making the bundled copies visible text.
PACKAGED_PATHS = {name: name + ".txt" for name in INPUTS if name.endswith(".py")}
# Android's asset merger ignores dot directories.
PACKAGED_PATHS[".github/workflows/next-native-candidate.yml"] = "recipes/next-native-candidate.yml"
RECIPE_BEGIN = b"----- BEGIN ORIGINAL SOURCE BYTES -----\n"
RECIPE_END = b"\n----- END ORIGINAL SOURCE BYTES -----\n"
UNRESOLVED = (
    {"id": "android-reused-native", "license": "NOASSERTION", "sourcePublicationVerified": False,
     "detail": "Reused AAR ELF components still need their exact source, Termux patches and build/install correspondence. The 18 header archives and Java source JAR are not that complete source."},
    {"id": "android-nio", "license": "NOASSERTION", "sourcePublicationVerified": False,
     "detail": "NIO 1,459 classes and configuration 29 helper classes still need exact generated/renamed source, header and build correspondence; recorded source candidates are not verified coverage."},
    {"id": "mac-tools", "license": "NOASSERTION", "sourcePublicationVerified": False,
     "detail": "Relocated Homebrew FFmpeg/Node libraries and standalone yt-dlp still need exact matching source/build and notice coverage. Tool version/hash records alone do not provide it."},
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL)


def regular_file(root: Path, name: str) -> Path:
    relative = PurePosixPath(name)
    if relative.is_absolute() or ".." in relative.parts or "\\" in name:
        raise ValueError("Unsafe notice input name")
    path = root.joinpath(*relative.parts)
    if any(p.is_symlink() for p in [path, *path.parents] if p == root or root in p.parents):
        raise ValueError(f"Notice input/output must not be a symlink: {name}")
    if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError(f"Missing or oversized notice input: {name}")
    return path


def immutable_url(revision: str, name: str) -> str:
    return f"https://github.com/{REPOSITORY}/blob/{revision}/{quote(name, safe='/')}"


def packaged_path(name: str) -> str:
    return PACKAGED_PATHS.get(name, name)


def recipe_display(name: str, data: bytes, url: str | None) -> bytes:
    """A readable, reversible display document; the delimited source is unchanged."""
    data.decode("utf-8")  # Source must remain readable text, not an encoded payload.
    header = ("Source recipe display (plain text; not executable)\n"
              f"sourcePath: {name}\nsourceBytes: {len(data)}\nsourceSha256: {digest(data)}\n"
              f"immutableUrl: {url or 'unavailable: local source differs from recorded revision'}\n"
              "Original source bytes are preserved between the explicit delimiters.\n"
              "For rebuilding, obtain the original .py from its recorded URL or Git source snapshot.\n\n")
    return header.encode("utf-8") + RECIPE_BEGIN + data + RECIPE_END


def revision_matches(root: Path, revision: str, name: str, data: bytes) -> bool:
    try:
        return git(root, "show", f"{revision}:{name}") == data
    except subprocess.CalledProcessError:
        return False


def markdown_for_bundle(root: Path, revision: str, name: str, data: bytes) -> bytes:
    """Keep bundled links local; externalize only byte-matching committed references.

    A locally changed, unbundled reference is visibly unavailable instead of being
    misrepresented as the content at HEAD. The canonical NOTICE stays unmodified.
    """
    def replace(match: re.Match[str]) -> str:
        label, target = match.groups()
        url = urlsplit(target)
        if url.scheme or url.netloc or not url.path:
            return match.group(0)
        relative = os.path.normpath(str(PurePosixPath(name).parent / url.path)).replace("\\", "/")
        if relative in INPUTS:
            destination = os.path.relpath(packaged_path(relative), PurePosixPath(packaged_path(name)).parent).replace("\\", "/")
            return f"[{label}]({destination}" + ("#" + url.fragment if url.fragment else "") + ")"
        path = regular_file(root, relative)
        if revision_matches(root, revision, relative, path.read_bytes()):
            suffix = "#" + quote(url.fragment, safe="-") if url.fragment else ""
            return f"[{label}]({immutable_url(revision, relative)}{suffix})"
        return f"{label} (`{relative}`: local change; no immutable reference yet)"
    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", replace, data.decode("utf-8")).encode("utf-8")


def create_files(root: Path, platform: str, *, require_committed: bool = False) -> dict[str, bytes]:
    if platform not in {"android", "windows", "mac", "all"}:
        raise ValueError("Unsupported notice platform")
    revision = git(root, "rev-parse", "HEAD").decode().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("A full Git source revision is required")
    files: dict[str, bytes] = {}
    entries = []
    for name in INPUTS:
        original = regular_file(root, name).read_bytes()
        matches = revision_matches(root, revision, name, original)
        output_name = packaged_path(name)
        url = immutable_url(revision, name) if matches else None
        if name.endswith(".py"):
            files[output_name] = recipe_display(name, original, url)
        else:
            files[output_name] = markdown_for_bundle(root, revision, name, original) if name.endswith(".md") else original
        row = {"path": output_name, "sourcePath": name, "bytes": len(files[output_name]), "sha256": digest(files[output_name]),
               "sourceSha256": digest(original), "matchesRevision": matches, "immutableUrl": url}
        if name.endswith(".py"):
            row.update(displayTransform="plain-source-text-v1", sourceBytes=len(original))
        entries.append(row)
    modified = bool(git(root, "status", "--porcelain", "--untracked-files=normal").strip())
    if require_committed and (modified or not all(row["matchesRevision"] for row in entries)):
        raise ValueError("Publication index requires an unchanged committed source checkout")
    for record in json.loads(files["config/source-notice-texts.json"])["texts"]:
        data = files[record["path"]]
        if len(data) != record["bytes"] or digest(data) != record["sha256"]:
            raise ValueError("Distribution license text differs from its recorded artifact bytes")
    pins = json.loads(files["config/newpipe-dependencies.json"])
    expected = next(row["license_evidence"] for row in pins["artifacts"]
                    if row["coordinate"] == "com.android.tools:desugar_jdk_libs_configuration_nio:2.1.5")
    license_text = re.search(r"```text\n(Copyright \(c\) 2016, the R8 project authors\..*?)```",
                             files["NOTICE.md"].decode(), re.S)
    if not license_text:
        raise ValueError("Missing full NIO configuration license in NOTICE")
    # The Markdown closing fence needs a newline; the pinned JAR text has none.
    nio = license_text.group(1).removesuffix("\n").encode()
    if len(nio) != expected["bytes"] or digest(nio) != expected["sha256"]:
        raise ValueError("NIO configuration license differs from its pinned bytes")
    files[NIO_LICENSE] = nio
    entries.append({"path": NIO_LICENSE, "bytes": len(nio), "sha256": digest(nio),
                    "derivedFrom": "NOTICE.md / config/newpipe-dependencies.json license_evidence"})
    payload = {"schema": 1, "kind": "notice-and-acquisition-index", "platform": platform,
               "repository": REPOSITORY, "revision": revision, "hasLocalChanges": modified,
               "sourceSnapshotUrl": (f"https://github.com/{REPOSITORY}/archive/{revision}.tar.gz"
                                     if not modified and all(row.get("matchesRevision", True) for row in entries) else None),
               "sourceSnapshotIsCompleteCorrespondingSource": False,
               "sourcePublicationVerified": False, "correspondingSourceComplete": False,
               "noticeCoverageComplete": False, "publicPass": False,
               "unresolved": list(UNRESOLVED), "files": entries}
    files[INDEX] = (json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
    lines = ["# Source and notices / ソースと表示の取得索引", "",
             "これはnotice本文と既存の取得情報です。対応sourceの完全性や PUBLIC_PASS を認定しません。",
             f"Source revision: `{revision}`; local changes: `{str(modified).lower()}`; target: `{platform}`.", "",
             "[全文NOTICE](NOTICE.md) / [MIT本文](LICENSE) / [配布条件](docs/RELEASE.md) / [機械可読index](SOURCE-INDEX.json)", "",
             "## 取得手順", "",
             "下表の固定manifestには取得URL・hash・source候補の区別があります。binary、header、source JARを完全な対応sourceと読み替えません。",
             "同梱recipeはそのmanifestを使う再現手順です。immutable linkは記録commitと入力bytesが一致するfileにだけ付けています。",
             "Python recipeの `.py.txt` は表示用plain textです。説明headerと明示区切りを追加し、その間の原source bytesは無改変で保持します。packageから実行するファイルではありません。",
             "indexの `sourcePath` / `sourceBytes` / `sourceSha256` は原本、`path` / `bytes` / `sha256` は表示copyを示します。再build用の元 `.py` は下表のrecorded revisionまたはGit source snapshotから取得してください。",
             "URLの到達性・source対応・公開提供は別途確認が必要です。Git source snapshotだけでは完全な対応sourceではありません。", "",
             "| File | Bundled copy | Immutable source |", "|---|---|---|"]
    for row in entries:
        name = row["path"]
        url = row.get("immutableUrl")
        lines.append(f"| `{name}` | [open]({name}) | " + (f"[recorded revision]({url})" if url else "local/derived; no immutable assertion") + " |")
    lines.extend(["", "## 未解決の対応", "", "sourcePublicationVerified=false / correspondingSourceComplete=false / noticeCoverageComplete=false / publicPass=false", ""])
    lines.extend(f"- **{row['id']} — NOASSERTION**: {row['detail']}" for row in UNRESOLVED)
    lines.extend(["", "既存Windows FFmpegの `tools/ffmpeg-notices/`・`FFmpeg-SOURCES.json`、JAR内ThirdPartyNotices、Java runtimeのlegal文書も保持します。",
                  "このindexはそれらを置き換えず、選択platformで全てが同梱済みとも主張しません。", ""])
    files[README] = "\n".join(lines).encode()
    return files


def stage(root: Path, output: Path, platform: str, *, archive: Path | None = None,
          require_committed: bool = False) -> dict:
    files = create_files(root, platform, require_committed=require_committed)
    for name in INPUTS:
        if name.endswith(".py") and ((output / name).exists() or (output / name).is_symlink()):
            # Reused generated outputs must not silently retain the old executable
            # presentation. Never delete an existing app/native file on its behalf.
            raise ValueError("Legacy executable notice recipe remains; use a clean generated notice output")
    # Validate every destination before writing. Existing app/native files outside
    # this fixed set are untouched and never enter the sidecar archive.
    for name in files:
        path = output / name
        if any(parent.is_symlink() for parent in [path, *path.parents]
               if parent == output or output in parent.parents):
            raise ValueError("Refusing a symlink notice destination")
        if path.exists() and not path.is_file():
            raise ValueError("Refusing a non-file notice destination")
    if archive is not None and (archive.is_symlink() or (archive.exists() and not archive.is_file())):
        raise ValueError("Refusing a non-file notice archive")
    for name, data in files.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    if archive is not None:
        archive.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
            for name, data in sorted(files.items()):
                info = zipfile.ZipInfo(ARCHIVE_ROOT + name, (1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                target.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return json.loads(files[INDEX])


def validate_archive(path: Path, revision: str, *, root: Path = ROOT) -> None:
    """Bind every archive byte to the caller's verified, clean source revision."""
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        expected = {ARCHIVE_ROOT + packaged_path(name) for name in (*INPUTS, NIO_LICENSE, INDEX, README)}
        if len(names) != len(set(names)) or set(names) != expected:
            raise ValueError("Unexpected source-notice archive entries")
        for item in archive.infolist():
            if stat.S_IFMT(item.external_attr >> 16) != stat.S_IFREG or item.file_size > 2 * 1024 * 1024:
                raise ValueError("Non-regular or oversized source-notice entry")
        index = json.loads(archive.read(ARCHIVE_ROOT + INDEX))
        if index.get("revision") != revision or index.get("hasLocalChanges") is not False:
            raise ValueError("Source-notice revision or working tree mismatch")
        if index.get("kind") != "notice-and-acquisition-index" or index.get("repository") != REPOSITORY:
            raise ValueError("Invalid source-notice identity")
        if index.get("sourceSnapshotUrl") != f"https://github.com/{REPOSITORY}/archive/{revision}.tar.gz":
            raise ValueError("Source-notice snapshot reference mismatch")
        for key in ("sourcePublicationVerified", "correspondingSourceComplete", "noticeCoverageComplete", "publicPass", "sourceSnapshotIsCompleteCorrespondingSource"):
            if index.get(key) is not False:
                raise ValueError("Source-notice index cannot assert unresolved publication gates")
        if index.get("unresolved") != list(UNRESOLVED):
            raise ValueError("Source-notice unresolved correspondence changed")
        rows = index.get("files", [])
        if len(rows) != len(INPUTS) + 1 or {row["path"] for row in rows} != {packaged_path(name) for name in (*INPUTS, NIO_LICENSE)}:
            raise ValueError("Source-notice file inventory mismatch")
        for row in rows:
            data = archive.read(ARCHIVE_ROOT + row["path"])
            if len(data) != row["bytes"] or digest(data) != row["sha256"]:
                raise ValueError("Source-notice file hash mismatch")
            if row["path"] != NIO_LICENSE and (row.get("matchesRevision") is not True or
                    row.get("sourcePath") not in INPUTS or packaged_path(row["sourcePath"]) != row["path"] or
                    row.get("immutableUrl") != immutable_url(revision, row["sourcePath"])):
                raise ValueError("Source-notice immutable reference mismatch")
        # Hashes and immutable URLs declared inside the ZIP are not source proof.
        # Reuse the canonical generator against the caller's actual Git checkout;
        # this also checks original license pins and the Markdown transformation.
        try:
            if git(root, "rev-parse", "HEAD").decode().strip() != revision:
                raise ValueError("Source-notice revision does not match the source checkout")
            canonical = create_files(root, index.get("platform"), require_committed=True)
        except (OSError, subprocess.CalledProcessError, UnicodeError) as error:
            raise ValueError("Source-notice source checkout cannot be verified") from error
        for name, data in canonical.items():
            if archive.read(ARCHIVE_ROOT + name) != data:
                raise ValueError(f"Source-notice canonical bytes mismatch: {name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--platform", choices=("android", "windows", "mac", "all"), required=True)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--require-committed", action="store_true")
    args = parser.parse_args()
    index = stage(args.root, args.out, args.platform, archive=args.archive,
                  require_committed=args.require_committed)
    print(json.dumps({"revision": index["revision"], "files": len(index["files"]),
                      "sourcePublicationVerified": False, "publicPass": False}))


if __name__ == "__main__":
    main()
