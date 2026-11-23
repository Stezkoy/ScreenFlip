package com.screenflip.app.swap

/**
 * The three ways the two screens can be re-arranged.
 */
enum class SwapMode(val key: String) {
    /** Move both top tasks to the opposite display (classic swap). */
    SWAP("swap"),

    /** Move the top display's task to the bottom display. */
    TOP_TO_BOTTOM("top_to_bottom"),

    /** Move the bottom display's task to the top display. */
    BOTTOM_TO_TOP("bottom_to_top");

    companion object {
        fun fromKey(key: String?): SwapMode =
            entries.firstOrNull { it.key == key } ?: SWAP
    }
}
