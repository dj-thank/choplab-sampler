from pathlib import Path
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

from scripts.verify_android_next_probe import (
    ANDROID, PACKAGE, NEXT_ACTIVITY, RESOURCE_PREFIX, ZIP_EXTRA_FIELD_CLASSES, VerificationError,
    verify_compose_resources, verify_dex_hierarchy, verify_mapping, verify_next_launcher, verify_test_mapping,
    verify_zip_extra_fields,
)


def hierarchy_dex(classes):
    """Small DEX class tables: (descriptor, access, superclass); no executable code."""
    types = sorted({name for name, _, _ in classes} | {parent for _, _, parent in classes if parent})
    type_offset = 112 + len(types) * 4
    class_offset = type_offset + len(types) * 4
    result = bytearray(class_offset + len(classes) * 32)
    result[:8] = b"dex\n039\0"
    struct.pack_into("<II", result, 56, len(types), 112)
    struct.pack_into("<II", result, 64, len(types), type_offset)
    struct.pack_into("<II", result, 96, len(classes), class_offset)
    for index, name in enumerate(types):
        value = name.encode("ascii")
        assert len(value) < 128
        struct.pack_into("<I", result, 112 + index * 4, len(result))
        result.extend(bytes([len(value)]) + value + b"\0")
        struct.pack_into("<I", result, type_offset + index * 4, index)
    for index, (name, access, parent) in enumerate(classes):
        struct.pack_into("<IIIIIIII", result, class_offset + index * 32,
                         types.index(name), access, types.index(parent) if parent else 0xffffffff,
                         0, 0xffffffff, 0, 0, 0)
    struct.pack_into("<I", result, 32, len(result))
    return bytes(result)


def constructor_dex(descriptor, access=1, constructor_access=0x10001, *, argument=False, code=True):
    """One class with a direct constructor and code_item; never executed as a program."""
    strings = [descriptor, "Ljava/lang/Object;", "V", "I", "<init>"]
    type_offset = 112 + len(strings) * 4
    proto_offset = type_offset + 4 * 4
    method_offset = proto_offset + 12
    class_offset = method_offset + 8
    result = bytearray(class_offset + 32)
    result[:8] = b"dex\n039\0"
    for header, size, offset in ((56, len(strings), 112), (64, 4, type_offset),
                                 (72, 1, proto_offset), (88, 1, method_offset), (96, 1, class_offset)):
        struct.pack_into("<II", result, header, size, offset)
    for index, name in enumerate(strings):
        value = name.encode("ascii")
        assert len(value) < 128
        struct.pack_into("<I", result, 112 + index * 4, len(result))
        result.extend(bytes([len(value)]) + value + b"\0")
    struct.pack_into("<IIII", result, type_offset, 0, 1, 2, 3)
    result.extend(b"\0" * (-len(result) % 4))
    parameters = len(result) if argument else 0
    if argument:
        result.extend(struct.pack("<IH", 1, 3))
    struct.pack_into("<III", result, proto_offset, 2, 2, parameters)
    struct.pack_into("<HHI", result, method_offset, 0, 0, 4)
    result.extend(b"\0" * (-len(result) % 4))
    code_offset = len(result) if code else 0
    if code:
        result.extend(struct.pack("<HHHHIIH", 1 + argument, 1 + argument, 0, 0, 0, 1, 0x000e))
    data_offset = len(result)
    for number in (0, 0, 1, 0, 0, constructor_access, code_offset):
        while number >= 128:
            result.append((number & 127) | 128)
            number >>= 7
        result.append(number)
    struct.pack_into("<IIIIIIII", result, class_offset, 0, access, 1, 0, 0xffffffff, 0, data_offset, 0)
    struct.pack_into("<I", result, 32, len(result))
    return bytes(result)


