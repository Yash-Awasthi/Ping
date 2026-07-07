package com.ping.app.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ping.app.R
import com.ping.app.databinding.FragmentProfileBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Prefill from the saved card once.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val profile = viewModel.profile.first()
                profile ?: return@repeatOnLifecycle
                binding.etName.setText(profile.displayName)
                binding.etPhone.setText(profile.phone)
                binding.etEmail.setText(profile.email)
                binding.etSocial.setText(profile.social)
                binding.etNote.setText(profile.note)
            }
        }

        binding.btnSave.setOnClickListener {
            val name = binding.etName.text?.toString().orEmpty().trim()
            if (name.isBlank()) {
                Toast.makeText(requireContext(), R.string.profile_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.save(
                name = name,
                phone = binding.etPhone.text?.toString().orEmpty(),
                email = binding.etEmail.text?.toString().orEmpty(),
                social = binding.etSocial.text?.toString().orEmpty(),
                note = binding.etNote.text?.toString().orEmpty(),
            )
            Toast.makeText(requireContext(), R.string.profile_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
