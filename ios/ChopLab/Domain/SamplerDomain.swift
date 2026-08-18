import Foundation

struct PadCell: Identifiable, Equatable {
    let id: Int

    var title: String {
        String(format: "%02d", id + 1)
    }
}

struct SliceRange: Equatable {
    let start: Double
    let end: Double

    init(start: Double, end: Double) {
        let boundedStart = min(max(start, 0), 1)
        let boundedEnd = min(max(end, 0), 1)
        if boundedStart <= boundedEnd {
            self.start = boundedStart
            self.end = boundedEnd
        } else {
            self.start = boundedEnd
            self.end = boundedStart
        }
    }

    var length: Double { end - start }
}

enum PadGridPolicy {
    static let columnCount = 4

    static func cells(count: Int = 16) -> [PadCell] {
        guard count > 0 else { return [] }
        return (0..<count).map(PadCell.init(id:))
    }

    static func row(for index: Int) -> Int {
        max(index, 0) / columnCount
    }

    static func column(for index: Int) -> Int {
        max(index, 0) % columnCount
    }
}
