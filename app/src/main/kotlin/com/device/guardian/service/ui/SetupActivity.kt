package com.device.guardian.service.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.device.guardian.service.databinding.ActivitySetupBinding
import com.device.guardian.service.service.GuardianAccessibilityService

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: SharedPreferences

    private val requestLocationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Toast.makeText(this, "Location permission granted ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
        refreshAllStatuses()
    }

    companion object {
        private const val PREFS_NAME = "gd_prefs"
        private const val KEY_PARENT_ID = "pid"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupButtons()
        restoreSavedParentId()
    }

    override fun onResume() {
        super.onResume()
        // Refresh all status indicators every time user returns from settings
        refreshAllStatuses()
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun setupButtons() {

        // Step 1 — Save Parent ID
        binding.btnSaveParentId.setOnClickListener {
            val id = binding.etParentId.text.toString().trim()
            if (id.length < 4) {
                Toast.makeText(this, "Parent ID must be at least 4 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_PARENT_ID, id).apply()
            Toast.makeText(this, "Parent ID saved ✓", Toast.LENGTH_SHORT).show()
            refreshAllStatuses()
        }

        // Step 2 — Open Accessibility Settings
        binding.btnEnableAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Find 'System Accessibility Helper' and enable it",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                // Fallback for some OEMs
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // Step 3 — Battery Optimization
        binding.btnBatteryOptimization.setOnClickListener {
            if (isBatteryOptimizationDisabled()) {
                Toast.makeText(this, "Already configured ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback — open battery settings manually
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Find this app and select 'Unrestricted'",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Step 4 — Request Location Permission
        binding.btnEnableLocation.setOnClickListener {
            if (isLocationPermissionGranted()) {
                Toast.makeText(this, "Permission already granted ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun restoreSavedParentId() {
        val saved = prefs.getString(KEY_PARENT_ID, "")
        if (!saved.isNullOrBlank()) {
            binding.etParentId.setText(saved)
        }
    }

    // ── Status Refresh ─────────────────────────────────────────────────────────

    private fun refreshAllStatuses() {
        updateStep1Status()
        updateStep2Status()
        updateStep3Status()
        updateStep4Status()
        updateFinalBanner()
    }

    private fun updateStep1Status() {
        val saved = prefs.getString(KEY_PARENT_ID, "")
        val done = !saved.isNullOrBlank()
        binding.tvStep1Status.text = if (done) "✅ Done" else "⬜ Pending"
        binding.tvStep1Status.setTextColor(
            getColor(if (done) android.R.color.holo_green_dark else android.R.color.darker_gray)
        )
    }

    private fun updateStep2Status() {
        val active = isAccessibilityServiceEnabled()
        binding.tvStep2Status.text = if (active) "✅ Active" else "⬜ Pending"
        binding.tvStep2Status.setTextColor(
            getColor(if (active) android.R.color.holo_green_dark else android.R.color.darker_gray)
        )
        binding.btnEnableAccessibility.text =
            if (active) "Service Active ✓" else "Open Accessibility Settings"
        binding.btnEnableAccessibility.isEnabled = !active
    }

    private fun updateStep3Status() {
        val optimized = isBatteryOptimizationDisabled()
        binding.tvStep3Status.text = if (optimized) "✅ Done" else "⬜ Pending"
        binding.tvStep3Status.setTextColor(
            getColor(if (optimized) android.R.color.holo_green_dark else android.R.color.darker_gray)
        )
        binding.btnBatteryOptimization.text =
            if (optimized) "Battery Configured ✓" else "Disable Battery Optimization"
        binding.btnBatteryOptimization.isEnabled = !optimized
    }

    private fun updateStep4Status() {
        val granted = isLocationPermissionGranted()
        binding.tvStep4Status.text = if (granted) "✅ Done" else "⬜ Pending"
        binding.tvStep4Status.setTextColor(
            getColor(if (granted) android.R.color.holo_green_dark else android.R.color.darker_gray)
        )
        binding.btnEnableLocation.text =
            if (granted) "Location Configured ✓" else "Grant Location Access"
        binding.btnEnableLocation.isEnabled = !granted
    }

    private fun updateFinalBanner() {
        val allDone = isParentIdSaved() &&
                      isAccessibilityServiceEnabled() &&
                      isBatteryOptimizationDisabled() &&
                      isLocationPermissionGranted()

        if (allDone) {
            binding.tvFinalStatus.text = "✅ Monitoring Active"
            binding.tvFinalSubtext.text = "All steps complete — service is running"
            binding.cardStatus.setCardBackgroundColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvFinalStatus.text = "⚠ Setup Incomplete"
            binding.tvFinalSubtext.text = "Complete all 4 steps above"
            binding.cardStatus.setCardBackgroundColor(
                resources.getColor(android.R.color.black, theme)
            )
        }
    }

    // ── Checks ─────────────────────────────────────────────────────────────────

    private fun isParentIdSaved(): Boolean {
        return !prefs.getString(KEY_PARENT_ID, "").isNullOrBlank()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${packageName}/${GuardianAccessibilityService::class.java.canonicalName}"
        return enabledServices.contains(serviceName, ignoreCase = true)
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isLocationPermissionGranted(): Boolean {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
               checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
