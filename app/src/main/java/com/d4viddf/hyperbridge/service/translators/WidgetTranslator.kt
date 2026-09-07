package com.d4viddf.hyperbridge.service.translators

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Log
import android.widget.RemoteViews
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.widget.WidgetManager
import com.d4viddf.hyperbridge.models.HyperIslandData
import com.d4viddf.hyperbridge.models.WidgetRenderMode
import com.d4viddf.hyperbridge.models.WidgetSize
import com.d4viddf.hyperbridge.util.downscaleSafe
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class WidgetTranslator(context: Context) : BaseTranslator(context) {

    private val preferences = AppPreferences(context)

    suspend fun translate(widgetId: Int): HyperIslandData {
        val config = preferences.getWidgetConfigFlow(widgetId).first()

        val targetSize = config.size
        val renderMode = config.renderMode

        // 1. Get Widget Provider Info
        val widgetInfo = WidgetManager.getWidgetInfo(context, widgetId)
        val packageName = widgetInfo?.provider?.packageName
        val label = if (widgetInfo != null) widgetInfo.loadLabel(context.packageManager) else "Widget"

        val title = label.toString()
        val builder = HyperIslandNotification.Builder(context, "widget_channel", title)

        // 2. Load the App Icon
        val iconKey = "widget_icon_$widgetId"
        var iconBitmap: Bitmap? = null

        if (packageName != null) {
            try {
                // Downscale app icon to safe max size (128x128)
                iconBitmap = context.packageManager.getApplicationIcon(packageName).toBitmap().downscaleSafe(128)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // --- View Rendering Logic ---
        if (renderMode == WidgetRenderMode.SNAPSHOT) {
            val density = context.resources.displayMetrics.density
            val widthPx = (350 * density).toInt()

            val heightDp = when(targetSize) {
                WidgetSize.SMALL -> 100
                WidgetSize.MEDIUM -> 180
                WidgetSize.LARGE -> 280
                WidgetSize.XLARGE -> 380
                WidgetSize.ORIGINAL -> 180
            }
            val heightPx = (heightDp * density).toInt()

            val bitmap: Bitmap? = withContext(Dispatchers.Main) {
                try {
                    WidgetManager.getWidgetBitmap(context, widgetId, widthPx, heightPx)
                } catch (_: Exception) { null }
            }

            if (bitmap != null) {
                // Downscale & compress bitmap before embedding in RemoteViews to reduce Binder payload
                // and avoid TransactionTooLargeException (~1MB shared buffer limit in SystemUI).
                // Do NOT recycle the original bitmap here because it is cached and reused by WidgetManager.
                val safeBitmap = if (bitmap.width > 512 || bitmap.height > 512) {
                    bitmap.downscaleSafe(maxDimension = 512, recycleOriginal = false)
                } else {
                    bitmap
                }

                // Compress bitmap to PNG to reduce payload and produce an immutable parcel-optimized bitmap
                val compressedBitmap = try {
                    val stream = ByteArrayOutputStream()
                    safeBitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                    val bytes = stream.toByteArray()
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) {
                        Log.d("WidgetTranslator", "Compressed snapshot for widget $widgetId: ${bytes.size} bytes (orig: ${safeBitmap.byteCount} bytes)")
                        if (safeBitmap !== bitmap && !safeBitmap.isRecycled) {
                            safeBitmap.recycle()
                        }
                        decoded
                    } else {
                        safeBitmap
                    }
                } catch (e: Exception) {
                    Log.w("WidgetTranslator", "Failed to compress widget snapshot bitmap, using safe bitmap directly", e)
                    safeBitmap
                }

                val snapshotView = RemoteViews(context.packageName, R.layout.layout_island_widget_snapshot)
                snapshotView.setImageViewBitmap(R.id.widget_snapshot_view, compressedBitmap)
                builder.setCustomRemoteView(snapshotView)
            }

        } else {
            val remoteViews = WidgetManager.getLatestRemoteViews(widgetId)

            if (remoteViews != null) {
                if (targetSize == WidgetSize.ORIGINAL) {
                    builder.setCustomRemoteView(remoteViews)
                } else {
                    val wrapper = RemoteViews(context.packageName, targetSize.layoutRes)
                    wrapper.removeAllViews(R.id.widget_insertion_point)
                    wrapper.addView(R.id.widget_insertion_point, remoteViews)
                    builder.setCustomRemoteView(wrapper)
                }
            }
        }

        // [FIX] 3. Apply the Icon to the Island
        if (iconBitmap != null) {
            val icon = Icon.createWithBitmap(iconBitmap)

            // Fix: Create the HyperPicture object first
            val picture = HyperPicture(iconKey, icon)
            builder.addPicture(picture)

            builder.setBigIslandInfo(left = ImageTextInfoLeft(picInfo = PicInfo(pic = iconKey)))
            builder.setSmallIsland(iconKey)
        } else {
            builder.setBigIslandInfo(left = ImageTextInfoLeft(picInfo = PicInfo(pic = "default_icon")))
            builder.setSmallIsland("default_icon")
            builder.addPicture(getTransparentPicture("default_icon"))
        }

        builder.setIslandConfig(timeout = config.timeout , dismissible = false)
        builder.setEnableFloat(false)
        builder.setShowNotification(config.isShowShade)
        builder.setIslandFirstFloat(false)
        builder.setReopen(true)
        builder.setHideDeco(true)

        return HyperIslandData(builder.buildCustomExtras(), builder.buildJsonParam())
    }
}