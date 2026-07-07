package com.ping.app.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ping.app.R
import com.ping.app.databinding.BottomSheetContactDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Full contact detail with call / email / copy / delete / favourite actions. */
@AndroidEntryPoint
class ContactDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetContactDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ContactDetailViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetContactDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val contactId = arguments?.getString(ARG_CONTACT_ID) ?: return
        viewModel.loadContact(contactId)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.contact.collect { c ->
                    c ?: return@collect
                    binding.tvName.text = c.displayName.ifBlank {
                        getString(R.string.contact_unknown_name)
                    }
                    binding.tvDetails.text = buildList {
                        if (c.phone.isNotBlank()) add(getString(R.string.contact_label_phone) + ": " + c.phone)
                        if (c.email.isNotBlank()) add(getString(R.string.contact_label_email) + ": " + c.email)
                        if (c.social.isNotBlank()) add(getString(R.string.contact_label_social) + ": " + c.social)
                        if (c.note.isNotBlank()) add(getString(R.string.contact_label_note) + ": " + c.note)
                    }.joinToString("\n")

                    binding.btnCall.isEnabled = c.phone.isNotBlank()
                    binding.btnEmail.isEnabled = c.email.isNotBlank()

                    binding.btnCall.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")))
                    }
                    binding.btnEmail.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${c.email}")))
                    }
                    binding.btnCopy.setOnClickListener {
                        val text = listOf(c.displayName, c.phone, c.email, c.social, c.note)
                            .filter { it.isNotBlank() }.joinToString("\n")
                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Contact", text))
                        Toast.makeText(requireContext(), R.string.contact_copied, Toast.LENGTH_SHORT).show()
                    }
                    binding.btnDelete.setOnClickListener {
                        viewModel.deleteContact(c)
                        dismiss()
                    }
                    binding.btnFavourite.setImageResource(
                        if (c.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border
                    )
                    binding.btnFavourite.setOnClickListener { viewModel.toggleFavorite(c) }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_CONTACT_ID = "contact_id"
        fun newInstance(contactId: String) = ContactDetailBottomSheet().apply {
            arguments = bundleOf(ARG_CONTACT_ID to contactId)
        }
    }
}
