package com.d4viddf.hyperbridge.data.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("StaticFieldLeak")
object WidgetManager {

    private const val TAG = "WidgetManager"
    private const val HOST_ID = 1024
    private var appWidgetManager: AppWidgetManager? = null
    private var appWidgetHost: HyperAppWidgetHost? = null
    private var context: Context? = null

    // Cache EXCLUSIVE for background snapshot pipeline (getWidgetBitmap)
    // Never shared with UI previews to prevent parent detachment conflicts and race conditions
    private val snapshotHostViewCache = ConcurrentHashMap<Int, AppWidgetHostView>()
    private val snapshotHostViewProviderCache = ConcurrentHashMap<Int, ComponentName>()
    private val bitmapCache = ConcurrentHashMap<Int, Bitmap>()

    private val _widgetUpdates = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 10)
    val widgetUpdates: SharedFlow<Int> = _widgetUpdates.asSharedFlow()

    private var isListening = false

    fun init(ctx: Context) {
        if (context == null) {
            context = ctx.applicationContext
            appWidgetManager = AppWidgetManager.getInstance(context)
            appWidgetHost = HyperAppWidgetHost(context!!, HOST_ID)
        }

        if (!isListening) {
            try {
                appWidgetHost?.startListening()
                isListening = true
            } catch (e: IllegalStateException) {
                // Thrown if the device is not unlocked yet
                e.printStackTrace()
            }
        }
    }

    // --- ID MANAGEMENT ---

    fun allocateId(ctx: Context): Int {
        init(ctx)
        return appWidgetHost?.allocateAppWidgetId() ?: -1
    }

    fun deleteId(ctx: Context, widgetId: Int) {
        init(ctx)
        cleanupWidget(widgetId)
        appWidgetHost?.deleteAppWidgetId(widgetId)
    }

    // [FIX] Return Boolean so UI knows if binding succeeded or needs permission intent
    fun bindWidget(ctx: Context, widgetId: Int, provider: ComponentName): Boolean {
        init(ctx)
        return try {
            appWidgetManager?.bindAppWidgetIdIfAllowed(widgetId, provider) ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // --- UPDATE NOTIFICATION ---

    fun notifyWidgetUpdated(widgetId: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            _widgetUpdates.emit(widgetId)
        }
    }

    // --- CORE FUNCTIONS ---

    fun getWidgetInfo(ctx: Context, widgetId: Int): AppWidgetProviderInfo? {
        init(ctx)
        return appWidgetManager?.getAppWidgetInfo(widgetId)
    }

    fun getConfigurationActivity(ctx: Context, widgetId: Int): ComponentName? {
        init(ctx)
        return getWidgetInfo(ctx, widgetId)?.configure
    }

    /**
     * Creates a fresh, un-cached [AppWidgetHostView] dedicated for UI previews (e.g., Compose AndroidView).
     *
     * UI previews do NOT share views with the background snapshot pipeline to prevent:
     * 1) The snapshot cycle removing the view from the UI wrapper while visible on screen.
     * 2) Conflicting measure/layout cycles with snapshot dimensions.
     * 3) Compose state corruption or preview disappearance.
     */
    fun createPreview(ctx: Context, widgetId: Int): AppWidgetHostView? {
        init(ctx)
        val info = getWidgetInfo(ctx, widgetId) ?: return null
        return appWidgetHost?.createView(ctx.applicationContext, widgetId, info)
    }

    /**
     * Retrieves or creates an [AppWidgetHostView] exclusively for the background snapshot pipeline.
     * Cached views are reused across snapshot cycles (~3s) to prevent GC churn and memory spikes.
     */
    private fun getOrCreateSnapshotHostView(ctx: Context, widgetId: Int): AppWidgetHostView? {
        init(ctx)
        val info = getWidgetInfo(ctx, widgetId) ?: return null
        val currentProvider = info.provider

        val cachedView = snapshotHostViewCache[widgetId]
        val cachedProvider = snapshotHostViewProviderCache[widgetId]

        if (cachedView != null && cachedProvider == currentProvider) {
            return cachedView
        }

        // Provider changed or view not yet cached: clean up previous view
        cachedView?.let { cleanupHostView(it) }

        val newView = appWidgetHost?.createView(ctx.applicationContext, widgetId, info) ?: return null
        snapshotHostViewCache[widgetId] = newView
        snapshotHostViewProviderCache[widgetId] = currentProvider
        return newView
    }

    /**
     * Detaches view and clears listeners.
     * Guaranteed to execute on the Main thread to avoid CalledFromWrongThreadException.
     */
    private fun cleanupHostView(view: AppWidgetHostView) {
        val action = {
            try {
                view.setOnClickListener(null)
                (view.parent as? ViewGroup)?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up AppWidgetHostView", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    /**
     * Renders a snapshot of widget into a cached [Bitmap].
     * Uses [snapshotHostViewCache] exclusively, isolated from any UI preview views.
     * Target bitmap is reused per [widgetId] to prevent frequent large allocations.
     * Must be called on Main thread (enforced by WidgetTranslator withContext(Dispatchers.Main)).
     */
    fun getWidgetBitmap(ctx: Context, widgetId: Int, width: Int, height: Int): Bitmap? {
        init(ctx)
        val hostView = getOrCreateSnapshotHostView(ctx, widgetId) ?: return null
        val info = getWidgetInfo(ctx, widgetId) ?: return null
        hostView.setAppWidget(widgetId, info)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        hostView.measure(widthSpec, heightSpec)
        hostView.layout(0, 0, hostView.measuredWidth, hostView.measuredHeight)

        try {
            var bitmap = bitmapCache[widgetId]
            if (bitmap == null || bitmap.isRecycled || bitmap.width != width || bitmap.height != height) {
                if (bitmap != null && !bitmap.isRecycled) {
                    Log.d(TAG, "Recycling old bitmap (${bitmap.width}x${bitmap.height}) for widget $widgetId")
                    bitmap.recycle()
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmapCache[widgetId] = bitmap
                Log.d(TAG, "Allocated new cached bitmap (${width}x${height}) for widget $widgetId")
            } else {
                // Clear the reusable bitmap before drawing next frame
                bitmap.eraseColor(Color.TRANSPARENT)
            }

            val canvas = Canvas(bitmap)
            hostView.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error generating widget bitmap for widgetId $widgetId", e)
            return null
        }
    }

    /**
     * Releases cached snapshot [AppWidgetHostView] and [Bitmap] for [widgetId].
     * Called when a widget is killed, deleted, or dismissed to prevent memory leaks.
     */
    fun cleanupWidget(widgetId: Int) {
        Log.d(TAG, "Cleaning up snapshot resources for widget $widgetId")
        snapshotHostViewCache.remove(widgetId)?.let { hostView ->
            cleanupHostView(hostView)
        }
        snapshotHostViewProviderCache.remove(widgetId)

        bitmapCache.remove(widgetId)?.let { bitmap ->
            if (!bitmap.isRecycled) {
                Log.d(TAG, "Recycling cached bitmap for widget $widgetId")
                bitmap.recycle()
            }
        }

        HyperAppWidgetHostView.cachedRemoteViews.remove(widgetId)
    }

    /**
     * Releases all cached snapshot views and bitmaps for all widgets.
     */
    fun cleanupAllWidgets() {
        Log.d(TAG, "Cleaning up all cached snapshot widget resources")
        val ids = (snapshotHostViewCache.keys().toList() + bitmapCache.keys().toList()).distinct()
        ids.forEach { id ->
            cleanupWidget(id)
        }
    }

    fun getLatestRemoteViews(widgetId: Int): RemoteViews? {
        return HyperAppWidgetHostView.cachedRemoteViews[widgetId]
    }
}