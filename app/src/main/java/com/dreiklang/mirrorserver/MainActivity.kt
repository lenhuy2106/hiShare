package com.dreiklang.mirrorserver

import android.Manifest.permission
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dreiklang.mirrorserver.MediaCaptureService.Companion.EXTRA_RESULT_DATA
import com.dreiklang.mirrorserver.ui.theme.MirrorServerTheme

/**
 * local ice candidates (to be send) only start to gather after set local desc (eg. from offer)
 */
class MainActivity : ComponentActivity() {

    companion object {
        private val TAG: String? = MainActivity::class.simpleName
        private const val REQ_CODE_PERM_RECORDING = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MirrorServerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        // logic

        // TODO continue only on permission granted callback
        // ask permissions
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(permission.RECORD_AUDIO),
                0);
        }

        if (ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(permission.POST_NOTIFICATIONS),
                0);
        }

        // TODO check if android 10
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE_PERM_RECORDING);
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_PERM_RECORDING) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "screen/audio recording permission granted.")
                // val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // val mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data!!) as MediaProjection

                // init(mediaProjection)
                // vs service

                val audioCaptureIntent = Intent(this, MediaCaptureService::class.java).apply {
                    action = MediaCaptureService.ACTION_START
                    putExtra(EXTRA_RESULT_DATA, data)
                }

                ContextCompat.startForegroundService(this, audioCaptureIntent)
            }
        }
    }

    @Composable
    fun Greeting(name: String, modifier: Modifier = Modifier) {
        Text(
            text = "Hello $name!",
            modifier = modifier
        )
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        MirrorServerTheme {
            Greeting("Android")
        }
    }
}

