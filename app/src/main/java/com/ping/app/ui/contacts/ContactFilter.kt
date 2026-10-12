package com.ping.app.ui.contacts

import com.ping.app.model.Contact

/** Case-insensitive match on name, phone, email, social, note and memo; favourites sort first. */
fun filterContacts(all: List<Contact>, query: String): List<Contact> {
    val q = query.trim()
    val hits = if (q.isEmpty()) all else all.filter { c ->
        listOf(c.displayName, c.phone, c.email, c.social, c.note, c.memo).any { it.contains(q, ignoreCase = true) }
    }
    return hits.sortedWith(compareByDescending<Contact> { it.isFavorite }.thenByDescending { it.receivedAt })
}
