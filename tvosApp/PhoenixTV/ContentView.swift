import SwiftUI
import PhoenixTvCore

private enum TvScreen: String, Hashable {
    case connect, justLift, routines, routineOverview, account
}

@MainActor
private final class TvAppModel: ObservableObject {
    let bridge = TvAppBridge()
    @Published var snapshot: TvAppSnapshot?
    private var refreshTimer: Timer?

    init() {
        bridge.start()
        refresh()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    deinit { refreshTimer?.invalidate() }

    func refresh() {
        let next = bridge.snapshot()
        if snapshot?.isEqual(next) != true { snapshot = next }
    }
}

struct ContentView: View {
    @StateObject private var model = TvAppModel()
    @State private var path: [TvScreen] = []
    @Environment(\.scenePhase) private var scenePhase

    private var snapshot: TvAppSnapshot? { model.snapshot }
    private var needsExitConfirmation: Binding<Bool> {
        Binding(
            get: { snapshot?.exitConfirmationPending == true },
            set: { _ in } // Buttons resolve the shared exit request; SwiftUI dismisses before invoking actions.
        )
    }
    private var hasSafetyHazard: Binding<Bool> {
        Binding(
            get: { snapshot?.safetyHazardVisible == true },
            set: { _ in }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            fixtureWarning
            NavigationStack(path: $path) {
                Group {
                    if snapshot?.startupState == "failed" {
                        startupFailure
                    } else if snapshot?.eulaReady != true {
                        loading
                    } else if snapshot?.eulaAccepted != true {
                        eulaGate
                    } else if snapshot?.startupState != "ready" {
                        loading
                    } else {
                        home
                    }
                }
                .navigationDestination(for: TvScreen.self) { screen in
                    destination(screen)
                        // Intercept Menu at the destination before NavigationStack's
                        // default pop; an outer handler can otherwise pop twice.
                        .onExitCommand {
                            if snapshot?.sessionOpen == true {
                                model.bridge.handleBackDuringSession()
                                model.refresh()
                            } else if !path.isEmpty {
                                path.removeLast()
                            }
                        }
                }
            }
        }
        .preferredColorScheme(.dark)
        .onPlayPauseCommand {
            model.bridge.handlePlayPause()
            model.refresh()
        }
        .alert("End training session?", isPresented: needsExitConfirmation) {
            Button("Keep Training", role: .cancel) { model.bridge.cancelSessionExit(); model.refresh() }
                .accessibilityIdentifier("session.keep-training")
            Button("End Session", role: .destructive) { model.bridge.confirmSessionExit(); model.refresh() }
                .accessibilityIdentifier("session.confirm-end")
        } message: {
            Text("Phoenix stops any active load before leaving. Keep your trainer's physical controls within reach.")
        }
        .fullScreenCover(isPresented: hasSafetyHazard) {
            safetyHazardModal
        }
        .onAppear { if scenePhase == .active { model.bridge.onForeground() } }
        .onChange(of: snapshot?.sessionOpen) { wasOpen, isOpen in
            if wasOpen == true && isOpen == false { path.removeAll() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.bridge.onForeground()
            } else if phase == .inactive || phase == .background {
                model.bridge.onBackground()
            }
            model.refresh()
        }
    }

    @ViewBuilder
    private var fixtureWarning: some View {
        if snapshot?.simulatorFixtureMode == true {
            Label("SIMULATED TRAINER · NO REAL BLUETOOTH", systemImage: "exclamationmark.triangle.fill")
                .font(.caption.weight(.heavy)).tracking(1.1).foregroundStyle(.black)
                .padding(.vertical, 10).frame(maxWidth: .infinity)
                .background(Color.yellow)
                .accessibilityIdentifier("fixture.warning")
        }
    }

