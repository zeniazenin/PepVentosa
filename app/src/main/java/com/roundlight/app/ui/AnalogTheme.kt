package com.roundlight.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.roundlight.app.camera.Lens
import kotlin.math.*
import kotlin.random.Random

object AnalogColors {
    val LeatherBase = Color(0xFF0E1311)
    val LeatherPanel = Color(0xFF151C19)
    val GunmetalTop = Color(0xFF343E39)
    val GunmetalMid = Color(0xFF232B27)
    val GunmetalDeep = Color(0xFF151B18)
    val BrassLight = Color(0xFFEED6A7)
    val BrassMid = Color(0xFFC29E69)
    val BrassDeep = Color(0xFF83653C)
    val SteelLight = Color(0xFF4F5B55)
    val SteelMid = Color(0xFF303935)
    val SteelDeep = Color(0xFF1D2420)
    val RecessedBg = Color(0xFF101613)
    val EngravedDark = Color(0xFF121714)
    val EnamelCream = Color(0xFFF3EFE4)
    val MutedMetal = Color(0xFFA2AEA4)
    val SageGlow = Color(0xFFD8E6A3)
    val WarmAmber = Color(0xFFF2C57C)
    val IndexRed = Color(0xFFE66447)
    val SpecularTop = Color(0x52FFF5E0)
    val ShadowBottom = Color(0xCC040706)
    val BrassLine = Color(0x66D4B27D)
}

val AnalogColorScheme = darkColorScheme(
    primary = AnalogColors.BrassLight,
    onPrimary = AnalogColors.EngravedDark,
    background = AnalogColors.LeatherBase,
    onBackground = AnalogColors.EnamelCream,
    surface = AnalogColors.GunmetalMid,
    onSurface = AnalogColors.EnamelCream,
    secondary = AnalogColors.SageGlow,
    onSecondary = AnalogColors.EngravedDark,
    onSurfaceVariant = AnalogColors.MutedMetal,
    surfaceVariant = AnalogColors.GunmetalDeep,
    outline = Color(0xFF59645B)
)

/** Cached seamless procedural leatherette & CNC-brushed metal shaders (created once, tiled at 120fps). */
object AnalogTextures {
    val leatherBrush: ShaderBrush by lazy { ShaderBrush(ImageShader(buildLeatherTile(), TileMode.Repeated, TileMode.Repeated)) }
    val brushedGrainBrush: ShaderBrush by lazy { ShaderBrush(ImageShader(buildBrushedMetalTile(), TileMode.Repeated, TileMode.Repeated)) }

