import SwiftUI
import shared
import os.log

@main
struct PhoenixAppEntry: App {

    private let logger = Logger(subsystem: "com.devil.phoenixproject", category: "AppInit")

    init() {
        logger.info("========== APP INITIALIZATION START ==========")

        // Initialize Koin for dependency injection.
        // The iOS entrypoint lives in shared/iosMain (KoinInitIos.kt) so the Kotlin/Native
        // export class is KoinInitIosKt, not KoinInitKt.
        logger.info("[STEP 1/2] Starting Koin initialization...")
        do {
            try KoinInitIosKt.doInitKoin()
            logger.info("[STEP 1/2] Koin initialization completed")
        } catch {
            logger.error("[STEP 1/2] Koin initialization FAILED: \(error.localizedDescription)")
        }

        // Persisted-file and row-level migrations are gated by IosAppHost so
        // failures render a retryable shared screen instead of being ignored.
        logger.info("[STEP 2/2] App init complete, loading gated UI...")
        logger.info("========== APP INITIALIZATION SUCCESS ==========")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
