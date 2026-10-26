import SwiftUI
import PhoenixTvCore

private enum TvScreen: String, Hashable {
    case connect, justLift, routines, routineOverview, account
}

@MainActor
private final class TvAppModel: ObservableObject {
    let bridge = TvAppBridge()
    @Published var snapshot: TvAppSnapshot?

    init() {
        bridge.start()
        refresh()
        Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    func refresh() { snapshot = bridge.snapshot() }
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
            Text("Phoenix stops the active set before asking to leave. The app is not a certified emergency stop.")
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
                menuLink("Account & Sync", detail: snapshot?.cloudConfigured == true ? (snapshot?.authenticated == true ? (snapshot?.accountEmail ?? "Signed in") : "Email and password sign-in") : "Cloud unavailable in this build", screen: .account, id: "home.account")
            }
            .frame(maxWidth: 900)
            if let message = snapshot?.actionError, !message.isEmpty { errorBanner(message) }
        }
        .padding(64)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(background)
    }

    private var eulaGate: some View {
        VStack(alignment: .leading, spacing: 26) {
            title("PHOENIX", subtitle: "Experimental Apple TV beta")
            Text("This app is experimental and intended for supervised beta testing. It is not a certified emergency-stop system. Stay within reach of the trainer's physical controls and do not use this app as the sole means of stopping a workout.")
                .font(.title3).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Button("Accept and Continue") { model.bridge.acceptEula(); model.refresh() }
                .buttonStyle(.borderedProminent).tint(phoenixOrange)
                .accessibilityIdentifier("eula.accept")
        }
        .padding(72).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).background(background)
    }

    private var loading: some View {
        VStack(alignment: .leading, spacing: 20) {
            title("PHOENIX", subtitle: "Preparing shared training services…")
            ProgressView().tint(phoenixOrange)
            betaNotice
        }
        .padding(72).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).background(background)
    }

    private var startupFailure: some View {
        VStack(alignment: .leading, spacing: 24) {
            title("Phoenix could not start", subtitle: "No workout controls are available.")
            errorBanner("Startup requirement failed: \(snapshot?.startupErrorCode ?? "UNKNOWN")")
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
                    Text(title).font(.title3.weight(.semibold))
                    Text(detail).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.title3.weight(.bold)).foregroundStyle(phoenixOrange)
            }
            .padding(.horizontal, 26).padding(.vertical, 19)
            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.card)
        .accessibilityIdentifier(id)
    }

    private var betaNotice: some View {
        Label("EXPERIMENTAL BETA · NOT A CERTIFIED EMERGENCY-STOP SYSTEM", systemImage: "exclamationmark.triangle.fill")
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
}

private struct ConnectScreen: View {
    @ObservedObject var model: TvAppModel
    private var state: TvAppSnapshot? { model.snapshot }
    private var devices: [TvDeviceRow] { state?.devices ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            pageTitle("Connect", subtitle: "Select the trainer you intend to use. Phoenix never auto-connects to the first result.")
            HStack(spacing: 18) {
                Button("Scan for Trainers") { model.bridge.startScanning(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(phoenixOrange).accessibilityIdentifier("connect.scan")
                if state?.connectionState == "connected" {
                    Text(state?.connectionMessage ?? "Connected").font(.title3.weight(.semibold)).foregroundStyle(.green)
                    Button("Disconnect") { model.bridge.disconnectTrainer(); model.refresh() }.buttonStyle(.bordered)
                } else {
                    Text("Status: \(state?.connectionState ?? "disconnected")").font(.title3).foregroundStyle(.secondary)
                }
            }
            if devices.isEmpty {
                emptyState("No trainer selected", detail: "Start a scan, then choose a discovered trainer. Keep the trainer nearby and awake.")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(devices, id: \.address) { device in
                            Button {
                                model.bridge.connectSelectedTrainer(address: device.address)
                                model.refresh()
                            } label: {
                                HStack(spacing: 22) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(device.name).font(.title3.weight(.semibold))
                                        Text("Signal \(device.rssi) dBm · \(device.address)").font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 40)
                                    Text(state?.selectedTrainerAddress == device.address && state?.connectionState == "connected" ? "Selected" : "Connect")
                                        .font(.headline).foregroundStyle(phoenixOrange)
                                        .padding(.horizontal, 18).padding(.vertical, 12)
                                        .background(phoenixOrange.opacity(0.15), in: Capsule())
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(18)
                                .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                            }
                            .buttonStyle(.bordered)
                            .tint(phoenixOrange)
                            .accessibilityIdentifier("connect.device.\(device.address)")
                        }
                    }
                }
            }
            if let error = state?.connectionMessage, state?.connectionState == "error" { errorText(error) }
            if let error = state?.actionError { errorText(error) }
            Spacer()
        }
        .padding(60).background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
    }
}

