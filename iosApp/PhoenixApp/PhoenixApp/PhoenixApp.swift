import SwiftUI
import shared
import os.log

@main
struct PhoenixAppEntry: App {

    private let logger = Logger(subsystem: "com.devil.phoenixproject", category: "AppInit")
    /// False when `doInitKoin()` throws. ContentView must not mount in that case:
    /// Compose resolves Koin dependencies and crashes if the graph never started.
    private let koinStarted: Bool

    init() {
        // Initialize Koin for dependency injection.
        // The iOS entrypoint lives in shared/iosMain (KoinInitIos.kt) so the Kotlin/Native
        // export class is KoinInitIosKt, not KoinInitKt.
        // Capture the outcome before touching `self`: logger is an instance property.
        var started = false
        var failure: String?
        do {
            try KoinInitIosKt.doInitKoin()
            started = true
        } catch {
            failure = error.localizedDescription
        }
        koinStarted = started
        if let failure = failure {
            logger.error("Koin initialization failed: \(failure)")
        }

        // Persisted-file and row-level migrations are gated by IosAppHost so
        // failures render a retryable shared screen instead of being ignored.
        // That host is only reached through ContentView, after Koin has started.
    }

    var body: some Scene {
        WindowGroup {
            if koinStarted {
                ContentView()
            } else {
                KoinInitializationFailureView()
            }
        }
    }
}

/// Fallback when Koin never starts. Deliberately not ContentView / Compose.
private struct KoinInitializationFailureView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("Unable to start")
                .font(.headline)
            Text("Phoenix could not initialize. Close the app and open it again.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
