package com.example.ninthwardcanvas

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DatabaseAdapter(
    private var items: List<MapItem>,
    private val onRouteClick: (MapItem) -> Unit
) : RecyclerView.Adapter<DatabaseAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val addressText: TextView = view.findViewById(R.id.text_address)
        val detailsText: TextView = view.findViewById(R.id.text_details)
        val routeButton: Button = view.findViewById(R.id.btn_route)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_database, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.addressText.text = item.prop.address
        holder.detailsText.text = "Status: ${item.prop.status}\nDeadline: ${item.prop.deadline}"
        holder.routeButton.setOnClickListener { onRouteClick(item) }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<MapItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