    private var home: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack(spacing: 18) {
                Image(systemName: "bolt.heart.fill")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundStyle(phoenixOrange)
                Text("PHOENIX")
                    .font(.system(size: 44, weight: .bold, design: .rounded))
                    .tracking(5)
                    .accessibilityIdentifier("home.brand")
            }
            Text("Strength training, built for the big screen.")
                .font(.title2).foregroundStyle(.secondary)
            betaNotice
            if snapshot?.connectionState == "connected" {
                statusPill("TRAINER CONNECTED", color: .green)
            } else {
                statusPill("TRAINER NOT CONNECTED", color: .secondary)
            }
            VStack(alignment: .leading, spacing: 14) {
                menuLink("Connect to Trainer", detail: "Scan and choose a trainer", screen: .connect, id: "home.connect")
                menuLink("Just Lift", detail: "Choose an exercise and configure a set", screen: .justLift, id: "home.just-lift")
                menuLink("Daily Routines", detail: "Run a saved routine", screen: .routines, id: "home.daily-routines")
                menuLink(
                    "Account & Sync",
                    detail: snapshot?.cloudConfigured == true
                        ? (snapshot?.authenticated == true ? (snapshot?.accountEmail ?? "Signed in") : "Sign in to sync workouts and routines")
                        : "Cloud sync not configured",
                    screen: .account,
                    id: "home.account"
                )
            }
            .frame(maxWidth: 900)
            if let message = displayError(actionError: snapshot?.actionError, excluding: []) { errorBanner(message) }
        }
        .padding(64)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(background)
    }

    private var eulaGate: some View {
        VStack(alignment: .leading, spacing: 26) {
            title("PHOENIX", subtitle: "Apple TV companion beta")
            Text("This Apple TV app is not a certified emergency stop. Stay within reach of your trainer's physical controls during every set.")
                .font(.title3).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Button("Accept and Continue") { model.bridge.acceptEula(); model.refresh() }
                .buttonStyle(.borderedProminent).tint(phoenixOrange)
                .accessibilityIdentifier("eula.accept")
        }
        .padding(72).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).background(background)
    }

    private var loading: some View {
        VStack(alignment: .leading, spacing: 20) {
            title("PHOENIX", subtitle: "Preparing Phoenix…")
            ProgressView().tint(phoenixOrange)
            betaNotice
        }
        .padding(72).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).background(background)
    }

    private var startupFailure: some View {
        VStack(alignment: .leading, spacing: 24) {
            title("Phoenix could not start", subtitle: "Unable to initialize local training data.")
            errorBanner("Startup error (\(snapshot?.startupErrorCode ?? "UNKNOWN")). Please try again.")
            Button("Retry startup") { model.bridge.start(); model.refresh() }.buttonStyle(.borderedProminent)
        }
        .padding(72).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).background(background)
    }

    @ViewBuilder
    private func destination(_ screen: TvScreen) -> some View {
        switch screen {
        case .connect: ConnectScreen(model: model)
        case .justLift: JustLiftScreen(model: model)
        case .routines: RoutinesScreen(model: model, openOverview: { path.append(.routineOverview) })
        case .routineOverview: RoutineOverviewScreen(model: model)
        case .account: AccountScreen(model: model)
        }
    }

    private func menuLink(_ title: String, detail: String, screen: TvScreen, id: String) -> some View {
        NavigationLink(value: screen) {
            HStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(.title3.weight(.semibold)).lineLimit(1)
                    Text(detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.title3.weight(.bold)).foregroundStyle(phoenixOrange)
            }
            .padding(.horizontal, 26).padding(.vertical, 19)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.card)
        .accessibilityIdentifier(id)
    }

    private var betaNotice: some View {
        Label("APPLE TV BETA · NOT AN EMERGENCY STOP", systemImage: "exclamationmark.triangle.fill")
            .font(.caption.weight(.bold)).tracking(1.1).foregroundStyle(phoenixOrange)
    }

    private func title(_ title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased()).font(.system(size: 38, weight: .bold, design: .rounded)).tracking(3)
            Text(subtitle).font(.title3).foregroundStyle(.secondary)
        }
    }

    private func statusPill(_ text: String, color: Color) -> some View {
        Text(text).font(.caption.weight(.bold)).tracking(1.2).foregroundStyle(color)
            .padding(.horizontal, 14).padding(.vertical, 9).background(color.opacity(0.12), in: Capsule())
    }

    private func errorBanner(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.circle.fill")
            .font(.callout.weight(.medium)).foregroundStyle(.orange)
            .padding(16).background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            .fixedSize(horizontal: false, vertical: true)
    }

    private var background: some View { Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea() }
    private var phoenixOrange: Color { Color(red: 1.0, green: 0.39, blue: 0.12) }

    private var safetyHazardTitle: String {
        switch snapshot?.safetyHazardCause {
        case "SET_END_UNCONFIRMED": return "Set End Not Confirmed"
        case "UNKNOWN": return "Trainer Load Unconfirmed"
        default: return "Connection Lost"
        }
    }

    private var safetyHazardBody: String {
        let loadUnknown = "Phoenix cannot confirm whether resistance is still engaged. Do not assume the machine is unloaded."
        switch snapshot?.safetyHazardCause {
        case "SET_END_UNCONFIRMED":
            return "Phoenix did not get confirmation that your last set on this trainer ended with a stop command. \(loadUnknown)"
        case "UNKNOWN":
            return "Phoenix has an unresolved safety record for this trainer. \(loadUnknown)"
        default:
            return "Bluetooth to the trainer was lost. \(loadUnknown)"
        }
    }

    private var safetyRecoveryStatus: String? {
        switch snapshot?.safetyHazardPhase {
        case "CONNECTING":
            return "Looking for the trainer and requesting a software stop…"
        case "RELEASE_REQUEST_SENT":
            return "Stop requested over Bluetooth and acknowledged by the trainer. That is not proof the cables are unloaded: check the handles, then confirm below."
        case "RELEASE_REQUEST_FAILED":
            if snapshot?.safetyHazardConnectResult == "WRONG_TRAINER" {
                return "A different trainer is connected, so no stop was requested. Connect to the original trainer, or check it yourself and confirm below."
            } else if snapshot?.safetyHazardWriteResult == "DISCONNECTED_DURING_WRITE" {
                return "Bluetooth dropped while the stop was being sent. Try again, or check the trainer yourself and confirm below."
            } else if snapshot?.safetyHazardWriteResult == "TRANSPORT_FAIL" {
                return "The stop request failed to send. Try again, or check the trainer yourself and confirm below."
            } else {
                return "Could not reach the trainer over Bluetooth, so no stop was requested. If it is powered off or asleep, check the handles yourself and confirm below."
            }
        default:
            return nil
        }
    }

    private var safetyHazardModal: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Label(safetyHazardTitle, systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                    .foregroundStyle(.orange)
                    .accessibilityIdentifier("safety.title")
                Text(safetyHazardBody)
                    .font(.title3)
                    .fixedSize(horizontal: false, vertical: true)
                Text("A successful reconnect can request a software stop, but that is not proof the cables are unloaded. Follow the trainer's safety guidance; powering the trainer off is a valid last resort.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("New sets stay blocked until you confirm. Check the trainer yourself: handles back at rest and cables not pulling beyond their normal light resting tension.")
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
                if let status = safetyRecoveryStatus {
                    Text(status)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(phoenixOrange)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("safety.status")
                }
                if snapshot?.safetyRecoveryInProgress == true {
                    ProgressView().tint(phoenixOrange)
                }
                HStack(spacing: 20) {
                    Button("Reconnect & Request Stop") {
                        model.bridge.requestMachineSafetyRecovery()
                        model.refresh()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(phoenixOrange)
                    .disabled(snapshot?.safetyRecoveryInProgress == true)
                    .accessibilityIdentifier("safety.reconnect")

                    Button("Confirm Handles Are At Rest") {
                        model.bridge.acknowledgeMachineSafetyUnloaded()
                        model.refresh()
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("safety.acknowledge")

                    Button("Remind Me Later") {
                        model.bridge.dismissMachineSafetyWarning()
                        model.refresh()
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("safety.dismiss")
                }
            }
            .padding(72)
            .frame(maxWidth: 1100, alignment: .leading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(background)
        .onExitCommand {
            model.bridge.dismissMachineSafetyWarning()
            model.refresh()
        }
    }
}

private struct ConnectScreen: View {
    @ObservedObject var model: TvAppModel
    private var state: TvAppSnapshot? { model.snapshot }
    private var devices: [TvDeviceRow] { state?.devices ?? [] }
    private var shownConnectionError: String? {
        guard state?.connectionState == "error",
              let message = state?.connectionMessage,
              !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return message
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                pageTitle("Connect", subtitle: "Scan and select your Phoenix trainer to connect.")
                HStack(spacing: 18) {
                    Button("Scan for Trainers") { model.bridge.startScanning(); model.refresh() }
                        .buttonStyle(.borderedProminent).tint(phoenixOrange).accessibilityIdentifier("connect.scan")
                    if state?.connectionState == "connected" {
                        Text(state?.connectionMessage ?? "Connected").font(.title3.weight(.semibold)).foregroundStyle(.green)
                        Button("Disconnect") { model.bridge.disconnectTrainer(); model.refresh() }.buttonStyle(.bordered)
                    } else {
                        Text("Status: \(displayConnectionStatus(state?.connectionState))").font(.title3).foregroundStyle(.secondary)
                    }
                }
                if devices.isEmpty {
                    emptyState("No trainer selected", detail: "Select Scan for Trainers with your trainer powered on and nearby.")
                } else {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(devices, id: \.address) { device in
                            Button {
                                model.bridge.connectSelectedTrainer(address: device.address)
                                model.refresh()
                            } label: {
                                HStack(spacing: 22) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(device.name)
                                            .font(.title3.weight(.semibold))
                                            .lineLimit(2)
                                            .multilineTextAlignment(.leading)
                                        Text("Signal \(device.rssi) dBm · \(device.address)")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 40)
                                    Text(state?.selectedTrainerAddress == device.address && state?.connectionState == "connected" ? "Selected" : "Connect")
                                        .font(.headline).foregroundStyle(phoenixOrange)
                                        .padding(.horizontal, 18).padding(.vertical, 12)
                                        .background(phoenixOrange.opacity(0.15), in: Capsule())
                                }
                                .frame(maxWidth: .infinity, minHeight: 88, alignment: .leading)
                                .padding(.horizontal, 18)
                            }
                            .buttonStyle(.card)
                            .accessibilityIdentifier("connect.device.\(device.address)")
                        }
                    }
                }
                if let error = shownConnectionError { errorText(error) }
                if let error = displayError(actionError: state?.actionError, excluding: [shownConnectionError]) {
                    errorText(error)
                }
            }
            .padding(60)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
    }
}

