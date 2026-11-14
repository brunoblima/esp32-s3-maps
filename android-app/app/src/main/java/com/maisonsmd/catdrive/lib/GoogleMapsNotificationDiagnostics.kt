package com.maisonsmd.catdrive.lib

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.view.children

internal object GoogleMapsNotificationDiagnostics {
    private const val MAX_REPORT_LENGTH = 10_000
    private const val MAX_TEXT_LENGTH = 500
    private const val MAX_VIEWS = 60

    fun createReport(context: Context, notification: StatusBarNotification): String {
        val report = StringBuilder()
        val details = notification.notification

        report.appendLine("Package: ${notification.packageName}")
        report.appendLine("ID: ${notification.id}")
        report.appendLine("Ongoing: ${notification.isOngoing}")
        report.appendLine("Category: ${details.category ?: "(none)"}")
        report.appendLine("When: ${details.`when`}")
        try {
            report.appendLine()
            report.appendLine("Standard notification fields:")
            appendText(report, "title", details.extras.getCharSequence(Notification.EXTRA_TITLE))
            appendText(report, "text", details.extras.getCharSequence(Notification.EXTRA_TEXT))
            appendText(report, "bigText", details.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            appendText(report, "subText", details.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            appendText(report, "infoText", details.extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            appendText(report, "summaryText", details.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))

            report.appendLine()
            report.appendLine("Text extras:")
            details.extras.keySet().sorted().take(30).forEach { key ->
                val value = details.extras.get(key)
                when (value) {
                    is CharSequence -> if (key !in STANDARD_TEXT_KEYS) appendText(report, key, value)
                    is Bitmap -> report.appendLine("$key: Bitmap ${value.width}x${value.height}")
                    is Icon -> report.appendLine("$key: ${describeIcon(value)}")
                    null -> report.appendLine("$key: (null)")
                    else -> report.appendLine("$key: ${value.javaClass.name}")
                }
            }
        } catch (error: Exception) {
            report.appendLine("Could not read notification extras: ${error.javaClass.simpleName}: ${error.message}")
        }

        report.appendLine()
        report.appendLine("Notification icons:")
        appendIcon(report, "smallIcon", details.smallIcon)
        appendIcon(report, "largeIcon", details.getLargeIcon())

        val sourceContext = try {
            context.createPackageContext(notification.packageName, 0)
        } catch (error: Exception) {
            report.appendLine()
            report.appendLine("Could not access Maps resources: ${error.javaClass.simpleName}: ${error.message}")
            return report.toString().take(MAX_REPORT_LENGTH)
        }

        appendRemoteViews(report, sourceContext, "contentView", details.contentView)
        appendRemoteViews(report, sourceContext, "bigContentView", details.bigContentView)
        return report.toString().take(MAX_REPORT_LENGTH)
    }

    private fun appendRemoteViews(
        report: StringBuilder,
        sourceContext: Context,
        label: String,
        remoteViews: RemoteViews?
    ) {
        report.appendLine()
        report.appendLine("$label:")
        if (remoteViews == null) {
            report.appendLine("(none)")
            return
        }

        report.appendLine("Layout ID: ${remoteViews.layoutId}")
        try {
            val root = LayoutInflater.from(sourceContext)
                .inflate(remoteViews.layoutId, null) as? ViewGroup
                ?: run {
                    report.appendLine("Could not inflate root view")
                    return
                }
            remoteViews.reapply(sourceContext, root)
            appendViewTree(report, sourceContext, root, label, intArrayOf(0))
        } catch (error: Exception) {
            report.appendLine(
                "Could not inspect views: ${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    private fun appendViewTree(
        report: StringBuilder,
        sourceContext: Context,
        view: View,
        parentPath: String,
        viewCount: IntArray
    ) {
        if (viewCount[0] >= MAX_VIEWS || report.length >= MAX_REPORT_LENGTH) return
        viewCount[0]++

        val resourceName = if (view.id == View.NO_ID) {
            "(no id)"
        } else {
            try {
                sourceContext.resources.getResourceEntryName(view.id)
            } catch (_: Exception) {
                view.id.toString()
            }
        }
        val path = "$parentPath/$resourceName"

        when (view) {
            is TextView -> {
                val text = view.text
                report.appendLine("$path [${view.javaClass.simpleName}]: ${text.toString().take(MAX_TEXT_LENGTH)}")
                if (text is Spanned) {
                    val spans = text.getSpans(0, text.length, StyleSpan::class.java)
                    if (spans.isNotEmpty()) {
                        report.appendLine(
                            "$path styles: " + spans.joinToString {
                                "${it.style}@${text.getSpanStart(it)}-${text.getSpanEnd(it)}"
                            }
                        )
                    }
                }
            }
            is ImageView -> report.appendLine("$path [ImageView]: ${view.drawable?.javaClass?.simpleName ?: "(no drawable)"}")
        }

        if (view is ViewGroup) {
            view.children.forEach { child ->
                appendViewTree(report, sourceContext, child, path, viewCount)
            }
        }
    }

    private fun appendText(report: StringBuilder, key: String, value: CharSequence?) {
        report.appendLine("$key: ${value?.toString()?.take(MAX_TEXT_LENGTH) ?: "(none)"}")
    }

    private fun appendIcon(report: StringBuilder, name: String, icon: Icon?) {
        report.appendLine("$name: ${icon?.let(::describeIcon) ?: "(none)"}")
    }

    private fun describeIcon(icon: Icon): String =
        "Icon(type=${icon.type}, details=$icon)"

    private val STANDARD_TEXT_KEYS = setOf(
        Notification.EXTRA_TITLE,
        Notification.EXTRA_TEXT,
        Notification.EXTRA_BIG_TEXT,
        Notification.EXTRA_SUB_TEXT,
        Notification.EXTRA_INFO_TEXT,
        Notification.EXTRA_SUMMARY_TEXT
    )
}
