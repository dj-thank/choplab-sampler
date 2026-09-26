import AVFoundation
import CoreMedia
import Foundation
import ScreenCaptureKit

/// Captures other apps' system audio and writes `CHOPLAB-PCM <hz> <channels>` plus PCM-16 LE on stdout.
@main
struct ChoplabSystemAudio {
    static func main() async {
        do {
            try await capture()
        } catch {
            let detail = error.localizedDescription.replacingOccurrences(of: "\n", with: " ")
            FileHandle.standardOutput.write(Data("CHOPLAB-ERROR \(detail)\n".utf8))
            exit(1)
        }
    }

    private static func capture() async throws {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else {
            throw CaptureFailure("表示中のディスプレイが見つかりません")
        }
        // Capture runs in a child helper. excludesCurrentProcessAudio alone only
        // excludes this helper, not the Java host that plays ChopLab's audio.
        let hostApplications = content.applications.filter { $0.processID == getppid() }
        let filter = SCContentFilter(
            display: display, excludingApplications: hostApplications, exceptingWindows: []
        )
        let configuration = SCStreamConfiguration()
        configuration.width = 2
        configuration.height = 2
        configuration.capturesAudio = true
        configuration.excludesCurrentProcessAudio = true
        configuration.sampleRate = 48_000
        configuration.channelCount = 2
        let sink = AudioSink()
        let stream = SCStream(filter: filter, configuration: configuration, delegate: sink)
        try stream.addStreamOutput(sink, type: .audio, sampleHandlerQueue: sink.queue)
        try await stream.startCapture()
        sink.writeHeader(sampleRate: 48_000, channels: 2)
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                _ = FileHandle.standardInput.readDataToEndOfFile()
                continuation.resume()
            }
        }
        try await stream.stopCapture()
    }
}

private struct CaptureFailure: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private final class AudioSink: NSObject, SCStreamOutput, SCStreamDelegate {
    let queue = DispatchQueue(label: "choplab.system-audio")
    private let output = FileHandle.standardOutput
    private let lock = NSLock()
    private var headerWritten = false

    func writeHeader(sampleRate: Int, channels: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard !headerWritten else { return }
        headerWritten = true
        output.write(Data("CHOPLAB-PCM \(sampleRate) \(channels)\n".utf8))
    }

    /// A capture that stops by itself (revoked permission, display change) must not look like a
    /// finished recording: ending the helper here makes ChopLab report the early stop.
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        let detail = error.localizedDescription.replacingOccurrences(of: "\n", with: " ")
        FileHandle.standardError.write(Data("CHOPLAB-ERROR \(detail)\n".utf8))
        exit(2)
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, CMSampleBufferIsValid(sampleBuffer), CMSampleBufferDataIsReady(sampleBuffer) else { return }
        guard let description = CMSampleBufferGetFormatDescription(sampleBuffer),
              let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description) else { return }
        var streamDescription = basic.pointee
        guard let format = AVAudioFormat(streamDescription: &streamDescription) else { return }
        let frames = AVAudioFrameCount(CMSampleBufferGetNumSamples(sampleBuffer))
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return }
        buffer.frameLength = frames
        let status = CMSampleBufferCopyPCMDataIntoAudioBufferList(
            sampleBuffer,
            at: 0,
            frameCount: Int32(frames),
            into: buffer.mutableAudioBufferList
        )
        guard status == noErr else { return }
        writePcm(buffer)
    }

    private func writePcm(_ buffer: AVAudioPCMBuffer) {
        let frameCount = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        guard frameCount > 0, channels >= 1 else { return }
        // The header promises two channels: mono is written to both sides, extra channels are dropped.
        let right = channels > 1 ? 1 : 0
        var bytes = [UInt8]()
        bytes.reserveCapacity(frameCount * 4)
        if let samples = buffer.floatChannelData {
            for frame in 0..<frameCount {
                for channel in [0, right] {
                    var value = Int16(max(-1, min(1, samples[channel][frame])) * 32_767).littleEndian
                    withUnsafeBytes(of: &value) { bytes.append(contentsOf: $0) }
                }
            }
        } else if let samples = buffer.int16ChannelData {
            for frame in 0..<frameCount {
                for channel in [0, right] {
                    var value = samples[channel][frame].littleEndian
                    withUnsafeBytes(of: &value) { bytes.append(contentsOf: $0) }
                }
            }
        } else {
            return
        }
        lock.lock()
        if headerWritten { output.write(Data(bytes)) }
        lock.unlock()
    }
}
