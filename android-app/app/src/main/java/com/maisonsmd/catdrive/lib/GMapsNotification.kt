package com.maisonsmd.catdrive.lib

import android.app.Notification
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.children
import org.json.JSONObject
import timber.log.Timber

const val GMAPS_PACKAGE = "com.google.android.apps.maps"

enum class ContentViewType {
    NORMAL,
    BIG,
    BEST,
}

internal class GMapsNotification(cx: Context, sbn: StatusBarNotification) : NavigationNotification(cx, sbn) {
    init {
        parseStandardExtras()

        val normalContent = getContentView(ContentViewType.NORMAL)
        if (normalContent != null)
            parseRemoteView(getRemoteViewGroup(normalContent))

        val bestContentView = getContentView(ContentViewType.BEST)
        if (bestContentView != normalContent)
            parseRemoteView(getRemoteViewGroup(bestContentView))
    }

    private fun parseStandardExtras() {
        val extras = mNotification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                )?.toString()?.trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val primaryInfo = extras.getCharSequence("android.ongoingActivityNoti.primaryInfo")
            ?.toString()
        val secondaryInfo = extras.getCharSequence("android.ongoingActivityNoti.secondaryInfo")
            ?.toString()

        if (!title.isNullOrEmpty() || !text.isNullOrEmpty()) {
            val parsedDirection = GoogleMapsNavigationText.parseDirection(
                title = title?.takeIf { it.isNotEmpty() } ?: text,
                primaryInfo = primaryInfo,
                secondaryInfo = secondaryInfo
            )
            navigationData.nextDirection = parsedDirection.copy(
                distance = parsedDirection.distance?.takeIf { it.isNotEmpty() }
                    ?: navigationData.nextDirection.distance
            )
        }

        val arrivalTime = subText
            ?.takeIf { it.contains(':') }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (arrivalTime != null) {
            navigationData.eta = navigationData.eta.copy(eta = arrivalTime)
        }

