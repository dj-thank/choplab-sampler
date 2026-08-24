import AVFoundation
import Combine
import Foundation

protocol RecordingPermissionSession {
    var recordPermission: AVAudioSession.RecordPermission { get }

    func requestRecordPermission(_ response: @escaping (Bool) -> Void)
}

extension AVAudioSession: RecordingPermissionSession {}

@MainActor
final class SamplerStore: NSObject, ObservableObject, AVAudioRecorderDelegate {
    let pads = PadGridPolicy.cells()

    @Published private(set) var sourceName = "音源未選択"
    @Published private(set) var statusMessage = "音源を読み込むか録音してください"
    @Published private(set) var isSourcePlaying = false
    @Published private(set) var isRecording = false
    @Published private(set) var activePadID: Int?
    @Published var selectedPadID = 0 {
        didSet {
            guard padRanges.indices.contains(selectedPadID) else { return }
            editStart = padRanges[selectedPadID].start
            editEnd = padRanges[selectedPadID].end
        }
    }
    @Published var editStart = 0.0
    @Published var editEnd = 1.0
    @Published var bpm = 92.0

    private let engine = AVAudioEngine()
    private let sourcePlayer = AVAudioPlayerNode()
    private var padPlayers: [AVAudioPlayerNode] = []
    private let sourceRepository: SourceFileRepository
    private let recordingPermissionSession: RecordingPermissionSession
    private var sourceFile: AVAudioFile?
    private var activeSourceURL: URL?
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?
    private var recordingPermissionLease = RecordingPermissionLease()
    private var playbackLease = PlaybackLease()
    private var padRanges = Array(repeating: SliceRange(start: 0, end: 1), count: 16)

    init(
        sourceRepository: SourceFileRepository = SourceFileRepository(),
        recordingPermissionSession: RecordingPermissionSession = AVAudioSession.sharedInstance()
    ) {
        self.sourceRepository = sourceRepository
        self.recordingPermissionSession = recordingPermissionSession
        super.init()
        engine.attach(sourcePlayer)
        engine.connect(sourcePlayer, to: engine.mainMixerNode, format: nil)

        padPlayers = pads.map { _ in
            let player = AVAudioPlayerNode()
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: nil)
            return player
        }

