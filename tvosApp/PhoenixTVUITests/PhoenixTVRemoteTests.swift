import XCTest

final class PhoenixTVRemoteTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    func testRemoteSelectOpensConnectAndMenuReturnsHome() throws {
        app.launchArguments = ["--phoenix-tv-phantom", "--phoenix-tv-isolated-data", "--phoenix-tv-empty-scan"]
        app.launch()
        let accept = app.buttons["eula.accept"]
        if accept.waitForExistence(timeout: 8) {
            let eulaHidden = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: accept)
            XCUIRemote.shared.press(.select)
            wait(for: [eulaHidden], timeout: 10)
        }

        let connectHome = app.buttons["home.connect"]
        XCTAssertTrue(connectHome.waitForExistence(timeout: 45), "Phoenix home should appear after shared startup")
        let brand = app.staticTexts["home.brand"]
        let warning = app.descendants(matching: .any)["fixture.warning"].firstMatch
        XCTAssertTrue(brand.exists && warning.exists)
        XCTAssertGreaterThanOrEqual(brand.frame.minY, warning.frame.maxY, "Fixture disclosure must not cover the Phoenix header")
        captureScreen("phoenix-home")

        XCUIRemote.shared.press(.select)
        let scan = app.buttons["connect.scan"]
        XCTAssertTrue(scan.waitForExistence(timeout: 15), "Select should open the Connect route")
        captureScreen("phoenix-connect-empty")

        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.staticTexts["No trainer selected"].waitForExistence(timeout: 10), "No synthetic trainer should be shown when simulator BLE is empty")

        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(connectHome.waitForExistence(timeout: 15), "Menu should return to Home when no workout session is open")

        let justLift = app.buttons["home.just-lift"]
        XCTAssertTrue(justLift.exists)
        XCUIRemote.shared.press(.down)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.staticTexts["Connect to a trainer first"].waitForExistence(timeout: 10), "Just Lift must require an explicit machine connection")
        captureScreen("phoenix-just-lift-not-connected")

        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(connectHome.waitForExistence(timeout: 15))
        XCUIRemote.shared.press(.down)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.staticTexts["No saved routines"].waitForExistence(timeout: 10), "An empty local routine library should be honest")
        captureScreen("phoenix-routines-empty")
        XCUIRemote.shared.press(.menu)
        openHome("home.account", row: 3)
        XCTAssertTrue(app.descendants(matching: .any)["account.unavailable"].waitForExistence(timeout: 10))
        captureScreen("phoenix-account-unavailable")
    }

    func testExplicitPhantomFixtureConnectsAndPlayPauseUsesSharedStop() throws {
        app.launchArguments = ["--phoenix-tv-phantom", "--phoenix-tv-isolated-data"]
        app.launch()

        let accept = app.buttons["eula.accept"]
        if accept.waitForExistence(timeout: 8) {
            let eulaHidden = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: accept)
            XCUIRemote.shared.press(.select)
            wait(for: [eulaHidden], timeout: 10)
        }
        XCTAssertTrue(app.buttons["home.connect"].waitForExistence(timeout: 45))
        XCTAssertTrue(app.staticTexts["SIMULATED TRAINER · NO REAL BLUETOOTH"].exists)

        XCUIRemote.shared.press(.select)
        let scan = app.buttons["connect.scan"]
        XCTAssertTrue(scan.waitForExistence(timeout: 15))
        XCUIRemote.shared.press(.select)

        let deviceConnect = app.buttons["connect.device.PH:AN:TO:MS:BX:01"]
        XCTAssertTrue(deviceConnect.waitForExistence(timeout: 20), "The explicit simulator fixture should advertise one clearly labelled trainer.")
        XCUIRemote.shared.press(.down)
        XCUIRemote.shared.press(.select)
        let selected = expectation(for: NSPredicate(format: "label CONTAINS %@", "Selected"), evaluatedWith: deviceConnect)
        wait(for: [selected], timeout: 20)

        XCUIRemote.shared.press(.menu)
        let justLiftHome = app.buttons["home.just-lift"]
        XCTAssertTrue(justLiftHome.waitForExistence(timeout: 15))
        XCUIRemote.shared.press(.down)
        XCUIRemote.shared.press(.select)

        let exercise = app.buttons["justlift.exercise.Ab_Crunch_Machine"]
        XCTAssertTrue(exercise.waitForExistence(timeout: 20), "The simulator fixture should offer a real machine-compatible Phoenix exercise.")
        XCUIRemote.shared.press(.select)
        let selectedExercise = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Ab Crunch Machine ·")).firstMatch
        XCTAssertTrue(selectedExercise.waitForExistence(timeout: 15), "The selected trainer-compatible exercise must propagate through shared Phoenix workout parameters.")

        let startSet = app.buttons["justlift.start"]
        XCTAssertTrue(startSet.waitForExistence(timeout: 10))
        let sharedSelectionReady = expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: startSet)
        wait(for: [sharedSelectionReady], timeout: 15)
        for _ in 0..<8 {
            if isFocused("justlift.start") { break }
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(isFocused("justlift.start"), "Siri Remote navigation must focus Start Set before activation. Current tree:\n" + app.debugDescription)
        captureScreen("phoenix-phantom-just-lift-setup")
        XCTAssertTrue(app.buttons["justlift.rest-setting"].label.contains("60s"), "Setup must show configured rest, not a running timer's remaining value.")
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.staticTexts["ACTIVE SET"].waitForExistence(timeout: 20), "The shared workout engine should enter an active set after explicit Select.")
        captureScreen("phoenix-phantom-active-set")

        XCUIRemote.shared.press(.playPause)
        let completed = app.staticTexts["SET COMPLETE"]
        let resting = app.staticTexts["REST"]
        let stopError = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Phoenix could not confirm a safe machine stop")).firstMatch
        let stopped = completed.waitForExistence(timeout: 20) || resting.waitForExistence(timeout: 1)
        XCTAssertTrue(stopped, "Play/Pause did not complete the shared set-stop path. Error: \(stopError.exists ? stopError.label : "no stop error reported")")
        captureScreen("phoenix-phantom-stopped-set")
        selectFocusedButton("justlift.end", direction: .right)
        confirmSessionEnd(screenshot: "phoenix-just-lift-end-confirmation")
    }


    func testMenuDuringActiveSetStopsBeforeExitAndKeepTrainingDoesNotResume() throws {
        launchIsolatedFixture()
        connectFixture()
        openHome("home.just-lift", row: 1)
        selectFocusedButton("justlift.exercise.Ab_Crunch_Machine", direction: .right)
        selectFocusedButton("justlift.start", direction: .down)
        XCTAssertTrue(app.buttons["justlift.stop"].waitForExistence(timeout: 20))
        captureScreen("phoenix-back-active-before-stop")
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(app.alerts["End training session?"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["justlift.stop"].exists, "Menu must finish the shared stop before prompting to leave.")
        captureScreen("phoenix-back-safe-exit-confirmation")
        selectFocusedButton("session.keep-training", direction: .up)
        let activeAgain = expectation(for: NSPredicate(format: "exists == true"), evaluatedWith: app.buttons["justlift.stop"])
        activeAgain.isInverted = true
        wait(for: [activeAgain], timeout: 6)
        captureScreen("phoenix-back-keep-training-no-resume")
        XCUIRemote.shared.press(.menu)
        confirmSessionEnd(screenshot: "phoenix-back-finish")
    }

    func testSavedDailyRoutineRunsTwoSetsWithExplicitNextAndFinish() throws {
        launchIsolatedFixture()
        connectFixture()
        openHome("home.daily-routines", row: 2)
        selectFocusedButton("routine.tv-fixture-two-sets", direction: .down)
        XCTAssertTrue(app.buttons["routine.begin"].waitForExistence(timeout: 10))
        captureScreen("phoenix-daily-routine-overview")
        // Menu in the read-only overview must navigate, not create an exit dialog.
        XCUIRemote.shared.press(.menu)
        XCTAssertFalse(app.alerts["End training session?"].exists)
        selectFocusedButton("routine.tv-fixture-two-sets", direction: .down)
        selectFocusedButton("routine.begin", direction: .down)
        XCTAssertTrue(app.buttons["routine.start-set"].waitForExistence(timeout: 15))
        captureScreen("phoenix-routine-set-one-setup")
        selectFocusedButton("routine.start-set", direction: .up)
        XCTAssertTrue(app.buttons["routine.stop"].waitForExistence(timeout: 20))
        captureScreen("phoenix-routine-set-one-active")
        XCTAssertTrue(app.staticTexts["routine.summary"].waitForExistence(timeout: 35))
        captureScreen("phoenix-routine-set-one-summary")
        selectFocusedButton("routine.continue", direction: .up)
        XCTAssertTrue(app.staticTexts["routine.rest"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["routine.rest"].label.contains("Manual rest"))
        captureScreen("phoenix-routine-manual-rest")
        XCTAssertTrue(app.buttons["routine.start-set"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Set 2 of 2")).firstMatch.exists)
        let noAutomaticStart = expectation(for: NSPredicate(format: "exists == true"), evaluatedWith: app.buttons["routine.stop"])
        noAutomaticStart.isInverted = true
        wait(for: [noAutomaticStart], timeout: 6)
        captureScreen("phoenix-routine-set-two-ready-no-autostart")
        selectFocusedButton("routine.start-set", direction: .up)
        XCTAssertTrue(app.buttons["routine.stop"].waitForExistence(timeout: 20))
        captureScreen("phoenix-routine-set-two-active")
        XCTAssertTrue(app.staticTexts["routine.summary"].waitForExistence(timeout: 35))
        captureScreen("phoenix-routine-set-two-summary")
        selectFocusedButton("routine.continue", direction: .up)
        if app.staticTexts["routine.rest"].waitForExistence(timeout: 2) {
            selectFocusedButton("routine.continue", direction: .up)
        }
        XCTAssertTrue(app.staticTexts["routine.complete"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["routine.start-set"].exists)
        XCTAssertFalse(app.buttons["workout.retry-save"].exists, "A fixture persistence failure must not be hidden as successful completion.")
        captureScreen("phoenix-routine-completion")
        selectFocusedButton("routine.end", direction: .down)
        confirmSessionEnd(screenshot: "phoenix-routine-end-confirmation")
    }

    func testBodyweightSavedRoutineCompletesWithoutTrainerConnection() throws {
        launchIsolatedFixture(extra: ["--phoenix-tv-bodyweight-routine"])
        openHome("home.daily-routines", row: 2)
        selectFocusedButton("routine.tv-fixture-bodyweight", direction: .down)
        selectFocusedButton("routine.begin", direction: .down)
        selectFocusedButton("routine.start-set", direction: .up)
        XCTAssertTrue(app.staticTexts["bodyweight.entry"].waitForExistence(timeout: 40))
        XCTAssertFalse(app.staticTexts["routine.summary"].exists, "Bodyweight completion requires actual rep confirmation.")
        captureScreen("phoenix-bodyweight-rep-entry")
        selectFocusedButton("bodyweight.reps.up", direction: .right)
        selectFocusedButton("bodyweight.variant", direction: .down)
        selectFocusedButton("bodyweight.confirm", direction: .down)
        XCTAssertTrue(app.staticTexts["routine.summary"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["routine.summary"].label.contains("9 reps"))
        XCTAssertFalse(app.buttons["workout.retry-save"].exists)
        captureScreen("phoenix-bodyweight-saved-summary")
        selectFocusedButton("routine.continue", direction: .up)
        XCTAssertTrue(app.staticTexts["routine.complete"].waitForExistence(timeout: 15))
        selectFocusedButton("routine.end", direction: .down)
        confirmSessionEnd(screenshot: "phoenix-bodyweight-finish")
    }

    func testSelectedConnectionFailureIsVisibleAndNeverStartsTraining() throws {
        launchIsolatedFixture(extra: ["--phoenix-tv-connect-failure"])
        openHome("home.connect", row: 0)
        selectFocusedButton("connect.scan", direction: .up)
        selectFocusedButton("connect.device.PH:AN:TO:MS:BX:01", direction: .down)
        let failure = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "SIMULATOR FIXTURE: connection refused")).firstMatch
        XCTAssertTrue(failure.waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["connect.device.PH:AN:TO:MS:BX:01"].label.contains("Selected"))
        captureScreen("phoenix-connect-failure")
        XCUIRemote.shared.press(.menu)
        openHome("home.just-lift", row: 1)
        XCTAssertTrue(app.staticTexts["Connect to a trainer first"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["justlift.start"].exists)
    }

    private func launchIsolatedFixture(extra: [String] = []) {
        // Every launch gets a new in-memory SQLDelight DB and unique settings suite.
        // No app uninstall, simulator erase, or dependence on another test's saved data.
        app.launchArguments = ["--phoenix-tv-phantom", "--phoenix-tv-isolated-data"] + extra
        app.launch()
        XCTAssertTrue(app.buttons["eula.accept"].waitForExistence(timeout: 15))
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.buttons["home.connect"].waitForExistence(timeout: 45))
        XCTAssertTrue(app.staticTexts["SIMULATED TRAINER · NO REAL BLUETOOTH"].exists)
    }

    private func connectFixture() {
        openHome("home.connect", row: 0)
        selectFocusedButton("connect.scan", direction: .up)
        let id = "connect.device.PH:AN:TO:MS:BX:01"
        XCTAssertTrue(app.buttons[id].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons[id].label.contains("Selected"), "Discovery is not permission to connect.")
        selectFocusedButton(id, direction: .down)
        let connected = expectation(for: NSPredicate(format: "label CONTAINS %@", "Selected"), evaluatedWith: app.buttons[id])
        wait(for: [connected], timeout: 20)
        captureScreen("phoenix-explicitly-selected-connection")
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(app.buttons["home.connect"].waitForExistence(timeout: 10))
    }

    private func openHome(_ id: String, row: Int) {
        XCTAssertTrue(app.buttons["home.connect"].waitForExistence(timeout: 15))
        for _ in 0..<4 { XCUIRemote.shared.press(.up) }
        for _ in 0..<row { XCUIRemote.shared.press(.down) }
        XCTAssertTrue(isFocused(id), "Home focus must match \(id).\n" + app.debugDescription)
        XCUIRemote.shared.press(.select)
    }

    private func selectFocusedButton(_ id: String, direction: XCUIRemote.Button) {
        let button = app.buttons[id]
        XCTAssertTrue(button.waitForExistence(timeout: 15), "Missing control \(id)")
        let enabled = expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: button)
        wait(for: [enabled], timeout: 15)
        for _ in 0..<8 {
            if isFocused(id) { break }
            XCUIRemote.shared.press(direction)
        }
        XCTAssertTrue(isFocused(id), "Remote could not focus \(id).\n" + app.debugDescription)
        XCUIRemote.shared.press(.select)
    }

    private func confirmSessionEnd(screenshot: String) {
        XCTAssertTrue(app.alerts["End training session?"].waitForExistence(timeout: 20))
        captureScreen(screenshot)
        selectFocusedButton("session.confirm-end", direction: .right)
        XCTAssertTrue(app.buttons["home.connect"].waitForExistence(timeout: 15), "Ending the shared session must return Home.")
        captureScreen(screenshot + "-home")
    }

    private func isFocused(_ identifier: String) -> Bool {
        let marker = "identifier: '\(identifier)'"
        // UIKit alerts focus an outer Button while SwiftUI places the identifier
        // on a nested Button. Follow actual AX ancestry; never infer focus from position.
        var ancestors: [(indent: Int, line: String)] = []
        for line in app.debugDescription.components(separatedBy: .newlines) {
            let indent = line.prefix(while: { $0 == " " }).count
            while let last = ancestors.last, last.indent >= indent { ancestors.removeLast() }
            if line.contains(marker) {
                if line.contains("Focused") { return true }
                return ancestors.reversed().first(where: { $0.line.contains("Button,") })?
                    .line.contains("Focused") ?? false
            }
            ancestors.append((indent, line))
        }
        return false
    }

    private func captureScreen(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
