package com.device.guardian.service.ui

import android.content.Context
import android.content.Intent
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
import com.device.guardian.service.service.GuardianNotificationService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.BatteryManager
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: com.device.guardian.service.utils.PrefsManager
    private val auth by lazy { FirebaseAuth.getInstance() }

    // Step 1: Request foreground location only
    private val requestForegroundLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Toast.makeText(this, "Location permission granted ✓", Toast.LENGTH_SHORT).show()
            // BUG-03 fix: Now request background location SEPARATELY after foreground is granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationLauncher.launch(
                    arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                )
            }
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
        refreshAllStatuses()
    }

    // Step 2: Background location requested separately (BUG-03 fix)
    private val requestBackgroundLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bgGranted = permissions[android.Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
        if (bgGranted) {
            Toast.makeText(this, "Background location granted ✓", Toast.LENGTH_SHORT).show()
            syncStatusNowOnSetup()
        } else {
            Toast.makeText(this, "Background location denied — location may not work when app is closed", Toast.LENGTH_LONG).show()
        }
        refreshAllStatuses()

        // Chain: if SMS/Calls not granted, prompt next
        if (!isSmsCallsPermissionGranted()) {
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            )
        }
    }

    private val requestSmsCallsPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val receiveSmsGranted = permissions[android.Manifest.permission.RECEIVE_SMS] ?: false
        val readSmsGranted = permissions[android.Manifest.permission.READ_SMS] ?: false
        val phoneStateGranted = permissions[android.Manifest.permission.READ_PHONE_STATE] ?: false
        val callLogGranted = permissions[android.Manifest.permission.READ_CALL_LOG] ?: false
        if (receiveSmsGranted && readSmsGranted && phoneStateGranted && callLogGranted) {
            Toast.makeText(this, "SMS & Call permissions granted ✓", Toast.LENGTH_SHORT).show()
            syncStatusNowOnSetup()
        } else {
            Toast.makeText(this, "Some permissions were denied. SMS/Call logs might be partial.", Toast.LENGTH_LONG).show()
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

        // Auto request missing permissions sequentially on launch
        autoRequestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()

        // If app is fully configured, trigger background log synchronization
        if (isParentIdSaved() && isLocationPermissionGranted() && isSmsCallsPermissionGranted()) {
            syncStatusNowOnSetup()
        }
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun setupButtons() {

        // Step 1 — Save Parent ID + Firebase Anon Auth for child
        binding.btnSaveParentId.setOnClickListener {
            val id = binding.etParentId.text.toString().trim()
            if (id.length >= 4) {
                binding.btnSaveParentId.isEnabled = false
                binding.btnSaveParentId.text = "Connecting..."

                // Authenticate child anonymously with Firebase
                auth.signInAnonymously()
                    .addOnSuccessListener { result ->
                        val childUid = result.user?.uid
                        prefs.parentId = id
                        prefs.childUid = childUid
                        Toast.makeText(this, "Connected to Parent ✓", Toast.LENGTH_SHORT).show()
                        refreshAllStatuses()
                        binding.btnSaveParentId.isEnabled = true
                        binding.btnSaveParentId.text = "Save ID"

                        // Reset sync status and sync everything
                        val db = com.device.guardian.service.data.local.AppDatabase.getInstance(this)
                        lifecycleScope.launch {
                            try {
                                db.messageDao().resetSyncStatus()
                            } catch (_: Exception) {}
                        }

                        syncStatusNowOnSetup()

                        // Trigger immediate sync of messages
                        val repo = com.device.guardian.service.data.remote.FirebaseRepository(db.messageDao(), this)
                        lifecycleScope.launch {
                            try {
                                repo.syncPending()
                                Toast.makeText(this@SetupActivity, "Linked to Parent Dashboard ✓", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@SetupActivity, "Sync Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        binding.btnSaveParentId.isEnabled = true
                        binding.btnSaveParentId.text = "Save ID"
                        Toast.makeText(this, "Auth failed: ${e.message}", Toast.LENGTH_LONG).show()
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
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find this app and select 'Unrestricted'", Toast.LENGTH_LONG).show()
            }
        }

        // Step 4 — Request Location Permission (foreground only first — BUG-03 fix)
        binding.btnEnableLocation.setOnClickListener {
            if (isLocationPermissionGranted()) {
                Toast.makeText(this, "Permission already granted ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestForegroundLocationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
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

        // Step 6 — SMS & Calls Tracking
        binding.btnEnableSmsCalls.setOnClickListener {
            if (isSmsCallsPermissionGranted()) {
                Toast.makeText(this, "Permissions already granted ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            )
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
        updateStep6Status()
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

    private fun updateStep6Status() {
        val active = isSmsCallsPermissionGranted()
        binding.tvStep6Status.text = if (active) "✅ Done" else "⬜ Pending"
        binding.tvStep6Status.setTextColor(
            getColor(if (active) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableSmsCalls.text =
            if (active) "SMS & Call Tracking Active ✓" else "Grant SMS & Call Access"
        binding.btnEnableSmsCalls.isEnabled = !active
    }

    private fun updateFinalBanner() {
        val allDone = isParentIdSaved() &&
                      isAccessibilityServiceEnabled() &&
                      isBatteryOptimizationDisabled() &&
                      isLocationPermissionGranted() &&
                      isNotificationServiceEnabled() &&
                      isSmsCallsPermissionGranted()

        if (allDone) {
            binding.tvFinalStatus.text = "✅ Monitoring Active"
            binding.tvFinalSubtext.text = "All steps complete — service is running"
            binding.cardStatus.setCardBackgroundColor(getColor(R.color.status_success))
        } else {
            binding.tvFinalStatus.text = "⚠ Setup Incomplete"
            binding.tvFinalSubtext.text = "Complete all 6 steps above"
            binding.cardStatus.setCardBackgroundColor(
                resources.getColor(android.R.color.black, theme)
            )
        }
    }

    private fun autoRequestPermissionsIfNeeded() {
        if (!isParentIdSaved()) return

        if (!isLocationPermissionGranted()) {
            // BUG-03 fix: Only request foreground location first
            requestForegroundLocationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else if (!isSmsCallsPermissionGranted()) {
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
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

    // BUG-21 fix: Use class reference instead of hardcoded string
    private fun isNotificationServiceEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val serviceName = "${packageName}/${GuardianNotificationService::class.java.canonicalName}"
        return enabledListeners?.contains(serviceName) == true
    }

    private fun isSmsCallsPermissionGranted(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
                    } catch (_: Exception) {}
                }

                repo.syncDeviceStatus(
                    batteryLevel = batteryPct,
                    isCharging = isCharging,
                    isOnline = isOnline,
                    latitude = lat,
                    longitude = lon
                )

                // Import last 20 SMS + 20 call logs from device
                com.device.guardian.service.utils.LocalLogImporter.importHistory(this@SetupActivity, db, repo)
            } catch (_: Exception) {}
        }
    }
}
