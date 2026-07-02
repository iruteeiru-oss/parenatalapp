package com.device.guardian.service.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.device.guardian.service.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    companion object {
        private const val PREFS = "gd_parent_prefs"
        private const val KEY_PID = "parent_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Auto-login if ID saved
        val saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PID, null)
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
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PID, id).apply()

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
