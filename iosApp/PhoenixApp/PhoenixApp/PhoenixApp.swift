import SwiftUI
import shared
import os.log

@main
struct PhoenixAppEntry: App {

    private let logger = Logger(subsystem: "com.devil.phoenixproject", category: "AppInit")

    init() {
        // Initialize Koin for dependency injection.
        // The iOS entrypoint lives in shared/iosMain (KoinInitIos.kt) so the Kotlin/Native
        // export class is KoinInitIosKt, not KoinInitKt.
        do {
            try KoinInitIosKt.doInitKoin()
        } catch {
            logger.error("Koin initialization failed: \(error.localizedDescription)")
            return
        }

        // Persisted-file and row-level migrations are gated by IosAppHost so
        // failures render a retryable shared screen instead of being ignored.
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
