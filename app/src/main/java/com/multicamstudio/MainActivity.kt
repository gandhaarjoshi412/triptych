package com.multicamstudio

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import com.multicamstudio.features.AppNav
import com.multicamstudio.ui.theme.MultiCamTheme

class MainActivity : ComponentActivity() {
    /** Device orientation rounded to 0/90/180/270, used for the MP4 orientation hint at record start. */
    private val deviceRotation = mutableIntStateOf(0)
    private val permissionsVersion = mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsVersion.intValue++
    }

    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation.intValue = ((orientation + 45) / 90 * 90) % 360
            }
        }
        val container = (application as MultiCamApp).container
        setContent {
            MultiCamTheme {
                AppNav(
                    container = container,
                    deviceRotation = { deviceRotation.intValue },
                    permissionsVersion = permissionsVersion.intValue,
                    requestPermissions = ::requestAppPermissions,
                )
            }
        }
        requestAppPermissions()
    }

    fun requestAppPermissions() {
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }
}