        val iconKeys = listOf(
            "android.progressTrackerIcon",
            "android.ongoingActivityNoti.chipIcon",
            "android.ongoingActivityNoti.nowbarIcon",
            "android.ongoingActivityNoti.secondIcon"
        )
        for (key in iconKeys) {
            val icon = extras.get(key) as? Icon ?: continue
            val bitmap = try {
                icon.loadDrawable(mAppSourceContext)?.toBitmap()
            } catch (error: Exception) {
                Timber.w(error, "Could not load Google Maps navigation icon from $key")
                null
            }
            if (bitmap != null) {
                navigationData.actionIcon = NavigationIcon(bitmap)
                break
            }
        }
    }

    private fun getContentView(type: ContentViewType = ContentViewType.BEST): RemoteViews? {
        if (type == ContentViewType.BIG || type == ContentViewType.BEST) {
            val remoteViews = Notification.Builder.recoverBuilder(mContext, mNotification).createBigContentView()

            if (remoteViews != null || type == ContentViewType.BIG)
                return remoteViews
        }

        return Notification.Builder.recoverBuilder(mContext, mNotification).createContentView()
    }

    private fun getRemoteViewGroup(remoteViews: RemoteViews?): ViewGroup {
        if (remoteViews == null) {
            throw Exception("Impossible to create notification view")
        }

        val layoutInflater = mAppSourceContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val viewGroup = layoutInflater.inflate(remoteViews.layoutId, null) as ViewGroup?
            ?: throw Exception("Impossible to inflate viewGroup")

        remoteViews.reapply(mAppSourceContext, viewGroup)

        return viewGroup
    }

    private fun getEntryName(item: View): String {
        val entryName: String = try {
            if (item.id > 0)
                mAppSourceContext.resources.getResourceEntryName(item.id)
            else ""
        } catch (e: Exception) {
            ""
        }

        return entryName
    }

    private fun getEntryJsonKey(item: View): String {
        return "${item.javaClass.simpleName}:${getEntryName(item)}"
    }

    private fun findChildByName(group: ViewGroup, name: CharSequence): View? {
        for (child in group.children) {
            val entryName = getEntryName(child)

            if (entryName == name)
                return child

            if (child is ViewGroup) {
                val c = findChildByName(child, name)
                if (c != null)
                    return c
            }
        }

        return null
    }

    private fun parseRemoteView(group: ViewGroup): NavigationData {
        val data = navigationData

        val directionText = findChildByName(group, "text") as TextView?
        val etaText = findChildByName(group, "header_text") as TextView?
        val titleText = findChildByName(group, "title") as TextView?
        // val timeText = findChildByName(group, "time") as TextView?
        val rightIcon = findChildByName(group, "right_icon") as ImageView?

        // parse ETE & ETA
        if (etaText != null) {
            val etaList = etaText.text.split("·")
            if (etaList.size == 3) {
                val distance = etaList[1].trim()
                data.eta = NavigationEta(etaList[2].removeSuffix("ETA").trim(), etaList[0].trim(), distance)
            }
        }

        val parsedTitle = GoogleMapsNavigationText.parseDirection(
            title = titleText?.text?.toString(),
            primaryInfo = data.nextDirection.distance,
            secondaryInfo = data.nextDirection.nextRoadAdditionalInfo
        )
        val nextDistance = parsedTitle.distance
            ?.takeIf { it.isNotEmpty() }
            ?: data.nextDirection.distance.orEmpty()
        var nextRoad = data.nextDirection.nextRoad.orEmpty()
        var nextRoadDesc = data.nextDirection.nextRoadAdditionalInfo.orEmpty()
        if (nextRoad.isEmpty() && !parsedTitle.nextRoad.isNullOrEmpty()) {
            nextRoad = parsedTitle.nextRoad
        }
        if (directionText != null && directionText.text !is Spanned) {
            // must be the text "Rerouting..."
            val remoteDirection = directionText.text.toString().trim()
            if (remoteDirection.isNotEmpty()) {
                Timber.w("Direction Text is not Spanned, text: $remoteDirection")
                nextRoad = remoteDirection
            }
        } else if (directionText?.text is Spanned) {
            // Road names are in Typeface.BOLD, sub texts are in Typeface.NORMAL
            val directionList = ParserHelper.splitByStyleSpan(directionText.text as Spanned, Typeface.NORMAL, 2)
            if (directionList.isNotEmpty()) {
                val nextRoadList = mutableListOf(directionList.first())
                val nextRoadDescList = mutableListOf<ParserHelper.SpanSplitResult>()

                val rest = directionList.drop(1)
                val index = rest.indexOfFirst { it.isKeySpan && it.text.trim() != "/" }
                if (index == -1) {
                    nextRoadList.addAll(rest)
                } else {
                    nextRoadList.addAll(rest.subList(0, index))
                    nextRoadDescList.addAll(rest.subList(index, rest.size))
                }
                val remoteRoad = nextRoadList.joinToString(" ") { it.text }.trim()
                val remoteRoadDescription = nextRoadDescList.joinToString(" ") { it.text }.trim()
                if (remoteRoad.isNotEmpty()) nextRoad = remoteRoad
                if (remoteRoadDescription.isNotEmpty()) nextRoadDesc = remoteRoadDescription
            }
        }
        data.nextDirection = NavigationDirection(nextRoad, nextRoadDesc, nextDistance)

        (rightIcon?.drawable as BitmapDrawable?)?.bitmap?.also {
            data.actionIcon = NavigationIcon(it.copy(it.config, false))
        }

        // Timber.v("$data")

        return data
    }

    // for debugging
    private fun parseRemoteViewToJson(group: ViewGroup, json: JSONObject? = null): JSONObject {
        var rawJson = json ?: JSONObject()

        val parentGroupKey = getEntryJsonKey(group)
        rawJson.apply {
            put(parentGroupKey, JSONObject())
        }

        for (child in group.children) {
            val entryName = getEntryName(child)
            when (child) {
                is ImageView -> {
                    rawJson.getJSONObject(parentGroupKey).apply { put(getEntryJsonKey(child), entryName) }
                }
                is Button -> {
                    // result.getJSONObject(parentGroupKey).apply { put(getEntryJsonKey(child), child.text) }
                }
                is TextView -> {
                    rawJson.getJSONObject(parentGroupKey).apply {
                        put(getEntryJsonKey(child), JSONObject().apply {
                            put(
                                "rawText",
                                child.text
                            )
                        })
                    }
                }
                is ViewGroup -> {
                    rawJson = parseRemoteViewToJson(child, rawJson)
                }
            }
        }

        return rawJson
    }
}
