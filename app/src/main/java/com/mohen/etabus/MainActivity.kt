package com.mohen.etabus

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.mohen.etabus.ui.BusApp
import com.mohen.etabus.ui.BusViewModel
import com.mohen.etabus.update.AppUpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EtabusApplication : Application()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[BusViewModel::class.java]
        val updates = ViewModelProvider(this)[AppUpdateViewModel::class.java]
        setContent { BusApp(model, updates) }
    }
}