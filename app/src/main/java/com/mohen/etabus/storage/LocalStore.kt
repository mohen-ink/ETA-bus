package com.mohen.etabus.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.google.gson.Gson
import com.mohen.etabus.model.RouteDetail
import com.mohen.etabus.model.SavedItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Entity(tableName = "favorites", primaryKeys = ["cityCode", "id"], indices = [Index(value = ["cityCode", "sortOrder"])])
internal data class FavoriteEntity(val cityCode: String, val id: String, val sortOrder: Long, val payload: String)

@Entity(tableName = "history", primaryKeys = ["cityCode", "id"], indices = [Index(value = ["cityCode", "viewedAt"])])
internal data class HistoryEntity(val cityCode: String, val id: String, val viewedAt: Long, val payload: String)

@Entity(tableName = "route_cache", primaryKeys = ["cityCode", "routeName", "direction"])
internal data class RouteCacheEntity(
    val cityCode: String, val routeName: String, val direction: String, val cachedAt: Long, val payload: String
)

@Dao
internal interface LocalDao {
    @Query("SELECT * FROM favorites WHERE cityCode = :cityCode ORDER BY sortOrder ASC, id ASC")
    fun favorites(cityCode: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE cityCode = :cityCode ORDER BY sortOrder ASC, id ASC")
    suspend fun favoriteList(cityCode: String): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE cityCode = :cityCode AND id = :id LIMIT 1")
    suspend fun favorite(cityCode: String, id: String): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE id IN (:ids)")
    suspend fun favoritesByIds(ids: List<String>): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE cityCode = :cityCode AND id = :id")
    suspend fun deleteFavorite(cityCode: String, id: String)

    @Query("DELETE FROM favorites WHERE cityCode = :cityCode")
    suspend fun clearFavorites(cityCode: String)

    @Query("UPDATE favorites SET sortOrder = :sortOrder WHERE cityCode = :cityCode AND id = :id")
    suspend fun moveFavorite(cityCode: String, id: String, sortOrder: Long)

    @Query("SELECT * FROM history WHERE cityCode = :cityCode ORDER BY viewedAt DESC, id ASC LIMIT 50")
    fun history(cityCode: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun historyById(id: String): List<HistoryEntity>

    @Query("SELECT MAX(viewedAt) FROM history WHERE cityCode = :cityCode")
    suspend fun latestHistoryTime(cityCode: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHistory(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE cityCode = :cityCode AND id NOT IN (SELECT id FROM history WHERE cityCode = :cityCode ORDER BY viewedAt DESC, id ASC LIMIT 50)")
    suspend fun trimHistory(cityCode: String)

    @Query("DELETE FROM history WHERE cityCode = :cityCode AND id = :id")
    suspend fun deleteHistory(cityCode: String, id: String)

    @Query("DELETE FROM history WHERE cityCode = :cityCode")
    suspend fun clearHistory(cityCode: String)

    @Query("SELECT * FROM route_cache WHERE cityCode = :cityCode AND routeName = :routeName AND direction = :direction LIMIT 1")
    suspend fun cachedRoute(cityCode: String, routeName: String, direction: String): RouteCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRoute(entity: RouteCacheEntity)

    @Query("DELETE FROM route_cache WHERE cityCode = :cityCode AND routeName = :routeName AND direction = :direction")
    suspend fun deleteRoute(cityCode: String, routeName: String, direction: String)
}

@Database(entities = [FavoriteEntity::class, HistoryEntity::class, RouteCacheEntity::class], version = 1, exportSchema = false)
internal abstract class LocalDatabase : RoomDatabase() {
    abstract fun localDao(): LocalDao
}

@Singleton
class LocalStore @Inject constructor(@ApplicationContext context: Context) {
    private val database = Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, "etabus_local.db").build()
    private val dao = database.localDao()
    private val gson = Gson()

    fun favorites(cityCode: String): Flow<List<SavedItem>> = dao.favorites(cityCode).map { rows ->
        rows.mapNotNull { row -> decodeSaved(row.payload, row.cityCode, row.id)?.copy(sortOrder = row.sortOrder) }
    }.distinctUntilChanged()

    fun history(cityCode: String): Flow<List<SavedItem>> = dao.history(cityCode).map { rows ->
        rows.mapNotNull { row -> decodeSaved(row.payload, row.cityCode, row.id)?.copy(viewedAt = row.viewedAt) }
    }.distinctUntilChanged()

    suspend fun toggleFavorite(item: SavedItem) {
        validate(item)
        database.withTransaction {
            val existing = dao.favorite(item.cityCode, item.id)
            if (existing == null) {
                val order = dao.favoriteList(item.cityCode).maxOfOrNull { it.sortOrder }?.plus(1) ?: 0L
                putFavorite(item.copy(sortOrder = order))
            } else {
                dao.deleteFavorite(item.cityCode, item.id)
            }
        }
    }

    suspend fun saveFavorite(item: SavedItem) {
        validate(item)
        database.withTransaction { putFavorite(item) }
    }

    suspend fun deleteFavorite(id: String) {
        database.withTransaction {
            val matches = dao.favoritesByIds(listOf(id))
            require(matches.size <= 1) { "收藏 ID 在多个城市重复，请使用带城市代码的删除接口" }
            matches.singleOrNull()?.let { dao.deleteFavorite(it.cityCode, id) }
        }
    }

    suspend fun deleteFavorite(cityCode: String, id: String) = dao.deleteFavorite(cityCode, id)

    suspend fun reorderFavorites(ids: List<String>) {
        if (ids.isEmpty()) return
        database.withTransaction {
            val matches = dao.favoritesByIds(ids.distinct())
            val cities = matches.map { it.cityCode }.distinct()
            require(cities.size <= 1) { "不能跨城市排序收藏，请使用带城市代码的排序接口" }
            cities.singleOrNull()?.let { reorder(it, ids) }
        }
    }

    suspend fun reorderFavorites(cityCode: String, ids: List<String>) {
        database.withTransaction { reorder(cityCode, ids) }
    }

    suspend fun record(item: SavedItem) {
        validate(item)
        database.withTransaction {
            val previousTime = dao.latestHistoryTime(item.cityCode) ?: 0L
            val viewedAt = maxOf(System.currentTimeMillis(), previousTime + 1)
            val latest = item.copy(viewedAt = viewedAt)
            dao.putHistory(HistoryEntity(latest.cityCode, latest.id, latest.viewedAt, gson.toJson(latest)))
            dao.trimHistory(item.cityCode)
        }
    }

    suspend fun deleteHistory(id: String) {
        database.withTransaction {
            val matches = dao.historyById(id)
            require(matches.size <= 1) { "历史 ID 在多个城市重复，请使用带城市代码的删除接口" }
            matches.singleOrNull()?.let { dao.deleteHistory(it.cityCode, id) }
        }
    }

    suspend fun deleteHistory(cityCode: String, id: String) = dao.deleteHistory(cityCode, id)
    suspend fun clearHistory(cityCode: String) = dao.clearHistory(cityCode)
    suspend fun clearFavorites(cityCode: String) = dao.clearFavorites(cityCode)

    suspend fun cachedRoute(cityCode: String, routeName: String, direction: String): RouteDetail? = database.withTransaction {
        val cached = dao.cachedRoute(cityCode, routeName, direction) ?: return@withTransaction null
        val age = System.currentTimeMillis() - cached.cachedAt
        val detail = if (age in 0 until CACHE_TTL) runCatching {
            gson.fromJson(cached.payload, RouteDetail::class.java)?.takeIf { it.routeName == routeName && it.direction == direction }
        }.getOrNull() else null
        if (detail == null) dao.deleteRoute(cityCode, routeName, direction)
        detail
    }

    suspend fun cacheRoute(cityCode: String, detail: RouteDetail) {
        require(cityCode.isNotBlank() && detail.routeName.isNotBlank()) { "缓存缺少城市或线路名称" }
        val staticDetail = detail.copy(buses = emptyList())
        dao.putRoute(RouteCacheEntity(cityCode, detail.routeName, detail.direction, System.currentTimeMillis(), gson.toJson(staticDetail)))
    }

    private suspend fun putFavorite(item: SavedItem) {
        dao.putFavorite(FavoriteEntity(item.cityCode, item.id, item.sortOrder, gson.toJson(item)))
    }

    private suspend fun reorder(cityCode: String, ids: List<String>) {
        val all = dao.favoriteList(cityCode)
        val existing = all.associateBy { it.id }
        val ordered = ids.distinct().filter { it in existing }
        val chosen = ordered.toSet()
        val replacements = ordered.iterator()
        all.map { if (it.id in chosen) replacements.next() else it.id }.forEachIndexed { index, id ->
            dao.moveFavorite(cityCode, id, index.toLong())
        }
    }

    private fun decodeSaved(payload: String, cityCode: String, id: String): SavedItem? = runCatching {
        gson.fromJson(payload, SavedItem::class.java)?.takeIf {
            it.cityCode == cityCode && it.id == id && it.kind in com.mohen.etabus.model.SavedKind.entries
        }
    }.getOrNull()

    private fun validate(item: SavedItem) {
        require(item.id.isNotBlank() && item.cityCode.isNotBlank()) { "记录缺少 ID 或城市代码" }
    }

    private companion object {
        const val CACHE_TTL = 24 * 60 * 60 * 1000L
    }
}
