package com.cleanroom.stadiumdsp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 100% Clean-Room lightweight Compose vector icons replacing the 44 MB `material-icons-extended` jar.
 * Cuts APK size by ~45 MB and accelerates cold-start DEX loading on mobile devices.
 */
object StadiumIcons {
    private val fill = SolidColor(Color.White)

    val Camera: ImageVector by lazy {
        icon("Camera") {
            // Camera body + lens ring
            path(stroke = fill, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 8f); lineTo(7f, 8f); lineTo(9f, 5.5f); lineTo(15f, 5.5f); lineTo(17f, 8f); lineTo(21f, 8f)
                lineTo(21f, 18.5f); lineTo(3f, 18.5f); close()
            }
            path(stroke = fill, strokeLineWidth = 1.9f) {
                moveTo(15.5f, 13f)
                arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 13f)
                arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 13f)
            }
        }
    }

    val LockOpen: ImageVector by lazy {
        icon("LockOpen") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 11f); lineTo(19f, 11f); lineTo(19f, 20f); lineTo(5f, 20f); close()
                moveTo(8f, 11f); lineTo(8f, 7f)
                arcTo(4f, 4f, 0f, false, true, 15.5f, 5.5f)
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            path(fill = fill) {
                moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
                moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
            }
        }
    }

    val Undo: ImageVector by lazy {
        icon("Undo") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 7f); lineTo(4f, 11f); lineTo(9f, 15f)
                moveTo(4f, 11f); lineTo(14.5f, 11f)
                arcTo(5f, 5f, 0f, false, true, 14.5f, 20f)
                lineTo(11f, 20f)
            }
        }
    }

    val Redo: ImageVector by lazy {
        icon("Redo") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 7f); lineTo(20f, 11f); lineTo(15f, 15f)
                moveTo(20f, 11f); lineTo(9.5f, 11f)
                arcTo(5f, 5f, 0f, false, false, 9.5f, 20f)
                lineTo(13f, 20f)
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        icon("VolumeUp") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12.5f, 5.5f); lineTo(12.5f, 18.5f); lineTo(7.5f, 14.5f); lineTo(3f, 14.5f); close()
                moveTo(16f, 9f); quadTo(18.5f, 12f, 16f, 15f)
                moveTo(18.5f, 6.5f); quadTo(22.5f, 12f, 18.5f, 17.5f)
            }
        }
    }

    val VolumeOff: ImageVector by lazy {
        icon("VolumeOff") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12.5f, 5.5f); lineTo(12.5f, 18.5f); lineTo(7.5f, 14.5f); lineTo(3f, 14.5f); close()
                moveTo(16f, 9f); lineTo(21f, 15f)
                moveTo(21f, 9f); lineTo(16f, 15f)
            }
        }
    }

    val FolderOpen: ImageVector by lazy {
        icon("FolderOpen") {
            path(stroke = fill, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 6f); lineTo(9.5f, 6f); lineTo(11.5f, 8.5f); lineTo(21f, 8.5f)
                lineTo(19.5f, 18.5f); lineTo(4.5f, 18.5f); close()
            }
        }
    }

    val FocusEq: ImageVector by lazy {
        icon("FocusEq") {
            path(stroke = fill, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 10f); lineTo(4f, 14f)
                moveTo(8f, 6f); lineTo(8f, 18f)
                moveTo(12f, 3.5f); lineTo(12f, 20.5f)
                moveTo(16f, 7f); lineTo(16f, 17f)
                moveTo(20f, 10f); lineTo(20f, 14f)
            }
        }
    }

    val Presets: ImageVector by lazy {
        icon("Presets") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 18f); lineTo(15f, 18f)
            }
        }
    }

    val Tuner: ImageVector by lazy {
        icon("Tuner") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(5f, 18f); arcTo(7f, 7f, 0f, false, true, 19f, 18f)
                moveTo(12f, 18f); lineTo(16f, 9f)
                moveTo(11f, 18f); lineTo(13f, 18f)
            }
        }
    }

    val Power: ImageVector by lazy {
        icon("Power") {
            path(stroke = fill, strokeLineWidth = 2.1f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 3.5f); lineTo(12f, 11.5f)
                moveTo(16.6f, 6.2f)
                arcTo(7.5f, 7.5f, 0f, true, true, 7.4f, 6.2f)
            }
        }
    }

    val Sliders: ImageVector by lazy {
        icon("Sliders") {
            path(stroke = fill, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 6.5f); lineTo(20f, 6.5f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17.5f); lineTo(20f, 17.5f)
                moveTo(9f, 4.5f); lineTo(9f, 8.5f)
                moveTo(15f, 10f); lineTo(15f, 14f)
                moveTo(10.5f, 15.5f); lineTo(10.5f, 19.5f)
            }
        }
    }

    val Clock: ImageVector by lazy {
        icon("Clock") {
            path(stroke = fill, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, true, true, 3.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, true, true, 20.5f, 12f)
                moveTo(12f, 7.2f); lineTo(12f, 12.2f); lineTo(15.5f, 14.2f)
            }
        }
    }

    val Info: ImageVector by lazy {
        icon("Info") {
            path(stroke = fill, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(20.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, true, true, 3.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, true, true, 20.5f, 12f)
                moveTo(12f, 10.8f); lineTo(12f, 16.5f)
                moveTo(12f, 7.6f); lineTo(12f, 7.8f)
            }
        }
    }

    val Star: ImageVector by lazy {
        icon("Star") {
            path(stroke = fill, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3.5f); lineTo(14.6f, 8.8f); lineTo(20.5f, 9.6f); lineTo(16.2f, 13.8f)
                lineTo(17.2f, 19.6f); lineTo(12f, 16.8f); lineTo(6.8f, 19.6f); lineTo(7.8f, 13.8f)
                lineTo(3.5f, 9.6f); lineTo(9.4f, 8.8f); close()
            }
        }
    }

    val CloseX: ImageVector by lazy {
        icon("CloseX") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    val GridView: ImageVector by lazy {
        icon("GridView") {
            path(fill = fill) {
                moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close()
                moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
                moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close()
                moveTo(13.5f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 20f); lineTo(13.5f, 20f); close()
            }
        }
    }

    val ListView: ImageVector by lazy {
        icon("ListView") {
            path(stroke = fill, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(5f, 6.5f); lineTo(19f, 6.5f)
                moveTo(5f, 12f); lineTo(19f, 12f)
                moveTo(5f, 17.5f); lineTo(19f, 17.5f)
            }
        }
    }

    val GuitarPick: ImageVector by lazy {
        icon("GuitarPick") {
            path(stroke = fill, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 5.5f)
                quadTo(12f, 3.2f, 17.5f, 5.5f)
                quadTo(20f, 11.5f, 12f, 20f)
                quadTo(4f, 11.5f, 6.5f, 5.5f)
                close()
            }
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()
}
