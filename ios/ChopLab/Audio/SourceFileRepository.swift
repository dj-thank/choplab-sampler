import Foundation

enum IOSAudioLimits {
    static let maximumImportBytes: Int64 = 256 * 1024 * 1024
    static let maximumDurationSeconds = 10.0 * 60.0
    static let maximumRecordingSeconds = 10.0 * 60.0

    static func validateImport(byteCount: Int64, frameCount: Int64, sampleRate: Double) throws {
        guard byteCount >= 0, byteCount <= maximumImportBytes else {
            throw SourceFileRepositoryError.fileTooLarge
        }
        guard frameCount > 0, sampleRate.isFinite, sampleRate >= 8_000 else {
            throw SourceFileRepositoryError.invalidAudio
        }
        guard Double(frameCount) / sampleRate <= maximumDurationSeconds else {
            throw SourceFileRepositoryError.audioTooLong
        }
    }
}

enum SourceFileRepositoryError: LocalizedError, Equatable {
    case fileTooLarge
    case audioTooLong
    case invalidAudio
    case unsafeCandidate

    var errorDescription: String? {
        switch self {
        case .fileTooLarge:
            return "音声ファイルが大きすぎます。256 MiB以下のファイルを使用してください"
        case .audioTooLong:
            return "音声が長すぎます。10分以内の音声を使用してください"
        case .invalidAudio:
            return "音源が空か、対応できない形式です"
        case .unsafeCandidate:
            return "音源の保存先が不正です"
        }
    }
}

struct SourceFileRepository {
    let directory: URL
    private let fileManager: FileManager
    private let maximumBytes: Int64
    private let knownSizeProvider: (URL) -> Int64?

    private static let copyBufferBytes = 64 * 1024

    init(
        directory: URL? = nil,
        fileManager: FileManager = .default,
        maximumBytes: Int64 = IOSAudioLimits.maximumImportBytes,
        knownSizeProvider: @escaping (URL) -> Int64? = { url in
            guard let values = try? url.resourceValues(forKeys: [.fileSizeKey]),
                  let size = values.fileSize else {
                return nil
            }
            return Int64(size)
        }
    ) {
        precondition(maximumBytes >= 0, "maximumBytes must not be negative")
        self.fileManager = fileManager
        self.maximumBytes = maximumBytes
        self.knownSizeProvider = knownSizeProvider
        self.directory = directory ?? fileManager
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sources", isDirectory: true)
    }

    func stageCopy(from source: URL) throws -> URL {
        try ensureDirectory()
        let accessed = source.startAccessingSecurityScopedResource()
        defer {
            if accessed { source.stopAccessingSecurityScopedResource() }
        }

        if let knownSize = knownSizeProvider(source), knownSize > maximumBytes {
            throw SourceFileRepositoryError.fileTooLarge
        }

        let ext = source.pathExtension.isEmpty ? "audio" : source.pathExtension
        let candidate = directory.appendingPathComponent(".pending-\(UUID().uuidString).\(ext)")
        do {
            try copyBounded(from: source, to: candidate)
            return candidate
        } catch {
            try? fileManager.removeItem(at: candidate)
            throw error
        }
    }

    func promote(_ candidate: URL) throws -> URL {
        try ensureDirectory()
        guard candidate.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL,
              candidate.lastPathComponent.hasPrefix(".pending-") else {
            throw SourceFileRepositoryError.unsafeCandidate
        }
        let ext = candidate.pathExtension.isEmpty ? "audio" : candidate.pathExtension
        let destination = directory.appendingPathComponent("source-\(UUID().uuidString).\(ext)")
        try fileManager.moveItem(at: candidate, to: destination)
        return destination
    }

    func retire(_ url: URL?) {
        guard let url,
              url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL else {
            return
        }
        try? fileManager.removeItem(at: url)
    }

    func purgeOrphans(keeping activeURL: URL?) {
        guard let entries = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else { return }
        let keep = activeURL?.standardizedFileURL
        for entry in entries where entry.standardizedFileURL != keep {
            try? fileManager.removeItem(at: entry)
        }
        // Hidden pending candidates are intentionally included in a second pass.
        guard let allEntries = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: []
        ) else { return }
        for entry in allEntries where entry.standardizedFileURL != keep {
            try? fileManager.removeItem(at: entry)
        }
    }

    private func ensureDirectory() throws {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func copyBounded(from source: URL, to candidate: URL) throws {
        guard fileManager.createFile(atPath: candidate.path, contents: nil) else {
            throw SourceFileRepositoryError.invalidAudio
        }

        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let output = try FileHandle(forWritingTo: candidate)
        defer { try? output.close() }

        var copiedBytes: Int64 = 0
        while true {
            let data = try input.read(upToCount: Self.copyBufferBytes) ?? Data()
            if data.isEmpty { break }
            let chunkBytes = Int64(data.count)
            guard chunkBytes <= maximumBytes - copiedBytes else {
                throw SourceFileRepositoryError.fileTooLarge
            }
            try output.write(contentsOf: data)
            copiedBytes += chunkBytes
        }
        try output.synchronize()
    }
}

struct PlaybackLease {
    private(set) var generation: UInt64 = 0

    mutating func begin() -> UInt64 {
        generation &+= 1
        return generation
    }

    mutating func invalidate() {
        generation &+= 1
    }

    func isCurrent(_ candidate: UInt64) -> Bool {
        generation == candidate
    }
}

enum SourceImportAdmission: Equatable {
    case allowed
    case rejectedWhileRecording
}

enum SourceImportPolicy {
    static func admission(isRecording: Bool) -> SourceImportAdmission {
        isRecording ? .rejectedWhileRecording : .allowed
    }
}
