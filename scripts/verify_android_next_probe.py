#!/usr/bin/env python3
"""Bind NEXT candidate size to its launcher, R8 mapping and ja/en resources; no device operations."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

try:
    from .measure_distribution import measure
    from .verify_android_release import ANDROID, VerificationError, find_android_tool, read_manifest, run, verify_alignment, verify_manifest
except ImportError:
    from measure_distribution import measure
    from verify_android_release import ANDROID, VerificationError, find_android_tool, read_manifest, run, verify_alignment, verify_manifest

PACKAGE = "com.choplab.sampler.preview"
NEXT_ACTIVITY = "com.choplab.sampler.next.NextActivity"
RESOURCE_PREFIX = "assets/composeResources/com.choplab.ui.resources/"
REFLECTIVE_CLASSES = (
    "ai.onnxruntime.OnnxTensor", "androidx.tracing.Trace", "com.yausername.youtubedl_android.mapper.VideoInfo",
    "kotlin.LazyKt", "com.yausername.ffmpeg.FFmpeg", "com.yausername.youtubedl_android.YoutubeDLRequest",
    "com.yausername.youtubedl_android.YoutubeDLResponse",
    "org.mozilla.javascript.Context", "org.schabi.newpipe.extractor.timeago.patterns.en",
    "org.schabi.newpipe.extractor.timeago.patterns.ja",
)
# Exact Class.newInstance() registrations in commons-compress:1.12's
# ExtraFieldUtils.java:40-54,64-89 (YoutubeDL common:0.18.1 runtime dependency).
# Source JAR SHA-256: ae08ac41171c3805f5e8ff04c29a95163a1bf8fb25ccff49581f7af6c902606d
# Runtime JAR SHA-256: 2c1542faf343185b7cab9c3d55c8ae5471d6d095d3887a4adefdbdf2984dc0b6
ZIP_EXTRA_FIELD_CLASSES = tuple("org.apache.commons.compress.archivers.zip." + name for name in (
    "AsiExtraField", "X5455_ExtendedTimestamp", "X7875_NewUnix", "JarMarker",
    "UnicodePathExtraField", "UnicodeCommentExtraField", "Zip64ExtendedInformationExtraField",
    "X000A_NTFS", "X0014_X509Certificates", "X0015_CertificateIdForFile",
    "X0016_CertificateIdForCentralDirectory", "X0017_StrongEncryptionHeader",
    "X0019_EncryptionRecipientCertificateList",
))


def verify_next_launcher(manifest):
    activity = manifest.find(f"./application/activity[@{ANDROID}name='{NEXT_ACTIVITY}']")
    if activity is None or activity.get(f"{ANDROID}exported") != "true" or activity.get(f"{ANDROID}taskAffinity") != PACKAGE + ".next":
        raise VerificationError("NEXT exported launcher with isolated task affinity is missing")
    if not any(
        intent.find(f"action[@{ANDROID}name='android.intent.action.MAIN']") is not None
        and intent.find(f"category[@{ANDROID}name='android.intent.category.LAUNCHER']") is not None
        for intent in activity.findall("intent-filter")
    ):
        raise VerificationError("NEXT MAIN/LAUNCHER entry is missing")


def verify_mapping(mapping, dex_files, required_classes=REFLECTIVE_CLASSES):
    text = mapping.read_text(encoding="utf-8")
    match = re.search(r"^# pg_map_id: ([0-9a-f]+)$", text, re.MULTILINE)
    if not match:
        raise VerificationError("R8 mapping ID missing")
    markers = [json.loads(value) for dex in dex_files for value in re.findall(rb'~~R8(\{[^\x00]*?\})', dex)]
    app = [marker for marker in markers if marker.get("pg-map-id") == match[1]]
    if not app or any(marker.get("compilation-mode") != "release" or marker.get("r8-mode") != "full" for marker in app):
        raise VerificationError("APK does not contain this full/release R8 mapping ID")
    for name in required_classes:
        if not re.search(r"^" + re.escape(name) + r" -> " + re.escape(name) + r":$", text, re.MULTILINE):
            raise VerificationError(f"Reflective runtime class was removed or renamed: {name}")
    return {"mappingSha256": hashlib.sha256(mapping.read_bytes()).hexdigest(), "mapId": match[1],
            "compilerVersion": app[0].get("version"), "mode": "full/release", "reflectiveClassNames": list(required_classes)}


def verify_test_mapping(configuration, expected_app_map_id):
    match = re.search(r'^-applymapping "([^"\n]+)"$', configuration.read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        raise VerificationError("Matching test R8 configuration has no explicit app mapping")
    mapping = Path(match[1])
    with mapping.open(encoding="utf-8") as source:
        header = "".join(source.readline() for _ in range(12))
    if f"# pg_map_id: {expected_app_map_id}\n" not in header:
        raise VerificationError("Test R8 uses a different app mapping")
    return {"testedAppMapId": expected_app_map_id, "configurationSha256": hashlib.sha256(configuration.read_bytes()).hexdigest()}


def dex_contents(archive):
    entries = [entry for entry in archive.infolist() if re.fullmatch(r"classes\d*\.dex", entry.filename)]
    if not entries or sum(entry.file_size for entry in entries) > 64 * 1024 * 1024:
        raise VerificationError("Unexpected APK DEX extent")
    return [archive.read(entry) for entry in entries]


def dex_definitions(dex_files, constructor_classes=()):
    """Read class access/hierarchy and selected direct public no-arg constructors."""
    definitions = {}
    try:
        for dex in dex_files:
            if len(dex) < 112 or not dex.startswith(b"dex\n"):
                raise ValueError("DEX header")

            def table(offset, count, width):
                if offset > len(dex) or count > (len(dex) - offset) // width:
                    raise ValueError("DEX table extent")
                return range(offset, offset + count * width, width)

            def uleb(position):
                result = 0
                for shift in range(0, 35, 7):
                    value = dex[position]; position += 1
                    if shift == 28 and value > 15:
                        raise ValueError("DEX ULEB128 overflow")
                    result |= (value & 127) << shift
                    if not value & 128:
                        return result, position
                raise ValueError("DEX ULEB128 extent")

            count, offset = struct.unpack_from("<II", dex, 56)
            strings = []
            for entry in table(offset, count, 4):
                position = struct.unpack_from("<I", dex, entry)[0]
                for _ in range(5):
                    value = dex[position]; position += 1
                    if not value & 128:
                        break
                else:
                    raise ValueError("DEX string length")
                strings.append(dex[position:dex.index(0, position)].decode("utf-8", errors="replace"))
            count, offset = struct.unpack_from("<II", dex, 64)
            types = [strings[struct.unpack_from("<I", dex, entry)[0]] for entry in table(offset, count, 4)]
            count, offset = struct.unpack_from("<II", dex, 96)
            for entry in table(offset, count, 32):
                name, access, superclass, interfaces = struct.unpack_from("<IIII", dex, entry)
                parents = [] if superclass == 0xffffffff else [types[superclass]]
                if interfaces:
                    size = struct.unpack_from("<I", dex, interfaces)[0]
                    parents.extend(types[struct.unpack_from("<H", dex, index)[0]]
                                   for index in table(interfaces + 4, size, 2))
                constructors = []
                data_offset = struct.unpack_from("<I", dex, entry + 24)[0]
                if types[name] in constructor_classes and data_offset:
                    counts = []
                    position = data_offset
                    for _ in range(4):
                        size, position = uleb(position)
                        counts.append(size)
                    for _ in range(counts[0] + counts[1]):
                        _, position = uleb(position)  # encoded field index
                        _, position = uleb(position)  # field access
                    method_count, method_offset = struct.unpack_from("<II", dex, 88)
                    methods = table(method_offset, method_count, 8)
                    proto_count, proto_offset = struct.unpack_from("<II", dex, 72)
                    protos = table(proto_offset, proto_count, 12)
                    method_index = 0
                    for _ in range(counts[2]):
                        delta, position = uleb(position)
                        method_index += delta
                        flags, position = uleb(position)
                        code, position = uleb(position)
                        owner, proto, method_name = struct.unpack_from("<HHI", dex, methods[method_index])
                        if owner != name:
                            raise ValueError("DEX direct method owner")
                        if strings[method_name] != "<init>":
                            continue
                        _, returns, parameters = struct.unpack_from("<III", dex, protos[proto])
                        argument_count = 0
                        if parameters:
                            argument_count = struct.unpack_from("<I", dex, parameters)[0]
                            table(parameters + 4, argument_count, 2)
                        if types[returns] == "V" and argument_count == 0 and code:
                            table(code, 1, 16)  # code_item header
                            instructions = struct.unpack_from("<I", dex, code + 12)[0]
                            table(code + 16, instructions, 2)
                            if instructions:
                                constructors.append(flags)
                definitions[types[name]] = (access, parents, constructors)
    except (ValueError, IndexError, struct.error) as error:
        raise VerificationError(f"Invalid DEX hierarchy/class data: {error}") from error
    return definitions


def verify_dex_hierarchy(dex_files):
    """Reject inaccessible app/test superclasses after R8 moves classes between packages."""
    definitions = dex_definitions(dex_files)
    for child, (_, parents, _) in definitions.items():
        for parent in parents:
            if (parent in definitions and not definitions[parent][0] & 1
                    and child.rpartition("/")[0] != parent.rpartition("/")[0]):
                raise VerificationError(f"Inaccessible DEX superclass/interface: {child} -> {parent}")
    return {"definedClasses": len(definitions), "crossPackageInheritance": "PASS"}


def verify_zip_extra_fields(mapping, dex_files):
    """Names may be obfuscated; actual registered DEX classes must remain instantiable."""
    class_names = dict(re.findall(r"^(\S+) -> (\S+):$", mapping.read_text(encoding="utf-8"), re.MULTILINE))
    missing = set(ZIP_EXTRA_FIELD_CLASSES) - class_names.keys()
    if missing:
        raise VerificationError(f"ZIP extra-field mapping missing: {sorted(missing)}")
    descriptors = {name: "L" + class_names[name].replace(".", "/") + ";" for name in ZIP_EXTRA_FIELD_CLASSES}
    if len(set(descriptors.values())) != len(descriptors):
        raise VerificationError("ZIP extra-field classes were merged")
    definitions = dex_definitions(dex_files, set(descriptors.values()))
    registrations = []
    for name, descriptor in descriptors.items():
        if descriptor not in definitions:
            raise VerificationError(f"ZIP extra-field DEX class missing: {name} -> {descriptor}")
        access, _, constructors = definitions[descriptor]
        if not access & 1 or access & (0x200 | 0x400):
            raise VerificationError(f"ZIP extra-field is not public/concrete: {name} -> {descriptor}, flags={access:#x}")
        # public + constructor; reject private/protected/static/native/abstract flags.
        valid = [flags for flags in constructors if flags & 0x10001 == 0x10001 and not flags & 0x50e]
        if len(valid) != 1:
            raise VerificationError(f"ZIP extra-field public no-arg constructor missing: {name} -> {descriptor}")
        registrations.append({"class": name, "dexClass": descriptor, "classAccess": access, "constructorAccess": valid[0]})
    return {"dependency": "org.apache.commons:commons-compress:1.12", "registrations": registrations,
            "publicConcreteNoArgConstructors": "PASS"}


def verify_compose_resources(names):
    families = {}
    for locale in ("values", "values-ja"):
        prefix = RESOURCE_PREFIX + locale + "/"
        families[locale] = {name.removeprefix(prefix) for name in names if name.startswith(prefix) and name.endswith(".cvr")}
    required = {name + ".commonMain.cvr" for name in ("continuous_strings", "bank_pad_strings", "pattern_editor_strings", "source_hand_strings")}
    if not required <= families["values"] or families["values"] != families["values-ja"]:
        raise VerificationError("NEXT ja/en Compose resource families are missing or differ")
    return {"locales": ["en", "ja"], "families": sorted(families["values"])}


def inspect(apk, mapping, version, version_code):
    report = measure(apk, "android-next-size-probe")
    manifest, manifest_tool = read_manifest(apk)
    verify_manifest(manifest, expected_version=version, expected_version_code=version_code, expected_application_id=PACKAGE)
    verify_next_launcher(manifest)
    verify_alignment(apk)
    with zipfile.ZipFile(apk) as archive:
        dex_files = dex_contents(archive)
        report["r8"] = verify_mapping(mapping, dex_files)
        report["dexHierarchy"] = verify_dex_hierarchy(dex_files)
        report["zipExtraFields"] = verify_zip_extra_fields(mapping, dex_files)
        report["composeResources"] = verify_compose_resources(archive.namelist())
    labels = {}
    for locale, expected in (("default", "Earth Song NEXT"), ("ja", "おとひろい NEXT")):
        value = run([find_android_tool("apkanalyzer"), "resources", "value", "--config", locale,
                     "--name", "next_app_name", "--type", "string", "--package", PACKAGE, str(apk)]).stdout.strip()
        if value != expected:
            raise VerificationError(f"NEXT {locale} launcher label missing or changed")
        labels[locale] = value
    report.update({"applicationId": PACKAGE, "launcher": NEXT_ACTIVITY, "labels": labels, "manifestTool": manifest_tool,
                   "runtime": "NOT_RUN", "device": "NOT_RUN", "provider": "NOT_RUN",
                   "boundary": "Candidate size/static contracts only. Resource shrinking is configured by choplabNextSizeProbe. Matching instrumentation, native codecs, signing, combined production tree and public acceptance remain separate."})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--test-mapping", type=Path)
    parser.add_argument("--test-configuration", type=Path)
    args = parser.parse_args()
    inputs = [args.apk, args.mapping, args.test_apk, args.test_mapping, args.test_configuration]
    if args.output.resolve() in {path.resolve() for path in inputs if path is not None}:
        raise VerificationError("Report must not overwrite an input")
    test_inputs = (args.test_apk, args.test_mapping, args.test_configuration)
    if any(test_inputs) and not all(test_inputs):
        parser.error("Test APK, mapping and configuration must be provided together")
    report = inspect(args.apk, args.mapping, args.version, args.version_code)
    if args.test_apk:
        manifest, _ = read_manifest(args.test_apk)
        instrumentation = manifest.find("instrumentation")
        if (manifest.get("package") != PACKAGE + ".test" or instrumentation is None
                or instrumentation.get(f"{ANDROID}targetPackage") != PACKAGE
                or instrumentation.get(f"{ANDROID}name") != "androidx.test.runner.AndroidJUnitRunner"):
            raise VerificationError("Test APK does not target the NEXT Preview runner")
        with zipfile.ZipFile(args.test_apk) as archive:
            test_dex = dex_contents(archive)
            test = verify_mapping(args.test_mapping, test_dex, required_classes=())
        with zipfile.ZipFile(args.apk) as archive:
            test["dexHierarchy"] = verify_dex_hierarchy(dex_contents(archive) + test_dex)
        test.update(verify_test_mapping(args.test_configuration, report["r8"]["mapId"]))
        test.update({"filename": args.test_apk.name, "bytes": args.test_apk.stat().st_size,
                     "sha256": hashlib.sha256(args.test_apk.read_bytes()).hexdigest()})
        report["matchingInstrumentation"] = test
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"NEXT candidate: {report['bytes']} / {report['limitBytes']} bytes: {report['sizeGate']}; static contracts PASS, runtime NOT_RUN")
    if report["sizeGate"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
