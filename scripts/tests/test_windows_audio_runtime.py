import hashlib
from io import BytesIO
import json
from pathlib import Path, PurePosixPath
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import windows_audio_runtime as policy
from scripts import build_windows_audio_runtime as build
from scripts.acceptance import windows_audio_tools as acceptance
from scripts.check_public_surface import is_packaged_runtime_binary_path, packaged_runtime_digest_findings
from scripts.acceptance.windows_audio_tools import codec_names


class WindowsAudioRuntimeTest(unittest.TestCase):
    def fixture(self, directory):
        profile = {"schema": 1, "id": "fixture", "ffmpeg": {"sha256": "source identity"}}
        payloads = {"ffmpeg.exe": b"fixture ffmpeg", "ffprobe.exe": b"fixture ffprobe", "codec.dll": b"fixture DLL",
                    "FFmpeg-runtime.json": b"{}", "FFmpeg-SOURCES.json": json.dumps(profile).encode(),
                    "FFmpeg-LICENSE.txt": b"fixture notice"}
        for name, content in payloads.items():
            (directory / name).write_bytes(content)
        files = {name: {"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()} for name, value in payloads.items()}
        profile["derived"] = {"profileInputsSha256": policy.inputs_hash(profile), "files": files}
        return profile, payloads

    def test_candidate_bytes_source_notice_and_complete_file_set_are_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            profile, payloads = self.fixture(directory)
            self.assertEqual(set(payloads), set(policy.validate_directory(directory, profile)))
            (directory / "unaccounted.dll").write_bytes(b"not admitted")
            with self.assertRaisesRegex(ValueError, "file set"):
                policy.validate_directory(directory, profile)
            (directory / "unaccounted.dll").unlink()
            (directory / "codec.dll").write_bytes(b"different")
            with self.assertRaisesRegex(ValueError, "identity differs"):
                policy.validate_directory(directory, profile)
            (directory / "codec.dll").write_bytes(payloads["codec.dll"])
            (directory / "FFmpeg-LICENSE.txt").unlink()
            with self.assertRaisesRegex(ValueError, "file set"):
                policy.validate_directory(directory, profile)

    def test_unsafe_path_stale_source_and_symlink_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            profile, _ = self.fixture(directory)
            profile["ffmpeg"]["sha256"] = "changed source"
            with self.assertRaisesRegex(ValueError, "source/build"):
                policy.pinned_files(profile)
            profile, _ = self.fixture(directory)
            profile["derived"]["files"]["../escape"] = {"bytes": 0, "sha256": ""}
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                policy.pinned_files(profile)
            profile, _ = self.fixture(directory)
            (directory / "linked").symlink_to(directory / "codec.dll")
            with self.assertRaisesRegex(ValueError, "symlink"):
                policy.validate_directory(directory, profile)

    def test_scanner_admits_only_exact_dll_and_rejects_self_reported_hash_or_missing_notice(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile, payloads = self.fixture(Path(temporary))
            entry = PurePosixPath("ChopLab Preview/tools/codec.dll")
            with patch.object(policy, "load_profile", return_value=profile):
                self.assertTrue(is_packaged_runtime_binary_path(entry, None))
                self.assertFalse(is_packaged_runtime_binary_path(entry.with_name("unknown.dll"), None))
                self.assertFalse(is_packaged_runtime_binary_path(entry, PurePosixPath("nested.zip")))
                for changed, omitted, expected_failure in ((False, False, False), (True, False, True), (False, True, True)):
                    with zipfile.ZipFile(BytesIO(), "w") as archive:
                        data = b"self declared replacement" if changed else payloads["codec.dll"]
                        manifest = {"ffmpegAudioInputsSha256": policy.inputs_hash(profile), "sha256": {"codec.dll": hashlib.sha256(data).hexdigest()}}
                        archive.writestr("ChopLab Preview/tools/runtime.json", json.dumps(manifest))
                        for name, content in payloads.items():
                            if name.endswith((".exe", ".dll")) or (omitted and name == "FFmpeg-LICENSE.txt"):
                                continue
                            archive.writestr(str(entry.parent / name), content)
                        findings = packaged_runtime_digest_findings(archive, entry, data, len(data), "fixture")
                        self.assertEqual(expected_failure, bool(findings))

    def test_dll_closure_rejects_unresolved_dependencies_and_entry_points(self):
        roots = [Path("ffmpeg.exe")]
        libraries = {"codec.dll": Path("codec.dll")}
        imports = {"ffmpeg.exe": {"codec.dll": ["decode"]}, "codec.dll": {"KERNEL32.dll".lower(): ["Sleep"]}}
        with patch.object(build, "pe_imports", side_effect=lambda _, path: imports[path.name]), \
                patch.object(build.subprocess, "check_output", return_value="  Name: decode\n"):
            self.assertEqual({"ffmpeg.exe", "codec.dll"}, set(build.dll_closure(Path("readobj"), roots, libraries)))
            imports["codec.dll"]["missing.dll"] = ["entry"]
            with self.assertRaisesRegex(ValueError, "Unresolved DLL"):
                build.dll_closure(Path("readobj"), roots, libraries)
            del imports["codec.dll"]["missing.dll"]
            imports["ffmpeg.exe"]["codec.dll"] = ["missing_entry"]
            with self.assertRaisesRegex(ValueError, "Unresolved imports"):
                build.dll_closure(Path("readobj"), roots, libraries)

    def test_cli_codec_inventory_keeps_required_video_and_excludes_legend_or_invalid_flags(self):
        text = ("Decoders:\n A..... = Audio\n V..... = Video\n S..... = Subtitle\n"
                " A....D aac AAC\n A....D libopus Opus\n VF...D hdr HDR (High Dynamic Range) image\n"
                " A..X.D sonic Sonic\n S..... subrip text\n AXXXXX not_a_codec invalid flags\n")
        self.assertEqual({"aac", "libopus", "hdr", "sonic", "subrip"}, codec_names(text))


class WindowsCodecInventoryTest(unittest.TestCase):
    # Independently transcribed from the pinned FFCodec registrations; these are CLI names, not codec IDs.
    EXPECTED_ALIASES = {
        "decoder": {"acelp_kelvin": "acelp.kelvin", "adpcm_g722": "g722", "adpcm_g726": "g726",
                    "adpcm_g726le": "g726le", "atrac3p": "atrac3plus", "atrac3pal": "atrac3plusal",
                    "ffwavesynth": "wavesynth", "interplay_acm": "interplayacm", "ra_144": "real_144", "ra_288": "real_288"},
        "encoder": {"adpcm_g722": "g722", "adpcm_g726": "g726", "adpcm_g726le": "g726le",
                    "ra_144": "real_144", "sonic_ls": "sonicls"},
    }

    def setUp(self):
        self.profile = policy.load_profile()
        self.configured = {kind: set(next(value.split("=", 1)[1].split(",") for value in self.profile["configure"]
                                        if value.startswith("--enable-" + kind + "="))) for kind in ("decoder", "encoder")}
        self.public = {kind: {self.EXPECTED_ALIASES[kind].get(name, name) for name in names}
                       for kind, names in self.configured.items()}

    def run_inventory(self, kind=None, missing=None, replacement=None, filter_flags="..", missing_filter=None):
        def command(_, *args):
            option = args[-1]
            if option in ("-decoders", "-encoders"):
                current = option[1:-1]
                names = self.public[current].copy()
                if current == kind:
                    names.remove(missing)
                    if replacement:
                        names.add(replacement)
                # HDR is registered as AVMEDIA_TYPE_VIDEO in hdrdec.c:223 / hdrenc.c:174.
                text = " A..... = Audio\n V..... = Video\n" + "\n".join(
                    (" VF...D " if name == "hdr" else " A....D ") + name + " fixture" for name in sorted(names))
            elif option == "-protocols":
                text = "file\npipe\nhttp\nhttps\ntls\ntcp\nudp\nsrt\nsftp\nzmq\n"
            elif option == "-filters":
                text = "\n".join(" " + filter_flags + " " + name + " A->A fixture" for name in
                                 ("aresample", "atempo", "rubberband", "loudnorm", "amix", "afade", "atrim", "concat", "amovie", "azmq") if name != missing_filter)
            else:
                self.fail("Unexpected CLI query: " + option)
            return text.encode(), b""
        with patch.object(acceptance, "run", side_effect=command):
            return acceptance.inventory(Path("fixture-ffmpeg.exe"), self.profile)

    def test_all_221_decoder_and_86_encoder_requirements_use_exact_registered_names(self):
        self.assertEqual({"decoder": 221, "encoder": 86}, {kind: len(names) for kind, names in self.configured.items()})
        result = self.run_inventory()
        self.assertEqual(self.profile["ffmpeg"]["sha256"], result["codecNameSourceSha256"])
        for kind in self.configured:
            self.assertEqual(len(self.configured[kind]), len(self.public[kind]), "No requirement was merged or dropped")
            self.assertEqual(self.public[kind], set(result[kind + "s"]))
            self.assertEqual(self.EXPECTED_ALIASES[kind], result[kind + "Aliases"])
            self.assertIn("hdr", result[kind + "s"])

    def test_any_single_missing_registration_still_fails_including_hdr_and_every_alias(self):
        for kind, names in self.public.items():
            for name in sorted(names):
                with self.subTest(kind=kind, missing=name), self.assertRaisesRegex(AssertionError, "Missing " + kind):
                    self.run_inventory(kind, name)

    def test_actual_two_column_filter_flags_preserve_all_ten_and_reject_every_missing_filter(self):
        expected = {"aresample", "atempo", "rubberband", "loudnorm", "amix", "afade", "atrim", "concat", "amovie", "azmq"}
        for flags in ("..", "T.", "...", "TSC"):
            self.assertEqual(expected, set(self.run_inventory(filter_flags=flags)["requiredFilters"]))
        for name in expected:
            with self.subTest(missing=name), self.assertRaisesRegex(AssertionError, "Missing audio filter: " + name):
                self.run_inventory(missing_filter=name)

    def test_configure_symbols_cannot_substitute_for_missing_public_aliases(self):
        for kind, aliases in self.EXPECTED_ALIASES.items():
            for configured, public in aliases.items():
                with self.subTest(kind=kind, configured=configured), self.assertRaisesRegex(AssertionError, "Missing " + kind):
                    self.run_inventory(kind, public, replacement=configured)

    def test_mapping_refuses_unreviewed_source_or_empty_required_set(self):
        self.profile["ffmpeg"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "source identity"):
            self.run_inventory()
        self.profile = policy.load_profile()
        self.profile["configure"] = [value for value in self.profile["configure"] if not value.startswith("--enable-decoder=")]
        with self.assertRaisesRegex(ValueError, "configured decoder"):
            self.run_inventory()


if __name__ == "__main__":
    unittest.main()
