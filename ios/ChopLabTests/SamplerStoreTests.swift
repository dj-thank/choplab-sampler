import AVFoundation
import Foundation
import XCTest
@testable import ChopLab

final class SamplerStoreTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("choplab-store-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
        root = nil
    }

    @MainActor
    func testRecordingRejectsImportAdmissionBeforeReplacement() {
        XCTAssertEqual(
            SourceImportPolicy.admission(isRecording: true),
            .rejectedWhileRecording
        )
        XCTAssertEqual(SourceImportPolicy.admission(isRecording: false), .allowed)
    }

    @MainActor
    func testPickerCancellationPreservesImportedSource() throws {
        let store = makeStore()
        let source = try makeWaveFile(named: "existing")
        XCTAssertTrue(store.importSource(from: source))
        let originalName = store.sourceName

        store.reportImportPickerFailure(CocoaError(.userCancelled))

        XCTAssertEqual(store.sourceName, originalName)
        XCTAssertEqual(store.statusMessage, "音源の読み込みをキャンセルしました")
        XCTAssertFalse(store.isRecording)
    }

    @MainActor
    func testPickerErrorPreservesImportedSource() throws {
        let store = makeStore()
        let source = try makeWaveFile(named: "existing")
        XCTAssertTrue(store.importSource(from: source))
        let originalName = store.sourceName

        store.reportImportPickerFailure(TestError.providerUnavailable)

        XCTAssertEqual(store.sourceName, originalName)
        XCTAssertTrue(store.statusMessage.hasPrefix("音源を選べませんでした:"))
        XCTAssertFalse(store.isRecording)
    }

    @MainActor
    private func makeStore() -> SamplerStore {
        SamplerStore(
            sourceRepository: SourceFileRepository(
                directory: root.appendingPathComponent("Sources", isDirectory: true)
            )
        )
    }

    private func makeWaveFile(named name: String) throws -> URL {
        let url = root.appendingPathComponent("\(name).wav")
        let format = try XCTUnwrap(
            AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)
        )
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        let buffer = try XCTUnwrap(
            AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 128)
        )
        buffer.frameLength = 128
        try file.write(from: buffer)
        return url
    }
}

private enum TestError: LocalizedError {
    case providerUnavailable

    var errorDescription: String? {
        "provider unavailable"
    }
}
