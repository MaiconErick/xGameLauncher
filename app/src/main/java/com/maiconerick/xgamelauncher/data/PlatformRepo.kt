package com.maiconerick.xgamelauncher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object PlatformRepo {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val intentsSerializer = ListSerializer(LaunchIntent.serializer())
    private val pathsSerializer = ListSerializer(String.serializer())

    suspend fun list(db: AppDatabase): List<Platform> = withContext(Dispatchers.IO) {
        db.platformMetaDao().all().map { it.toPlatform() }
    }

    suspend fun get(db: AppDatabase, id: String): Platform? = withContext(Dispatchers.IO) {
        db.platformMetaDao().all().firstOrNull { it.id == id }?.toPlatform()
    }

    suspend fun upsert(db: AppDatabase, p: Platform, sortOrder: Int = 0) =
        withContext(Dispatchers.IO) {
            db.platformMetaDao().upsertAll(listOf(p.toRow(sortOrder)))
        }

    /** Replaces a platform's folders, keeping its sort order and everything else. */
    suspend fun updatePaths(db: AppDatabase, id: String, romsPath: String, extraPaths: List<String>) =
        withContext(Dispatchers.IO) {
            val row = db.platformMetaDao().all().firstOrNull { it.id == id } ?: return@withContext
            db.platformMetaDao().upsertAll(listOf(row.copy(
                romsPath = romsPath,
                extraPathsJson = json.encodeToString(pathsSerializer, extraPaths),
            )))
        }

    suspend fun delete(db: AppDatabase, platformId: String) = withContext(Dispatchers.IO) {
        db.romDao().clearPlatform(platformId)
        val remaining = db.platformMetaDao().all().filterNot { it.id == platformId }
        db.platformMetaDao().clear()
        db.platformMetaDao().upsertAll(remaining)
    }

    private fun PlatformMetaRow.toPlatform(): Platform {
        val intents = runCatching {
            json.decodeFromString(intentsSerializer, intentsJson)
        }.getOrDefault(emptyList())
        val extras = runCatching {
            extraPathsJson?.let { json.decodeFromString(pathsSerializer, it) } ?: emptyList()
        }.getOrDefault(emptyList())
        return Platform(
            id = id,
            name = name,
            romsPath = romsPath,
            extraPaths = extras,
            fileExtensionsRegex = fileExtensionsRegex,
            intents = intents,
            defaultIntentId = defaultIntentId,
            artSearchName = artSearchName,
            columnsWithoutPanel = columnsWithoutPanel,
            columnsWithPanel = columnsWithPanel,
            accentColorHex = accentColorHex,
            backgroundImagePath = backgroundImagePath,
            iconImagePath = iconImagePath,
        )
    }

    private fun Platform.toRow(sortOrder: Int) = PlatformMetaRow(
        id = id,
        name = name,
        romsPath = romsPath,
        extraPathsJson = json.encodeToString(pathsSerializer, extraPaths),
        fileExtensionsRegex = fileExtensionsRegex,
        intentsJson = json.encodeToString(intentsSerializer, intents),
        defaultIntentId = defaultIntentId,
        artSearchName = artSearchName,
        sortOrder = sortOrder,
        columnsWithoutPanel = columnsWithoutPanel,
        columnsWithPanel = columnsWithPanel,
        accentColorHex = accentColorHex,
        backgroundImagePath = backgroundImagePath,
        iconImagePath = iconImagePath,
    )
}
