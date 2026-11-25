package com.screenflip.app.service

import com.screenflip.app.R
import com.screenflip.app.swap.PServerSwapEngine

/**
 * Maps a technical [com.screenflip.app.swap.SwapResult.Failed] reason to a
 * localized message resource.
 *
 * The engine reports stable identifiers for its own failures; anything else
 * is a raw vendor-shell error and is returned as null so the caller can show
 * it verbatim (and log it).
 */
internal fun swapFailureStringRes(reason: String): Int? = when {
    reason == PServerSwapEngine.FAILURE_NO_PSERVER ->
        R.string.swap_failed_no_pserver
    reason == PServerSwapEngine.FAILURE_NO_SECOND_DISPLAY ->
        R.string.swap_failed_no_second_display
    reason.startsWith(PServerSwapEngine.FAILURE_PROBE_PREFIX) -> when (
        probeDisplayId(reason)
    ) {
        0 -> R.string.swap_failed_no_top_app
        null -> null
        else -> R.string.swap_failed_no_bottom_app
    }
    reason.startsWith(PServerSwapEngine.FAILURE_MOVE_PREFIX) ->
        R.string.swap_failed_move
    else -> null
}

/** Extracts the display id from "Task probe for display <id> failed". */
private fun probeDisplayId(reason: String): Int? =
    reason.removePrefix(PServerSwapEngine.FAILURE_PROBE_PREFIX)
        .substringBefore(' ')
        .toIntOrNull()