private struct JustLiftScreen: View {
    @ObservedObject var model: TvAppModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedExerciseId = ""
    @FocusState private var focusedControl: String?
    private var state: TvAppSnapshot? { model.snapshot }
    private var exercises: [TvExerciseRow] { state?.exercises.filter { !$0.isBodyweight } ?? [] }
    private var active: Bool { ["active", "paused"].contains(state?.workoutState ?? "") }
    private var summary: Bool { ["set_summary", "resting", "completed"].contains(state?.workoutState ?? "") }

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            pageTitle("Just Lift", subtitle: "Shared Phoenix workout logic · explicit start required")
            if state?.saveFailure == true {
                errorText("This set could not be saved locally. Keep the session open and retry.")
                Button("Retry saving set") { model.bridge.retryWorkoutSave(); model.refresh() }
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
                emptyState("Connect to a trainer first", detail: "Use Connect to scan and explicitly select the machine.")
            } else {
                setup
            }
            if state?.teardownState == "recovery_required" {
                errorText("Machine stop recovery is required. Do not leave the session while the trainer may still be loaded.")
                Button("Retry safe stop recovery") { model.bridge.retrySafeStopRecovery(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red)
            }
            if let error = state?.actionError { errorText(error) }
            Spacer()
        }
        .padding(60).background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
        .onPlayPauseCommand { model.bridge.handlePlayPause(); model.refresh() }
        .onExitCommand {
            if state?.sessionOpen == true { model.bridge.handleBackDuringSession(); model.refresh() }
            else { dismiss() }
        }
        .onChange(of: state?.selectedExerciseId == selectedExerciseId && !selectedExerciseId.isEmpty) { _, ready in
            if ready { focusedControl = "start" }
        }
    }

    private var setup: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Choose exercise").font(.title2.weight(.semibold))
            if exercises.isEmpty {
                emptyState("Exercise library unavailable", detail: "Phoenix could not load exercises. No mock exercises are shown.")
            } else {
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 18) {
                        ForEach(exercises, id: \.id) { exercise in
                            Button {
                                selectedExerciseId = exercise.id
                                model.bridge.selectJustLiftExercise(id: exercise.id); model.refresh()
                            } label: {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(exercise.name).font(.headline)
                                    Text(exercise.isBodyweight ? "Bodyweight" : "Cable exercise").font(.caption).foregroundStyle(.secondary)
                                }
                                .padding(20).frame(width: 280, alignment: .leading)
                                .background(.white.opacity(selectedExerciseId == exercise.id ? 0.16 : 0.07), in: RoundedRectangle(cornerRadius: 14))
                            }
                            .buttonStyle(.card)
                            .accessibilityIdentifier("justlift.exercise.\(exercise.id)")
                        }
                    }
                }
                .focusSection()
            }
            Text("\(state?.exerciseName ?? "No exercise selected") · \(state?.weightPerCableKg ?? 0, specifier: "%.1f") kg per cable")
                .font(.title3.weight(.semibold))
            HStack(spacing: 18) {
                Button("− 2.5 kg / cable") { model.bridge.adjustJustLiftWeight(weightKg: max(0, (state?.weightPerCableKg ?? 0) - 2.5)); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.weight.down")
                Button("+ 2.5 kg / cable") { model.bridge.adjustJustLiftWeight(weightKg: (state?.weightPerCableKg ?? 0) + 2.5); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.weight.up")
                Button("Rest: \(state?.configuredRestSeconds ?? 0)s · change") { model.bridge.adjustJustLiftRest(seconds: state?.configuredRestSeconds == 0 ? 60 : 0); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("justlift.rest-setting")
            }
            Text("Apple TV uses manual starts. Your phone's autoplay preferences are unchanged; each TV set requires Select.")
                .font(.caption).foregroundStyle(.secondary)
            Button("Start Set") { model.bridge.startJustLift(); model.refresh() }
                .buttonStyle(.borderedProminent).tint(phoenixOrange)
                .focused($focusedControl, equals: "start")
                .disabled(selectedExerciseId.isEmpty || state?.selectedExerciseId != selectedExerciseId)
                .accessibilityIdentifier("justlift.start")
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
            Text("Phoenix is validating and preparing the set you requested. Play/Pause or Menu cancels preparation through the shared stop path.")
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
            errorText(state?.workoutMessage ?? "Phoenix could not start this set. No active controls are available.")
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
        emptyState("Bodyweight sets are unavailable on Apple TV", detail: "Select a cable exercise to train with the connected Vitruvian trainer.")
    }

    private var activeSet: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("ACTIVE SET").font(.caption.weight(.bold)).tracking(2).foregroundStyle(phoenixOrange)
            Text(state?.exerciseName ?? "Workout").font(.system(size: 34, weight: .bold, design: .rounded))
            HStack(alignment: .firstTextBaseline, spacing: 18) {
                Text("\(state?.workingReps ?? 0)").font(.system(size: 82, weight: .bold, design: .rounded))
                Text("REPS").font(.title2.weight(.bold)).foregroundStyle(.secondary)
            }
            Text("Target: \(state?.weightPerCableKg ?? 0, specifier: "%.1f") kg per cable · measured total \(state?.measuredTotalLoadKg?.floatValue ?? 0, specifier: "%.1f") kg")
                .font(.title3).foregroundStyle(.secondary)
            Button("Stop Set") { model.bridge.stopActiveSet(); model.refresh() }
                .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("justlift.stop")
            Text("Play/Pause also requests Phoenix's shared stop path. This is not an emergency stop.")
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
        }.padding(26).background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }
}

