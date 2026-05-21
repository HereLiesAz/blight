package com.hereliesaz.redblightdistrict.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.redblightdistrict.FilterManager
import com.hereliesaz.redblightdistrict.MapManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.MapView

@Composable
fun MapHost(
    mapView: MapView,
    onMapMoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = {
            mapView.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { onMapMoved(); return false }
                override fun onZoom(event: ZoomEvent?): Boolean { onMapMoved(); return false }
            })
            mapView
        },
        modifier = modifier.fillMaxSize(),
    )

    // Pause / resume the MapView with the composition lifecycle.
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }
}

@Composable
fun DataFreshnessBadge(newest: Date?, modifier: Modifier = Modifier) {
    val empty = newest == null
    val borderColor = if (empty) Color(0xFF444444) else Cyan
    val textColor = if (empty) MutedText else Cyan
    val label = if (empty) {
        "DATA: —"
    } else {
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        "NEWEST NOTICE: ${fmt.format(newest).uppercase(Locale.getDefault())}"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xD90B0B0B))
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = androidx.compose.material3.MaterialTheme.typography.bodySmall.fontSize,
        )
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(text, color = Color.Black, fontFamily = FontFamily.Monospace)
    }
}