    private fun buildLeatherTile(): ImageBitmap {
        val size = 96
        val grid = 8
        val cell = size.toFloat() / grid
        val rng = Random(4207)
        val cx = FloatArray(grid * grid)
        val cy = FloatArray(grid * grid)
        val bump = FloatArray(grid * grid)
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                val i = gy * grid + gx
                cx[i] = (gx + 0.18f + rng.nextFloat() * 0.64f) * cell
                cy[i] = (gy + 0.18f + rng.nextFloat() * 0.64f) * cell
                bump[i] = 0.78f + rng.nextFloat() * 0.44f
            }
        }
        val pixels = IntArray(size * size)
        val radius = cell * 0.62f
        for (y in 0 until size) {
            val gy0 = (y / cell).toInt()
            for (x in 0 until size) {
                val gx0 = (x / cell).toInt()
                var bestDist = Float.MAX_VALUE
                var bestDx = 0f
                var bestDy = 0f
                var bestBump = 1f
                var secondDist = Float.MAX_VALUE
                for (oy in -1..1) {
                    val ny = (gy0 + oy + grid) % grid
                    for (ox in -1..1) {
                        val nx = (gx0 + ox + grid) % grid
                        val i = ny * grid + nx
                        var dx = x - cx[i]
                        var dy = y - cy[i]
                        if (dx > size / 2f) dx -= size else if (dx < -size / 2f) dx += size
                        if (dy > size / 2f) dy -= size else if (dy < -size / 2f) dy += size
                        val d = sqrt(dx * dx + dy * dy)
                        if (d < bestDist) {
                            secondDist = bestDist
                            bestDist = d
                            bestDx = dx
                            bestDy = dy
                            bestBump = bump[i]
                        } else if (d < secondDist) {
                            secondDist = d
                        }
                    }
                }
                // Soft domed leather pebble height + valley crease between pebbles
                val crease = ((secondDist - bestDist) / (cell * 0.36f)).coerceIn(0f, 1f)
                val dome = (1f - (bestDist / radius).coerceIn(0f, 1f).pow(1.7f)) * crease * bestBump
                // Directional catchlight from upper-left across each pebble dome
                val slope = ((-bestDx * 0.65f - bestDy * 0.76f) / radius).coerceIn(-1f, 1f)
                val grain = (rng.nextFloat() - 0.5f) * 3.5f
                val shade = dome * 12f + slope * crease * 5.5f + grain
                val r = (14f + shade * 0.88f).roundToInt().coerceIn(7, 36)
                val g = (19f + shade * 1.00f).roundToInt().coerceIn(10, 44)
                val b = (16f + shade * 0.90f).roundToInt().coerceIn(9, 39)
                pixels[y * size + x] = AndroidColor.argb(255, r, g, b)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private fun buildBrushedMetalTile(): ImageBitmap {
        val w = 128
        val h = 64
        val rng = Random(9181)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowBias = (rng.nextFloat() - 0.5f) * 2f
            val streakLen = 14 + rng.nextInt(32)
            var current = rowBias
            for (x in 0 until w) {
                if (x % streakLen == 0) current = (current * 0.4f) + (rng.nextFloat() - 0.5f) * 1.8f
                val v = (current + (rng.nextFloat() - 0.5f) * 0.55f).coerceIn(-1f, 1f)
                val alpha = (abs(v) * 20f).roundToInt().coerceIn(0, 22)
                val tone = if (v >= 0f) 248 else 6
                pixels[y * w + x] = AndroidColor.argb(alpha, tone, tone, tone)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

enum class MetalStyle { Brass, Steel, Gunmetal, Recessed }

fun Modifier.leatherSurface(vignetteAlpha: Float = 0.45f): Modifier = this.drawBehind {
    drawRect(brush = AnalogTextures.leatherBrush)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = vignetteAlpha)),
            center = Offset(size.width * 0.5f, size.height * 0.35f),
            radius = max(size.width, size.height) * 0.85f
        )
    )
}

fun Modifier.machinedMetal(
    style: MetalStyle,
    cornerRadius: Dp = 12.dp,
    pressed: Boolean = false
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .drawBehind {
        val r = cornerRadius.toPx()
        val colors = when (style) {
            MetalStyle.Brass -> if (pressed) listOf(AnalogColors.BrassDeep, AnalogColors.BrassMid, AnalogColors.BrassLight)
                else listOf(AnalogColors.BrassLight, AnalogColors.BrassMid, AnalogColors.BrassDeep)
            MetalStyle.Steel -> if (pressed) listOf(AnalogColors.SteelDeep, AnalogColors.SteelMid, AnalogColors.SteelLight)
                else listOf(AnalogColors.SteelLight, AnalogColors.SteelMid, AnalogColors.SteelDeep)
            MetalStyle.Gunmetal -> listOf(AnalogColors.GunmetalTop, AnalogColors.GunmetalMid, AnalogColors.GunmetalDeep)
            MetalStyle.Recessed -> listOf(Color(0xFF0D1210), Color(0xFF141B18), Color(0xFF1B231F))
        }
        drawRoundRect(
            brush = Brush.verticalGradient(colors),
            cornerRadius = CornerRadius(r, r)
        )
        drawRoundRect(
            brush = AnalogTextures.brushedGrainBrush,
            cornerRadius = CornerRadius(r, r)
        )
        // Specular top edge + shadowed bottom lip
        val topRim = when {
            style == MetalStyle.Recessed -> Color(0xAA040605)
            pressed -> Color(0xAA050807)
            style == MetalStyle.Brass -> Color(0xA6FFF8E6)
            else -> AnalogColors.SpecularTop
        }
        val bottomRim = when {
            style == MetalStyle.Recessed -> Color(0x38E0D8C4)
            pressed -> Color(0x44FFF4DC)
            else -> AnalogColors.ShadowBottom
        }
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(topRim, Color.Transparent, bottomRim)),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = 1.2.dp.toPx())
        )
    }

