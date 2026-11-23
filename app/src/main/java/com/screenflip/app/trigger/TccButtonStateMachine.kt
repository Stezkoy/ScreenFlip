package com.screenflip.app.trigger

/**
 * Pure state machine for the physical AYN / Dual-Screen button events
 * (broadcast `action.tcc.button.key.event`), focused on double-tap detection.
 *
 * Event semantics (matching the stock firmware):
 * - `keyActionDown == true && !isLongPress`  -> initial press down (no-op)
 * - `keyActionDown == true && isLongPress`   -> long-press threshold reached
 * - `keyActionDown == false`                 -> key released
 *
 * A "double tap" is two short presses whose key-up events fall inside
 * [doubleTapWindowMs]. Long-press releases are ignored (the long-press is
 * consumed by the stock behavior, e.g. blanking the bottom screen).
 *
 * The class is intentionally free of Android dependencies so it can be
 * unit-tested on the JVM.
 */
class TccButtonStateMachine(
    private val doubleTapWindowMs: Long = 500L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    private var lastTapUpTimeMs: Long = -1L
    private var longPressConsumed: Boolean = false

    /**
     * Feeds one TCC button event into the machine.
     *
     * @return true exactly when a double-tap was completed by this event.
     */
    fun onButtonEvent(keyActionDown: Boolean, isLongPress: Boolean): Boolean {
        if (keyActionDown) {
            if (isLongPress) {
                longPressConsumed = true
            }
            // Initial key down is a no-op; the following key-up must not be
            // counted as a tap when a long-press was consumed.
            return false
        }
        // Key up.
        if (longPressConsumed) {
            longPressConsumed = false
            lastTapUpTimeMs = -1L
            return false
        }
        val now = clock()
        val delta = if (lastTapUpTimeMs < 0L) Long.MAX_VALUE else now - lastTapUpTimeMs
        lastTapUpTimeMs = now
        return delta in 0L..doubleTapWindowMs
    }

    /** Resets all internal state (e.g. when the feature is toggled off). */
    fun reset() {
        lastTapUpTimeMs = -1L
        longPressConsumed = false
    }
}
