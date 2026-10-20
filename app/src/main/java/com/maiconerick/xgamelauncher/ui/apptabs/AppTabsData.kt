package com.maiconerick.xgamelauncher.ui.apptabs

import com.maiconerick.xgamelauncher.prefs.PrefsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * User-defined "tabs" on the Apps screen (Personal / Games / Companion / …). Each tab
 * is an ordered pill in the tab row; membership is many-to-many so an app can appear
 * under several tabs. Persisted as JSON in DataStore instead of Room — the shape is
 * tiny and this avoids another schema-version bump.
 */
@Serializable
data class AppTab(val id: String, val name: String)

/** A named horizontal separator inside a tab ("Downloaders"); apps can be placed under it. */
@Serializable
data class AppSection(val id: String, val name: String)

@Serializable
data class AppTabsData(
    val tabs: List<AppTab> = emptyList(),
    /** tabId -> packages assigned to it. */
    val memberships: Map<String, List<String>> = emptyMap(),
    /** tabId -> its separators, in display order. */
    val sections: Map<String, List<AppSection>> = emptyMap(),
    /** sectionId -> packages placed under it. */
    val sectionMembers: Map<String, List<String>> = emptyMap(),
) {
    fun sectionsIn(tabId: String): List<AppSection> = sections[tabId].orEmpty()

    /** The section [pkg] sits under in [tabId], or null (top of the tab). */
    fun sectionOf(tabId: String, pkg: String): String? =
        sectionsIn(tabId).firstOrNull { pkg in sectionMembers[it.id].orEmpty() }?.id

    fun withSectionAdded(tabId: String, name: String): AppTabsData {
        val id = "sec_" + System.currentTimeMillis().toString(36)
        return copy(sections = sections + (tabId to sectionsIn(tabId) + AppSection(id, name)))
    }

    fun withSectionRenamed(tabId: String, id: String, name: String): AppTabsData =
        copy(sections = sections + (tabId to sectionsIn(tabId).map { if (it.id == id) it.copy(name = name) else it }))

    fun withSectionMoved(tabId: String, id: String, delta: Int): AppTabsData {
        val list = sectionsIn(tabId).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return this
        val t = list[i]; list[i] = list[j]; list[j] = t
        return copy(sections = sections + (tabId to list))
    }

    fun withSectionRemoved(tabId: String, id: String): AppTabsData = copy(
        sections = sections + (tabId to sectionsIn(tabId).filterNot { it.id == id }),
        sectionMembers = sectionMembers - id,
    )

    /** Places [pkg] under [sectionId] in [tabId] (null = back to the top of the tab). */
    fun withAppInSection(tabId: String, pkg: String, sectionId: String?): AppTabsData {
        val cleared = sectionMembers.mapValues { (sid, pkgs) ->
            if (sectionsIn(tabId).any { it.id == sid }) pkgs - pkg else pkgs
        }
        val next = if (sectionId == null) cleared
                   else cleared + (sectionId to (cleared[sectionId].orEmpty() + pkg))
        return copy(sectionMembers = next)
    }

    fun packagesIn(tabId: String): List<String> = memberships[tabId].orEmpty()

    fun tabsFor(pkg: String): Set<String> =
        memberships.entries.filter { pkg in it.value }.map { it.key }.toSet()

    fun withTabAdded(name: String): AppTabsData {
        val id = "tab_" + System.currentTimeMillis().toString(36)
        return copy(tabs = tabs + AppTab(id, name))
    }

    fun withTabRenamed(id: String, name: String): AppTabsData =
        copy(tabs = tabs.map { if (it.id == id) it.copy(name = name) else it })

    fun withTabRemoved(id: String): AppTabsData = copy(
        tabs = tabs.filterNot { it.id == id },
        memberships = memberships - id,
        sections = sections - id,
        sectionMembers = sectionMembers - sectionsIn(id).map { it.id }.toSet(),
    )

    fun withMembershipToggled(tabId: String, pkg: String): AppTabsData {
        val current = memberships[tabId].orEmpty()
        val next = if (pkg in current) current - pkg else current + pkg
        return copy(memberships = memberships + (tabId to next))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(raw: String): AppTabsData =
            if (raw.isBlank()) AppTabsData()
            else runCatching { json.decodeFromString<AppTabsData>(raw) }.getOrDefault(AppTabsData())
        fun encode(data: AppTabsData): String = json.encodeToString(AppTabsData.serializer(), data)
    }
}

class AppTabsStore(private val prefs: PrefsStore) {
    val flow: Flow<AppTabsData> = prefs.appTabsJson.map { AppTabsData.decode(it) }
    suspend fun save(data: AppTabsData) = prefs.setAppTabsJson(AppTabsData.encode(data))
}
