/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A custom modifier that adds a soft, offset shadow to a circular shape.
 *
 * @param radius The blur radius of the shadow.
 * @param offsetY The vertical offset of the shadow from the composable.
 * @param shadowColor The color of the shadow.
 */
fun Modifier.circularShadow(
    radius: Dp, offsetY: Dp, shadowColor: Color = Color.Black
): Modifier = this.drawBehind {
    // The paint object that will draw the shadow
    val paint = Paint().apply {
        this.color = Color.Transparent // The shape itself is transparent
        this.isAntiAlias = true
        // Configure the shadow layer on the framework paint
        asFrameworkPaint().setShadowLayer(
            radius.toPx(), // Blur radius
            0f,            // Horizontal offset
            offsetY.toPx(),// Vertical offset
            shadowColor.toArgb()
        )
    }
    // Draw the shape that will cast the shadow
    drawIntoCanvas { canvas ->
        canvas.drawCircle(
            center = this.center, radius = this.size.width / 2, // Assumes a circular component
            paint = paint
        )
    }
}

/**
 * A custom modifier that adds a soft, offset shadow to a rounded rectangle shape.
 *
 * @param shadowRadius The blur radius of the shadow.
 * @param offsetY The vertical offset of the shadow from the composable.
 * @param cornerRadius The corner radius of the rectangle shape.
 * @param shadowColor The color of the shadow.
 * @param insetX The horizontal inset for the shadow shape, making it smaller than the component.
 * @param insetY The vertical inset for the shadow shape, making it smaller than the component.
 */
fun Modifier.roundedRectShadow(
    shadowRadius: Dp,
    offsetY: Dp,
    cornerRadius: Dp,
    shadowColor: Color = Color.Black,
    insetX: Dp = 0.dp,
    insetY: Dp = 0.dp
): Modifier = this.drawBehind {
    val paint = Paint().apply {
        this.color = Color.Transparent
        this.isAntiAlias = true
        asFrameworkPaint().setShadowLayer(
            shadowRadius.toPx(), 0f, offsetY.toPx(), shadowColor.toArgb()
        )
    }
    drawIntoCanvas { canvas ->
        val left = insetX.toPx()
        val top = insetY.toPx()
        val right = this.size.width - insetX.toPx()
        val bottom = this.size.height - insetY.toPx()

        canvas.drawRoundRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radiusX = cornerRadius.toPx(),
            radiusY = cornerRadius.toPx(),
            paint = paint
        )
    }
}

/**
 * A custom modifier that adds a soft, offset shadow to a pill-shaped (fully rounded) rectangle.
 * The corner radius is dynamically calculated as half the component's height.
 *
 * @param shadowRadius The blur radius of the shadow.
 * @param offsetY The vertical offset of the shadow from the composable.
 * @param shadowColor The color of the shadow.
 * @param insetX The horizontal inset for the shadow shape.
 * @param insetY The vertical inset for the shadow shape.
 */
fun Modifier.pillShapeShadow(
    shadowRadius: Dp,
    offsetY: Dp,
    shadowColor: Color = Color.Black,
    insetX: Dp = 0.dp,
    insetY: Dp = 0.dp
): Modifier = this.drawBehind {
    val paint = Paint().apply {
        this.color = Color.Transparent
        this.isAntiAlias = true
        asFrameworkPaint().setShadowLayer(
            shadowRadius.toPx(),
            0f,
            offsetY.toPx(),
            shadowColor.toArgb()
        )
    }
    drawIntoCanvas { canvas ->
        val left = insetX.toPx()
        val top = insetY.toPx()
        val right = this.size.width - insetX.toPx()
        val bottom = this.size.height - insetY.toPx()
        // Dynamically calculate the corner radius inside the drawing scope
        val cornerRadius = (bottom - top) / 2f

        canvas.drawRoundRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radiusX = cornerRadius,
            radiusY = cornerRadius,
            paint = paint
        )
    }
}