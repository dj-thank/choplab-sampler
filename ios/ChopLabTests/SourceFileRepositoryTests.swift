import Foundation
import XCTest
@testable import ChopLab

final class SourceFileRepositoryTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("choplab-source-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
        root = nil
    }

    func testStagePromoteAndPurgeKeepOnlyTheActiveSource() throws {
        let repository = SourceFileRepository(
            directory: root.appendingPathComponent("Sources", isDirectory: true),
            maximumBytes: 64
        )
        let firstInput = root.appendingPathComponent("first.wav")
        let secondInput = root.appendingPathComponent("second.wav")
        try Data([1, 2, 3]).write(to: firstInput)
        try Data([4, 5, 6]).write(to: secondInput)

        let first = try repository.promote(repository.stageCopy(from: firstInput))
        let second = try repository.promote(repository.stageCopy(from: secondInput))
        repository.purgeOrphans(keeping: second)

        XCTAssertFalse(FileManager.default.fileExists(atPath: first.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: second.path))
    }

    func testOversizedCandidateIsDeleted() throws {
        let sources = root.appendingPathComponent("Sources", isDirectory: true)
        let repository = SourceFileRepository(directory: sources, maximumBytes: 2)
        let input = root.appendingPathComponent("large.wav")
        try Data([1, 2, 3]).write(to: input)

        XCTAssertThrowsError(try repository.stageCopy(from: input))
        let remaining = (try? FileManager.default.contentsOfDirectory(atPath: sources.path)) ?? []
        XCTAssertTrue(remaining.isEmpty)
    }

    func testUnknownSizeSourceNeverWritesPastTheConfiguredLimit() throws {
        let sources = root.appendingPathComponent("Sources", isDirectory: true)
        let repository = SourceFileRepository(
            directory: sources,
            maximumBytes: 2,
            knownSizeProvider: { _ in nil }
        )
        let input = root.appendingPathComponent("provider-backed.wav")
        try Data([1, 2, 3]).write(to: input)

        XCTAssertThrowsError(try repository.stageCopy(from: input)) { error in
            XCTAssertEqual(error as? SourceFileRepositoryError, .fileTooLarge)
        }
        let remaining = (try? FileManager.default.contentsOfDirectory(atPath: sources.path)) ?? []
        XCTAssertTrue(remaining.isEmpty)
    }

    func testAudioPolicyRejectsDurationAndSizeBoundaries() {
        XCTAssertNoThrow(
            try IOSAudioLimits.validateImport(
                byteCount: IOSAudioLimits.maximumImportBytes,
                frameCount: 48_000 * 600,
                sampleRate: 48_000
            )
        )
        XCTAssertThrowsError(
            try IOSAudioLimits.validateImport(
                byteCount: IOSAudioLimits.maximumImportBytes + 1,
                frameCount: 1,
                sampleRate: 48_000
            )
        )
        XCTAssertThrowsError(
            try IOSAudioLimits.validateImport(
                byteCount: 1,
                frameCount: 48_000 * 601,
                sampleRate: 48_000
            )
        )
    }

    func testPlaybackLeaseRejectsStaleCompletion() {
        var lease = PlaybackLease()
        let first = lease.begin()
        XCTAssertTrue(lease.isCurrent(first))

        let second = lease.begin()
        XCTAssertFalse(lease.isCurrent(first))
        XCTAssertTrue(lease.isCurrent(second))

        lease.invalidate()
        XCTAssertFalse(lease.isCurrent(second))
    }
}