private struct JustLiftScreen: View {
    @ObservedObject var model: TvAppModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedExerciseId = ""
    @State private var selectedMuscleGroup = "All"
    @FocusState private var focusedControl: String?
    private var state: TvAppSnapshot? { model.snapshot }
    private var exercises: [TvExerciseRow] { state?.exercises.filter { !$0.isBodyweight } ?? [] }
    private var active: Bool { ["active", "paused"].contains(state?.workoutState ?? "") }
    private var summary: Bool { ["set_summary", "resting", "completed"].contains(state?.workoutState ?? "") }
    private var muscleGroups: [String] {
        let groups = Set(
            exercises
                .map { $0.muscleGroup.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        ).sorted()
        return ["All"] + groups
    }
    private var activeMuscleGroup: String {
        muscleGroups.contains(selectedMuscleGroup) ? selectedMuscleGroup : "All"
    }
    private var filteredExercises: [TvExerciseRow] {
        guard activeMuscleGroup != "All" else { return exercises }
        return exercises.filter {
            $0.muscleGroup.trimmingCharacters(in: .whitespacesAndNewlines) == activeMuscleGroup
        }
    }
    private var defaultExerciseFocus: String? {
        guard state?.connectionState == "connected",
              state?.sessionOpen != true,
              !active,
              !summary,
              state?.workoutState != "initializing",
              state?.workoutState != "countdown",
              state?.workoutState != "error",
              state?.workoutState != "bodyweight_entry",
              state?.completionFailed != true,
              state?.saveFailure != true,
              let first = filteredExercises.first else {
            return nil
        }
        return "exercise.\(first.id)"
    }
    private var shownWorkoutError: String? {
        guard state?.completionFailed == true || state?.workoutState == "error" else { return nil }
        return state?.workoutMessage
    }
    private var weightStepLabel: String {
        "\(formatWeight(state?.weightIncrementKg ?? 2.5, unit: state?.weightUnit)) / cable"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                pageTitle("Just Lift", subtitle: "Choose an exercise, set your load, and press Start Set when ready.")
                if state?.saveFailure == true {
                    errorText("This set could not be saved locally. Keep the session open and retry.")
                    Button("Retry saving set") { model.bridge.retryWorkoutSave(); model.refresh() }
                        .buttonStyle(.borderedProminent).tint(phoenixOrange)
                        .accessibilityIdentifier("workout.retry-save")
                } else if state?.completionFailed == true {
                    workoutError
                } else if active {
                    activeSet
                } else if summary {
                    setSummary
                } else if state?.workoutState == "initializing" || state?.workoutState == "countdown" {
                    preparingSet
                } else if state?.workoutState == "error" {
                    workoutError
                } else if state?.workoutState == "bodyweight_entry" {
                    bodyweightUnavailable
                } else if state?.connectionState != "connected" {
                    emptyState("Connect to a trainer first", detail: "Open Connect on the Home screen to select your trainer.")
                } else {
                    setup
                }
                if state?.teardownState == "recovery_required" {
                    errorText("Trainer stop did not confirm. Retry recovery before ending your session.")
                    Button("Retry safe stop recovery") { model.bridge.retrySafeStopRecovery(); model.refresh() }
                        .buttonStyle(.borderedProminent).tint(.red)
                }
                if let error = displayError(actionError: state?.actionError, excluding: [shownWorkoutError]) {
                    errorText(error)
                }
            }
            .padding(60)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
        .defaultFocus($focusedControl, defaultExerciseFocus)
        .onPlayPauseCommand { model.bridge.handlePlayPause(); model.refresh() }
        .onExitCommand {
            if state?.sessionOpen == true { model.bridge.handleBackDuringSession(); model.refresh() }
            else { dismiss() }
        }
        .onChange(of: state?.selectedExerciseId == selectedExerciseId && !selectedExerciseId.isEmpty) { _, ready in
            if ready { focusedControl = "start" }
        }
    }

