package com.example.ninthwardcanvas

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PinCategoryAdapter(
    private var categories: List<PinCategory>,
    private val onDeleteClick: (PinCategory) -> Unit
) : RecyclerView.Adapter<PinCategoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiText: TextView = view.findViewById(R.id.text_emoji)
        val nameText: TextView = view.findViewById(R.id.text_name)
        val deleteButton: Button = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pin_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cat = categories[position]
        holder.emojiText.text = cat.emoji
        holder.nameText.text = cat.name
        holder.deleteButton.setOnClickListener { onDeleteClick(cat) }
    }

    override fun getItemCount() = categories.size

    fun updateData(newCategories: List<PinCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }
}
