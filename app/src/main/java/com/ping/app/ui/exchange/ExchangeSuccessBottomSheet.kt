package com.ping.app.ui.exchange

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ping.app.R
import com.ping.app.databinding.BottomSheetExchangeSuccessBinding
import com.ping.app.ui.contacts.ContactDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Shown right after a successful swap: the received card, one tap to close. */
@AndroidEntryPoint
class ExchangeSuccessBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetExchangeSuccessBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContactDetailViewModel by viewModels()

    var onClose: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExchangeSuccessBinding.inflate(inflater, container, false)
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
                    binding.tvDetails.text = listOf(c.phone, c.email, c.social, c.note)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
            }
        }

        binding.btnClose.setOnClickListener {
            dismiss()
            onClose?.invoke()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ExchangeSuccessBottomSheet"
        private const val ARG_CONTACT_ID = "contact_id"

        fun newInstance(contactId: String) = ExchangeSuccessBottomSheet().apply {
            arguments = bundleOf(ARG_CONTACT_ID to contactId)
        }
    }
}
