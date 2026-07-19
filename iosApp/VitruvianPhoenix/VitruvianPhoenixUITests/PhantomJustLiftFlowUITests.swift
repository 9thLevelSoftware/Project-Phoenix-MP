import XCTest

final class PhantomJustLiftFlowUITests: XCTestCase {
    private let waitTimeout: TimeInterval = 30
    private let fixtureID = "just-lift-connected"
    private let cleanEulaFixtureID = "clean-eula"
    private let phantomDeviceName = "Vee_PhantomSimulator"

    func testHomeToJustLiftToPhantomConnected() {
        let app = XCUIApplication()
        app.launchEnvironment["PHOENIX_SIMULATOR_FIXTURE"] = fixtureID
        app.launch()

        let homeCheckpoint = semanticElement("screen-home", in: app)
        guard waitForSemanticElement(
            homeCheckpoint,
            description: "Home screen checkpoint",
            fixture: fixtureID,
        ) else {
            return
        }

        let justLiftAction = semanticElement("action-just-lift", in: app)
        guard waitForSemanticElement(
            justLiftAction,
            description: "Just Lift action on the Home screen",
            requireHittable: true,
            fixture: fixtureID,
        ) else {
            return
        }
        XCTAssertEqual(
            justLiftAction.elementType,
            .button,
            "Just Lift action must render as a button. Identifier=\(justLiftAction.identifier), Fixture=\(fixtureID).",
        )
        justLiftAction.tap()

        let justLiftCheckpoint = semanticElement("screen-just-lift", in: app)
        guard waitForSemanticElement(
            justLiftCheckpoint,
            description: "Just Lift screen checkpoint",
            fixture: fixtureID,
        ) else {
            return
        }

        let connectionControl = semanticElement(
            "connection-status-disconnected",
            in: app,
        )
        guard waitForSemanticElement(
            connectionControl,
            description: "disconnected connection control for the Phantom fixture",
            requireHittable: true,
            fixture: fixtureID,
        ) else {
            return
        }
        assertMeaningfulAccessibleLabel(
            connectionControl,
            description: "disconnected connection control for \(phantomDeviceName)",
            fixture: fixtureID,
        )
        connectionControl.tap()

        let connectedCheckpoint = semanticElement("connection-status-connected", in: app)
        guard waitForSemanticElement(
            connectedCheckpoint,
            description: "connected-state checkpoint for \(phantomDeviceName)",
            fixture: fixtureID,
        ) else {
            return
        }

        assertMeaningfulAccessibleLabel(
            connectedCheckpoint,
            description: "connected-state checkpoint for \(phantomDeviceName)",
            fixture: fixtureID,
        )
        assertConnectedAccessibleLabel(
            connectedCheckpoint,
            description: "connected-state checkpoint for \(phantomDeviceName)",
            fixture: fixtureID,
        )

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "Phantom Just Lift connected - \(phantomDeviceName)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testCleanEulaSemanticGatingAndNavigation() {
        let app = XCUIApplication()
        app.launchEnvironment["PHOENIX_SIMULATOR_FIXTURE"] = cleanEulaFixtureID
        app.launch()

        let eulaCheckpoint = semanticElement("screen-eula", in: app)
        guard waitForSemanticElement(
            eulaCheckpoint,
            description: "EULA screen checkpoint",
            requireHittable: true,
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }

        let eulaScrollContainer = semanticElement("eula-scroll-container", in: app)
        guard waitForSemanticElement(
            eulaScrollContainer,
            description: "EULA legal scroll container",
            requireHittable: true,
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }

        let ageConfirmation = semanticElement("eula-age-confirmation", in: app)
        guard waitForSemanticElement(
            ageConfirmation,
            description: "EULA age confirmation control",
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }

        let acceptControl = semanticElement("eula-accept", in: app)
        guard waitForSemanticElement(
            acceptControl,
            description: "EULA accept control",
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }

        XCTAssertEqual(
            acceptControl.elementType,
            .button,
            "EULA accept control must expose button semantics. Identifier=\(acceptControl.identifier), Fixture=\(cleanEulaFixtureID).",
        )
        assertToggleableActionShape(
            ageConfirmation,
            description: "EULA age confirmation control",
            fixture: cleanEulaFixtureID,
        )
        XCTAssertFalse(
            acceptControl.isEnabled,
            "EULA accept control must be disabled before age confirmation and legal scroll completion. "
                + "Identifier=\(acceptControl.identifier), Fixture=\(cleanEulaFixtureID).",
        )

        let eulaScreenshot = XCUIScreen.main.screenshot()
        let eulaAttachment = XCTAttachment(screenshot: eulaScreenshot)
        eulaAttachment.name = "Clean EULA semantic checkpoint"
        eulaAttachment.lifetime = .keepAlways
        add(eulaAttachment)

        // Drive the rendered legal scroll container, never the screen by coordinate.
        for _ in 0..<12 {
            eulaScrollContainer.swipeUp()
        }

        guard waitForSemanticElement(
            ageConfirmation,
            description: "hittable EULA age confirmation control after legal scroll",
            requireHittable: true,
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }

        XCTAssertFalse(
            acceptControl.isEnabled,
            "EULA accept control must remain disabled until age confirmation is selected. "
                + "Identifier=\(acceptControl.identifier), Fixture=\(cleanEulaFixtureID).",
        )

        ageConfirmation.tap()
        guard waitForSelected(
            ageConfirmation,
            description: "selected EULA age confirmation control",
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }
        guard waitForEnabled(
            acceptControl,
            description: "enabled EULA accept control after legal scroll and age confirmation",
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }
        acceptControl.tap()

        let homeCheckpoint = semanticElement("screen-home", in: app)
        guard waitForSemanticElement(
            homeCheckpoint,
            description: "Home screen after EULA acceptance",
            requireHittable: true,
            fixture: cleanEulaFixtureID,
        ) else {
            return
        }
    }

