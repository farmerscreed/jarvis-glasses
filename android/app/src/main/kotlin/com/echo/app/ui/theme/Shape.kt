package com.echo.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Radius set from the screens: 4 / 8 / 12 / 16 / 24, with 32 for full-screen sheets and pill for
 * status dots + listening states. extraLarge (24) is the workhorse card/modal radius.
 */
val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp), // chips, inputs
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), // buttons, medium cards
    extraLarge = RoundedCornerShape(24.dp), // cards, modals
)

/** Full-screen sheets / hero panels (the screens' rounded-[2rem]). Not an M3 slot. */
val SheetShape = RoundedCornerShape(32.dp)
