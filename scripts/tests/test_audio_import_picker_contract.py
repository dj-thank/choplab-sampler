import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/choplab/sampler/MainActivity.kt"
ANDROID_IMPORT = ROOT / "app/src/main/java/com/choplab/sampler/source/SourceImportViewModel.kt"
DESKTOP_APP = ROOT / "desktop/src/main/kotlin/com/choplab/desktop/DesktopApp.kt"
DESKTOP_POLICY = ROOT / "desktop/src/main/kotlin/com/choplab/desktop/DesktopAudioImportPolicy.kt"
LOCAL_LIBRARY = ROOT / "jvm-core/src/main/kotlin/com/choplab/sampler/source/LocalAudioLibrary.kt"


class AudioImportPickerContractTest(unittest.TestCase):
    """The pickers offer supported media only, and decoding guards the library itself.

    The audio library hub also takes video containers and source archives, so the
    picker can no longer be audio-typed alone. It still names the accepted types
    instead of the unrestricted wildcard, and every imported file is decoded before
    it is stored.
    """

    def between(self, source: str, start: str, end: str) -> str:
        self.assertIn(start, source)
        head = source.split(start, 1)[1]
        self.assertIn(end, head)
        return head.split(end, 1)[0]

    def test_audio_import_asks_for_the_supported_media_types(self) -> None:
        activity_source = MAIN_ACTIVITY.read_text(encoding="utf-8")

        self.assertIn(
            "rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments())",
            activity_source,
        )
        requested = self.between(activity_source, "importLauncher.launch(arrayOf(", "))")
        self.assertIn('"audio/*"', requested)
        self.assertNotIn('"*/*"', requested)
        self.assertIn("onImportAudio = { sourceViewModel.show() }", activity_source)

        import_source = ANDROID_IMPORT.read_text(encoding="utf-8")
        self.assertIn('uris.filter { it.scheme=="content" }', import_source)
        self.assertIn("hub.importInputs(", import_source)

    def test_windows_picker_disables_all_files_and_shows_only_supported_media(self) -> None:
        desktop_source = DESKTOP_APP.read_text(encoding="utf-8")

        self.assertIn("JFileChooser", desktop_source)
        chooser = self.between(desktop_source, "private val importChooser: JFileChooser by lazy {", "\n}")
        self.assertIn("isAcceptAllFileFilterUsed = false", chooser)
        self.assertIn("fileFilter = DesktopAudioImportPolicy.fileFilter", chooser)
        self.assertNotIn("FilenameFilter", chooser)

        policy_source = DESKTOP_POLICY.read_text(encoding="utf-8")
        self.assertIn("FileNameExtensionFilter(", policy_source)
        self.assertIn("LocalAudioLibrary.extensions", policy_source)

    def test_imported_files_are_decoded_before_they_enter_the_library(self) -> None:
        library_source = LOCAL_LIBRARY.read_text(encoding="utf-8")

        self.assertIn("validateAudio: (File)->Unit", library_source)
        stored = self.between(library_source, "validateAudio(temp)", "\n    fun ")
        self.assertIn("Files.move(temp.toPath()", stored)


if __name__ == "__main__":
    unittest.main()
