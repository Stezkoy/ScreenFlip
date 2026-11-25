package com.screenflip.app.swap

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import java.util.Locale

/**
 * Outcome of a screen swap attempt.
 */
sealed interface SwapResult {
    /** Both top tasks were moved to the opposite displays. */
    data object Swapped : SwapResult

    /** Exactly one display had a task, it was moved to the other one. */
    data object Partial : SwapResult

    /** A single task was moved in the requested direction. */
    data class Moved(val toBottom: Boolean) : SwapResult

    /** Neither display had a foreground standard task. */
    data object NoTasks : SwapResult

    /** The swap could not be performed. */
    data class Failed(val reason: String) : SwapResult
}

/**
 * Swaps the top foreground tasks of the top display (id 0) and the bottom
 * display using the firmware shell via [PServerClient]:
 *
 * 1. resolves the bottom display id at runtime (display ids are not stable
 *    across boots, so we never hard-code "4");
 * 2. probes the top task id of each display out of `dumpsys activity
 *    activities` (one short command per display, because the firmware
 *    shell truncates longer commands);
 * 3. moves the tasks with `am display move-stack`.
 */
object PServerSwapEngine {

    private const val TAG = "PServerSwapEngine"
    private const val EXEC_TIMEOUT_MS = 10_000L

    /**
     * Swaps the top foreground tasks of the two displays.
     *
     * The firmware shell (PServerBinder) has quirks that shaped this
     * implementation:
     * 1. it does not support `$( ... )` command substitution (such scripts
     *    complete with empty output);
     * 2. it returns only the first line of stdout (and ~256 bytes of it);
     * 3. it truncates commands at ~256 characters (verified with the
     *    one-shot diagnostics: 215-char commands run, 315-char ones are
     *    cut off mid-way and fail).
     *
     * So the swap is performed as a sequence of small single-line commands
     * (each far below the 256-char limit) and all the logic lives here in
     * Kotlin:
     *   1. one short awk probe per display prints the top task id;
     *   2. each task is moved to the opposite display with
     *      `am display move-stack <task> <display>`.
     */
    fun swap(context: Context, mode: SwapMode = SwapMode.SWAP): SwapResult {
        if (!PServerClient.isAvailable()) {
            return SwapResult.Failed("PServerBinder service not available")
        }
        val bottomId = resolveBottomDisplayId(context)
            ?: return SwapResult.Failed("No second physical display found")
        Log.i(TAG, "Swapping displays, mode = $mode, bottom display id = $bottomId")

        // Probe only what the requested mode needs: a failing probe of the
        // untouched display must not abort a one-direction move.
        val topTaskId = if (mode != SwapMode.BOTTOM_TO_TOP) {
            val raw0 = probeTopTask(0)
                ?: return SwapResult.Failed("Task probe for display 0 failed")
            parseTopTaskId(raw0)
        } else {
            null
        }
        val bottomTaskId = if (mode != SwapMode.TOP_TO_BOTTOM) {
            val rawB = probeTopTask(bottomId)
                ?: return SwapResult.Failed("Task probe for display $bottomId failed")
            parseTopTaskId(rawB)
        } else {
            null
        }
        Log.i(TAG, "Top tasks: display 0 [$topTaskId], display $bottomId [$bottomTaskId]")
        return when (mode) {
            SwapMode.SWAP -> when {
                topTaskId == null && bottomTaskId == null -> SwapResult.NoTasks
                topTaskId != null && bottomTaskId != null -> {
                    val o1 = runMove(topTaskId, bottomId)
                        ?: return SwapResult.Failed("move-stack $topTaskId -> $bottomId failed")
                    val o2 = runMove(bottomTaskId, 0)
                        ?: return SwapResult.Partial
                    val movedTop = !o1.contains("Exception")
                    val movedBottom = !o2.contains("Exception")
                    when {
                        movedTop && movedBottom -> SwapResult.Swapped
                        movedTop || movedBottom -> SwapResult.Partial
                        else -> SwapResult.Failed("move-stack failed")
                    }
                }
                topTaskId != null -> {
                    val o = runMove(topTaskId, bottomId) ?: return SwapResult.Failed("move-stack failed")
                    if (o.contains("Exception")) {
                        SwapResult.Failed(o.take(120))
                    } else {
                        SwapResult.Partial
                    }
                }
                else -> {
                    val v = bottomTaskId ?: return SwapResult.NoTasks
                    val o = runMove(v, 0) ?: return SwapResult.Failed("move-stack failed")
                    if (o.contains("Exception")) {
                        SwapResult.Failed(o.take(120))
                    } else {
                        SwapResult.Partial
                    }
                }
            }
            SwapMode.TOP_TO_BOTTOM -> if (topTaskId == null) {
                SwapResult.NoTasks
            } else {
                val o = runMove(topTaskId, bottomId) ?: return SwapResult.Failed("move-stack failed")
                if (o.contains("Exception")) {
                    SwapResult.Failed(o.take(120))
                } else {
                    SwapResult.Moved(toBottom = true)
                }
            }
            SwapMode.BOTTOM_TO_TOP -> if (bottomTaskId == null) {
                SwapResult.NoTasks
            } else {
                val o = runMove(bottomTaskId, 0) ?: return SwapResult.Failed("move-stack failed")
                if (o.contains("Exception")) {
                    SwapResult.Failed(o.take(120))
                } else {
                    SwapResult.Moved(toBottom = false)
                }
            }
        }
    }

