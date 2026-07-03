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
        private const val ROLE_PARENT = "parent"
        private const val ROLE_CHILD = "child"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = com.device.guardian.service.utils.PrefsManager(this)
        prefs.migrateOldPrefsIfNeeded(this)
        val savedRole = prefs.role

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
            prefs.role = ROLE_PARENT
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.cardChildMode.setOnClickListener {
            prefs.role = ROLE_CHILD
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }
}
