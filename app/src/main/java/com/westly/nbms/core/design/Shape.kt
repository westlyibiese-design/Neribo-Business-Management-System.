package com.westly.nbms.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** ExtraSmall 4dp, Small 6dp, Medium 8dp (base radius), Large 12dp (cards), ExtraLarge 16dp. */
val NbmsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

/** Westly shadow: elevation tinted navy. Apply BEFORE clip/background. */
fun Modifier.nbmsShadow(elevation: Dp, shape: Shape): Modifier =
    this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = FixedTokens.shadowTint,
        spotColor = FixedTokens.shadowTint
    )
