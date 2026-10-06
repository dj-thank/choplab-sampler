import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import android_runtime_policy as policy
from scripts import build_android_audio_runtime as build


class AndroidAudioRuntimeTest(unittest.TestCase):
    def test_first_configure_has_the_same_relative_source_as_subsequent_builds(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            source = work / "ffmpeg-7.1.1"
            source.mkdir()
            (source / "configure").write_text("owned configure fixture")
            build_directory = work / "android-audio"
            build.prepare_build_source(build_directory, source)
            self.assertTrue((build_directory / "src/configure").is_file())
            self.assertEqual(Path("../ffmpeg-7.1.1"), (build_directory / "src").readlink())
            build.prepare_build_source(build_directory, source)
            (build_directory / "src").unlink()
            (build_directory / "src").symlink_to(work, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "Unexpected FFmpeg build source"):
                build.prepare_build_source(build_directory, source)

    def test_source_cache_edit_and_archive_escape_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "source.tar"
            with tarfile.open(archive, "w") as output:
                entry = tarfile.TarInfo("source/public.h")
                entry.size = 6
                output.addfile(entry, io.BytesIO(b"header"))
            source = build.extract_source(archive, root / "extracted")
            self.assertEqual(b"header", (source / "public.h").read_bytes())
            extra = source / "config.h"
            extra.write_bytes(b"unreviewed configuration")
            with self.assertRaisesRegex(ValueError, "outside its pinned archive"):
                build.extract_source(archive, root / "extracted")
            extra.unlink()
            (source / "public.h").write_bytes(b"edited")
            with self.assertRaisesRegex(ValueError, "differs from its pinned archive"):
                build.extract_source(archive, root / "extracted")
            with tarfile.open(archive, "w") as output:
                entry = tarfile.TarInfo("../escape")
                entry.size = 6
                output.addfile(entry, io.BytesIO(b"header"))
            with self.assertRaises((tarfile.FilterError, ValueError)):
                build.extract_source(archive, root / "unsafe")

    def test_cached_source_symlink_cannot_replace_a_file_even_with_identical_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "source.tar"
            with tarfile.open(archive, "w") as output:
                for name in ("source/include/public.h", "source/include/other.h"):
                    entry = tarfile.TarInfo(name)
                    entry.size = 6
                    output.addfile(entry, io.BytesIO(b"header"))
            source = build.extract_source(archive, root / "extracted")
            public = source / "include/public.h"
            outside = root / "outside.h"
            outside.write_bytes(b"header")
            for target, message in ((source / "include/other.h", "differs from its pinned archive"),
                                    (outside, "path escapes its archive")):
                with self.subTest(target=target.name):
                    public.unlink()
                    public.symlink_to(target)
                    with self.assertRaisesRegex(ValueError, message):
                        build.extract_source(archive, root / "extracted")
                    self.assertEqual(b"header", target.read_bytes())

    def test_derivation_preserves_java_notices_and_other_abis_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original, derived = root / "original.aar", root / "derived.aar"
            replacements = {"jni/arm64-v8a/" + name: b"derived " + name.encode()
                            for name in ("libffmpeg.so", "libffprobe.so", "libffmpeg.zip.so")}
            preserved = {"classes.jar": b"Java API", "proguard.txt": b"JNI rules", "META-INF/LICENSE": b"license",
                         "jni/x86_64/libffmpeg.so": b"unchanged emulator binary"}
            build.write_zip(original, {**preserved, **{name: b"original" for name in replacements}})
            receipt = build.write_derived_aar(original, derived, replacements)
            with zipfile.ZipFile(derived) as archive:
                self.assertEqual({**preserved, **replacements}, {name: archive.read(name) for name in archive.namelist()})
            self.assertEqual(len(preserved), receipt["unchangedEntryCount"])
            first = derived.read_bytes()
            build.write_derived_aar(original, derived, replacements)
            self.assertEqual(first, derived.read_bytes())
            with self.assertRaisesRegex(ValueError, "Only the three arm64"):
                build.write_derived_aar(original, derived, {**replacements, "classes.jar": b"modified"})

    def test_audio_selection_keeps_concat_without_enabling_video_codecs(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / "libavcodec").mkdir()
            (source / "libavfilter").mkdir()
            (source / "libavcodec/allcodecs.c").write_text(
                "ff_h264_decoder; /* audio codecs */ ff_flac_decoder; ff_pcm_s24le_encoder; /* subtitles */ ff_text_decoder;"
            )
            (source / "libavfilter/allfilters.c").write_text("ff_af_aresample; ff_asrc_sine; ff_avf_concat; ff_vf_scale;")
            options = build.audio_options(source)
            self.assertIn("--enable-decoder=flac", options)
            self.assertIn("--enable-encoder=pcm_s24le", options)
            self.assertIn("--enable-filter=amovie,aresample,concat,sine", options)
            self.assertNotIn("h264", " ".join(options))
            self.assertNotIn("scale", " ".join(options))

    def test_transitive_native_dependencies_cannot_disappear(self):
        dynamic = {"ffmpeg": "Shared library: [libavcodec.so.61]", "codec": "Shared library: [libopus.so]",
                   "opus": "Shared library: [libc.so]\nShared library: [libssl.so.3]"}
        with patch.object(build.subprocess, "check_output", side_effect=lambda args, **_: dynamic[args[-1].name]):
            libraries = {"libavcodec.so.61": Path("codec"), "libopus.so": Path("opus")}
            self.assertEqual(libraries, build.dependency_closure([Path("ffmpeg")], libraries, Path("readelf")))
            with self.assertRaisesRegex(ValueError, "Unresolved native runtime dependency: libopus.so"):
                build.dependency_closure([Path("ffmpeg")], {"libavcodec.so.61": Path("codec")}, Path("readelf"))

    def test_alternative_pins_keep_exact_path_size_and_hash_boundaries(self):
        pins = policy.load_pins()
        alternatives = [pin for pin in pins if pin.coordinate.startswith("choplab:")]
        self.assertEqual(4, len(alternatives))
        for pin in alternatives:
            self.assertEqual(pin, policy.runtime_candidate(pin.apk_path, pin.size, pins))
            with self.assertRaises(ValueError):
                policy.runtime_candidate(pin.apk_path, pin.size + 1, pins)
            with self.assertRaises(ValueError):
                policy.runtime_candidate("assets/" + Path(pin.apk_path).name, pin.size, pins)
            self.assertFalse(policy.verify_runtime_content(pin, b"untrusted"))

    def test_profile_cannot_widen_to_another_abi_or_drop_upstream_identity(self):
        upstream = json.loads(policy.PIN_FILE.read_text())
        profile = json.loads(build.PROFILE.read_text())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "config").mkdir()
            path = root / "config/android-ffmpeg-audio.json"
            with patch.object(policy, "PIN_FILE", root / "scripts/android_runtime_pins.json"):
                for changed in ("abi", "upstream", "empty"):
                    candidate = json.loads(json.dumps(profile))
                    if changed == "abi":
                        candidate["derived"]["members"][0]["path"] = "jni/x86_64/libffmpeg.so"
                    elif changed == "upstream":
                        candidate["upstream"]["sha256"] = "0" * 64
                    else:
                        candidate["derived"]["members"] = []
                    path.write_text(json.dumps(candidate))
                    with self.subTest(changed=changed), self.assertRaises(ValueError):
                        policy.load_audio_derivation_pins(upstream)


if __name__ == "__main__":
    unittest.main()
