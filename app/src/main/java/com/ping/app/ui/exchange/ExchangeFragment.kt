package com.ping.app.ui.exchange

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ping.app.R
import com.ping.app.auth.GestureCamera
import com.ping.app.databinding.FragmentExchangeBinding
import com.ping.app.model.ExchangeSession
import com.ping.app.service.NearbyExchangeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Active swap screen.
 *
 * 1. Front camera runs [GestureCamera]; when a gesture code locks, we stop the
 *    camera and hand that code to [NearbyExchangeService].
 * 2. The service advertises the code and connects only to a nearby phone whose
 *    gesture code matches, then swaps cards.
 * 3. On COMPLETED we show the received contact and offer to view Contacts.
 */
@AndroidEntryPoint
class ExchangeFragment : Fragment() {

    private var _binding: FragmentExchangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExchangeViewModel by viewModels()

    /** True once we've locked a gesture and started the service. */
    private var searchStarted = false
    private var successShown = false
    private var countdownJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        NearbyExchangeService.clearSession()
        binding.tvStatus.setText(R.string.exchange_show_gesture)

        binding.btnCancel.setOnClickListener { cancelAndLeave() }
        binding.btnRetry.setOnClickListener { restartCapture() }

        viewModel.startCamera(viewLifecycleOwner, binding.gesturePreview)
        observeCamera()
        observeSession()
    }

    /** Reset back to the live-camera capture state after a no-match / error. */
    private fun restartCapture() {
        NearbyExchangeService.stop(requireContext())
        NearbyExchangeService.clearSession()
        countdownJob?.cancel()
        searchStarted = false
        successShown = false
        binding.btnRetry.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.pbStability.progress = 0
        binding.tvGestureCode.visibility = View.INVISIBLE
        binding.tvStatus.setText(R.string.exchange_show_gesture)
        viewModel.resetCamera()
        viewModel.startCamera(viewLifecycleOwner, binding.gesturePreview)
    }

    private fun observeCamera() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cameraState.collect { state ->
                    if (searchStarted) return@collect
                    when (state) {
                        is GestureCamera.State.NoHand -> {
                            binding.pbStability.progress = 0
                            binding.tvGestureCode.visibility = View.INVISIBLE
                            binding.tvStatus.setText(R.string.gesture_no_hand)
                        }
                        is GestureCamera.State.Detecting -> {
                            binding.pbStability.progress = (state.stability * 100).toInt()
                            binding.tvGestureCode.text = state.fingerprint.label
                            binding.tvGestureCode.visibility = View.VISIBLE
                            binding.tvStatus.text = getString(
                                R.string.gesture_detecting,
                                state.fingerprint.label,
                                (state.stability * 100).toInt()
                            )
                        }
                        is GestureCamera.State.Locked -> {
                            binding.pbStability.progress = 100
                            binding.tvGestureCode.text = state.fingerprint.label
                            binding.tvGestureCode.visibility = View.VISIBLE
                            onGestureLocked(state.fingerprint.code, state.fingerprint.label)
                        }
                        is GestureCamera.State.ModelError -> {
                            binding.pbStability.progress = 0
                            binding.tvStatus.setText(R.string.gesture_model_error)
                        }
                    }
                }
            }
        }
    }

    private fun onGestureLocked(code: String, label: String) {
        searchStarted = true
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        binding.tvStatus.text = getString(R.string.exchange_gesture_locked, label)
        viewModel.stopCamera()
        NearbyExchangeService.start(requireContext(), code)
    }

    private fun observeSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.session.collect { session ->
                    session ?: return@collect
                    updateUi(session)
                }
            }
        }
    }

    private fun updateUi(session: ExchangeSession) {
        // Countdown ticks only while SEARCHING; any other state cancels it.
        if (session.state == ExchangeSession.State.SEARCHING) {
            startCountdown()
        } else {
            countdownJob?.cancel()
        }

        val showRetry = session.state == ExchangeSession.State.NO_MATCH ||
            session.state == ExchangeSession.State.ERROR
        binding.btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE

        val (text, spinning) = when (session.state) {
            ExchangeSession.State.SEARCHING -> return          // text driven by countdown
            ExchangeSession.State.CONNECTING -> getString(R.string.status_connecting) to true
            ExchangeSession.State.EXCHANGING -> getString(R.string.status_exchanging) to true
            ExchangeSession.State.COMPLETED -> {
                val name = session.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.someone)
                getString(R.string.exchange_completed, name) to false
            }
            ExchangeSession.State.NO_MATCH -> getString(R.string.exchange_no_match) to false
            ExchangeSession.State.CANCELLED -> getString(R.string.exchange_cancelled) to false
            ExchangeSession.State.ERROR -> getString(R.string.exchange_error_generic) to false
        }
        binding.tvStatus.text = text
        binding.progressBar.visibility = if (spinning) View.VISIBLE else View.GONE

        if (session.state == ExchangeSession.State.COMPLETED && !successShown) {
            successShown = true
            binding.btnCancel.setText(R.string.action_done)
            binding.btnCancel.setOnClickListener {
                findNavController().navigate(R.id.action_exchange_to_contacts)
            }
            val id = session.receivedContact?.id
            if (id != null) {
                ExchangeSuccessBottomSheet.newInstance(id).apply {
                    onClose = { findNavController().popBackStack(R.id.homeFragment, false) }
                }.show(childFragmentManager, ExchangeSuccessBottomSheet.TAG)
            }
        }
    }

    /** Show a live countdown of the pairing window while searching. */
    private fun startCountdown() {
        if (countdownJob?.isActive == true) return
        binding.progressBar.visibility = View.VISIBLE
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            var remaining = NearbyExchangeService.WINDOW_SECONDS
            while (isActive && remaining > 0) {
                binding.tvStatus.text =
                    getString(R.string.exchange_waiting_peer_countdown, remaining)
                delay(1000)
                remaining--
            }
        }
    }

    private fun cancelAndLeave() {
        countdownJob?.cancel()
        viewModel.stopCamera()
        NearbyExchangeService.stop(requireContext())
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        viewModel.stopCamera()
        super.onDestroyView()
        _binding = null
    }
}
