package com.hereliesaz.redblightdistrict.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hereliesaz.redblightdistrict.FilterManager
import com.hereliesaz.redblightdistrict.FilterState
import com.hereliesaz.redblightdistrict.MapItem
import com.hereliesaz.redblightdistrict.PinCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersDrawerDialog(
    filterManager: FilterManager,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    // Snapshot of the editable state. We mutate filterManager.state directly on Apply.
    val state = filterManager.state
    var residential by remember { mutableStateOf("residential" in state.propertyTypes) }
    var commercial by remember { mutableStateOf("commercial" in state.propertyTypes) }
    var mixed by remember { mutableStateOf("mixed" in state.propertyTypes) }
    var vacant by remember { mutableStateOf("vacant" in state.propertyTypes) }
    var deadlineWindow by remember { mutableStateOf(state.deadlineWindow) }
    var hasStructure by remember { mutableStateOf(state.hasStructure) }
    var activeRehab by remember { mutableStateOf(state.activeRehab) }
    var cluster by remember { mutableStateOf(state.cluster) }
    var demoStatus by remember { mutableStateOf(state.demoStatus) }
    var daysBlightedMin by remember { mutableStateOf(state.daysBlightedMin.toFloat()) }
    var permitsMin by remember { mutableStateOf(state.permitsMin.toFloat()) }
    var repeatOnly by remember { mutableStateOf(state.repeatOnly) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack)
                .padding(16.dp),
        ) {
            Text("FILTERS", color = Amber, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(12.dp))

            FilterHeader("Property type")
            Row {
                FilterToggle(residential, "Residential") { residential = it }
                FilterToggle(commercial, "Commercial") { commercial = it }
            }
            Row {
                FilterToggle(mixed, "Mixed-use") { mixed = it }
                FilterToggle(vacant, "Vacant lot") { vacant = it }
            }
            Spacer(Modifier.height(8.dp))

            FilterHeader("Deadline window")
            SingleChipRow(
                options = listOf("all" to "All", "<7" to "<7d", "7-14" to "7-14d", ">14" to ">14d", "expired" to "Expired"),
                selected = deadlineWindow,
                onSelected = { deadlineWindow = it },
            )

            FilterHeader("Has structure")
            SingleChipRow(
                options = listOf("yes" to "Yes", "no" to "No", "both" to "Both"),
                selected = hasStructure,
                onSelected = { hasStructure = it },
            )

            FilterHeader("Days under blight (min): ≥ ${daysBlightedMin.toInt()}")
            Slider(value = daysBlightedMin, onValueChange = { daysBlightedMin = it }, valueRange = 0f..3650f)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = repeatOnly, onCheckedChange = { repeatOnly = it })
                Text("Repeat offender (≥2 cases)", color = Amber)
            }

            FilterHeader("Active rehab")
            SingleChipRow(
                options = listOf("exclude" to "Exclude", "all" to "All", "only" to "Only"),
                selected = activeRehab,
                onSelected = { activeRehab = it },
            )

            FilterHeader("Cluster")
            SingleChipRow(
                options = listOf("solo" to "Solo", "cluster" to "Cluster", "all" to "All"),
                selected = cluster,
                onSelected = { cluster = it },
            )

            FilterHeader("Demolition")
            SingleChipRow(
                options = listOf("all" to "All", "pending" to "Pending", "permitted" to "Permitted", "completed" to "Completed", "none" to "None"),
                selected = demoStatus,
                onSelected = { demoStatus = it },
            )

            FilterHeader("Recent permits (min in 365d): ≥ ${permitsMin.toInt()}")
            Slider(value = permitsMin, onValueChange = { permitsMin = it }, valueRange = 0f..10f, steps = 9)

            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(
                    onClick = {
                        // Reset edits to defaults via filterManager.applyPreset("all"),
                        // then mirror the new state back into local UI state.
                        filterManager.applyPreset("all")
                        residential = true; commercial = true; mixed = true; vacant = true
                        deadlineWindow = "all"; hasStructure = "both"; activeRehab = "all"
                        cluster = "all"; demoStatus = "all"
                        daysBlightedMin = 0f; permitsMin = 0f; repeatOnly = false
                        onReset()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Reset") }

                Button(
                    onClick = {
                        state.propertyTypes.clear()
                        if (residential) state.propertyTypes.add("residential")
                        if (commercial) state.propertyTypes.add("commercial")
                        if (mixed) state.propertyTypes.add("mixed")
                        if (vacant) state.propertyTypes.add("vacant")
                        state.deadlineWindow = deadlineWindow
                        state.hasStructure = hasStructure
                        state.activeRehab = activeRehab
                        state.cluster = cluster
                        state.demoStatus = demoStatus
                        state.daysBlightedMin = daysBlightedMin.toInt()
                        state.permitsMin = permitsMin.toInt()
                        state.repeatOnly = repeatOnly
                        onApply()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                    modifier = Modifier.weight(1f),
                ) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun FilterHeader(text: String) {
    Text(
        text,
        color = Amber,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterToggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Amber,
            selectedLabelColor = Color.Black,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleChipRow(options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Row {
        options.forEach { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelected(id) },
                label = { Text(label) },
                modifier = Modifier.padding(end = 6.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Amber,
                    selectedLabelColor = Color.Black,
                ),
            )
        }
    }
}

@Composable
fun PropertyListDialog(
    title: String,
    items: List<MapItem>,
    onDismiss: () -> Unit,
    onRouteClick: (Double, Double) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = DimText, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close", color = Blight) }
            }
            Divider(color = Charcoal)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRouteClick(item.lat, item.lng) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(item.prop.address, color = DimText)
                        Text(
                            "${item.prop.status} • Deadline: ${item.prop.deadline}",
                            color = if (item.prop.status == "Guilty") Blight else Amber,
                        )
                    }
                    Divider(color = Charcoal)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinCategoriesDialog(
    categories: List<PinCategory>,
    onAdd: (PinCategory) -> Unit,
    onDelete: (PinCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    var emoji by remember { mutableStateOf("📍") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NearBlack,
        title = { Text("📂 Pin categories", color = Amber) },
        text = {
            Column {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(categories) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cat.emoji, modifier = Modifier.width(36.dp))
                            Text(cat.name, color = DimText, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDelete(cat) }) {
                                Text("×", color = Blight)
                            }
                        }
                        Divider(color = Charcoal)
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it.take(4) },
                        modifier = Modifier.width(72.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = DimText, textAlign = TextAlign.Center),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Category name") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions.Default,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (emoji.isNotBlank() && name.isNotBlank()) {
                                onAdd(PinCategory(emoji.trim(), name.trim()))
                                emoji = "📍"; name = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = DimText) }
        },
    )
}

@Composable
fun QrDialog(bitmap: Bitmap?, onDismiss: () -> Unit) {
    if (bitmap == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = null,
        text = {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Beam intel QR",
                modifier = Modifier.size(256.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close Link", color = Color.Black) }
        },
    )
}

@Composable
fun CamoOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF87CEEB))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.padding(top = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("New Orleans", color = Color.White, fontSize = androidx.compose.material3.MaterialTheme.typography.headlineMedium.fontSize)
            Text("72°", color = Color.White, fontSize = androidx.compose.material3.MaterialTheme.typography.displayLarge.fontSize)
            Text("Partly Cloudy", color = Color.White, fontSize = androidx.compose.material3.MaterialTheme.typography.headlineSmall.fontSize)
            Text("H:78° L:65°", color = Color.White, fontSize = androidx.compose.material3.MaterialTheme.typography.headlineSmall.fontSize)
        }
    }
}
