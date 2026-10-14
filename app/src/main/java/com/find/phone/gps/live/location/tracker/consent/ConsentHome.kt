package com.find.phone.gps.live.location.tracker.consent

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.find.phone.gps.live.location.tracker.helpers.WardenRail

class ConsentHome : Fragment() {
    private var androidPermission: String? = null
    private var onResult: ((Boolean) -> Unit)? = null
    private var launched = false

    private lateinit var launcher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launcher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val cb = onResult
            onResult = null
            detach()
            cb?.invoke(granted)
        }
    }

    override fun onStart() {
        super.onStart()

        val perm = androidPermission
        if (!launched && perm != null) {
            launched = true
            runCatching { launcher.launch(perm) }.onFailure {
                WardenRail.error(TAG, "launch() failed for $perm", it)
                val cb = onResult
                onResult = null
                detach()
                cb?.invoke(false)
            }
        }
    }

    private fun detach() {
        val fm = fragmentManagerOrNull() ?: return
        runCatching {
            fm.beginTransaction().remove(this).commitAllowingStateLoss()
        }
    }

    private fun fragmentManagerOrNull() =
        if (isAdded) parentFragmentManager else null

    companion object {
        private const val TAG = "ConsentDriver"
        private const val FRAGMENT_TAG = "permission_launcher_fragment"

        fun launch(
            activity: Activity,
            androidPermission: String,
            onResult: (Boolean) -> Unit,
        ) {
            if (activity is FragmentActivity && !activity.isFinishing && !activity.isDestroyed) {
                val fm = activity.supportFragmentManager
                if (fm.isStateSaved) {
                    WardenRail.log(TAG, "State already saved; skipping request for $androidPermission")
                    onResult(false)
                    return
                }

                val fragment = ConsentHome().apply {
                    this.androidPermission = androidPermission
                    this.onResult = onResult
                }
                runCatching {
                    fm.beginTransaction()
                        .add(fragment, FRAGMENT_TAG)
                        .commitAllowingStateLoss()
                }.onFailure {
                    WardenRail.error(TAG, "Failed to attach PermissionLauncher", it)
                    onResult(false)
                }
            } else {
                WardenRail.log(TAG, "Host is not a FragmentActivity; using ActivityCompat fallback")
                runCatching {
                    ActivityCompat.requestPermissions(
                        activity, arrayOf(androidPermission), FALLBACK_REQUEST_CODE
                    )
                }
                onResult(false)
            }
        }

        private const val FALLBACK_REQUEST_CODE = 7301
    }
}
