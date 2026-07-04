package com.device.guardian.service.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.device.guardian.service.R
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.device.guardian.service.databinding.ActivitySetupBinding
import com.device.guardian.service.service.GuardianAccessibilityService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.BatteryManager
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.tasks.await

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: com.device.guardian.service.utils.PrefsManager

    private val requestLocationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val bgGranted = permissions[android.Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
        if (fineGranted || coarseGranted || bgGranted) {
            Toast.makeText(this, "Location permission granted ✓", Toast.LENGTH_SHORT).show()
            // Immediately sync actual location to Firebase
            syncStatusNowOnSetup()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
        refreshAllStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = com.device.guardian.service.utils.PrefsManager(this)

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
            if (id.length >= 4) {
                prefs.parentId = id
                Toast.makeText(this, "Parent ID Saved", Toast.LENGTH_SHORT).show()
                refreshAllStatuses()
                
                // Reset sync status of all local messages to force them to upload to the new parent ID
                val db = com.device.guardian.service.data.local.AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    try {
                        db.messageDao().resetSyncStatus()
                    } catch (e: Exception) {}
                }
                
                // Immediately sync actual battery, online status, and location to Firebase
                syncStatusNowOnSetup()
                
                // Trigger immediate sync of messages
                val repo = com.device.guardian.service.data.remote.FirebaseRepository(db.messageDao(), this)
                lifecycleScope.launch {
                    try {
                        repo.syncPending()
                        Toast.makeText(this@SetupActivity, "Linked to Parent Dashboard ✓", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SetupActivity, "Firebase Sync Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                binding.etParentId.error = "Must be at least 4 chars"
            }
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
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            )
        }

        // Step 5 — Notification Access
        binding.btnEnableNotification.setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            try {
                startActivity(intent)
                Toast.makeText(this, "Enable Guardian for Notification Access", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun restoreSavedParentId() {
        val saved = prefs.parentId
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
        updateStep5Status()
        updateFinalBanner()
    }

    private fun updateStep1Status() {
        val saved = prefs.parentId
        val done = !saved.isNullOrBlank()
        binding.tvStep1Status.text = if (done) "✅ Saved" else "⬜ Pending"
        binding.tvStep1Status.setTextColor(
            getColor(if (done) R.color.status_success else R.color.status_pending)
        )
    }

    private fun updateStep2Status() {
        val active = isAccessibilityServiceEnabled()
        binding.tvStep2Status.text = if (active) "✅ Active" else "⬜ Pending"
        binding.tvStep2Status.setTextColor(
            getColor(if (active) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableAccessibility.text =
            if (active) "Service Active ✓" else "Open Accessibility Settings"
        binding.btnEnableAccessibility.isEnabled = !active
    }

    private fun updateStep3Status() {
        val optimized = isBatteryOptimizationDisabled()
        binding.tvStep3Status.text = if (optimized) "✅ Done" else "⬜ Pending"
        binding.tvStep3Status.setTextColor(
            getColor(if (optimized) R.color.status_success else R.color.status_pending)
        )
        binding.btnBatteryOptimization.text =
            if (optimized) "Battery Configured ✓" else "Disable Battery Optimization"
        binding.btnBatteryOptimization.isEnabled = !optimized
    }

    private fun updateStep4Status() {
        val granted = isLocationPermissionGranted()
        binding.tvStep4Status.text = if (granted) "✅ Done" else "⬜ Pending"
        binding.tvStep4Status.setTextColor(
            getColor(if (granted) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableLocation.text =
            if (granted) "Location Configured ✓" else "Grant Location Access"
        binding.btnEnableLocation.isEnabled = !granted
    }

    private fun updateStep5Status() {
        val active = isNotificationServiceEnabled()
        binding.tvStep5Status.text = if (active) "✅ Active" else "⬜ Pending"
        binding.tvStep5Status.setTextColor(
            getColor(if (active) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableNotification.text =
            if (active) "Notification Capture Active ✓" else "Grant Notification Access"
        binding.btnEnableNotification.isEnabled = !active
    }

    private fun updateFinalBanner() {
        val allDone = isParentIdSaved() &&
                      isAccessibilityServiceEnabled() &&
                      isBatteryOptimizationDisabled() &&
                      isLocationPermissionGranted() &&
                      isNotificationServiceEnabled()

        if (allDone) {
            binding.tvFinalStatus.text = "✅ Monitoring Active"
            binding.tvFinalSubtext.text = "All steps complete — service is running"
            binding.cardStatus.setCardBackgroundColor(getColor(R.color.status_success))
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
        return !prefs.parentId.isNullOrBlank()
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

    private fun isNotificationServiceEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val serviceName = "${packageName}/com.device.guardian.service.service.GuardianNotificationService"
        return enabledListeners?.contains(serviceName) == true
    }

    private fun syncStatusNowOnSetup() {
        val id = prefs.parentId
        if (id.isNullOrBlank()) return
        
        val db = com.device.guardian.service.data.local.AppDatabase.getInstance(this)
        val repo = com.device.guardian.service.data.remote.FirebaseRepository(db.messageDao(), this)
        lifecycleScope.launch {
            try {
                // Get battery info
                val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = registerReceiver(null, batteryFilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

                // Get online status
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

                // Get location (if permission granted)
                var lat: Double? = null
                var lon: Double? = null
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try {
                        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this@SetupActivity)
                        val location = fusedLocationClient.getCurrentLocation(
                            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null
                        ).await()
                        if (location != null) {
                            lat = location.latitude
                            lon = location.longitude
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                repo.syncDeviceStatus(
                    batteryLevel = batteryPct,
                    isCharging = isCharging,
                    isOnline = isOnline,
                    latitude = lat,
                    longitude = lon
                )
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
