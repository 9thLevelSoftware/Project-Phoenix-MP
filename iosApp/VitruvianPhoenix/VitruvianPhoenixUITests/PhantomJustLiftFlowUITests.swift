import XCTest

final class PhantomJustLiftFlowUITests: XCTestCase {
    private let waitTimeout: TimeInterval = 30
    private let fixtureID = "just-lift-connected"
    private let phantomDeviceName = "Vee_PhantomSimulator"

    func testHomeToJustLiftToPhantomConnected() {
        let app = XCUIApplication()
        app.launchEnvironment["PHOENIX_SIMULATOR_FIXTURE"] = fixtureID
        app.launch()

        let homeCheckpoint = semanticElement("screen-home", in: app)
        guard waitForSemanticElement(
            homeCheckpoint,
            description: "Home screen checkpoint",
        ) else {
            return
        }

        let justLiftAction = app.buttons["Open Just Lift"]
        guard waitForSemanticElement(
            justLiftAction,
            description: "Just Lift action on the Home screen",
            requireHittable: true,
        ) else {
            return
        }
        justLiftAction.tap()

        let justLiftCheckpoint = semanticElement("screen-just-lift", in: app)
        guard waitForSemanticElement(
            justLiftCheckpoint,
            description: "Just Lift screen checkpoint",
        ) else {
            return
        }

        let connectionControl = app.buttons["Tap to connect to machine"]
        guard waitForSemanticElement(
            connectionControl,
            description: "connection control for the Phantom fixture",
            requireHittable: true,
        ) else {
            return
        }
        connectionControl.tap()

        let connectedCheckpoint = semanticElement("connection-status-connected", in: app)
        guard waitForSemanticElement(
            connectedCheckpoint,
            description: "connected-state checkpoint for \(phantomDeviceName)",
        ) else {
            return
        }

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "Phantom Just Lift connected - \(phantomDeviceName)"
        attachment.lifetime = .keepAlways
        add(attachment)

        let connectedControl = app.buttons["Connected to machine. Tap to disconnect"]
        _ = waitForSemanticElement(
            connectedControl,
            description: "connected semantic control for \(phantomDeviceName)",
        )
    }

    private func semanticElement(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    @discardableResult
    private func waitForSemanticElement(
        _ element: XCUIElement,
        description: String,
        requireHittable: Bool = false,
    ) -> Bool {
        let format = requireHittable
            ? "exists == true AND hittable == true"
            : "exists == true"
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: format),
            object: element,
        )
        let result = XCTWaiter().wait(for: [expectation], timeout: waitTimeout)
        guard result == .completed else {
            XCTFail(
                "Timed out after \(waitTimeout)s waiting for \(description). "
                    + "Expected semantic identifier/label \(element.identifier.isEmpty ? element.label : element.identifier). "
                    + "Fixture=\(fixtureID), Phantom=\(phantomDeviceName).",
            )
            return false
        }
        return true
    }
}