private struct RoutinesScreen: View {
    @ObservedObject var model: TvAppModel
    let openOverview: () -> Void
    private var routines: [TvRoutineRow] { model.snapshot?.routines ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            pageTitle("Daily Routines", subtitle: "Saved routines from Phoenix. Sync is explicit after sign-in and identity resolution.")
            if routines.isEmpty {
                emptyState("No saved routines", detail: "Sign in, resolve account identity if prompted, then choose Sync Now. Routines are never fetched by login alone.")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(routines, id: \.id) { routine in
                            Button {
                                model.bridge.openRoutineOverview(id: routine.id)
                                model.refresh()
                                openOverview()
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(routine.name).font(.title3.weight(.semibold))
                                        Text("\(routine.exerciseCount) exercises · \(routine.description_)").font(.subheadline).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").foregroundStyle(phoenixOrange)
                                }
                                .padding(22).background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                            }
                            .buttonStyle(.card).accessibilityIdentifier("routine.\(routine.id)")
                        }
                    }
                }
            }
            if let error = model.snapshot?.actionError { errorText(error) }
            Spacer()
        }.padding(60).background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
    }
}

private struct RoutineOverviewScreen: View {
    @ObservedObject var model: TvAppModel
    @Environment(\.dismiss) private var dismiss
    private var state: TvAppSnapshot? { model.snapshot }
    private var exercises: [TvRoutineExerciseRow] { state?.selectedRoutineExercises ?? [] }

