package com.find.phone.gps.live.location.tracker.foundation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.store.LockerBook
import java.util.Locale

/**
 * Base class for every Fragment in the app.
 *
 * Uses ViewBinding, safely clears the binding in [onDestroyView] to avoid
 * memory leaks, and exposes [initView] / [initObservers] hooks.
 *
 * Usage:
 * ```
 * class OverviewFragment : FrameFragment<PanelHomeBinding>() {
 *     override fun expandBinding(inflater, container) =
 *         PanelHomeBinding.inflate(inflater, container, false)
 * }
 * ```
 */
abstract class FrameFragment<VB : ViewBinding> : Fragment() {

    private var _binding: VB? = null
    protected val binding: VB get() = _binding!!

    /** Inflate the concrete ViewBinding for this fragment. */
    protected abstract fun expandBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = expandBinding(inflater, container)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
    }

    /** Set up views, listeners, adapters. */
    protected open fun initView() {}

    /** Subscribe to ViewModel LiveData / Flows. */
    protected open fun initObservers() {}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // --- Screen-view analytics ---
    // CoreHubActivity hosts tabs via add/show/hide, so log when a fragment is
    // actually visible: on first resume and whenever it is un-hidden. Hidden
    // fragments still receive onResume on app-resume, hence the isHidden guard.

    override fun onResume() {
        super.onResume()
        if (!isHidden) recordScreenView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) recordScreenView()
    }

    private fun recordScreenView() {
        context?.recordKeyEvent("screen_${this::class.java.simpleName.lowercase(Locale.ROOT)}")
    }

    // --- Shared runtime-permission handling ---

    /**
     * Requests [permission] through the given [launcher], but once the user has denied
     * it twice (permanently denied — the system will no longer show its dialog), opens
     * the app's settings page instead so they can enable it manually.
     */
    protected fun demandPermissionManaged(
        permission: String,
        launcher: ActivityResultLauncher<String>
    ) {
        val prefs = LockerBook(requireContext())
        when {
            // First-ever request → show the system dialog.
            !prefs.hasRequestedPermission(permission) -> {
                prefs.flagPermissionRequested(permission)
                launcher.launch(permission)
            }
            // Denied before but the system will still show the dialog → ask again.
            shouldShowRequestPermissionRationale(permission) -> launcher.launch(permission)
            // Permanently denied → the dialog won't appear, so send them to Settings.
            else -> enterAppSettings()
        }
    }

    // --- Chained runtime-permission requests ---

    private val permissionChain = ArrayDeque<String>()
    private var onPermissionChainComplete: (() -> Unit)? = null
    private var lastChainPermission: String? = null

    private val chainLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lastChainPermission?.let { context?.recordPermissionResult(it, granted) }
        advancePermissionChain()
    }

    /**
     * Requests [permissions] one after another (skipping any already granted), then
     * runs [onComplete] once the whole sequence is finished — e.g. to kick off the
     * overlay-permission step. A permanently-denied permission diverts to the app's
     * Settings page and pauses the chain; calling this again restarts it.
     */
    protected fun demandPermissionChain(
        permissions: List<String>,
        onComplete: () -> Unit
    ) {
        permissionChain.clear()
        permissionChain.addAll(permissions)
        onPermissionChainComplete = onComplete
        advancePermissionChain()
    }

    private fun advancePermissionChain() {
        val ctx = context ?: return
        val prefs = LockerBook(ctx)
        while (permissionChain.isNotEmpty()) {
            val permission = permissionChain.removeFirst()
            if (ContextCompat.checkSelfPermission(ctx, permission)
                == PackageManager.PERMISSION_GRANTED
            ) continue

            when {
                // First-ever request → show the system dialog (callback continues the chain).
                !prefs.hasRequestedPermission(permission) -> {
                    prefs.flagPermissionRequested(permission)
                    lastChainPermission = permission
                    chainLauncher.launch(permission)
                }
                // Denied before but the dialog still appears → ask again.
                shouldShowRequestPermissionRationale(permission) -> {
                    lastChainPermission = permission
                    chainLauncher.launch(permission)
                }
                // Permanently denied → divert to Settings and pause here.
                else -> enterAppSettings()
            }
            return
        }
        // Sequence exhausted — fire the completion hook.
        val done = onPermissionChainComplete
        onPermissionChainComplete = null
        done?.invoke()
    }

    /** Opens this app's system settings (App info) screen. */
    protected fun enterAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        }
    }

    // --- Shared direct-calling (CALL_PHONE) ---

    private var pendingCallNumber: String? = null

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        context?.recordPermissionResult(Manifest.permission.CALL_PHONE, granted)
        val number = pendingCallNumber
        pendingCallNumber = null
        if (number != null) if (granted) beginCall(number) else enterDialer(number)
    }

    /** Places the call directly (CALL_PHONE), requesting the permission if needed. */
    protected fun siteCall(number: String) {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beginCall(number)
        } else {
            pendingCallNumber = number
            demandPermissionManaged(Manifest.permission.CALL_PHONE, callPermissionLauncher)
        }
    }

    private fun beginCall(number: String) {
        val placed = runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))); true
        }.getOrDefault(false)
        if (!placed) enterDialer(number)
    }

    private fun enterDialer(number: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }
}
