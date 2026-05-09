package com.example.ninthwardcanvas

import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import android.view.View
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow

class PinInfoWindow(
    mapView: MapView,
    private val pin: UserPin,
    private val storageManager: StorageManager,
    private val onPinUpdate: () -> Unit,
    private val onPinDelete: (String) -> Unit
) : InfoWindow(R.layout.custom_pin_info_window, mapView) {

    override fun onOpen(itemOverlay: Any?) {
        val marker = itemOverlay as? Marker ?: return

        val cats = storageManager.loadPinCategories()
        val cat = cats.find { it.name == pin.category } ?: cats.firstOrNull()

        mView.findViewById<TextView>(R.id.pin_info_title).text = "${cat?.emoji ?: "📍"} ${pin.category}"

        val editTitle = mView.findViewById<EditText>(R.id.pin_edit_title)
        editTitle.setText(pin.title)
        editTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pin.title = s.toString()
                savePin()
            }
        })

        val editNote = mView.findViewById<EditText>(R.id.pin_edit_note)
        editNote.setText(pin.note)
        editNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pin.note = s.toString()
                savePin()
            }
        })

        val spinner = mView.findViewById<Spinner>(R.id.pin_spinner_category)
        val catNames = cats.map { "${it.emoji} ${it.name}" }
        val adapter = ArrayAdapter(mView.context, android.R.layout.simple_spinner_item, catNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val selectedIdx = cats.indexOfFirst { it.name == pin.category }
        if (selectedIdx >= 0) spinner.setSelection(selectedIdx)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (pin.category != cats[position].name) {
                    pin.category = cats[position].name
                    mView.findViewById<TextView>(R.id.pin_info_title).text = "${cats[position].emoji} ${pin.category}"
                    savePin()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        mView.findViewById<Button>(R.id.pin_btn_delete).setOnClickListener {
            onPinDelete(pin.id)
            close()
        }
    }

    private fun savePin() {
        val pins = storageManager.loadUserPins().toMutableList()
        val idx = pins.indexOfFirst { it.id == pin.id }
        if (idx >= 0) {
            pins[idx] = pin
            storageManager.saveUserPins(pins)
            onPinUpdate()
        }
    }

    override fun onClose() {
    }
}
