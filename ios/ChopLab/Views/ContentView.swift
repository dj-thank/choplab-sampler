import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @StateObject private var store = SamplerStore()
    @State private var showingImporter = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 12), count: 4)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    sourceControls
                    padGrid
                    rangeEditor
                    transportControls
                }
                .padding(20)
            }
            .background(Color(red: 0.96, green: 0.92, blue: 0.84))
            .navigationTitle("おとひろい")
            .navigationBarTitleDisplayMode(.inline)
        }
        .fileImporter(
            isPresented: $showingImporter,
            allowedContentTypes: [.audio],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    store.importSource(from: url)
                } else {
                    store.reportImportPickerCancellation()
                }
            case .failure(let error):
                store.reportImportPickerFailure(error)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("曲を入れる → PADを叩く → ビートを作る")
                .font(.headline)
                .foregroundStyle(Color(red: 0.12, green: 0.12, blue: 0.1))
            Text(store.statusMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityLabel("状態: \(store.statusMessage)")
        }
    }

    private var sourceControls: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("音源")
                .font(.title3.weight(.semibold))
            Text(store.sourceName)
                .lineLimit(1)
                .font(.subheadline)
                .accessibilityLabel("選択中の音源: \(store.sourceName)")

            HStack(spacing: 10) {
                Button("音源を読み込む") {
                    showingImporter = true
                }
                .buttonStyle(.borderedProminent)
                .disabled(store.isRecording)
                .accessibilityHint(
                    store.isRecording
                        ? "録音を停止すると音源を選べます"
                        : "ファイルアプリから音声を選びます"
                )

                Button(store.isSourcePlaying ? "停止" : "曲を再生") {
                    if store.isSourcePlaying {
                        store.stopAll()
                    } else {
                        store.playSource()
                    }
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var padGrid: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("16 PAD")
                    .font(.title3.weight(.semibold))
                Spacer()
                Text("選択: \(store.pads[store.selectedPadID].title)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(store.pads) { pad in
                    Button {
                        store.playPad(pad)
                    } label: {
                        Text(pad.title)
                            .font(.headline.monospacedDigit())
                            .frame(maxWidth: .infinity, minHeight: 70)
                            .background(store.activePadID == pad.id ? Color.orange : Color(red: 0.18, green: 0.42, blue: 0.34))
                            .foregroundStyle(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("PAD \(pad.title)")
                    .accessibilityHint("選択範囲を再生します")
                }
            }
        }
    }

    private var rangeEditor: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("チョップ範囲")
                .font(.title3.weight(.semibold))
            Text("PAD \(store.pads[store.selectedPadID].title) の範囲")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("開始")
                    Spacer()
                    Text("\(Int(store.editStart * 100))%")
                        .monospacedDigit()
                }
                Slider(value: $store.editStart, in: 0...1)
                    .accessibilityLabel("開始位置")

                HStack {
                    Text("終了")
                    Spacer()
                    Text("\(Int(store.editEnd * 100))%")
                        .monospacedDigit()
                }
                Slider(value: $store.editEnd, in: 0...1)
                    .accessibilityLabel("終了位置")
            }

            Button("範囲をPADに保存") {
                store.applySelectedRange()
            }
            .buttonStyle(.bordered)
        }
    }

    private var transportControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("制作")
                    .font(.title3.weight(.semibold))
                Spacer()
                Text("BPM \(Int(store.bpm))")
                    .monospacedDigit()
            }
            Slider(value: $store.bpm, in: 60...180, step: 1)
                .accessibilityLabel("テンポ")

            HStack(spacing: 12) {
                Button(store.isRecording ? "録音停止" : "録音開始") {
                    if store.isRecording {
                        store.stopRecording()
                    } else {
                        store.startRecording()
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(store.isRecording ? .red : .orange)

                Button("ALL STOP") {
                    store.stopAll()
                }
                .buttonStyle(.bordered)
                .accessibilityHint("再生と録音を停止します")
            }
        }
    }
}

#Preview {
    ContentView()
}
