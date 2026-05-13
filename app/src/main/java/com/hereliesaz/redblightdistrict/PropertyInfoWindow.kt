package com.hereliesaz.redblightdistrict

import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow

class PropertyInfoWindow(
    mapView: MapView,
    private val item: MapItem,
    private val storageManager: StorageManager,
    private val onRouteClick: (Double, Double) -> Unit,
    private val onOpticsClick: (String) -> Unit
) : InfoWindow(R.layout.custom_info_window, mapView) {

    override fun onOpen(itemOverlay: Any?) {
        val marker = itemOverlay as? Marker ?: return

        mView.findViewById<TextView>(R.id.info_title).text = marker.title
        mView.findViewById<TextView>(R.id.info_snippet).text = marker.snippet

        val editNote = mView.findViewById<EditText>(R.id.edit_note)
        editNote.setText(storageManager.getNote(item.prop.address))

        editNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                storageManager.saveNote(item.prop.address, s.toString())
            }
        })

        mView.findViewById<Button>(R.id.btn_route).setOnClickListener {
            onRouteClick(item.lat, item.lng)
        }

        mView.findViewById<Button>(R.id.btn_optics).setOnClickListener {
            onOpticsClick(item.prop.address)
        }
    }

    override fun onClose() {
    }
}