    /**
     * Single-line command that prints the top task id of [displayId] out of
     * `dumpsys activity activities` (empty when the display has no resumed
     * activity).
     *
     * The id is read from the `topResumedActivity` line of the per-display
     * "activities from top to bottom" list (`...} t291` - the same number
     * as `#291` in the task header, which is what `am display move-stack`
     * expects).
     *
     * The display is located by walking the `Display #N` headers: the gate
     * is closed on every header and opened only on the exact target header
     * (`#4` must not match `#40`), and `exit` stops the scan right after the
     * first hit. Closing the gate on every header matters: when the target
     * display has no resumed activity, a later display's section must not
     * leak its task into the result.
     *
     * The command is deliberately kept far below the firmware shell's
     * ~256-char command limit (~165 chars for a two-digit display id).
     */
    internal fun topTaskCommand(displayId: Int): String {
        val gate = "/Display #/{d=9} /Display #$displayId[^0-9]/{d=0} d==0&&"
        return "dumpsys activity activities | awk '" + gate +
            "/topResumed/{if(match($0,/\\} t[0-9]+/))print substr($0,RSTART+3,RLENGTH-3);exit}'"
    }

    /** Runs one top-task probe and returns its output, or null on failure. */
    private fun probeTopTask(displayId: Int): String? {
        return PServerClient.execWithTimeout(
            topTaskCommand(displayId), wait = true, timeoutMs = EXEC_TIMEOUT_MS
        ).fold(
            onSuccess = { it.trim() },
            onFailure = {
                Log.e(TAG, "Task probe for display $displayId failed", it)
                null
            }
        )
    }

    /**
     * Parses the probe output of [topTaskCommand]: a bare task id like
     * "291". Missing or malformed input yields null.
     */
    internal fun parseTopTaskId(raw: String): String? {
        val id = raw.trim()
        return if (id.isNotEmpty() && id.all { it.isDigit() }) id else null
    }

    /** Runs one `am display move-stack` and returns its (first-line) output. */
    private fun runMove(taskId: String, displayId: Int): String? {
        return PServerClient.execWithTimeout(
            "am display move-stack $taskId $displayId",
            wait = true,
            timeoutMs = EXEC_TIMEOUT_MS
        ).fold(
            onSuccess = { out ->
                Log.i(TAG, "move-stack $taskId -> display $displayId: [$out]")
                out
            },
            onFailure = {
                Log.e(TAG, "move-stack $taskId -> display $displayId failed", it)
                null
            }
        )
    }

    /**
     * Returns the id of the second physical display (the bottom panel).
     * Display ids are not stable across boots, so they are resolved at
     * runtime through [DisplayManager]; its ids are the logical display ids
     * that `am display move-stack` targets.
     *
     * `am display id` is NOT a valid subcommand on this firmware (verified
     * on device: "Error: unknown command 'id'"), so the vendor shell must
     * not be used for display discovery.
     *
     * Virtual / cast displays are filtered out by name, then the smallest
     * panel wins so the secondary Thor screen is picked over a virtual one.
     */
    fun resolveBottomDisplayId(context: Context): Int? {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val candidates = displayManager.displays
            .map { it.displayId }
            .filter { it != 0 }
            .distinct()
            .sorted()
        if (candidates.isEmpty()) return null
        // Prefer candidates with known metrics, smallest physical panel wins.
        val sized = candidates.mapNotNull { id ->
            val display = displayManager.getDisplay(id) ?: return@mapNotNull null
            if (looksLikeCastDisplay(display.name)) return@mapNotNull null
            val metrics = context.createDisplayContext(display).resources.displayMetrics
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return@mapNotNull null
            id to (metrics.widthPixels * metrics.heightPixels)
        }
        return if (sized.isEmpty()) candidates.first() else sized.minByOrNull { it.second }?.first
    }

    private val CAST_DISPLAY_KEYWORDS: List<String> = listOf(
        "castdisplay", "virtualdisplay", "screencast", "screen cast", "cast",
        "chromecast", "android auto", "carprojection", "nearby", "companion"
    )

    private fun looksLikeCastDisplay(name: CharSequence?): Boolean {
        val trimmed = name?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
        if (trimmed.isEmpty()) return false
        return trimmed in CAST_DISPLAY_KEYWORDS ||
            CAST_DISPLAY_KEYWORDS.any { trimmed.contains(it) }
    }
}