@Composable
private fun ScrewHead(modifier: Modifier = Modifier) {
    Canvas(modifier.size(7.dp)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFF0A0D0B), radius = r, center = c)
        drawCircle(
            brush = Brush.linearGradient(listOf(Color(0xFF5D6861), Color(0xFF1E2421))),
            radius = r * 0.82f,
            center = c
        )
        drawLine(
            color = Color(0xFF090C0A),
            start = c + Offset(-r * 0.52f, -r * 0.52f),
            end = c + Offset(r * 0.52f, r * 0.52f),
            strokeWidth = 1.1f
        )
    }
}

@Composable
fun CameraTopPlate(
    showBack: Boolean,
    enabled: Boolean,
    aiConnected: Boolean,
    onBack: () -> Unit,
    onAccount: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF2D3631), AnalogColors.GunmetalMid, Color(0xFF151B18))
                    )
                )
                drawRect(brush = AnalogTextures.brushedGrainBrush)
                // Machined bottom ridge + subtle antique brass pinstripe
                drawLine(
                    color = AnalogColors.BrassLine,
                    start = Offset(0f, size.height - 1.5.dp.toPx()),
                    end = Offset(size.width, size.height - 1.5.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color(0xDD050806),
                    start = Offset(0f, size.height - 0.5.dp.toPx()),
                    end = Offset(size.width, size.height - 0.5.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showBack) {
                MetalIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    enabled = enabled,
                    onClick = onBack
                )
            } else {
                // Analog engraved index medallion on home screen
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(AnalogColors.BrassLight, AnalogColors.BrassDeep)))
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(AnalogColors.GunmetalDeep),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(AnalogColors.BrassMid, radius = size.minDimension * 0.44f, center = c, style = Stroke(1.6f))
                        drawCircle(AnalogColors.SageGlow.copy(alpha = 0.85f), radius = size.minDimension * 0.19f, center = c)
                        drawCircle(AnalogColors.IndexRed, radius = 2.2f, center = Offset(c.x, c.y - size.minDimension * 0.44f))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = "LIGHTPLAY",
                        fontSize = 15.sp,
                        letterSpacing = 3.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = AnalogColors.EnamelCream
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AnalogColors.IndexRed)
                    )
                }
                Text(
                    text = "go shoot",
                    fontSize = 10.5.sp,
                    letterSpacing = 1.4.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AnalogColors.BrassLight.copy(alpha = 0.85f)
                )
            }
            Box {
                MetalIconButton(
                    icon = Icons.Outlined.PersonOutline,
                    contentDescription = "Account and diagnostics",
                    enabled = enabled,
                    onClick = onAccount
                )
                if (aiConnected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AnalogColors.SageGlow)
                            .border(1.dp, AnalogColors.GunmetalDeep, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
fun MetalIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.94f else 1f, label = "iconPress")
    val style = if (active) MetalStyle.Brass else MetalStyle.Steel
    val tint = if (active) AnalogColors.EngravedDark else AnalogColors.EnamelCream
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else 0.42f)
            .clip(CircleShape)
            .background(Color(0xFF0A0E0C))
            .padding(2.dp)
            .machinedMetal(style = style, cornerRadius = size / 2, pressed = pressed)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun MetalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MetalStyle = MetalStyle.Steel,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, label = "btnPress")
    val contentColor = when (style) {
        MetalStyle.Brass -> AnalogColors.EngravedDark
        MetalStyle.Steel, MetalStyle.Gunmetal -> AnalogColors.EnamelCream
        MetalStyle.Recessed -> AnalogColors.BrassLight
    }
    Box(
        modifier = modifier
            .heightIn(min = height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else 0.42f)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xAA060807))
            .padding(1.5.dp)
            .machinedMetal(style = style, cornerRadius = 11.5.dp, pressed = pressed)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = if (style == MetalStyle.Brass) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MetalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .machinedMetal(
                style = if (selected) MetalStyle.Brass else MetalStyle.Steel,
                cornerRadius = 10.dp,
                pressed = pressed
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(AnalogColors.IndexRed))
            }
            Text(
                text = label,
                color = if (selected) AnalogColors.EngravedDark else AnalogColors.EnamelCream,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeatherCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    val base = modifier
        .clip(shape)
        .border(
            width = 1.2.dp,
            brush = Brush.verticalGradient(listOf(Color(0x55E2D4B8), Color(0x88080B09))),
            shape = shape
        )
        .leatherSurface(vignetteAlpha = 0.35f)
        .drawWithContent {
            drawContent()
            // Subtle stitched inner seam line evoking classic camera leatherette wrap
            val inset = 4.dp.toPx()
            drawRoundRect(
                color = Color(0x22DFCFAF),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                style = Stroke(width = 0.7.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            )
        }
    val finalModifier = when {
        onLongClick != null -> base.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }
        )
        onClick != null -> base.clickable(onClick = onClick)
        else -> base
    }
    Column(modifier = finalModifier, content = content)
}