    @ViewBuilder
    private var setup: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Choose exercise").font(.title2.weight(.semibold))
            if exercises.isEmpty {
                emptyState("Exercise library unavailable", detail: "Phoenix could not load the exercise library. Try restarting the app.")
            } else {
                exerciseStrip
                muscleFilterStrip
            }
            Text("\(state?.exerciseName ?? "No exercise selected") · \(formatWeightPerCable(state?.weightPerCableKg ?? 0, unit: state?.weightUnit))")
                .font(.title3.weight(.semibold))
            HStack(spacing: 18) {
                Button("− \(weightStepLabel)") { model.bridge.stepJustLiftWeight(deltaSteps: -1); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.weight.down")
                Button("+ \(weightStepLabel)") { model.bridge.stepJustLiftWeight(deltaSteps: 1); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.weight.up")
                Button("Rest: \(state?.configuredRestSeconds ?? 0)s · Change") { model.bridge.cycleJustLiftRestPreset(); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.rest-setting")
                Button("Mode: \(state?.programModeName ?? "Old School")") { model.bridge.cycleJustLiftMode(); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.mode")
            }
            Text("Press Start Set whenever you are ready. Auto-start is disabled on Apple TV.")
                .font(.caption).foregroundStyle(.secondary)
            Button("Start Set") { model.bridge.startJustLift(); model.refresh() }
                .buttonStyle(.borderedProminent).tint(phoenixOrange)
                .focused($focusedControl, equals: "start")
                .disabled(selectedExerciseId.isEmpty || state?.selectedExerciseId != selectedExerciseId)
                .accessibilityIdentifier("justlift.start")
        }
        .focusSection()
    }

    private var exerciseStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 18) {
                ForEach(filteredExercises, id: \.id) { exercise in
                    exerciseCard(exercise)
                }
            }
        }
        .focusSection()
    }

    private func exerciseCard(_ exercise: TvExerciseRow) -> some View {
        let isSelected = selectedExerciseId == exercise.id
        return Button {
            selectedExerciseId = exercise.id
            model.bridge.selectJustLiftExercise(id: exercise.id)
            model.refresh()
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                Text(exercise.name)
                    .font(.headline)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                HStack {
                    Text(exercise.muscleGroup.isEmpty ? "Cable exercise" : exercise.muscleGroup)
                        .font(.caption)
                        .foregroundStyle(isSelected ? phoenixOrange : .secondary)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.caption)
                            .foregroundStyle(phoenixOrange)
                    }
                }
            }
            .padding(20)
            .frame(width: 280, height: 120, alignment: .topLeading)
        }
        .buttonStyle(.card)
        .focused($focusedControl, equals: "exercise.\(exercise.id)")
        .accessibilityIdentifier("justlift.exercise.\(exercise.id)")
    }

    private var muscleFilterStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(muscleGroups, id: \.self) { group in
                    Button(group) {
                        selectedMuscleGroup = group
                    }
                    .buttonStyle(.bordered)
                    .tint(activeMuscleGroup == group ? phoenixOrange : .secondary)
                    .focused($focusedControl, equals: "muscle.\(group)")
                    .accessibilityIdentifier("justlift.muscle.\(group)")
                }
            }
            .padding(.vertical, 4)
        }
        .focusSection()
    }

    private var preparingSet: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(state?.workoutState == "countdown" ? "GET READY" : "PREPARING TRAINER")
                .font(.caption.weight(.bold)).tracking(2).foregroundStyle(phoenixOrange)
                .accessibilityIdentifier("justlift.preparing")
            Text(state?.exerciseName ?? "Just Lift")
                .font(.system(size: 34, weight: .bold, design: .rounded))
            ProgressView().tint(phoenixOrange)
            Text("Sending workout settings to your trainer. Press Play/Pause or Menu to cancel.")
                .font(.title3).foregroundStyle(.secondary)
            Button("Cancel Set") { model.bridge.stopActiveSet(); model.refresh() }
                .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("justlift.cancel-start")
        }
        .padding(26).frame(maxWidth: 900, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }

    private var workoutError: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(state?.completionFailed == true ? "SET NOT SAVED" : "SET COULD NOT START").font(.caption.weight(.bold)).tracking(2).foregroundStyle(.orange)
            errorText(state?.workoutMessage ?? "Phoenix could not start this set. Check your trainer connection and try again.")
            if state?.teardownState == "recovery_required" {
                Button("Retry safe stop recovery") { model.bridge.retrySafeStopRecovery(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red)
            }
            if state?.sessionOpen == true {
                Button("End Session") { model.bridge.endSession(); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.error-end")
            }
        }
        .padding(26).frame(maxWidth: 900, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }

    private var bodyweightUnavailable: some View {
        emptyState("Bodyweight sets are unavailable on Apple TV", detail: "Select a cable exercise to train with your connected Phoenix trainer.")
    }

    private var activeSet: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("ACTIVE SET").font(.caption.weight(.bold)).tracking(2).foregroundStyle(phoenixOrange)
            Text(state?.exerciseName ?? "Workout").font(.system(size: 34, weight: .bold, design: .rounded))
            HStack(alignment: .firstTextBaseline, spacing: 18) {
                Text("\(state?.workingReps ?? 0)").font(.system(size: 82, weight: .bold, design: .rounded))
                Text("REPS").font(.title2.weight(.bold)).foregroundStyle(.secondary)
            }
            Text("Target: \(formatWeightPerCable(state?.weightPerCableKg ?? 0, unit: state?.weightUnit)) · measured total \(formatWeight(state?.measuredTotalLoadKg?.floatValue ?? 0, unit: state?.weightUnit))")
                .font(.title3).foregroundStyle(.secondary)
            Button("Stop Set") { model.bridge.stopActiveSet(); model.refresh() }
                .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("justlift.stop")
            Text("Press Play/Pause or Stop Set to unload cables. Stay near your trainer's controls.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(26).frame(maxWidth: 900, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }

    private var setSummary: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(state?.workoutState == "resting" ? "REST" : "SET COMPLETE")
                .font(.caption.weight(.bold)).tracking(2).foregroundStyle(phoenixOrange)
            Text("\(state?.exerciseName ?? "Exercise") · \(state?.workingReps ?? 0) reps")
                .font(.system(size: 32, weight: .bold, design: .rounded))
            if state?.workoutState == "resting" { Text("\(state?.restSeconds ?? 0)s remaining").font(.title2) }
            HStack(spacing: 18) {
                Button(state?.workoutState == "resting" ? "Skip Rest & Start Next" : "Start Next Set") {
                    model.bridge.startNextJustLiftSet(); model.refresh()
                }.buttonStyle(.borderedProminent).tint(phoenixOrange).accessibilityIdentifier("justlift.next")
                Button("End Session") { model.bridge.endSession(); model.refresh() }.buttonStyle(.bordered)
                    .accessibilityIdentifier("justlift.end")
            }
        }
        .padding(26).frame(maxWidth: 900, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }
}

