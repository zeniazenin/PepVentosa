package com.roundlight.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.roundlight.app.ui.Studio

class MainActivity: ComponentActivity() {
    private lateinit var model: StudioModel
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();model=ViewModelProvider(this)[StudioModel::class.java];setContent{Studio(model)}}
    override fun onResume(){super.onResume();if(::model.isInitialized)model.gate.setResumed(true)}
    override fun onPause(){if(::model.isInitialized)model.gate.setResumed(false);super.onPause()}
}