@Composable
fun MetalPlateCard(
    modifier: Modifier = Modifier,
    showScrews: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xBB050706))
            .padding(1.5.dp)
            .machinedMetal(style = MetalStyle.Gunmetal, cornerRadius = 14.5.dp)
    ) {
        if (showScrews) {
            ScrewHead(Modifier.align(Alignment.TopStart).padding(8.dp))
            ScrewHead(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

@Composable
fun FilmCounterWindow(
    count: Int,
    modifier: Modifier = Modifier,
    label: String = "EXP"
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF070A09))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(Color(0xFF060807), AnalogColors.BrassLine)),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            color = AnalogColors.MutedMetal
        )
        Text(
            text = "%02d".format(count),
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = AnalogColors.WarmAmber
        )
    }
}

/** Threaded concave mechanical brass & chrome shutter release button. */
@Composable
fun ShutterReleaseButton(
    enabled: Boolean,
    locked: Boolean,
    capturing: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1f, label = "shutterPress")
    val ringColor = if (locked) AnalogColors.IndexRed else Color(0xFF45504A)
    Box(
        modifier = Modifier
            .size(84.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color(0xFF080C0A))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = "Take photo" },
        contentAlignment = Alignment.Center
    ) {
        // Outer knurled steel shutter collar
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val rOuter = size.minDimension / 2f
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(AnalogColors.SteelLight, AnalogColors.SteelMid, AnalogColors.GunmetalDeep),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                ),
                radius = rOuter - 1f,
                center = c
            )
            // Knurled ticks around the collar
            for (deg in 0 until 360 step 10) {
                val rad = Math.toRadians(deg.toDouble())
                val p1 = Offset(c.x + (rOuter - 5.dp.toPx()) * cos(rad).toFloat(), c.y + (rOuter - 5.dp.toPx()) * sin(rad).toFloat())
                val p2 = Offset(c.x + (rOuter - 1.5.dp.toPx()) * cos(rad).toFloat(), c.y + (rOuter - 1.5.dp.toPx()) * sin(rad).toFloat())
                drawLine(Color(0x55060807), p1, p2, strokeWidth = 1.4f)
            }
            // Recessed collar groove
            drawCircle(Color(0xFF0A0E0C), radius = rOuter - 6.dp.toPx(), center = c)
            // Red anodized readiness ring (bright when exposure/WB locked)
            drawCircle(
                color = ringColor.copy(alpha = if (enabled) 0.95f else 0.35f),
                radius = rOuter - 7.5.dp.toPx(),
                center = c,
                style = Stroke(width = 2.4.dp.toPx())
            )
            // Concave brushed brass/silver plunger
            val rPlunger = rOuter - 10.dp.toPx()
            val plungerColors = if (enabled) {
                if (pressed) listOf(AnalogColors.BrassDeep, AnalogColors.BrassMid, AnalogColors.BrassLight)
                else listOf(AnalogColors.BrassLight, AnalogColors.BrassMid, AnalogColors.BrassDeep)
            } else {
                listOf(Color(0xFF3E4742), Color(0xFF28302C), Color(0xFF1A201D))
            }
            drawCircle(
                brush = Brush.linearGradient(plungerColors, start = Offset(c.x - rPlunger, c.y - rPlunger), end = Offset(c.x + rPlunger, c.y + rPlunger)),
                radius = rPlunger,
                center = c
            )
            // Fine machined lathe concentric ring
            drawCircle(
                color = Color(0x40FFF8E5),
                radius = rPlunger - 2.5.dp.toPx(),
                center = c,
                style = Stroke(width = 0.9.dp.toPx())
            )
        }
        if (capturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = AnalogColors.EngravedDark,
                strokeWidth = 2.5.dp
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = if (enabled) AnalogColors.EngravedDark else AnalogColors.MutedMetal.copy(alpha = 0.45f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
fun AnalogDialSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    key: String,
    onCommit: (Float) -> Unit
) {
    var local by remember(key, value) { mutableFloatStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                color = AnalogColors.EnamelCream,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0E0C))
                    .border(0.8.dp, Color(0x44C29E69), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = format(local),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = AnalogColors.WarmAmber
                )
            }
        }
        Slider(
            value = local.coerceIn(range),
            onValueChange = { local = it },
            valueRange = range,
            onValueChangeFinished = { if (local != value) onCommit(local) },
            colors = SliderDefaults.colors(
                thumbColor = AnalogColors.BrassLight,
                activeTrackColor = AnalogColors.BrassMid,
                inactiveTrackColor = Color(0xFF101613)
            ),
            modifier = Modifier.height(32.dp)
        )
        // Engraved fine optical scale ticks beneath the slider
        Canvas(Modifier.fillMaxWidth().height(5.dp).padding(horizontal = 8.dp)) {
            val steps = 20
            for (i in 0..steps) {
                val x = size.width * i / steps
                val major = i % 5 == 0
                drawLine(
                    color = if (major) AnalogColors.BrassLine else Color(0x33A2AEA4),
                    start = Offset(x, 0f),
                    end = Offset(x, if (major) size.height else size.height * 0.55f),
                    strokeWidth = if (major) 1.4f else 1f
                )
            }
        }
    }
}