private struct RoutinesScreen: View {
    @ObservedObject var model: TvAppModel
    let openOverview: () -> Void
    private var routines: [TvRoutineRow] { model.snapshot?.routines ?? [] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                pageTitle("Daily Routines", subtitle: "Select a saved routine to preview and start.")
                if routines.isEmpty {
                    emptyState("No saved routines", detail: "Create routines on your phone, then sign in and tap Sync Now under Account & Sync.")
                } else {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(routines, id: \.id) { routine in
                            Button {
                                model.bridge.openRoutineOverview(id: routine.id)
                                model.refresh()
                                openOverview()
                            } label: {
                                HStack(spacing: 20) {
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(routine.name)
                                            .font(.title3.weight(.semibold))
                                            .lineLimit(2)
                                            .multilineTextAlignment(.leading)
                                        Text("\(routine.exerciseCount) exercises · \(routine.description_)")
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(2)
                                    }
                                    Spacer(minLength: 16)
                                    Image(systemName: "chevron.right").foregroundStyle(phoenixOrange)
                                }
                                .padding(.horizontal, 22)
                                .frame(maxWidth: .infinity, minHeight: 104, alignment: .leading)
                            }
                            .buttonStyle(.card)
                            .accessibilityIdentifier("routine.\(routine.id)")
                        }
                    }
                }
                if let error = displayError(actionError: model.snapshot?.actionError, excluding: []) {
                    errorText(error)
                }
            }
            .padding(60)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
    }
}

