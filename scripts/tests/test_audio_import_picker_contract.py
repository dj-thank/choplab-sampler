import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/choplab/sampler/MainActivity.kt"
AUDIO_CONTRACT = ROOT / "app/src/main/java/com/choplab/sampler/AudioOpenDocumentContract.kt"
DESKTOP_APP = ROOT / "desktop/src/main/kotlin/com/choplab/desktop/DesktopApp.kt"


class AudioImportPickerContractTest(unittest.TestCase):
    def test_audio_import_uses_an_audio_typed_open_document_intent(self) -> None:
        activity_source = MAIN_ACTIVITY.read_text(encoding="utf-8")

        self.assertIn("contract = AudioOpenDocumentContract()", activity_source)
        self.assertIn("onImportAudio = { importLauncher.launch(Unit) }", activity_source)

        contract_source = AUDIO_CONTRACT.read_text(encoding="utf-8")
        self.assertIn('Intent(Intent.ACTION_OPEN_DOCUMENT)', contract_source)
        self.assertIn('.addCategory(Intent.CATEGORY_OPENABLE)', contract_source)
        self.assertIn('.setType(AUDIO_IMPORT_MIME_TYPE)', contract_source)
        self.assertIn('const val AUDIO_IMPORT_MIME_TYPE = "audio/*"', contract_source)
        self.assertNotIn('setType("*/*")', contract_source)
        self.assertNotIn("Intent.EXTRA_MIME_TYPES", contract_source)

    def test_windows_picker_disables_all_files_and_shows_only_supported_audio(self) -> None:
        desktop_source = DESKTOP_APP.read_text(encoding="utf-8")

        self.assertIn("JFileChooser", desktop_source)
        self.assertIn("isAcceptAllFileFilterUsed = false", desktop_source)
        self.assertIn("DesktopAudioImportPolicy.fileFilter", desktop_source)
        import_function = desktop_source.split("private fun chooseWav", 1)[1].split(
            "private fun chooseExportWav", 1
        )[0]
        self.assertNotIn("FilenameFilter", import_function)


if __name__ == "__main__":
    unittest.main()
