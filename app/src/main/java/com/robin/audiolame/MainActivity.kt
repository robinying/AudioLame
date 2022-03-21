package com.robin.audiolame

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.robin.audiolame.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    /**
     * A native method that is implemented by the 'audiolame' native library,
     * which is packaged with this application.
     */

}