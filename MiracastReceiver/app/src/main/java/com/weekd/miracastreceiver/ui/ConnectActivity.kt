package com.weekd.miracastreceiver.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.weekd.miracastreceiver.R
import timber.log.Timber

class ConnectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        Timber.i("ConnectActivity created")
    }
}
