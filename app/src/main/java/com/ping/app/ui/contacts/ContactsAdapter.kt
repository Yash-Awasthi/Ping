package com.ping.app.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ping.app.R
import com.ping.app.databinding.ItemContactBinding
import com.ping.app.model.Contact

class ContactsAdapter(
    private val onContactClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: Contact) {
            val ctx = binding.root.context
            val name = contact.displayName.ifBlank { ctx.getString(R.string.contact_unknown_name) }
            binding.tvName.text = name
            binding.tvSubtitle.text = listOf(contact.phone, contact.email, contact.social)
                .firstOrNull { it.isNotBlank() }.orEmpty()
            binding.tvInitials.text = name.split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifBlank { "?" }
            binding.root.setOnClickListener { onContactClick(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object DiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(old: Contact, new: Contact) = old.id == new.id
        override fun areContentsTheSame(old: Contact, new: Contact) = old == new
    }
}