class NextProbeTest(unittest.TestCase):
    def test_all_zip_registrations_require_real_concrete_classes_and_public_noarg_code(self):
        with tempfile.TemporaryDirectory() as directory:
            mapping = Path(directory) / "mapping.txt"
            names = [f"z{index}" for index in range(len(ZIP_EXTRA_FIELD_CLASSES))]
            original = "".join(f"{name} -> {short}:\n" for name, short in zip(ZIP_EXTRA_FIELD_CLASSES, names))
            mapping.write_text(original, encoding="utf-8")
            dex = [constructor_dex(f"L{name};") for name in names]
            result = verify_zip_extra_fields(mapping, dex)
            self.assertEqual(13, len(result["registrations"]))
            self.assertEqual("PASS", result["publicConcreteNoArgConstructors"])
            self.assertEqual("Lz0;", result["registrations"][0]["dexClass"])
            # The old APK's AsiExtraField had exactly public|abstract (0x401).
            # Every registration is required, not just the first failing class.
            for index, name in enumerate(names):
                for case, (changed, message) in enumerate((
                    (constructor_dex(f"L{name};", access=0x401), "public/concrete"),
                    (constructor_dex(f"L{name};", access=0x201), "public/concrete"),
                    (constructor_dex(f"L{name};", access=0), "public/concrete"),
                    (hierarchy_dex([(f"L{name};", 1, None)]), "constructor missing"),
                    (constructor_dex(f"L{name};", constructor_access=0x10002), "constructor missing"),
                    (constructor_dex(f"L{name};", constructor_access=0x10000), "constructor missing"),
                    (constructor_dex(f"L{name};", constructor_access=0x10101), "constructor missing"),
                    (constructor_dex(f"L{name};", argument=True), "constructor missing"),
                    (constructor_dex(f"L{name};", code=False), "constructor missing"),
                )):
                    with self.subTest(registration=name, case=case, failure=message):
                        with self.assertRaisesRegex(VerificationError, message):
                            verify_zip_extra_fields(mapping, dex[:index] + [changed] + dex[index + 1:])
                with self.subTest(missing_class=name), self.assertRaisesRegex(VerificationError, "DEX class missing"):
                    verify_zip_extra_fields(mapping, dex[:index] + dex[index + 1:])
                mapping.write_text("".join(line for line in original.splitlines(keepends=True)
                                           if not line.startswith(ZIP_EXTRA_FIELD_CLASSES[index] + " -> ")), encoding="utf-8")
                with self.subTest(missing_mapping=name), self.assertRaisesRegex(VerificationError, "mapping missing"):
                    verify_zip_extra_fields(mapping, dex)
                mapping.write_text(original, encoding="utf-8")
            mapping.write_text(original.replace(" -> z1:", " -> z0:"), encoding="utf-8")
            with self.assertRaisesRegex(VerificationError, "were merged"):
                verify_zip_extra_fields(mapping, dex)

    def test_zip_constructor_gate_rejects_truncated_class_data(self):
        name = ZIP_EXTRA_FIELD_CLASSES[0]
        dex = constructor_dex("Lz;")
        with tempfile.TemporaryDirectory() as directory:
            mapping = Path(directory) / "mapping.txt"
            mapping.write_text(f"{name} -> z:\n", encoding="utf-8")
            with patch("scripts.verify_android_next_probe.ZIP_EXTRA_FIELD_CLASSES", (name,)):
                for invalid in (dex[:-1], dex[:-5] + b"\xff" * 5):
                    with self.assertRaisesRegex(VerificationError, "Invalid DEX"):
                        verify_zip_extra_fields(mapping, [invalid])

    def test_repackaged_child_requires_accessible_superclass(self):
        parent = "Lkotlin/collections/CollectionsKt__IterablesKt;"
        private_parent = hierarchy_dex([(parent, 0, None)])
        moved_child = hierarchy_dex([("Lnw;", 1, parent)])
        with self.assertRaisesRegex(VerificationError, "Inaccessible DEX superclass"):
            verify_dex_hierarchy([private_parent, moved_child])
        public_parent = hierarchy_dex([(parent, 1, None)])
        self.assertEqual(2, verify_dex_hierarchy([public_parent, moved_child])["definedClasses"])
        same_package = hierarchy_dex([("Lkotlin/collections/nw;", 1, parent)])
        self.assertEqual("PASS", verify_dex_hierarchy([private_parent, same_package])["crossPackageInheritance"])
        malformed = bytearray(public_parent)
        struct.pack_into("<I", malformed, 60, len(malformed) + 4)
        with self.assertRaisesRegex(VerificationError, "Invalid DEX hierarchy"):
            verify_dex_hierarchy([malformed])

    def test_legacy_apk_and_wrong_next_task_are_rejected(self):
        manifest = ET.Element("manifest")
        application = ET.SubElement(manifest, "application")
        ET.SubElement(application, "activity", {ANDROID + "name": "com.choplab.sampler.MainActivity"})
        with self.assertRaisesRegex(VerificationError, "NEXT exported"):
            verify_next_launcher(manifest)
        activity = ET.SubElement(application, "activity", {ANDROID + "name": NEXT_ACTIVITY,
            ANDROID + "exported": "true", ANDROID + "taskAffinity": PACKAGE + ".next"})
        with self.assertRaisesRegex(VerificationError, "MAIN/LAUNCHER"):
            verify_next_launcher(manifest)
        intent = ET.SubElement(activity, "intent-filter")
        ET.SubElement(intent, "action", {ANDROID + "name": "android.intent.action.MAIN"})
        ET.SubElement(intent, "category", {ANDROID + "name": "android.intent.category.LAUNCHER"})
        verify_next_launcher(manifest)
        activity.set(ANDROID + "taskAffinity", "com.choplab.sampler")
        with self.assertRaises(VerificationError):
            verify_next_launcher(manifest)

    def test_wrong_or_debug_r8_mapping_cannot_certify_the_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            mapping = Path(directory) / "mapping.txt"
            mapping.write_text("# pg_map_id: abcdef\n", encoding="utf-8")
            full = b'~~R8{"pg-map-id":"abcdef","compilation-mode":"release","r8-mode":"full"}\x00'
            self.assertEqual("abcdef", verify_mapping(mapping, [full], required_classes=())["mapId"])
            for wrong in (full.replace(b"abcdef", b"123456"), full.replace(b"release", b"debug")):
                with self.assertRaisesRegex(VerificationError, "mapping ID"):
                    verify_mapping(mapping, [wrong], required_classes=())
            with self.assertRaisesRegex(VerificationError, "Reflective runtime class"):
                verify_mapping(mapping, [full])
            configuration = Path(directory) / "configuration.txt"
            configuration.write_text(f'-applymapping "{mapping}"\n', encoding="utf-8")
            verify_test_mapping(configuration, "abcdef")
            with self.assertRaisesRegex(VerificationError, "different app mapping"):
                verify_test_mapping(configuration, "123456")

    def test_missing_japanese_resource_family_is_rejected(self):
        families = ("continuous_strings", "bank_pad_strings", "pattern_editor_strings", "source_hand_strings")
        names = [RESOURCE_PREFIX + locale + "/" + family + ".commonMain.cvr"
                 for locale in ("values", "values-ja") for family in families]
        verify_compose_resources(names)
        with self.assertRaisesRegex(VerificationError, "ja/en"):
            verify_compose_resources(names[:-1])