        // The preview does not yet persist a project across launches. Retire files
        // left by an interrupted import or a previous process before accepting a
        // new source, instead of accumulating one UUID file per import forever.
        sourceRepository.purgeOrphans(keeping: nil)
    }

    @discardableResult
    func importSource(from url: URL) -> Bool {
        guard SourceImportPolicy.admission(isRecording: isRecording) == .allowed else {
            statusMessage = "録音中は音源を変更できません。録音を停止してから読み込んでください"
            return false
        }
        return replaceSource(
            from: url,
            displayName: url.deletingPathExtension().lastPathComponent
        )
    }

    func reportImportPickerCancellation() {
        if isRecording {
            statusMessage = "録音中。音源の読み込みはキャンセルされました"
        } else {
            statusMessage = "音源の読み込みをキャンセルしました"
        }
    }

    func reportImportPickerFailure(_ error: Error) {
        let cocoaError = error as NSError
        guard cocoaError.domain != NSCocoaErrorDomain ||
                cocoaError.code != CocoaError.Code.userCancelled.rawValue else {
            reportImportPickerCancellation()
            return
        }
        let prefix = isRecording ? "録音中。音源を選べませんでした" : "音源を選べませんでした"
        statusMessage = "\(prefix): \(error.localizedDescription)"
    }

    func playSource() {
        guard let file = sourceFile else {
            statusMessage = "先に音源を読み込んでください"
            return
        }

        do {
            try configureAudioSession(forRecording: false)
            try startEngineIfNeeded()
            stopPlayers()
            let generation = playbackLease.begin()
            sourcePlayer.scheduleFile(file, at: nil) { [weak self] in
                Task { @MainActor in
                    guard let self, self.playbackLease.isCurrent(generation) else { return }
                    self.isSourcePlaying = false
                    self.activePadID = nil
                    self.statusMessage = "曲の再生が終了しました"
                }
            }
            sourcePlayer.play()
            isSourcePlaying = true
            activePadID = nil
            statusMessage = "曲を再生中"
        } catch {
            stopPlayers()
            statusMessage = "再生を開始できませんでした: \(error.localizedDescription)"
        }
    }

    func playPad(_ pad: PadCell) {
        selectedPadID = pad.id
        guard let file = sourceFile, padPlayers.indices.contains(pad.id) else {
            statusMessage = "先に音源を読み込んでください"
            return
        }

        let range = padRanges[pad.id]
        let totalFrames = AVAudioFramePosition(file.length)
        let startFrame = AVAudioFramePosition(Double(totalFrames) * range.start)
        let endFrame = AVAudioFramePosition(Double(totalFrames) * range.end)
        let frameCount = AVAudioFrameCount(max(1, endFrame - startFrame))

        do {
            try configureAudioSession(forRecording: false)
            try startEngineIfNeeded()
            stopPlayers()
            let generation = playbackLease.begin()
            padPlayers[pad.id].scheduleSegment(
                file,
                startingFrame: startFrame,
                frameCount: frameCount,
                at: nil
            ) { [weak self] in
                Task { @MainActor in
                    guard let self, self.playbackLease.isCurrent(generation) else { return }
                    self.isSourcePlaying = false
                    self.activePadID = nil
                    self.statusMessage = "PAD \(pad.title) の再生が終了しました"
                }
            }
            padPlayers[pad.id].play()
            isSourcePlaying = false
            activePadID = pad.id
            statusMessage = "PAD \(pad.title) を再生中"
        } catch {
            stopPlayers()
            statusMessage = "PADを再生できませんでした: \(error.localizedDescription)"
        }
    }

    func applySelectedRange() {
        guard padRanges.indices.contains(selectedPadID) else { return }
        let range = SliceRange(start: editStart, end: editEnd)
        padRanges[selectedPadID] = range
        editStart = range.start
        editEnd = range.end
        statusMessage = "PAD \(pads[selectedPadID].title) の範囲を保存しました"
    }

    func startRecording() {
        let permissionGeneration = recordingPermissionLease.begin()
        let session = recordingPermissionSession
        switch session.recordPermission {
        case .granted:
            beginRecording()
        case .denied:
            statusMessage = "録音権限が必要です。設定から許可してください"
        case .undetermined:
            statusMessage = "録音権限を確認しています"
            session.requestRecordPermission { [weak self] granted in
                Task { @MainActor in
                    guard let self,
                          self.recordingPermissionLease.consume(permissionGeneration) else {
                        return
                    }
                    if granted {
                        self.beginRecording()
                    } else {
                        self.statusMessage = "録音権限が許可されませんでした"
                    }
                }
            }
        @unknown default:
            statusMessage = "録音権限を確認できませんでした"
        }
    }

    func stopRecording() {
        recordingPermissionLease.invalidate()
        guard isRecording else { return }
        let activeRecorder = recorder
        activeRecorder?.delegate = nil
        activeRecorder?.stop()
        recorder = nil
        isRecording = false
        finishRecordingFile(successMessage: "録音を音源に設定しました")
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated func audioRecorderDidFinishRecording(
        _ recorder: AVAudioRecorder,
        successfully flag: Bool
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.recorder === recorder else { return }
            self.recorder = nil
            self.isRecording = false
            if flag {
                self.finishRecordingFile(
                    successMessage: "10分の録音上限に達したため停止し、音源に設定しました"
                )
            } else {
                self.discardRecordingFile()
                self.statusMessage = "録音を完了できませんでした"
            }
            try? AVAudioSession.sharedInstance().setActive(
                false,
                options: .notifyOthersOnDeactivation
            )
        }
    }

    func stopAll() {
        recordingPermissionLease.invalidate()
        recorder?.delegate = nil
        recorder?.stop()
        recorder = nil
        isRecording = false
        discardRecordingFile()
        stopPlayers()
        statusMessage = "停止しました"
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func beginRecording() {
        do {
            recorder?.delegate = nil
            recorder?.stop()
            recorder = nil
            discardRecordingFile()
            stopPlayers()
            try configureAudioSession(forRecording: true)
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("choplab-recording-\(UUID().uuidString).m4a")
            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
            ]
            let newRecorder = try AVAudioRecorder(url: url, settings: settings)
            newRecorder.delegate = self
            newRecorder.prepareToRecord()
            guard newRecorder.record(forDuration: IOSAudioLimits.maximumRecordingSeconds) else {
                newRecorder.delegate = nil
                try? FileManager.default.removeItem(at: url)
                statusMessage = "録音を開始できませんでした"
                return
            }
            recorder = newRecorder
            recordingURL = url
            isRecording = true
            isSourcePlaying = false
            activePadID = nil
            statusMessage = "録音中。停止でPAD素材にします（最大10分）"
        } catch {
            discardRecordingFile()
            statusMessage = "録音を開始できませんでした: \(error.localizedDescription)"
        }
    }

    @discardableResult
    private func replaceSource(from url: URL, displayName: String) -> Bool {
        var candidateURL: URL?
        var promotedURL: URL?
        do {
            let candidate = try sourceRepository.stageCopy(from: url)
            candidateURL = candidate

            let candidateFile = try AVAudioFile(forReading: candidate)
            let byteCount = Int64(
                try candidate.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            )
            try IOSAudioLimits.validateImport(
                byteCount: byteCount,
                frameCount: candidateFile.length,
                sampleRate: candidateFile.processingFormat.sampleRate
            )

            let promoted = try sourceRepository.promote(candidate)
            candidateURL = nil
            promotedURL = promoted
            let committedFile = try AVAudioFile(forReading: promoted)
            guard committedFile.length > 0 else {
                throw SourceFileRepositoryError.invalidAudio
            }

            let previousURL = activeSourceURL
            stopPlayers()
            sourceFile = committedFile
            activeSourceURL = promoted
            sourceName = displayName.isEmpty ? "読み込んだ音源" : displayName
            selectedPadID = 0
            sourceRepository.retire(previousURL)
            sourceRepository.purgeOrphans(keeping: promoted)
            statusMessage = "\(sourceName) を読み込みました"
            return true
        } catch {
            sourceRepository.retire(candidateURL)
            if promotedURL != activeSourceURL {
                sourceRepository.retire(promotedURL)
            }
            statusMessage = "音源を読み込めませんでした: \(error.localizedDescription)"
            return false
        }
    }

    private func finishRecordingFile(successMessage: String) {
        guard let url = recordingURL else {
            statusMessage = "録音ファイルが見つかりません"
            return
        }
        recordingURL = nil
        let imported = replaceSource(from: url, displayName: "録音素材")
        try? FileManager.default.removeItem(at: url)
        if imported {
            statusMessage = successMessage
        }
    }

    private func configureAudioSession(forRecording: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        if forRecording {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        } else {
            try session.setCategory(.playback, mode: .default)
        }
        try session.setActive(true)
    }

    private func startEngineIfNeeded() throws {
        guard !engine.isRunning else { return }
        engine.prepare()
        try engine.start()
    }

    private func stopPlayers() {
        playbackLease.invalidate()
        sourcePlayer.stop()
        padPlayers.forEach { $0.stop() }
        isSourcePlaying = false
        activePadID = nil
    }

    private func discardRecordingFile() {
        if let recordingURL {
            try? FileManager.default.removeItem(at: recordingURL)
        }
        recordingURL = nil
    }
}

struct RecordingPermissionLease {
    private(set) var generation: UInt64 = 0

    mutating func begin() -> UInt64 {
        generation &+= 1
        return generation
    }

    mutating func consume(_ candidate: UInt64) -> Bool {
        guard candidate == generation else { return false }
        generation &+= 1
        return true
    }

    mutating func invalidate() {
        generation &+= 1
    }
}
