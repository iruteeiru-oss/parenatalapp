package com.device.guardian.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.device.guardian.service.databinding.ActivityMainBinding
import com.device.guardian.service.ui.LoginActivity
import com.device.guardian.service.ui.SetupActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val PREFS_NAME = "gd_role_prefs"
        private const val KEY_ROLE = "role"
        private const val ROLE_PARENT = "parent"
        private const val ROLE_CHILD = "child"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedRole = prefs.getString(KEY_ROLE, null)

        if (savedRole != null) {
            when (savedRole) {
                ROLE_PARENT -> {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    return
                }
                ROLE_CHILD -> {
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                    return
                }
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardParentMode.setOnClickListener {
            prefs.edit().putString(KEY_ROLE, ROLE_PARENT).apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.cardChildMode.setOnClickListener {
            prefs.edit().putString(KEY_ROLE, ROLE_CHILD).apply()
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }
}
