package com.device.guardian.service.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.device.guardian.service.databinding.ActivityLoginBinding
import com.device.guardian.service.utils.PrefsManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val prefs by lazy { PrefsManager(this) }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Auto-login if ID is already saved (auth check bypassed for testing)
        val saved = prefs.parentId
        if (!saved.isNullOrBlank()) {
            navigateToDashboard(saved)
            return
        }

        // Generate secure random code on demand
        binding.btnGenerateCode.setOnClickListener {
            val secureCode = generateSecureRoomCode()
            binding.etParentId.setText(secureCode)
            binding.tvError.visibility = View.GONE
        }

        binding.btnLogin.setOnClickListener {
            val id = binding.etParentId.text.toString().trim()
            if (id.length < 4) {
                binding.tvError.text = "Code must be at least 4 characters"
                binding.tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            binding.tvError.visibility = View.GONE
            binding.progressLogin.visibility = View.VISIBLE
            binding.btnLogin.isEnabled = false

            // Push the code to Firestore immediately so the child can find it
            lifecycleScope.launch {
                try {
                    db.collection("monitors")
                        .document(id)
                        .set(
                            mapOf(
                                "createdAt" to System.currentTimeMillis(),
                                "platform" to "android",
                                "status" to "waiting_for_child"
                            ),
                            SetOptions.merge()
                        )
                        .await()

                    prefs.parentId = id
                    binding.progressLogin.visibility = View.GONE
                    navigateToDashboard(id)
                } catch (e: Exception) {
                    binding.progressLogin.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    binding.tvError.text = "Failed to register code: ${e.message}"
                    binding.tvError.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun navigateToDashboard(parentId: String) {
        val intent = Intent(this, DashboardActivity::class.java).apply {
            putExtra("parent_id", parentId)
        }
        startActivity(intent)
        finish()
    }

    /**
     * Generates a secure 8-character pairing code in form: GD-XXXX-XXXX
     * Uses SecureRandom for cryptographic safety (BUG-18 fix)
     */
    private fun generateSecureRoomCode(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = java.security.SecureRandom()
        val builder = StringBuilder("GD-")
        for (i in 0 until 4) {
            builder.append(chars[random.nextInt(chars.length)])
        }
        builder.append("-")
        for (i in 0 until 4) {
            builder.append(chars[random.nextInt(chars.length)])
        }
        return builder.toString()
    }
}