private struct RoutineOverviewScreen: View {
    @ObservedObject var model: TvAppModel
    @Environment(\.dismiss) private var dismiss
    private var state: TvAppSnapshot? { model.snapshot }
    private var exercises: [TvRoutineExerciseRow] { state?.selectedRoutineExercises ?? [] }
    private var shownWorkoutError: String? {
        guard state?.completionFailed == true || state?.workoutState == "error" else { return nil }
        return state?.workoutMessage
    }

    private var routineExecution: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("ROUTINE IN PROGRESS").font(.caption.weight(.bold)).tracking(2).foregroundStyle(phoenixOrange)
            switch state?.workoutState {
            case _ where state?.completionFailed == true:
                errorText(state?.workoutMessage ?? "Set could not be saved. Retry recovery before ending this session.")
            case _ where state?.saveFailure == true:
                Text("SET NOT SAVED").font(.title2.weight(.bold))
            case "bodyweight_entry":
                BodyweightEntryView(model: model)
            case "initializing", "countdown":
                Text("PREPARING SET").font(.title2.weight(.bold)).accessibilityIdentifier("routine.preparing")
                ProgressView().tint(phoenixOrange)
                Button("Cancel Set") { model.bridge.stopActiveSet(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("routine.cancel-start")
            case "active", "paused":
                Text(state?.routineExerciseName ?? "Active set").font(.system(size: 34, weight: .bold, design: .rounded))
                if state?.currentSetIsBodyweight == true {
                    Text("Bodyweight timer · \(state?.exerciseSecondsRemaining?.intValue ?? 0)s remaining. Log your reps once finished.")
                        .font(.title3).accessibilityIdentifier("routine.bodyweight-active")
                } else {
                    Text("\(state?.workingReps ?? 0) reps · \(formatWeightPerCable(state?.routineReadyWeightKg ?? 0, unit: state?.weightUnit))")
                        .font(.title2).foregroundStyle(.secondary)
                }
                Button("Stop Set") { model.bridge.stopActiveSet(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("routine.stop")
            case _ where state?.routineFlowState == "complete" || state?.workoutState == "routine_complete":
                Text("Routine complete").font(.system(size: 36, weight: .bold, design: .rounded))
                    .accessibilityIdentifier("routine.complete")
                Text("Workout complete. Visit Account & Sync to upload your session.")
                    .font(.title3).foregroundStyle(.secondary)
            case _ where state?.routineReadyToStart == true:
                Text("\(state?.routineExerciseName ?? "Next exercise") · Set \(state?.routineSetNumber ?? 1) of \(state?.routineSetCount ?? 0)")
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                Text(
                    state?.currentSetIsBodyweight == true
                        ? "\(state?.routineReadyReps ?? 0) planned reps · Bodyweight"
                        : "\(state?.routineReadyReps ?? 0) reps · \(formatWeightPerCable(state?.routineReadyWeightKg ?? 0, unit: state?.weightUnit))"
                )
                .font(.title2).foregroundStyle(.secondary)
                if let rest = state?.manualRoutineRestSeconds {
                    Text("Manual rest · Suggested \(rest.intValue)s. Press Start Set when you are ready.")
                        .font(.title3).foregroundStyle(.secondary).accessibilityIdentifier("routine.rest")
                }
                Button("Start Set") { model.bridge.startRoutineSet(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(phoenixOrange).accessibilityIdentifier("routine.start-set")
                    .disabled((state?.currentSetIsBodyweight != true && state?.connectionState != "connected") || state?.teardownState != "ready")
            case "resting":
                Text("Rest · \(state?.restSeconds ?? 0)s remaining").font(.title2.weight(.semibold))
                    .accessibilityIdentifier("routine.rest")
                Button("Continue Routine") { model.bridge.continueRoutine(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(phoenixOrange).accessibilityIdentifier("routine.continue")
            case "set_summary":
                Text("Set complete · \(state?.workingReps ?? 0) reps").font(.title2.weight(.semibold))
                    .accessibilityIdentifier("routine.summary")
                Button("Continue Routine") { model.bridge.continueRoutine(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(phoenixOrange).accessibilityIdentifier("routine.continue")
            case "error":
                errorText(state?.workoutMessage ?? "Phoenix reported a workout error.")
                Button("Retry safe stop recovery") { model.bridge.retrySafeStopRecovery(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red)
            default:
                Text("Preparing next step…").font(.title3)
            }
            if state?.saveFailure == true {
                errorText("This set could not be saved. Retry saving before ending your session.")
                Button("Retry saving set") { model.bridge.retryWorkoutSave(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(phoenixOrange)
                    .accessibilityIdentifier("workout.retry-save")
            }
            if !["active", "paused", "initializing", "countdown"].contains(state?.workoutState ?? "") && state?.teardownState == "ready" {
                Button("End Routine Session") { model.bridge.endSession(); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("routine.end")
            }
            if state?.teardownState == "recovery_required" {
                errorText("Trainer stop did not confirm. Retry recovery before ending your session.")
                Button("Retry safe stop recovery") { model.bridge.retrySafeStopRecovery(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red)
            }
            if let error = displayError(actionError: state?.actionError, excluding: [shownWorkoutError]) {
                errorText(error)
            }
        }
        .padding(26).frame(maxWidth: 1000, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                pageTitle(state?.selectedRoutineName ?? "Routine", subtitle: state?.selectedRoutineDescription ?? "Saved routine overview")
                if state?.sessionOpen == true {
                    routineExecution
                } else if exercises.isEmpty {
                    emptyState("Routine details unavailable", detail: "This routine is unavailable. Go back to Daily Routines and select it again.")
                    if let error = displayError(actionError: state?.actionError, excluding: []) {
                        errorText(error)
                    }
                } else {
                    routinePreview
                }
            }
            .padding(60)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
        .onPlayPauseCommand { model.bridge.handlePlayPause(); model.refresh() }
        .onExitCommand {
            if state?.sessionOpen == true { model.bridge.handleBackDuringSession(); model.refresh() }
            else { dismiss() }
        }
    }

    private var routinePreview: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 12) {
                Button("Begin Routine") { model.bridge.beginSelectedRoutine(); model.refresh() }
                    .buttonStyle(.borderedProminent)
                    .tint(phoenixOrange)
                    .accessibilityIdentifier("routine.begin")
                Text("Press Start Set before each set. Bodyweight exercises use an on-screen timer and manual rep entry.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if state?.connectionState != "connected" && exercises.contains(where: { !$0.isBodyweight }) {
                errorText("Connect to a selected trainer before starting a cable set.")
            }
            if let error = displayError(actionError: state?.actionError, excluding: []) {
                errorText(error)
            }
            Text("Exercises (\(exercises.count))")
                .font(.title3.weight(.semibold))
                .padding(.top, 8)
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(Array(exercises.enumerated()), id: \.offset) { index, exercise in
                    routinePreviewRow(index: index, exercise: exercise)
                }
            }
        }
    }

    private func routinePreviewRow(index: Int, exercise: TvRoutineExerciseRow) -> some View {
        let subtitle: String = exercise.isBodyweight
            ? "\(exercise.setCount) sets · \(exercise.reps) planned reps · Bodyweight"
            : "\(exercise.setCount) sets · \(exercise.reps) reps · \(formatWeightPerCable(exercise.weightPerCableKg, unit: state?.weightUnit))"
        return Button {} label: {
            HStack(spacing: 20) {
                Text(String(format: "%02d", index + 1))
                    .font(.title2.weight(.bold))
                    .foregroundStyle(phoenixOrange)
                    .frame(width: 44, alignment: .leading)
                VStack(alignment: .leading, spacing: 4) {
                    Text(exercise.name)
                        .font(.title3.weight(.semibold))
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 92, alignment: .leading)
        }
        .buttonStyle(.card)
    }
}

private struct BodyweightEntryView: View {
    @ObservedObject var model: TvAppModel
    @State private var reps: Int32 = 0
    @State private var variantIndex: Int32 = 0
    private var variants: [String] { model.snapshot?.bodyweightVariants ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Enter actual bodyweight reps").font(.title2.weight(.bold))
                .accessibilityIdentifier("bodyweight.entry")
            Text("\(reps) reps").font(.title)
            HStack(spacing: 20) {
                Button("− Rep") { reps = max(0, reps - 1) }
                    .accessibilityIdentifier("bodyweight.reps.down")
                Button("+ Rep") { if reps < Int32.max { reps += 1 } }
                    .accessibilityIdentifier("bodyweight.reps.up")
            }.buttonStyle(.bordered)
            if !variants.isEmpty {
                Button("Variant: \(variants.indices.contains(Int(variantIndex)) ? variants[Int(variantIndex)] : variants[0])") {
                    variantIndex = Int32((Int(variantIndex) + 1) % variants.count)
                }.buttonStyle(.bordered).accessibilityIdentifier("bodyweight.variant")
            }
            Button("Save Bodyweight Set") {
                model.bridge.confirmBodyweightReps(reps: reps, variantIndex: variantIndex)
                model.refresh()
            }.buttonStyle(.borderedProminent).tint(phoenixOrange)
                .disabled(variants.isEmpty).accessibilityIdentifier("bodyweight.confirm")
            Text("Choose your exercise variation and completed reps to log this bodyweight set.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .onAppear {
            reps = model.snapshot?.bodyweightPlannedReps ?? 0
            variantIndex = model.snapshot?.bodyweightSelectedVariant ?? 0
        }
    }
}

private struct AccountScreen: View {
    @ObservedObject var model: TvAppModel
    @State private var email = ""
    @State private var password = ""
    private var state: TvAppSnapshot? { model.snapshot }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                pageTitle("Account & Sync", subtitle: "Sign in with your Phoenix account to sync routines and workouts.")
                if state?.authenticated == true {
                    Text("Signed in as \(state?.accountEmail ?? "account")").font(.title2.weight(.semibold))
                    Text("Sync status: \(displaySyncState(state?.syncState))").font(.title3).foregroundStyle(.secondary)
                    if state?.accountMismatch == true {
                        errorText("This trainer data belongs to \(state?.previousAccount ?? "another account"), and you signed in as \(state?.newAccount ?? "this account"). Choose how to handle unsynced workouts:")
                        Button("Keep existing data separate") { model.bridge.resolveAccountMismatch(uploadNeverSynced: false); model.refresh() }
                            .buttonStyle(.borderedProminent).tint(phoenixOrange)
                        Button("Upload never-synced data to this account") { model.bridge.resolveAccountMismatch(uploadNeverSynced: true); model.refresh() }
                            .buttonStyle(.bordered)
                    } else {
                        Button("Sync Now") { model.bridge.syncNow(); model.refresh() }
                            .buttonStyle(.borderedProminent).tint(phoenixOrange).disabled(state?.cloudConfigured != true)
                            .accessibilityIdentifier("account.sync")
                        if state?.cloudConfigured != true {
                            errorText("Cloud sync is not configured in this build.")
                        }
                        Text("Select Sync Now to upload completed workouts and fetch your latest routines.")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    Button("Sign Out") { model.bridge.signOut(); model.refresh() }.buttonStyle(.bordered)
                } else if state?.cloudConfigured == true {
                    TextField("Email", text: $email).textContentType(.emailAddress).autocorrectionDisabled()
                        .textInputAutocapitalization(.never).frame(maxWidth: 650).accessibilityIdentifier("account.email")
                    SecureField("Password", text: $password).textContentType(.password)
                        .frame(maxWidth: 650).accessibilityIdentifier("account.password")
                    Button("Sign In") { model.bridge.login(email: email, password: password); password = ""; model.refresh() }
                        .buttonStyle(.borderedProminent).tint(phoenixOrange).disabled(email.isEmpty || password.isEmpty)
                        .accessibilityIdentifier("account.login")
                    Text("Sign in with your Phoenix email and password. Workout history and routines sync from your phone.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    errorText("Cloud sync is not configured in this build. You can still train locally without signing in.")
                        .accessibilityIdentifier("account.unavailable")
                }
                if let error = state?.syncError, !error.isEmpty { errorText(error) }
                if let error = displayError(actionError: state?.actionError, excluding: [state?.syncError]) {
                    errorText(error)
                }
            }
            .padding(60)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
    }
}

private var phoenixOrange: Color { Color(red: 1.0, green: 0.39, blue: 0.12) }

private func isPoundsUnit(_ weightUnit: String?) -> Bool {
    weightUnit?.uppercased() == "LB"
}

private func formatWeightValue(_ kg: Float, unit: String?) -> String {
    let value = isPoundsUnit(unit) ? (kg * 2.2046226) : kg
    return String(format: "%.1f", value)
}

private func weightUnitLabel(_ unit: String?) -> String {
    isPoundsUnit(unit) ? "lbs" : "kg"
}

private func formatWeight(_ kg: Float, unit: String?) -> String {
    "\(formatWeightValue(kg, unit: unit)) \(weightUnitLabel(unit))"
}

private func formatWeightPerCable(_ kg: Float, unit: String?) -> String {
    "\(formatWeight(kg, unit: unit)) per cable"
}

private func displayConnectionStatus(_ raw: String?) -> String {
    switch raw {
    case "connected": return "Connected"
    case "connecting": return "Connecting…"
    case "scanning": return "Scanning…"
    case "error": return "Connection error"
    default: return "Not connected"
    }
}

private func displaySyncState(_ raw: String?) -> String {
    switch raw {
    case "syncing": return "Syncing…"
    case "success": return "Up to date"
    case "partial_success": return "Synced with warnings"
    case "error": return "Sync failed"
    case "not_authenticated": return "Sign in required"
    case "not_premium": return "Subscription required for cloud sync"
    case "account_mismatch": return "Account switch confirmation needed"
    case "ownership_conflict": return "Account ownership conflict"
    default: return "Ready"
    }
}

private func displayError(actionError: String?, excluding shown: [String?]) -> String? {
    guard let trimmed = actionError?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return nil
    }
    let excluded = Set(shown.compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })
    return excluded.contains(trimmed) ? nil : actionError
}

private func pageTitle(_ title: String, subtitle: String) -> some View {
    VStack(alignment: .leading, spacing: 8) {
        Text(title.uppercased()).font(.system(size: 38, weight: .bold, design: .rounded)).tracking(3)
        Text(subtitle).font(.title3).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
    }
}

private func emptyState(_ title: String, detail: String) -> some View {
    VStack(alignment: .leading, spacing: 9) {
        Text(title).font(.title2.weight(.semibold))
        Text(detail).font(.body).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
    }.padding(22).frame(maxWidth: 900, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
}

private func errorText(_ text: String) -> some View {
    Label(text, systemImage: "exclamationmark.triangle.fill")
        .font(.callout.weight(.medium)).foregroundStyle(.orange)
        .fixedSize(horizontal: false, vertical: true)
}
