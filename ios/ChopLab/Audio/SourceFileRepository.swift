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

enum SourceFileRepositoryError: LocalizedError {
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

    init(
        directory: URL? = nil,
        fileManager: FileManager = .default,
        maximumBytes: Int64 = IOSAudioLimits.maximumImportBytes
    ) {
        self.fileManager = fileManager
        self.maximumBytes = maximumBytes
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

        let knownSize = try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize
        if let knownSize, Int64(knownSize) > maximumBytes {
            throw SourceFileRepositoryError.fileTooLarge
        }

        let ext = source.pathExtension.isEmpty ? "audio" : source.pathExtension
        let candidate = directory.appendingPathComponent(".pending-\(UUID().uuidString).\(ext)")
        do {
            try fileManager.copyItem(at: source, to: candidate)
            let actualSize = try candidate.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard Int64(actualSize) <= maximumBytes else {
                throw SourceFileRepositoryError.fileTooLarge
            }
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
