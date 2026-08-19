import AVFoundation
import Combine
import Foundation

@MainActor
final class SamplerStore: NSObject, ObservableObject {
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
    private var sourceFile: AVAudioFile?
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?
    private var padRanges = Array(repeating: SliceRange(start: 0, end: 1), count: 16)

    override init() {
        super.init()
        engine.attach(sourcePlayer)
        engine.connect(sourcePlayer, to: engine.mainMixerNode, format: nil)

        padPlayers = pads.map { _ in
            let player = AVAudioPlayerNode()
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: nil)
            return player
        }
    }

    func importSource(from url: URL) {
        do {
            let localURL = try copyIntoAppStorage(from: url)
            try loadSource(from: localURL, name: url.deletingPathExtension().lastPathComponent)
        } catch {
            statusMessage = "音源を読み込めませんでした: \(error.localizedDescription)"
        }
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
            sourcePlayer.scheduleFile(file, at: nil)
            sourcePlayer.play()
            isSourcePlaying = true
            activePadID = nil
            statusMessage = "曲を再生中"
        } catch {
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
            sourcePlayer.stop()
            padPlayers[pad.id].stop()
            padPlayers[pad.id].scheduleSegment(
                file,
                startingFrame: startFrame,
                frameCount: frameCount,
                at: nil
            )
            padPlayers[pad.id].play()
            isSourcePlaying = false
            activePadID = pad.id
            statusMessage = "PAD \(pad.title) を再生中"
        } catch {
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
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            beginRecording()
        case .denied:
            statusMessage = "録音権限が必要です。設定から許可してください"
        case .undetermined:
            session.requestRecordPermission { [weak self] granted in
                Task { @MainActor in
                    guard let self else { return }
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
        guard isRecording else { return }
        recorder?.stop()
        recorder = nil
        isRecording = false

        if let recordingURL {
            importSource(from: recordingURL)
            try? FileManager.default.removeItem(at: recordingURL)
        }
        self.recordingURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        statusMessage = "録音を音源に設定しました"
    }

    func stopAll() {
        recorder?.stop()
        recorder = nil
        isRecording = false
        discardRecordingFile()
        stopPlayers()
        isSourcePlaying = false
        activePadID = nil
        statusMessage = "停止しました"
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func beginRecording() {
        do {
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
            newRecorder.prepareToRecord()
            guard newRecorder.record() else {
                try? FileManager.default.removeItem(at: url)
                statusMessage = "録音を開始できませんでした"
                return
            }
            recorder = newRecorder
            recordingURL = url
            isRecording = true
            isSourcePlaying = false
            activePadID = nil
            statusMessage = "録音中。停止でPAD素材にします"
        } catch {
            statusMessage = "録音を開始できませんでした: \(error.localizedDescription)"
        }
    }

    private func loadSource(from url: URL, name: String) throws {
        stopPlayers()
        let file = try AVAudioFile(forReading: url)
        guard file.length > 0 else {
            throw NSError(domain: "ChopLab", code: 1, userInfo: [NSLocalizedDescriptionKey: "音源が空です"])
        }
        sourceFile = file
        sourceName = name.isEmpty ? "読み込んだ音源" : name
        selectedPadID = 0
        statusMessage = "\(sourceName) を読み込みました"
    }

    private func copyIntoAppStorage(from url: URL) throws -> URL {
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sources", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let ext = url.pathExtension.isEmpty ? "audio" : url.pathExtension
        let destination = directory.appendingPathComponent("source-\(UUID().uuidString).\(ext)")
        try FileManager.default.copyItem(at: url, to: destination)
        return destination
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
        sourcePlayer.stop()
        padPlayers.forEach { $0.stop() }
    }

    private func discardRecordingFile() {
        if let recordingURL {
            try? FileManager.default.removeItem(at: recordingURL)
        }
        recordingURL = nil
    }
}
