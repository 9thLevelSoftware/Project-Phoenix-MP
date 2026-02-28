package com.devil.phoenixproject.domain.model

enum class HudPage(val key: String) {
    EXECUTION("execution"),
    INSTRUCTION("instruction"),
    STATS("stats");

    companion object {
        fun fromKey(key: String): HudPage? = entries.find { it.key == key }
    }
}

enum class HudPreset(val key: String, val displayName: String, val description: String, val pages: List<HudPage>) {
    ESSENTIAL("essential", "Essential", "Rep counter and force gauge only", listOf(HudPage.EXECUTION)),
    BIOMECHANICS("biomechanics", "Biomechanics", "Execution + live velocity/force stats", listOf(HudPage.EXECUTION, HudPage.STATS)),
    FULL("full", "Full", "All pages including exercise video", listOf(HudPage.EXECUTION, HudPage.INSTRUCTION, HudPage.STATS));

    companion object {
        fun fromKey(key: String): HudPreset = entries.find { it.key == key } ?: FULL
    }
}
