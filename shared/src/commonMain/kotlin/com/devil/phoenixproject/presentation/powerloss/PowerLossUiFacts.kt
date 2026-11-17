package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerLossHudState
import com.devil.phoenixproject.domain.powerloss.PowerLossSetSummaryFacts

/**
 * Issue #1392 — INTEGRATION ADAPTER (P5 owned).
 *
 * The parallel engine/persistence packets add two names that are NOT yet present
 * on this branch:
 *  - `WorkoutState.SetSummary.powerLoss: PowerLossSetSummaryFacts?`
 *  - engine/state-holder `powerLossHud: StateFlow<PowerLossHudState>`
 *
 * Per the packet contract, those two references live ONLY behind this file. The
 * bodies below are the placeholder defaults used until integration; reconciling
 * this file at integration is a one-line change per function (marked INTEGRATION).
 * Everything else in presentation/powerloss renders strictly from the frozen
 * domain facts and is fully tested.
 */
object PowerLossUiFacts {

    /**
     * INTEGRATION: `summary.powerLoss` (WorkoutState.SetSummary.powerLoss).
     * Until the parallel packet lands, summaries carry no power facts.
     */
    fun summaryFacts(summaryPowerLoss: PowerLossSetSummaryFacts?): PowerLossSetSummaryFacts? =
        summaryPowerLoss

    /**
     * INTEGRATION: engine `powerLossHud` StateFlow value.
     * Until the parallel packet lands, HUD facts default to the inert state.
     */
    fun hudFacts(engineHud: PowerLossHudState?): PowerLossHudState =
        engineHud ?: PowerLossHudState()
}
