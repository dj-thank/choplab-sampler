from dataclasses import replace
import json
from pathlib import Path
import unittest

from scripts.android_runtime_policy import load_pins, required_native_preservation_globs, validate_packaging_preservation

ROOT = Path(__file__).resolve().parents[2]


def gradle_declaration(globs):
    return 'android { packaging { jniLibs.keepDebugSymbols += setOf(\n' + ',\n'.join(json.dumps(item) for item in sorted(globs)) + ',\n) } }'


class AndroidRuntimePackagingTest(unittest.TestCase):
    def setUp(self):
        self.pins = load_pins()
        self.expected = required_native_preservation_globs(self.pins)

    def test_app_preserves_every_pinned_native_filename_without_broad_exemptions(self):
        validate_packaging_preservation((ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8'), self.pins)

    def test_exact_eight_basename_preservation_passes(self):
        self.assertEqual(8, len(self.expected))
        self.assertNotIn('**/*.so', self.expected)
        validate_packaging_preservation(gradle_declaration(self.expected), self.pins)

    def test_either_missing_onnx_library_fails(self):
        for missing in ('**/libonnxruntime.so', '**/libonnxruntime4j_jni.so'):
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, 'missing='):
                validate_packaging_preservation(gradle_declaration(self.expected - {missing}), self.pins)

    def test_broad_or_unrelated_native_exemption_fails(self):
        for extra in ('**/*.so', '**/libunrelated.so'):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, 'extra='):
                validate_packaging_preservation(gradle_declaration(self.expected | {extra}), self.pins)

    def test_new_pinned_native_filename_requires_packaging_update(self):
        native = next(pin for pin in self.pins if pin.apk_path is not None)
        renamed = replace(native, apk_path='lib/arm64-v8a/libnewruntime.so')
        with self.assertRaisesRegex(ValueError, 'libnewruntime'):
            validate_packaging_preservation(gradle_declaration(self.expected), self.pins + (renamed,))

    def test_comments_or_an_unrelated_raw_string_do_not_satisfy_contract(self):
        declaration = gradle_declaration(self.expected)
        sources = ('\n'.join('// ' + line for line in declaration.splitlines()),
                   '/* outer /* inner */ jniLibs.keepDebugSymbols += setOf() */',
                   'val documentation = """'+declaration+'"""')
        for source in sources:
            with self.subTest(source=source[:20]), self.assertRaisesRegex(ValueError, 'Expected one'):
                validate_packaging_preservation(source, self.pins)

    def test_a_second_or_computed_declaration_fails_closed(self):
        declaration = gradle_declaration(self.expected)
        with self.assertRaisesRegex(ValueError, 'Expected one'):
            validate_packaging_preservation(declaration + declaration, self.pins)
        with self.assertRaisesRegex(ValueError, 'Expected one'):
            validate_packaging_preservation(declaration + '\njniLibs.keepDebugSymbols.clear()', self.pins)
        with self.assertRaisesRegex(ValueError, 'literal'):
            validate_packaging_preservation('jniLibs.keepDebugSymbols += setOf(runtimeNames)', self.pins)
