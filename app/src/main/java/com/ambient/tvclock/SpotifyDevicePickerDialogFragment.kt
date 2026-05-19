package com.ambient.tvclock

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Spotify Connect device picker dialog (Stream A, artboard 16).
 *
 * Replaces the legacy AlertDialog-based picker with a custom DialogFragment
 * that:
 *   • Blurs the underlying screen — `RenderEffect.createBlurEffect` on
 *     API 31+, pre-blurred Bitmap fallback elsewhere.
 *   • Hosts an accent-themed card with the redesigned device rows
 *     (active row shows VuBars + "Playing", inactive rows show chevron).
 *   • Closes on BACK or outside tap; surfaces the picked device id via
 *     the launcher callback.
 *
 * Pending state lives in a companion-level "pending args" holder rather
 * than the fragment Bundle so we don't need the kotlin-parcelize plugin
 * for `SpotifyDevice`. The fragment is also short-lived (dismissed once
 * the user picks a device or hits BACK) so cross-process recreation isn't
 * a concern.
 */
class SpotifyDevicePickerDialogFragment : DialogFragment() {

    private var onDeviceSelected: ((String) -> Unit)? = null
    private var onLaunchLocal: ((String) -> Unit)? = null
    private var localPackage: String? = null
    private var devices: List<SpotifyDevice> = emptyList()

    private data class PendingArgs(
        val devices: List<SpotifyDevice>,
        val localPackage: String?,
        val onLaunchLocal: (String) -> Unit,
        val onDeviceSelected: (String) -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val args = pendingArgs
        if (args != null) {
            devices = args.devices
            localPackage = args.localPackage
            onLaunchLocal = args.onLaunchLocal
            onDeviceSelected = args.onDeviceSelected
            pendingArgs = null
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Translucent fullscreen dialog so the BLUR underlay paints over
        // whatever was on screen when the picker opened.
        val dialog = Dialog(requireContext(), theme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.dialog_device_picker, container, false) as ViewGroup
        installBackdrop(view)
        installRecycler(view.findViewById(R.id.devicePickerRecycler))
        // Tap outside the card dismisses (cancelable=true won't quite catch
        // touches that hit our own scrim view).
        view.findViewById<View>(R.id.devicePickerScrim).setOnClickListener {
            dismissAllowingStateLoss()
        }
        return view
    }

    /**
     * Captures a screenshot of the host activity's content root, applies a
     * blur, and installs it as a backdrop ImageView under the dialog scrim.
     *
     * On API 31+ we use [RenderEffect.createBlurEffect] applied directly to
     * the host activity's `decorView`. On older devices we fall back to a
     * pre-blurred Bitmap rendered into an ImageView behind the scrim.
     */
    private fun installBackdrop(root: ViewGroup) {
        val activity = activity ?: return
        val decor = activity.window.decorView

        // Add the ImageView behind everything — index 0.
        val backdrop = ImageView(root.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(backdrop, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: blur the decor view's render output via RenderEffect.
            try {
                val effect = RenderEffect.createBlurEffect(
                    BLUR_RADIUS_PX,
                    BLUR_RADIUS_PX,
                    Shader.TileMode.CLAMP
                )
                decor.setRenderEffect(effect)
                // Restore on dismiss — see onDismiss().
                renderEffectActive = true
            } catch (e: Throwable) {
                Log.w(TAG, "RenderEffect failed, falling back", e)
                installBlurredBitmap(decor, backdrop)
            }
        } else {
            installBlurredBitmap(decor, backdrop)
        }
    }

    private var renderEffectActive: Boolean = false

    /**
     * Pre-blurred Bitmap fallback. Renders the decor view into a downsampled
     * bitmap (1/4 size), then drives [AlbumArtBlur] over it — same path the
     * NowPlaying background blur uses, so the result is consistent across
     * the app.
     */
    private fun installBlurredBitmap(decor: View, backdrop: ImageView) {
        try {
            val scale = 0.25f
            val w = (decor.width * scale).toInt().coerceAtLeast(1)
            val h = (decor.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply {
                scale(scale, scale)
            }
            decor.draw(canvas)
            val blurred = try {
                AlbumArtBlur.blur(bitmap)
            } catch (_: Exception) {
                bitmap
            }
            backdrop.setImageDrawable(BitmapDrawable(resources, blurred).apply {
                // Slight tint so the blurred background reads darker (HTML
                // also dims `brightness(0.55)`).
                colorFilter = android.graphics.PorterDuffColorFilter(
                    0x99000000.toInt(),
                    android.graphics.PorterDuff.Mode.SRC_ATOP
                )
            })
        } catch (e: Throwable) {
            Log.w(TAG, "Bitmap blur fallback failed", e)
            backdrop.setBackgroundColor(0xCC000000.toInt())
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Restore decor view if we installed a RenderEffect.
        if (renderEffectActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity?.window?.decorView?.setRenderEffect(null)
            renderEffectActive = false
        }
        super.onDismiss(dialog)
    }

    private fun installRecycler(rv: RecyclerView) {
        rv.layoutManager = LinearLayoutManager(rv.context)
        rv.itemAnimator = null
        rv.setHasFixedSize(false)

        val adapter = DevicePickerAdapter(
            onLaunchLocal = { pkg ->
                onLaunchLocal?.invoke(pkg)
                dismissAllowingStateLoss()
            },
            onDeviceSelected = { device ->
                onDeviceSelected?.invoke(device.id)
                dismissAllowingStateLoss()
            }
        )
        rv.adapter = adapter
        adapter.submit(devices, localPackage)

        // Defer focus to first row so the dialog opens DPAD-ready.
        rv.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    companion object {
        private const val TAG = "DevicePicker"
        private const val FRAGMENT_TAG = "spotify-device-picker"
        private const val BLUR_RADIUS_PX = 20f

        /**
         * Process-scoped pending args. The fragment is short-lived; the
         * holder is cleared by [onCreate] as soon as the fragment instance
         * picks up its state.
         */
        @Volatile
        private var pendingArgs: PendingArgs? = null

        /**
         * Public entry point. The fragment instance is held only by the
         * fragment manager so callers don't need to manage its lifecycle.
         */
        fun show(
            activity: FragmentActivity,
            devices: List<SpotifyDevice>,
            localPackage: String?,
            onLaunchLocal: (String) -> Unit,
            onDeviceSelected: (String) -> Unit
        ) {
            val fm = activity.supportFragmentManager
            // Dismiss any leftover instance so listeners on a stale fragment
            // don't fire after the activity has moved on.
            (fm.findFragmentByTag(FRAGMENT_TAG) as? DialogFragment)?.dismissAllowingStateLoss()

            pendingArgs = PendingArgs(
                devices = devices,
                localPackage = localPackage,
                onLaunchLocal = onLaunchLocal,
                onDeviceSelected = onDeviceSelected,
            )

            val fragment = SpotifyDevicePickerDialogFragment()
            fragment.show(fm, FRAGMENT_TAG)
        }
    }
}
