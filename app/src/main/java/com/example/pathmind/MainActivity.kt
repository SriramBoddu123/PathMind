package com.example.pathmind

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Apply window insets for edge-to-edge support
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupButtons()
        setupDeveloperAccess()
    }

    private fun setupButtons() {
        val btnLearnRoute = findViewById<Button>(R.id.btnLearnRoute)
        val btnMyRoutes = findViewById<Button>(R.id.btnMyRoutes)
        val btnRememberPlace = findViewById<Button>(R.id.btnRememberPlace)

        // Stage 3: Connect LEARN NEW ROUTE to RouteLearningActivity
        btnLearnRoute.setOnClickListener {
            val intent = Intent(this, RouteLearningActivity::class.java)
            startActivity(intent)
        }

        // Stage 3: Connect MY ROUTES to MyRoutesActivity
        btnMyRoutes.setOnClickListener {
            val intent = Intent(this, MyRoutesActivity::class.java)
            startActivity(intent)
        }

        // Stage 4: Connect REMEMBER A PLACE to RememberPlaceActivity
        btnRememberPlace.setOnClickListener {
            val intent = Intent(this, RememberPlaceActivity::class.java)
            startActivity(intent)
        }

        // Stage 8A: Connect VOICE COMMAND FAB to VoiceCommandBottomSheet
        val fabVoiceCommand = findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.fabVoiceCommand)
        fabVoiceCommand.setOnClickListener {
            val bottomSheet = com.example.pathmind.ui.VoiceCommandBottomSheet()
            bottomSheet.show(supportFragmentManager, "VoiceCommandBottomSheet")
        }
    }

    private fun setupDeveloperAccess() {
        val layoutBadge = findViewById<View>(R.id.layoutBadge)
        val btnOpenSensorDebug = findViewById<TextView>(R.id.btnOpenSensorDebug)
        val btnOpenMyMemories = findViewById<TextView>(R.id.btnOpenMyMemories)

        val openDebugScreen = View.OnClickListener {
            val intent = Intent(this, SensorDebugActivity::class.java)
            startActivity(intent)
        }

        layoutBadge.setOnClickListener(openDebugScreen)
        btnOpenSensorDebug.setOnClickListener(openDebugScreen)

        btnOpenMyMemories.setOnClickListener {
            val intent = Intent(this, MyMemoriesActivity::class.java)
            startActivity(intent)
        }
    }
}