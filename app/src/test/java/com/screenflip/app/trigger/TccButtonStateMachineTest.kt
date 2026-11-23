package com.screenflip.app.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the AYN button double-tap state machine.
 *
 * A controllable fake clock keeps the tests deterministic.
 */
class TccButtonStateMachineTest {

    private class FakeClock {
        var now: Long = 0L
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun machine(clock: FakeClock, windowMs: Long = 350L) =
        TccButtonStateMachine(windowMs) { clock.now }

    private fun press(machine: TccButtonStateMachine, clock: FakeClock): Boolean {
        val result = machine.onButtonEvent(keyActionDown = true, isLongPress = false)
        clock.advance(50)
        return machine.onButtonEvent(keyActionDown = false, isLongPress = false) || result
    }

    @Test
    fun `single press does not trigger double tap`() {
        val clock = FakeClock()
        val machine = machine(clock)
        assertFalse(press(machine, clock))
    }

    @Test
    fun `two quick presses trigger double tap on the second one`() {
        val clock = FakeClock()
        val machine = machine(clock)
        assertFalse(press(machine, clock))
        clock.advance(150)
        assertTrue(press(machine, clock))
    }

    @Test
    fun `two slow presses do not trigger double tap`() {
        val clock = FakeClock()
        val machine = machine(clock)
        assertFalse(press(machine, clock))
        clock.advance(500)
        assertFalse(press(machine, clock))
    }

    @Test
    fun `long press release is not counted as a tap`() {
        val clock = FakeClock()
        val machine = machine(clock)
        // First quick press.
        assertFalse(machine.onButtonEvent(true, false))
        clock.advance(50)
        assertFalse(machine.onButtonEvent(false, false))
        clock.advance(400)
        // Long-press sequence: down, long-press event, release.
        assertFalse(machine.onButtonEvent(true, false))
        clock.advance(600)
        assertFalse(machine.onButtonEvent(true, true))
        clock.advance(50)
        // Release after long press must not complete a double tap.
        assertFalse(machine.onButtonEvent(false, false))
        // A subsequent quick press must not be treated as part of a pair
        // with the consumed long-press release.
        clock.advance(150)
        assertFalse(press(machine, clock))
    }

    @Test
    fun `press down event alone never triggers`() {
        val clock = FakeClock()
        val machine = machine(clock)
        assertFalse(machine.onButtonEvent(true, false))
        assertFalse(machine.onButtonEvent(true, false))
    }

    @Test
    fun `reset clears pending double tap state`() {
        val clock = FakeClock()
        val machine = machine(clock)
        assertFalse(press(machine, clock))
        machine.reset()
        clock.advance(100)
        // After reset the next quick press is a fresh first tap.
        assertFalse(press(machine, clock))
    }
}
