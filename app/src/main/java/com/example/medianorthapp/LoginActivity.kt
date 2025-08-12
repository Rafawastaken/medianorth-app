package com.example.medianorthapp

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.medianorthapp.network.SupabaseClient
import kotlinx.coroutines.*

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val emailInput = findViewById<android.widget.EditText>(R.id.emailInput)
        val passwordInput = findViewById<android.widget.EditText>(R.id.passwordInput)
        val loginButton = findViewById<android.widget.Button>(R.id.loginButton)

        prefs = getSharedPreferences("medianorth", MODE_PRIVATE)

        // ───── Tentar login automático ─────
        val savedEmail = prefs.getString("email", null)
        val savedPass = prefs.getString("password", null)

        if (!savedEmail.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            attemptLogin(savedEmail, savedPass)
        }

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Preenche todos os campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            attemptLogin(email, password)
        }
    }

    private fun attemptLogin(email: String, password: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val device = SupabaseClient.loginDevice(email, password)

            withContext(Dispatchers.Main) {
                if (device != null) {
                    Log.d("LOGIN", "Login OK: device_id=${device.id}")
                    Toast.makeText(this@LoginActivity, "Login com ${device.name}", Toast.LENGTH_SHORT).show()

                    // Guardar credenciais localmente
                    prefs.edit()
                        .putString("email", email)
                        .putString("password", password)
                        .apply()

                    // Iniciar player
                    val intent = Intent(this@LoginActivity, VideoPlayerActivity::class.java)
                    intent.putExtra("device_id", device.id)
                    startActivity(intent)
                    finish()
                } else {
                    Log.e("LOGIN", "Login falhou")
                    Toast.makeText(this@LoginActivity, "Login falhou", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
