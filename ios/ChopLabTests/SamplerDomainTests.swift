import XCTest
@testable import ChopLab

final class SamplerDomainTests: XCTestCase {
    func testSixteenPadsAreFourColumns() {
        let pads = PadGridPolicy.cells()

        XCTAssertEqual(pads.count, 16)
        XCTAssertEqual(pads.first?.title, "01")
        XCTAssertEqual(pads.last?.title, "16")
        XCTAssertEqual(PadGridPolicy.row(for: 0), 0)
        XCTAssertEqual(PadGridPolicy.column(for: 3), 3)
        XCTAssertEqual(PadGridPolicy.row(for: 15), 3)
    }

    func testSliceRangeClampsAndOrdersBounds() {
        XCTAssertEqual(SliceRange(start: -0.2, end: 1.4), SliceRange(start: 0, end: 1))
        XCTAssertEqual(SliceRange(start: 0.8, end: 0.2), SliceRange(start: 0.2, end: 0.8))
        XCTAssertEqual(SliceRange(start: 0.4, end: 0.4).length, 0)
    }
}
