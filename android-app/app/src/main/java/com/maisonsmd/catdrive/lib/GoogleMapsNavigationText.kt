package com.maisonsmd.catdrive.lib

object GoogleMapsNavigationText {
    private val distancePattern =
        Regex("""^\d+(?:[.,]\d+)?\s*(?:m|km|ft|mi|yd)$""", RegexOption.IGNORE_CASE)

    fun parseDirection(
        title: String?,
        primaryInfo: String?,
        secondaryInfo: String?
    ): NavigationDirection {
        val normalizedTitle = normalizeWhitespace(title).orEmpty()
        val titleParts = normalizedTitle.split("·", limit = 2).map(String::trim)
        val titleDistance = titleParts.firstOrNull()
            ?.takeIf {
                (titleParts.size == 2 || titleParts.size == 1) &&
                        distancePattern.matches(it)
            }
        val titleInstruction = if (titleDistance != null) {
            titleParts.getOrNull(1).orEmpty()
        } else {
            normalizedTitle
        }
        val normalizedPrimaryInfo = normalizeWhitespace(primaryInfo)
        val distance = normalizedPrimaryInfo
            ?.takeIf { distancePattern.matches(it) }
            ?: titleDistance
        val description = normalizeWhitespace(secondaryInfo)
            ?.takeIf { it.isNotEmpty() }

        return NavigationDirection(
            nextRoad = titleInstruction,
            nextRoadAdditionalInfo = description.orEmpty(),
            distance = distance.orEmpty()
        )
    }

    private fun normalizeWhitespace(value: String?): String? =
        value?.replace('\u00A0', ' ')
            ?.replace('\u202F', ' ')
            ?.trim()
}