    private var routineExecution: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("ROUTINE IN PROGRESS").font(.caption.weight(.bold)).tracking(2).foregroundStyle(phoenixOrange)
            switch state?.workoutState {
            case _ where state?.completionFailed == true:
                errorText(state?.workoutMessage ?? "Set not saved. Complete safe stop recovery before ending the session.")
            case _ where state?.saveFailure == true:
                Text("SET NOT SAVED").font(.title2.weight(.bold))
            case "bodyweight_entry":
                BodyweightEntryView(model: model)
            case "initializing", "countdown":
                Text("PREPARING SET").font(.title2.weight(.bold)).accessibilityIdentifier("routine.preparing")
                ProgressView()
                Button("Cancel Set") { model.bridge.stopActiveSet(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("routine.cancel-start")
            case "active", "paused":
                Text(state?.routineExerciseName ?? "Active set").font(.system(size: 34, weight: .bold, design: .rounded))
                if state?.currentSetIsBodyweight == true {
                    Text("Bodyweight · \(state?.exerciseSecondsRemaining?.intValue ?? 0)s remaining. Enter actual reps after the timer; no trainer load is applied.")
                        .font(.title3).accessibilityIdentifier("routine.bodyweight-active")
                } else {
                    Text("\(state?.workingReps ?? 0) reps · \(state?.routineReadyWeightKg ?? 0, specifier: "%.1f") kg per cable")
                        .font(.title2).foregroundStyle(.secondary)
                }
                Button("Stop Set") { model.bridge.stopActiveSet(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red).accessibilityIdentifier("routine.stop")
            case _ where state?.routineFlowState == "complete" || state?.workoutState == "routine_complete":
                Text("Routine complete").font(.system(size: 36, weight: .bold, design: .rounded))
                    .accessibilityIdentifier("routine.complete")
                Text("Training finished. Sync is available from Account; completion alone does not confirm every set was saved.")
                    .font(.title3).foregroundStyle(.secondary)
            case _ where state?.routineReadyToStart == true:
                Text("\(state?.routineExerciseName ?? "Next exercise") · Set \(state?.routineSetNumber ?? 1) of \(state?.routineSetCount ?? 0)")
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                Text(state?.currentSetIsBodyweight == true ? "\(state?.routineReadyReps ?? 0) planned reps · Bodyweight" : "\(state?.routineReadyReps ?? 0) reps · \(String(format: "%.1f", state?.routineReadyWeightKg ?? 0)) kg per cable")
                    .font(.title2).foregroundStyle(.secondary)
                if let rest = state?.manualRoutineRestSeconds {
                    Text("Manual rest · suggested \(rest.intValue)s. No automatic countdown or start; select Start Set when ready.")
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
                Text("Waiting for the shared workout transition…").font(.title3)
            }
            if state?.saveFailure == true {
                errorText("A set failed to save locally. Retry before leaving this session.")
                Button("Retry saving set") { model.bridge.retryWorkoutSave(); model.refresh() }
                    .accessibilityIdentifier("workout.retry-save")
            }
            if !["active", "paused", "initializing", "countdown"].contains(state?.workoutState ?? "") && state?.teardownState == "ready" {
                Button("End Routine Session") { model.bridge.endSession(); model.refresh() }
                    .buttonStyle(.bordered).accessibilityIdentifier("routine.end")
            }
            if state?.teardownState == "recovery_required" {
                errorText("The machine stop requires recovery. Do not leave while load may remain applied.")
                Button("Retry safe stop recovery") { model.bridge.retrySafeStopRecovery(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(.red)
            }
            if let error = state?.actionError { errorText(error) }
        }
        .padding(26).frame(maxWidth: 1000, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            pageTitle(state?.selectedRoutineName ?? "Routine", subtitle: state?.selectedRoutineDescription ?? "Saved routine overview")
            if state?.sessionOpen == true {
                routineExecution
            } else if exercises.isEmpty {
                emptyState("Routine details unavailable", detail: "The selected routine may have changed. Return to Daily Routines and select it again.")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(Array(exercises.enumerated()), id: \.offset) { index, exercise in
                            HStack(spacing: 20) {
                                Text(String(format: "%02d", index + 1)).font(.title2.weight(.bold)).foregroundStyle(phoenixOrange)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(exercise.name).font(.title3.weight(.semibold))
                                    Text(exercise.isBodyweight ? "\(exercise.setCount) sets · \(exercise.reps) planned reps · Bodyweight" : "\(exercise.setCount) sets · \(exercise.reps) reps · \(String(format: "%.1f", exercise.weightPerCableKg)) kg per cable")
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                            }.padding(16).background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
                Text("Apple TV requires Select for every set; phone preferences are unchanged. Bodyweight sets use Phoenix's timer and manual rep entry without trainer load.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Begin Routine") { model.bridge.beginSelectedRoutine(); model.refresh() }
                    .buttonStyle(.borderedProminent).tint(phoenixOrange).accessibilityIdentifier("routine.begin")
            }
            if let error = state?.actionError { errorText(error) }
            if state?.connectionState != "connected" && exercises.contains(where: { !$0.isBodyweight }) {
                errorText("Connect to a selected trainer before starting a cable set.")
            }
            Spacer()
        }.padding(60).background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
        .onPlayPauseCommand { model.bridge.handlePlayPause(); model.refresh() }
        .onExitCommand {
            if state?.sessionOpen == true { model.bridge.handleBackDuringSession(); model.refresh() }
            else { dismiss() }
        }
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
            Text("Uses Phoenix's saved exercise variants and manual-completion logic. No trainer load is applied.")
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
        VStack(alignment: .leading, spacing: 22) {
            pageTitle("Account & Sync", subtitle: "Email/password authentication uses Phoenix's existing account and secure token storage.")
            if state?.authenticated == true {
                Text("Signed in as \(state?.accountEmail ?? "account")").font(.title2.weight(.semibold))
                Text("Sync state: \(state?.syncState ?? "idle")").font(.title3).foregroundStyle(.secondary)
                if state?.accountMismatch == true {
                    errorText("Account mismatch: previous \(state?.previousAccount ?? "unknown") · new \(state?.newAccount ?? "unknown"). Resolve identity before syncing.")
                    Button("Keep existing data separate") { model.bridge.resolveAccountMismatch(uploadNeverSynced: false); model.refresh() }
                        .buttonStyle(.borderedProminent).tint(phoenixOrange)
                    Button("Upload never-synced data to this account") { model.bridge.resolveAccountMismatch(uploadNeverSynced: true); model.refresh() }
                        .buttonStyle(.bordered)
                } else {
                    Button("Sync Now") { model.bridge.syncNow(); model.refresh() }
                        .buttonStyle(.borderedProminent).tint(phoenixOrange).disabled(state?.cloudConfigured != true)
                        .accessibilityIdentifier("account.sync")
                    if state?.cloudConfigured != true {
                        errorText("Cloud Sync is unavailable because this build has no server configuration.")
                    }
                    Text("Login does not pull routines. Sync Now runs only after identity resolution and required local migration.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                Button("Sign Out") { model.bridge.signOut(); model.refresh() }.buttonStyle(.bordered)
            } else if state?.cloudConfigured == true {
                TextField("Email", text: $email).textContentType(.emailAddress).autocorrectionDisabled()
                    .textInputAutocapitalization(.never).frame(maxWidth: 650).accessibilityIdentifier("account.email")
                SecureField("Password", text: $password).textContentType(.password)
                    .frame(maxWidth: 650).accessibilityIdentifier("account.password")
                Button("Sign In") { model.bridge.login(email: email, password: password); password = ""; model.refresh() }
                    .buttonStyle(.borderedProminent).tint(phoenixOrange).disabled(email.isEmpty)
                    .accessibilityIdentifier("account.login")
                Text("No OAuth, Health, backup, CSV, or analytics controls are available in this experimental TV app.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                errorText("Cloud account service is unavailable in this build because server configuration is missing. Local workouts remain available.")
                    .accessibilityIdentifier("account.unavailable")
            }
            if let error = state?.syncError { errorText(error) }
            if let error = state?.actionError { errorText(error) }
            Spacer()
        }.padding(60).background(Color(red: 0.04, green: 0.06, blue: 0.09).ignoresSafeArea())
    }
}

private var phoenixOrange: Color { Color(red: 1.0, green: 0.39, blue: 0.12) }

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
