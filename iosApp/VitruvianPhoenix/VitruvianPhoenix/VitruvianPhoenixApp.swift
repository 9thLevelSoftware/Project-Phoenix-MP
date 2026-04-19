import SwiftUI
import shared
import os.log

@main
struct VitruvianPhoenixApp: App {

    private let logger = Logger(subsystem: "com.devil.phoenixproject", category: "AppInit")

    init() {
        logger.info("========== APP INITIALIZATION START ==========")

        // Initialize Koin for dependency injection.
        // The iOS entrypoint lives in shared/iosMain (KoinInitIos.kt) so the Kotlin/Native
        // export class is KoinInitIosKt, not KoinInitKt.
        logger.info("[STEP 1/3] Starting Koin initialization...")
        do {
            try KoinInitIosKt.doInitKoin()
            logger.info("[STEP 1/3] Koin initialization completed")
        } catch {
            logger.error("[STEP 1/3] Koin initialization FAILED: \(error.localizedDescription)")
            // Re-throw to see crash in logs
        }

        // Run migrations after Koin is initialized (mirrors Android VitruvianApp.onCreate())
        logger.info("[STEP 2/3] Running migrations...")
        KoinInitKt.runMigrations()
        logger.info("[STEP 2/3] Migrations completed")

        logger.info("[STEP 3/3] App init complete, loading UI...")
        logger.info("========== APP INITIALIZATION SUCCESS ==========")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