    private func semanticElement(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func assertMeaningfulAccessibleLabel(
        _ element: XCUIElement,
        description: String,
        fixture: String,
    ) {
        let label = element.label.trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertFalse(
            label.isEmpty,
            "\(description) must expose meaningful accessible label/content. "
                + "Identifier=\(element.identifier), Fixture=\(fixture), Phantom=\(phantomDeviceName).",
        )
    }

    private func assertConnectedAccessibleLabel(
        _ element: XCUIElement,
        description: String,
        fixture: String,
    ) {
        let label = element.label.trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertTrue(
            label.localizedCaseInsensitiveContains("connected"),
            "\(description) must expose user-facing connected meaning. "
                + "Accessible label=\(label), Identifier=\(element.identifier), "
                + "Fixture=\(fixture), Phantom=\(phantomDeviceName).",
        )
    }

    private func assertToggleableActionShape(
        _ element: XCUIElement,
        description: String,
        fixture: String,
    ) {
        let isActionableCheckableBridge = element.elementType == .button
            || element.elementType == .checkBox
            || element.elementType == .switch
        XCTAssertTrue(
            isActionableCheckableBridge,
            "\(description) must expose an actionable checkbox/toggle bridge. "
                + "Element type=\(element.elementType.rawValue), Identifier=\(element.identifier), Fixture=\(fixture).",
        )
        XCTAssertFalse(
            element.isSelected,
            "\(description) must start unchecked/unselected. "
                + "Identifier=\(element.identifier), Fixture=\(fixture).",
        )
    }

    @discardableResult
    private func waitForSemanticElement(
        _ element: XCUIElement,
        description: String,
        requireHittable: Bool = false,
        fixture: String,
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
                    + "Fixture=\(fixture), Phantom=\(phantomDeviceName).",
            )
            return false
        }
        return true
    }

    @discardableResult
    private func waitForEnabled(
        _ element: XCUIElement,
        description: String,
        fixture: String,
    ) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true AND enabled == true"),
            object: element,
        )
        let result = XCTWaiter().wait(for: [expectation], timeout: waitTimeout)
        guard result == .completed else {
            XCTFail(
                "Timed out after \(waitTimeout)s waiting for \(description). "
                    + "Expected enabled semantic identifier/label \(element.identifier.isEmpty ? element.label : element.identifier). "
                    + "Fixture=\(fixture), Phantom=\(phantomDeviceName).",
            )
            return false
        }
        return true
    }

    @discardableResult
    private func waitForSelected(
        _ element: XCUIElement,
        description: String,
        fixture: String,
    ) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let element = object as? XCUIElement else {
                    return false
                }
                return element.exists && element.isSelected
            },
            object: element,
        )
        let result = XCTWaiter().wait(for: [expectation], timeout: waitTimeout)
        guard result == .completed else {
            XCTFail(
                "Timed out after \(waitTimeout)s waiting for \(description). "
                    + "Expected selected semantic identifier/label \(element.identifier.isEmpty ? element.label : element.identifier). "
                    + "Fixture=\(fixture), Phantom=\(phantomDeviceName).",
            )
            return false
        }
        return true
    }
}
