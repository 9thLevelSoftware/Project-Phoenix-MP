package com.devil.phoenixproject.util

/**
 * Issue #1400 F1 metric-unit contract versions for the per-row `metric_unit_version`
 * marker and for versioned persisted in-flight state (completion journal, exit snapshot).
 *
 * - [METRIC_UNIT_VERSION_LEGACY]: values carry the pre-#1400 cm / cm-s scale while
 *   being labelled mm / mm-s.
 * - [METRIC_UNIT_VERSION_MM]: values are true mm / mm-s (raw firmware units).
 */
const val METRIC_UNIT_VERSION_LEGACY: Int = 1

const val METRIC_UNIT_VERSION_MM: Int = 2
