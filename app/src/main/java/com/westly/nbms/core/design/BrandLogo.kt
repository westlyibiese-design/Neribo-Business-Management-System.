package com.westly.nbms.core.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.westly.nbms.R
import com.westly.nbms.core.util.Branding

/** Same navy as the launcher icon background (ic_launcher_background.xml). */
private val IconNavy = Color(0xFF0A1733)

/**
 * The official app icon (the golden arches on navy, the same art as the launcher icon) as a square mark.
 * Use it wherever the app's logo is shown instead of a letter. Apply any border or shadow with [modifier].
 */
@Composable
fun NbmsLogoMark(
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(size * 0.22f)
) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(IconNavy),
        contentAlignment = Alignment.Center
    ) {
        // The launcher art keeps a wide safe margin for adaptive icons, so it is enlarged to fill the mark.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground_art),
            contentDescription = Branding.APP_NAME,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().scale(1.4f)
        )
    }
}