@Composable
fun AnalogDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        MetalPlateCard(showScrews = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(AnalogColors.IndexRed))
                Text(
                    text = title,
                    fontFamily = FontFamily.Serif,
                    fontSize = 21.sp,
                    color = AnalogColors.EnamelCream
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = AnalogColors.BrassLine.copy(alpha = 0.45f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dismissButton != null) dismissButton()
                confirmButton()
            }
        }
    }
}

/** Tactile on-screen machined optical lens turret for multi-camera phones. */
@Composable
fun LensTurretBar(
    lenses: List<Lens>,
    selectedKey: String,
    lockedForShoot: Boolean,
    onSelect: (Lens) -> Unit,
    onOpenInspector: () -> Unit
) {
    val active = lenses.find { it.key == selectedKey }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lenses.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(lenses, key = { it.key }) { item ->
                    val isSelected = item.key == selectedKey
                    val enabled = !lockedForShoot || isSelected
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    Box(
                        modifier = Modifier
                            .alpha(if (enabled) 1f else 0.48f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color(0xAA060807))
                            .padding(1.2.dp)
                            .machinedMetal(
                                style = if (isSelected) MetalStyle.Brass else MetalStyle.Steel,
                                cornerRadius = 10.dp,
                                pressed = pressed && enabled
                            )
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                enabled = !lockedForShoot,
                                onClick = { if (!isSelected) onSelect(item) }
                            )
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isSelected) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(AnalogColors.IndexRed))
                                }
                                Text(
                                    text = item.zoomLabel,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) AnalogColors.EngravedDark else AnalogColors.EnamelCream
                                )
                            }
                            Text(
                                text = item.role,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isSelected) AnalogColors.EngravedDark.copy(alpha = 0.8f) else AnalogColors.MutedMetal
                            )
                        }
                    }
                }
            }
        }
        // Engraved optical barrel spec bar (tappable to inspect all cameras & properties)
        if (active != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .machinedMetal(style = MetalStyle.Recessed, cornerRadius = 9.dp)
                    .clickable(onClick = onOpenInspector)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Camera,
                    contentDescription = null,
                    tint = AnalogColors.BrassLight,
                    modifier = Modifier.size(15.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${active.zoomLabel} ${active.role}" + (active.equivMm?.let { " · ${it}mm eq" } ?: ""),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AnalogColors.EnamelCream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = active.specSummary.ifBlank { active.purpose },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AnalogColors.WarmAmber.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (lockedForShoot) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Lens locked for this shoot",
                        tint = AnalogColors.MutedMetal,
                        modifier = Modifier.size(14.dp)
                    )
                } else if (lenses.size > 1) {
                    Text(
                        text = "ALL LENSES",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AnalogColors.BrassLight
                    )
                }
            }
        }
    }
}

