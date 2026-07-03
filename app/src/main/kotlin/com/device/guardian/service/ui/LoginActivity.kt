package com.device.guardian.service.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.device.guardian.service.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val prefs by lazy { com.device.guardian.service.utils.PrefsManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Auto-login if ID saved
        val saved = prefs.parentId
        if (!saved.isNullOrBlank()) {
            navigateToDashboard(saved)
            return
        }

        // Generate custom room code on demand
        binding.btnGenerateCode.setOnClickListener {
            val secureCode = generateRandomRoomCode()
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

            // Save and proceed
            prefs.parentId = id

            navigateToDashboard(id)
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
     * Generates a premium secure 8-character pairing code in form: GD-XXXX-XXXX
     */
    private fun generateRandomRoomCode(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // Highly readable subset
        val builder = java.lang.StringBuilder("GD-")
        for (i in 0 until 4) {
            builder.append(chars.random())
        }
        builder.append("-")
        for (i in 0 until 4) {
            builder.append(chars.random())
        }
        return builder.toString()
    }
}
