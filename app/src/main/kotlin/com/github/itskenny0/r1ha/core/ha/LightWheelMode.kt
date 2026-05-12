package com.github.itskenny0.r1ha.core.ha

/**
 * What the wheel drives on a light card. BRIGHTNESS is the default for every dimmable
 * bulb. COLOR_TEMP is offered when the bulb's supported_color_modes includes
 * "color_temp". HUE is offered when supported_color_modes includes anything from the
 * RGB family ("hs" / "xy" / "rgb" / "rgbw" / "rgbww" / "rgbcw" — HA's full set of
 * colour mode names).
 *
 * Available modes are derived per-entity; the tap-the-readout gesture cycles through
 * whatever's available, skipping the modes the bulb can't handle.
 */
enum class LightWheelMode {
    BRIGHTNESS,
    COLOR_TEMP,
    HUE,
    ;

    companion object {
        /**
         * Compute the available wheel modes for a light given its supported_color_modes
         * list. Always includes BRIGHTNESS as the fallback (every dimmable light has it);
         * COLOR_TEMP and HUE are added based on the modes HA reports. Non-coloured bulbs
         * end up with just [BRIGHTNESS] so the cycle gesture is a no-op (one entry).
         */
        fun availableFor(supportedColorModes: List<String>): List<LightWheelMode> {
            val out = mutableListOf(BRIGHTNESS)
            if (supportedColorModes.any { it == "color_temp" }) out += COLOR_TEMP
            if (supportedColorModes.any { it in colorishModes }) out += HUE
            return out
        }

        /** Mode names that indicate a colour-capable bulb. HA's vocabulary covers all
         *  the per-mode and combo variants. */
        private val colorishModes = setOf("hs", "xy", "rgb", "rgbw", "rgbww", "rgbcw")
    }
}