@Composable
fun LensPickerDialog(
    lenses: List<Lens>,
    selectedKey: String,
    lockedForShoot: Boolean,
    onSelect: (Lens) -> Unit,
    onDismiss: () -> Unit
) {
    AnalogDialog(
        title = "Camera lenses",
        onDismissRequest = onDismiss,
        confirmButton = {
            MetalButton(text = "Done", style = MetalStyle.Brass, height = 40.dp, onClick = onDismiss)
        }
    ) {
        Text(
            text = if (lockedForShoot) {
                "This shoot is locked to its active lens so every orbit frame matches in perspective. Start a new shoot to switch lenses."
            } else {
                "Each physical lens changes perspective, field of view, and depth compression for your in-the-round orbit."
            },
            color = AnalogColors.MutedMetal,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            lenses.forEach { lens ->
                val isSelected = lens.key == selectedKey
                val selectable = !lockedForShoot
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (selectable || isSelected) 1f else 0.5f)
                        .clip(RoundedCornerShape(13.dp))
                        .machinedMetal(
                            style = if (isSelected) MetalStyle.Steel else MetalStyle.Recessed,
                            cornerRadius = 13.dp
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 0.8.dp,
                            color = if (isSelected) AnalogColors.BrassLight else Color(0x33A2AEA4),
                            shape = RoundedCornerShape(13.dp)
                        )
                        .clickable(enabled = selectable) {
                            onSelect(lens)
                            onDismiss()
                        }
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Circular engraved lens dial badge
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .machinedMetal(
                                    style = if (isSelected) MetalStyle.Brass else MetalStyle.Gunmetal,
                                    cornerRadius = 25.dp
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = lens.zoomLabel,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) AnalogColors.EngravedDark else AnalogColors.WarmAmber
                                )
                                lens.equivMm?.let { eq ->
                                    Text(
                                        text = "${eq}mm",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.5.sp,
                                        color = if (isSelected) AnalogColors.EngravedDark.copy(alpha = 0.78f) else AnalogColors.MutedMetal
                                    )
                                }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = lens.role,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = AnalogColors.EnamelCream
                                )
                                val tag = lens.physicalId?.let { "Lens #$it" } ?: "Cam #${lens.cameraId}"
                                Text(
                                    text = tag,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = AnalogColors.MutedMetal
                                )
                                if (isSelected) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(AnalogColors.IndexRed))
                                }
                            }
                            if (lens.purpose.isNotBlank()) {
                                Text(
                                    text = lens.purpose,
                                    fontSize = 11.5.sp,
                                    color = AnalogColors.MutedMetal,
                                    lineHeight = 15.sp
                                )
                            }
                            // Property pills (Aperture, MP, RAW, OIS, Macro, Manual)
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                lens.primaryAperture?.let { LensSpecTag("f/$it", highlight = true) }
                                if (lens.maxMegapixels > 0) LensSpecTag("${lens.maxMegapixels} MP")
                                if (lens.rawSize != null) LensSpecTag("RAW DNG", highlight = true)
                                if (lens.hasOis) LensSpecTag("OIS")
                                if (lens.manual) LensSpecTag("Manual")
                                if (lens.isMacroCapable) LensSpecTag("Macro")
                                if (lens.aeLock && !lens.manual) LensSpecTag("AE Lock")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LensSpecTag(text: String, highlight: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (highlight) Color(0x33C29E69) else Color(0xFF0C110F))
            .border(0.7.dp, if (highlight) AnalogColors.BrassLine else Color(0x33A2AEA4), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) AnalogColors.BrassLight else AnalogColors.MutedMetal
        )
    }
}
