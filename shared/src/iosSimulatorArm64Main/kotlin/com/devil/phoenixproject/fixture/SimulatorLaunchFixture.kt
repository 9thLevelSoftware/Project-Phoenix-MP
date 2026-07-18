package com.devil.phoenixproject.fixture

import com.devil.phoenixproject.di.IosLaunchFixture
import com.devil.phoenixproject.util.Constants
import com.russhwolf.settings.Settings
import platform.Foundation.NSProcessInfo

private const val FIXTURE_ENVIRONMENT_VARIABLE = "PHOENIX_SIMULATOR_FIXTURE"
private const val EULA_ACCEPTED_VERSION_KEY = "eula_accepted_version"
private const val EULA_ACCEPTED_TIMESTAMP_KEY = "eula_accepted_timestamp"

/**
 * Strict, process-local launch fixtures for the real iOS app simulator target.
 *
 * The fixture id is deliberately an enum allowlist. The runner controls persistent database
 * reset/uninstall; these fixtures only establish the launch settings needed by the UI scenario.
 */
internal enum class SimulatorLaunchFixture(
    override val id: String,
    private val seedSettings: (Settings) -> Unit,
) : IosLaunchFixture {
    CLEAN_EULA(
        id = "clean-eula",
        seedSettings = { settings ->
            settings.remove(EULA_ACCEPTED_VERSION_KEY)
            settings.remove(EULA_ACCEPTED_TIMESTAMP_KEY)
        },
    ),
    JUST_LIFT_CONNECTED(
        id = "just-lift-connected",
        seedSettings = { settings ->
            settings.putInt(EULA_ACCEPTED_VERSION_KEY, Constants.EULA_VERSION)
            settings.putLong(EULA_ACCEPTED_TIMESTAMP_KEY, DETERMINISTIC_EULA_TIMESTAMP)
        },
    ),
    ;

    override fun seed(settings: Settings) {
        seedSettings(settings)
    }

    companion object {
        /** 2024-01-01T00:00:00Z, stable across every simulator runner invocation. */
        const val DETERMINISTIC_EULA_TIMESTAMP = 1_704_067_200_000L

        fun resolve(fixtureId: String?): SimulatorLaunchFixture? = when (fixtureId) {
            null -> null
            "clean-eula" -> CLEAN_EULA
            "just-lift-connected" -> JUST_LIFT_CONNECTED
            else -> throw IllegalArgumentException(
                "Unsupported PHOENIX_SIMULATOR_FIXTURE; expected an allowlisted fixture id",
            )
        }

        fun resolveFromEnvironment(): SimulatorLaunchFixture? {
            val fixtureId = NSProcessInfo.processInfo.environment[FIXTURE_ENVIRONMENT_VARIABLE] as? String
            return resolve(fixtureId)
        }
    }
}
