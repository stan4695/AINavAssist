package com.stan4695.ainavigationassist.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.stan4695.ainavigationassist.databinding.FragmentSettingsBinding
import com.stan4695.ainavigationassist.settings.SettingsManager


class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        loadCurrentSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun loadCurrentSettings() {
        val context = requireContext()

        binding.switchGpu.isChecked = SettingsManager.isGpuAccelerationEnabled(requireContext())

        binding.switchTts.isChecked = SettingsManager.isTtsEnabled(context)

        binding.switchHaptic.isChecked = SettingsManager.isHapticEnabled(context)

        val sensitivity = SettingsManager.getDetectionSensitivity(context)
        // Inversam valorile seekbar-ului corespunzator sensibilitatii pentru a face aplicatia mai user-friendly
        val progress = ((1 - sensitivity) * 100).toInt()
        binding.seekbarSensitivity.progress = progress
        updateSensitivityLabel(sensitivity)
    }

    private fun setupListeners() {
        val context = requireContext()

        binding.switchGpu.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setGpuAccelerationEnabled(requireContext(), isChecked)
            Toast.makeText(requireContext(), "GPU Acceleration: ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.switchTts.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setTtsEnabled(context, isChecked)
            Toast.makeText(requireContext(), "TTS: ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setHapticEnabled(context, isChecked)
            Toast.makeText(requireContext(), "Haptic Feedback: ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.seekbarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Inversam valorile seekbar-ului pentru a obtine valorea reala
                val actualSensitivity = 1 - (progress / 100f)
                updateSensitivityLabel(actualSensitivity)

                // Previne schimbarea valorii in momentul pornirii aplicatiei
                if (fromUser) {
                    SettingsManager.setDetectionSensitivity(context, actualSensitivity)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    @SuppressLint("DefaultLocale", "SetTextI18n")
    private fun updateSensitivityLabel(sensitivity: Float) {
        // Sensibilitatea este invers proportionala cu treshold-ul de confidenta
        // Sensibilitate = 1 - treshold
        // Cu cat sensibilitatea este mai mica, cu atat treshold-ul este ridicat si prin urmare vor fi detectate mai putine obiecte
        val detectionSensitivity = when {
            sensitivity < 0.5f -> "Very High"
            sensitivity < 0.6f -> "High"
            sensitivity < 0.8f -> "Medium"
            sensitivity < 0.9f -> "Low"
            else -> "Very Low"
        }

        binding.tvSensitivityValue.text = "$detectionSensitivity (threshold: ${String.format("%.2f", sensitivity)})"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}