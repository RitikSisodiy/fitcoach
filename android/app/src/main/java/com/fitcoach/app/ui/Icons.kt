package com.fitcoach.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The app's own line icons (24 x 24, 2 px round strokes), so no icon library is needed. Tint them with Icon(tint = …). */
object FcIcons {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { p ->
                addPath(
                    pathData = addPathNodes(p),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Chat = icon("chat", "M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H10l-5 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z")
    val Pulse = icon("pulse", "M3 12h4l3-7 4 14 3-7h4")
    val Sliders = icon("sliders", "M4 7h5", "M13 7h7", "M11 9a2 2 0 1 0 0-4a2 2 0 1 0 0 4z",
        "M4 12h9", "M17 12h3", "M15 14a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", "M4 17h2", "M10 17h10", "M8 19a2 2 0 1 0 0-4a2 2 0 1 0 0 4z")
    val Phone = icon("phone", "M6 3h3l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 4 5a2 2 0 0 1 2-2z")
    val PhoneDown = icon("phone_down", "M3 14.5c5-4.5 13-4.5 18 0l-1.8 2.8-3.4-1.4v-2.6a12 12 0 0 0-7.6 0v2.6l-3.4 1.4z")
    val Mic = icon("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val MicOff = icon("mic_off", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3", "M4 4l16 16")
    val Speaker = icon("speaker", "M4 9h4l5-4v14l-5-4H4z", "M16.5 9a4 4 0 0 1 0 6", "M19 6.5a8 8 0 0 1 0 11")
    val Image = icon("image", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 16l5-5 4 4 3-3 6 6", "M15.5 9.5a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3z")
    val Send = icon("send", "M4 12l16-8-6 16-3-7z", "M11 13l9-9")
    val Stop = icon("stop", "M7 7h10v10H7z", filled = true)
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7")
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val ChevronRight = icon("chevron", "M9 6l6 6-6 6")
    val Spark = icon("spark", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z")
    val Refresh = icon("refresh", "M20 12a8 8 0 1 1-2.4-5.7", "M20 4v5h-5")
    val Download = icon("download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
}
