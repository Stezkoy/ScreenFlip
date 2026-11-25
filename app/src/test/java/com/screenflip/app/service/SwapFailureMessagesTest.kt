package com.screenflip.app.service

import com.screenflip.app.R
import com.screenflip.app.swap.PServerSwapEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwapFailureMessagesTest {

    @Test
    fun `maps engine failure identifiers to localized strings`() {
        assertEquals(
            R.string.swap_failed_no_pserver,
            swapFailureStringRes(PServerSwapEngine.FAILURE_NO_PSERVER)
        )
        assertEquals(
            R.string.swap_failed_no_second_display,
            swapFailureStringRes(PServerSwapEngine.FAILURE_NO_SECOND_DISPLAY)
        )
        assertEquals(
            R.string.swap_failed_move,
            swapFailureStringRes("move-stack failed")
        )
        assertEquals(
            R.string.swap_failed_move,
            swapFailureStringRes("move-stack 449 -> 4 failed")
        )
    }

    @Test
    fun `probe failures map to the matching screen`() {
        assertEquals(
            R.string.swap_failed_no_top_app,
            swapFailureStringRes("Task probe for display 0 failed")
        )
        assertEquals(
            R.string.swap_failed_no_bottom_app,
            swapFailureStringRes("Task probe for display 4 failed")
        )
    }

    @Test
    fun `raw vendor errors and unknown reasons stay unmapped`() {
        assertNull(swapFailureStringRes("java.lang.SecurityException: Permission denied"))
        assertNull(swapFailureStringRes("Task probe for display x failed"))
        assertNull(swapFailureStringRes(""))
    }
}
