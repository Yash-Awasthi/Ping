package com.ping.app.ui.contacts

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ping.app.model.Contact
import java.io.File

/** Writes [contacts] to one .vcf in the cache and opens the share sheet for it. */
fun shareVCards(context: Context, contacts: List<Contact>) {
    runCatching {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "Ping-contacts.vcf")
        file.writeText(Contact.toVCards(contacts))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/x-vcard")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }
}
