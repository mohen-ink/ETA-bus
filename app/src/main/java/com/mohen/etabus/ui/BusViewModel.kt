package com.mohen.etabus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mohen.etabus.model.*
import com.mohen.etabus.network.BusApi
import com.mohen.etabus.platform.LocationService
import com.mohen.etabus.storage.CityCatalog
import com.mohen.etabus.storage.LocalStore
import com.mohen.etabus.storage.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val DefaultCity = CityConfig("0719", "十堰市", "0719.mygolbs.com", "8081", "MyBusServer")

data class BusUiState(
    val ready: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val city: CityConfig = DefaultCity,
    val center: GeoPoint? = null,
    val userPoint: GeoPoint? = null,
    val locatedCity: String = "",
    val nearby: List<StationGroup> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val arrivals: Map<String, List<Arrival>> = emptyMap(),
    val loadingKeys: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val locating: Boolean = false,
    val refreshing: Boolean = false,
    val searchedNearby: Boolean = false,
    val searchQuery: String = "",
    val searchRoutes: List<RouteSummary> = emptyList(),
    val searchStations: List<StationGroup> = emptyList(),
    val searchLoading: Boolean = false,
    val searchWarning: String? = null,
    val station: StationGroup? = null,
    val platformIndex: Int = 0,
    val selection: RouteSelection? = null,
    val detail: RouteDetail? = null,
    val detailCached: Boolean = false,
    val alightingIndex: Int? = null,
    val favorites: List<SavedItem> = emptyList(),
    val history: List<SavedItem> = emptyList(),
    val page: String = "home"
) {
    val platform: Platform? get() = station?.platforms?.getOrNull(platformIndex)
    val stationArrivals: List<Arrival> get() = platform?.let { arrivals[it.key] }.orEmpty()
    val routeArrivals: List<Arrival> get() = selection?.platform?.let { arrivals[it.key] }.orEmpty()
}

@HiltViewModel
class BusViewModel @Inject constructor(
    private val api: BusApi,
    private val catalog: CityCatalog,
    private val preferences: SettingsStore,
    private val store: LocalStore,
    private val location: LocationService
) : ViewModel() {
    private val mutable = MutableStateFlow(BusUiState())
    val ui: StateFlow<BusUiState> = mutable.asStateFlow()
    val cities: List<CityConfig> get() = catalog.cities
    fun cityInitial(city: CityConfig): String = catalog.initial(city)
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = notices.asSharedFlow()
    private var owner = "page-0"
    private var epoch = 0L
    private var pageScope = newPageScope()
    private var searchJob: Job? = null
    private var locationJob: Job? = null
    private val refreshMutex = Mutex()
    private var visibleFavorites = emptySet<String>()
    private var visibleNearby = emptySet<String>()
    private var homeHistory = false

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                val city = catalog.find(settings.cityCode)
                val cityChanged = mutable.value.city.code != city.code
                if (cityChanged) resetPage()
                mutable.update { previous ->
                    if (cityChanged) BusUiState(ready = true, settings = settings, city = city,
                        userPoint = previous.userPoint, locatedCity = previous.locatedCity, page = previous.page)
                    else previous.copy(ready = true, settings = settings, city = city)
                }
            }
        }
        viewModelScope.launch {
            ui.map { it.city.code }.distinctUntilChanged().collectLatest { code ->
                coroutineScope {
                    launch { store.favorites(code).collect { list -> mutable.update { it.copy(favorites = list) } } }
                    launch { store.history(code).collect { list -> mutable.update { it.copy(history = list) } } }
                }
            }
        }
    }

    private fun newPageScope() = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    private fun resetPage() {
        api.cancelOwner(owner)
        pageScope.cancel()
        epoch++
        owner = "page-$epoch"
        pageScope = newPageScope()
        searchJob = null
        visibleFavorites = emptySet()
        visibleNearby = emptySet()
        mutable.update { it.copy(loading = false, searchLoading = false, refreshing = false, loadingKeys = emptySet()) }
    }

    fun onPage(page: String) {
        if (mutable.value.page == page) return
        resetPage()
        mutable.update { it.copy(page = page, errors = emptyMap()) }
        when (page) {
            "home" -> if (mutable.value.center != null && mutable.value.nearby.isEmpty() && !homeHistory) loadNearby()
            "station" -> mutable.value.platform?.let { loadPlatform(it) }
            "route" -> loadRoute()
            "search" -> if (mutable.value.searchQuery.isNotBlank()) search(mutable.value.searchQuery)
        }
    }

    fun setHomeHistory(history: Boolean) {
        if (homeHistory == history) return
        homeHistory = history
        resetPage()
        if (!history && mutable.value.center != null && mutable.value.nearby.isEmpty()) loadNearby()
    }

    fun acceptPrivacy(accepted: Boolean) = viewModelScope.launch {
        if (!accepted) {
            locationJob?.cancel()
            resetPage()
        }
        preferences.setPrivacyAccepted(accepted)
    }

    private fun canQuery(): Boolean {
        if (mutable.value.settings.privacyAccepted) return true
        message("请先阅读并同意隐私说明后查询网络数据")
        return false
    }

    fun updateSettings(value: AppSettings): Job {
        val baseline = mutable.value.settings
        return viewModelScope.launch {
            preferences.transform { current ->
                current.copy(
                    radius = if (value.radius != baseline.radius) value.radius else current.radius,
                    refreshSeconds = if (value.refreshSeconds != baseline.refreshSeconds) value.refreshSeconds else current.refreshSeconds,
                    theme = if (value.theme != baseline.theme) value.theme else current.theme,
                    digitFont = if (value.digitFont != baseline.digitFont) value.digitFont else current.digitFont
                )
            }
        }
    }
    fun clearSearchHistory() = viewModelScope.launch { preferences.clearSearch() }
    fun rememberSearch(query: String = mutable.value.searchQuery) = viewModelScope.launch {
        if (query.isNotBlank()) preferences.addSearch(query.trim())
    }

    fun useCity(code: String) = viewModelScope.launch {
        if (catalog.cities.none { it.code == code }) return@launch
        locationJob?.cancel()
        preferences.useCity(code)
    }

    fun setCenter(point: GeoPoint, retainNearby: Boolean = false) {
        if (!point.valid) { message("请输入有效经纬度"); return }
        if (!canQuery()) return
        val previousVisible = visibleNearby
        resetPage()
        if (retainNearby) visibleNearby = previousVisible
        mutable.update { it.copy(
            center = point,
            nearby = if (retainNearby) it.nearby else emptyList(),
            expanded = if (retainNearby) it.expanded else emptySet(),
            searchedNearby = retainNearby && it.searchedNearby
        ) }
        loadNearby()
    }

    fun locate() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            mutable.update { it.copy(locating = true, errors = it.errors - "location") }
            try {
                val result = location.locate(mutable.value.settings.privacyAccepted)
                val match = catalog.cities.firstOrNull { it.code == result.cityCode }
                    ?: catalog.cities.firstOrNull { it.displayName == result.cityName.removeSuffix("市") }
                mutable.update { it.copy(userPoint = result.point, locatedCity = result.cityName) }
                if (match?.code == mutable.value.city.code) setCenter(result.point, retainNearby = true)
                else {
                    val text = "定位城市为${result.cityName.ifBlank { "未知" }}，请切换到对应城市或手动设置当前城市查询中心"
                    mutable.update { it.copy(errors = it.errors + ("location" to text)) }
                    message(text)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val text = e.message ?: "定位失败，请重试"
                mutable.update { it.copy(errors = it.errors + ("location" to text)) }
                message(text)
            }
            finally { mutable.update { it.copy(locating = false) } }
        }
    }

    fun loadNearby(radius: Int = mutable.value.settings.radius) {
        val snapshot = mutable.value
        val center = snapshot.center ?: return
        val requestOwner = owner
        pageScope.launch {
            if (!canQuery()) {
                mutable.update { it.copy(loading = false, searchLoading = false, refreshing = false) }
                return@launch
            }
            mutable.update { it.copy(loading = it.nearby.isEmpty(), refreshing = it.nearby.isNotEmpty(), errors = it.errors - "nearby") }
            try {
                val platforms = api.nearby(snapshot.city, center, radius, owner = requestOwner)
                val groups = groupPlatforms(platforms, center, radius)
                mutable.update { it.copy(nearby = groups, searchedNearby = true,
                    expanded = it.expanded.intersect(groups.map { group -> group.key }.toSet())) }
                groups.firstOrNull()?.platforms?.firstOrNull()?.let { fetchPlatform(it, requestOwner, snapshot.city) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("nearby", e) }
            finally { mutable.update { it.copy(loading = false, refreshing = false) } }
        }
    }

    private fun groupPlatforms(items: List<Platform>, center: GeoPoint?, radius: Int? = null): List<StationGroup> {
        val filtered = items.filter { it.point.valid }.distinctBy { it.key }.filter {
            center == null || radius == null || center.distanceTo(it.point) <= radius
        }
        return filtered.groupBy { it.name }.map { (name, platforms) ->
            val sorted = if (center == null) platforms else platforms.sortedBy { center.distanceTo(it.point) }
            StationGroup(name, sorted, center?.distanceTo(sorted.first().point))
        }.sortedWith(compareBy<StationGroup> { it.distanceMeters ?: Double.MAX_VALUE }.thenBy { it.name })
    }

    fun toggleStation(group: StationGroup) {
        val expanded = group.key !in mutable.value.expanded
        mutable.update { it.copy(expanded = if (expanded) it.expanded + group.key else it.expanded - group.key) }
        if (expanded) group.platforms.forEach { loadPlatform(it) }
    }

    fun loadPlatform(platform: Platform) {
        val requestOwner = owner
        val city = mutable.value.city
        pageScope.launch {
            if (!canQuery()) {
                mutable.update { it.copy(loading = false, searchLoading = false, refreshing = false) }
                return@launch
            }
            fetchPlatform(platform, requestOwner, city)
        }
    }

    private suspend fun fetchPlatform(platform: Platform, requestOwner: String, city: CityConfig, priority: Int = 0) {
        mutable.update { it.copy(loadingKeys = it.loadingKeys + platform.key, errors = it.errors - platform.key) }
        try {
            val records = api.station(city, platform, owner = requestOwner, priority = priority)
            mutable.update { it.copy(arrivals = it.arrivals + (platform.key to records)) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error(platform.key, e) }
        finally { mutable.update { it.copy(loadingKeys = it.loadingKeys - platform.key) } }
    }

    fun openStation(group: StationGroup) {
        mutable.update { it.copy(station = group, platformIndex = 0) }
        viewModelScope.launch { store.record(stationItem(group)) }
        if (mutable.value.page == "station") { resetPage(); group.platforms.firstOrNull()?.let { loadPlatform(it) } }
    }

    fun switchPlatform() {
        val current = mutable.value
        val count = current.station?.platforms?.size ?: return
        if (count < 2) return
        resetPage()
        mutable.update { it.copy(platformIndex = (it.platformIndex + 1) % count) }
        mutable.value.platform?.let { loadPlatform(it) }
    }

    fun openRoute(selection: RouteSelection) {
        mutable.update { it.copy(selection = selection, detail = null, alightingIndex = null, detailCached = false) }
        viewModelScope.launch { store.record(routeItem(selection)) }
        if (mutable.value.page == "route") { resetPage(); loadRoute() }
    }

    fun routeFromArrival(arrival: Arrival, platform: Platform) = openRoute(RouteSelection(
        arrival.routeName, arrival.direction, arrival.fromStation, arrival.toStation, platform, arrival.stationIndex
    ))

    fun loadRoute() {
        val selection = mutable.value.selection ?: return
        val city = mutable.value.city
        val requestOwner = owner
        pageScope.launch {
            if (!canQuery()) {
                mutable.update { it.copy(loading = false, searchLoading = false, refreshing = false) }
                return@launch
            }
            mutable.update { it.copy(loading = it.detail == null, errors = it.errors - "route") }
            try {
                if (mutable.value.detail == null) store.cachedRoute(city.code, selection.routeName, selection.direction)?.let { cached ->
                    mutable.update { it.copy(detail = cached, detailCached = true) }
                }
                val detail = api.route(city, selection, owner = requestOwner)
                store.cacheRoute(city.code, detail)
                val index = if (selection.platform != null) {
                    selection.stationIndex.takeIf { i -> i in detail.stations.indices && detail.stations[i] == selection.platform.name }
                        ?: detail.stations.indexOf(selection.platform.name)
                } else {
                    selection.stationIndex.takeIf { it in detail.stations.indices }
                        ?: detail.stationIndex.takeIf { it in detail.stations.indices } ?: -1
                }
                val platform = selection.platform?.takeIf { index >= 0 }
                    ?: detail.stationPoints.getOrNull(index)?.let { Platform(detail.stations[index], it) }
                mutable.update { it.copy(detail = detail, detailCached = false,
                    selection = selection.copy(platform = platform, stationIndex = index,
                        fromStation = detail.stations.firstOrNull().orEmpty(), toStation = detail.stations.lastOrNull().orEmpty())) }
                platform?.let { fetchPlatform(it, requestOwner, city) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("route", e) }
            finally { mutable.update { it.copy(loading = false) } }
        }
    }

    fun reverseDirection() {
        val current = mutable.value
        val old = current.selection ?: return
        val oldName = old.platform?.name
        resetPage()
        val next = old.copy(direction = if (old.direction == "1") "2" else "1", platform = null, stationIndex = -1)
        mutable.update { it.copy(selection = next, detail = null, alightingIndex = null, loading = true) }
        val city = current.city
        val requestOwner = owner
        pageScope.launch {
            if (!canQuery()) {
                mutable.update { it.copy(loading = false, searchLoading = false, refreshing = false) }
                return@launch
            }
            try {
                val detail = api.route(city, next, owner = requestOwner)
                val index = detail.stations.indexOf(oldName)
                val platform = if (index >= 0) detail.stationPoints.getOrNull(index)?.let { Platform(detail.stations[index], it) } else null
                val selection = next.copy(platform = platform, stationIndex = index,
                    fromStation = detail.stations.firstOrNull().orEmpty(), toStation = detail.stations.lastOrNull().orEmpty())
                mutable.update { it.copy(detail = detail, selection = selection, detailCached = false) }
                store.cacheRoute(city.code, detail)
                store.record(routeItem(selection))
                if (platform != null) fetchPlatform(platform, requestOwner, city)
                else if (oldName != null) message("反方向未找到可查询的同名站台，请选择等车站")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("route", e) }
            finally { mutable.update { it.copy(loading = false) } }
        }
    }

    fun chooseWaitingStation(index: Int) {
        val detail = mutable.value.detail ?: return
        if (index !in detail.stations.indices) return
        val point = detail.stationPoints.getOrNull(index)
        val platform = point?.let { Platform(detail.stations[index], it) }
        val selection = mutable.value.selection ?: return
        mutable.update { it.copy(selection = selection.copy(platform = platform, stationIndex = index), alightingIndex = null) }
        platform?.let { loadPlatform(it) } ?: message("该站点坐标缺失，无法查询实时到站")
        viewModelScope.launch { store.record(routeItem(mutable.value.selection ?: selection)) }
    }

    fun chooseAlightingStation(index: Int?) {
        val state = mutable.value
        val waiting = state.selection?.stationIndex ?: -1
        mutable.update { it.copy(alightingIndex = index?.takeIf { i -> i > waiting && i in state.detail?.stations.orEmpty().indices }) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        mutable.update { it.copy(searchQuery = query, searchLoading = query.isNotBlank(), searchWarning = null) }
        if (query.isBlank()) {
            mutable.update { it.copy(searchRoutes = emptyList(), searchStations = emptyList(), searchLoading = false) }
            return
        }
        val city = mutable.value.city
        val center = mutable.value.center
        val requestOwner = owner
        searchJob = pageScope.launch {
            if (!canQuery()) {
                mutable.update { it.copy(loading = false, searchLoading = false, refreshing = false) }
                return@launch
            }
            delay(400)
            val lineQuery = query.any(Char::isDigit)
            var routes: List<RouteSummary> = emptyList()
            var stations: List<StationGroup> = emptyList()
            val warnings = mutableListOf<String>()
            try {
                routes = api.search(city, query.trim(), center, byStation = !lineQuery, owner = requestOwner)
                    .sortedBy { if (normalizeRoute(it.routeName) == normalizeRoute(query)) 0 else 1 }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { warnings += e.message ?: "线路搜索失败" }
            if (center != null) {
                try { stations = groupPlatforms(api.nearby(city, center, mutable.value.settings.radius, query.trim(), requestOwner), center) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { warnings += e.message ?: "站点搜索失败" }
            } else warnings += "站点关键词查询需要当前城市的坐标，请先定位或设置查询中心；无坐标线路查询的兼容性尚未验证"
            mutable.update { it.copy(searchRoutes = routes, searchStations = stations, searchLoading = false,
                searchWarning = warnings.takeIf { list -> list.isNotEmpty() }?.joinToString("\n")) }
        }
    }

    fun favoriteStation(group: StationGroup) = viewModelScope.launch { store.toggleFavorite(stationItem(group)) }
    fun favoriteRoute(selection: RouteSelection) = viewModelScope.launch { store.toggleFavorite(routeItem(selection)) }
    fun restoreFavorite(item: SavedItem) = viewModelScope.launch { store.saveFavorite(item) }
    fun deleteFavorite(id: String) = viewModelScope.launch { store.deleteFavorite(id) }
    fun reorderFavorites(ids: List<String>) = viewModelScope.launch { store.reorderFavorites(ids) }
    fun deleteHistory(id: String) = viewModelScope.launch { store.deleteHistory(id) }
    fun clearHistory() = viewModelScope.launch { store.clearHistory(mutable.value.city.code) }
    fun clearFavorites() = viewModelScope.launch { store.clearFavorites(mutable.value.city.code) }
    fun isFavoriteStation(group: StationGroup): Boolean = mutable.value.favorites.any { it.id == stationItem(group).id }
    fun isFavoriteRoute(selection: RouteSelection): Boolean = mutable.value.favorites.any { it.id == routeItem(selection).id }

    private fun stationItem(group: StationGroup) = SavedItem(
        id = "${mutable.value.city.code}|station|${group.key}", cityCode = mutable.value.city.code,
        kind = SavedKind.STATION, station = group)
    private fun routeItem(route: RouteSelection) = SavedItem(
        id = "${mutable.value.city.code}|route|${normalizeRoute(route.routeName)}|${route.direction}|${route.platform?.key ?: route.stationIndex}",
        cityCode = mutable.value.city.code, kind = SavedKind.ROUTE, route = route)

    private val visibleJobs = mutableMapOf<String, Job>()

    private fun favoritePlatforms(): List<Platform> = mutable.value.favorites.filter { it.id in visibleFavorites }.flatMap { item ->
        item.route?.platform?.let { listOf(it) } ?: item.station?.platforms.orEmpty().take(1)
    }.distinctBy { it.key }

    private fun nearbyPlatforms(): List<Platform> {
        val state = mutable.value
        return state.nearby.filterIndexed { index, group ->
            group.key in visibleNearby && (index == 0 || group.key in state.expanded)
        }.flatMap { if (it.key in state.expanded) it.platforms else it.platforms.take(1) }.distinctBy { it.key }
    }

    private fun cancelInvisible(tag: String, platforms: List<Platform>) {
        val wanted = platforms.map { "$tag|${it.key}" }.toSet()
        visibleJobs.filterKeys { it.startsWith("$tag|") && it !in wanted }.values.toList().forEach { it.cancel() }
    }

    fun setVisibleFavorites(ids: Set<String>) {
        visibleFavorites = ids
        cancelInvisible("favorites", favoritePlatforms())
    }

    fun setVisibleNearby(keys: Set<String>) {
        visibleNearby = keys
        cancelInvisible("home", nearbyPlatforms())
    }

    private suspend fun queryVisible(platforms: List<Platform>, tag: String, priority: Int, onlyMissing: Boolean = false) = coroutineScope {
        val state = mutable.value
        val requestOwner = owner
        val pending = mutableListOf<Job>()
        for (platform in platforms) {
            val key = "$tag|${platform.key}"
            val existing = visibleJobs[key]
            if (existing?.isActive == true) {
                pending += existing
                continue
            }
            if (onlyMissing && platform.key in state.arrivals && platform.key !in state.errors) continue
            val job = launch(start = CoroutineStart.LAZY) {
                try {
                    val stillVisible = when (tag) {
                        "home" -> !homeHistory && nearbyPlatforms().any { it.key == platform.key }
                        "favorites" -> favoritePlatforms().any { it.key == platform.key }
                        else -> false
                    }
                    if (owner == requestOwner && mutable.value.page == tag && stillVisible) {
                        fetchPlatform(platform, requestOwner, state.city, priority)
                    }
                } finally {
                    if (visibleJobs[key] === currentCoroutineContext()[Job]) visibleJobs.remove(key)
                }
            }
            visibleJobs[key] = job
            pending += job
            job.start()
        }
        pending.joinAll()
    }

    suspend fun foregroundRefreshLoop() {
        ui.map { Triple(it.settings.refreshSeconds, it.page, epoch) }.distinctUntilChanged().collectLatest { (seconds, _, _) ->
            if (seconds <= 0) return@collectLatest
            while (currentCoroutineContext().isActive) {
                delay(seconds * 1000L)
                refreshVisible()
            }
        }
    }

    suspend fun refreshVisible() {
        if (!refreshMutex.tryLock()) return
        try {
            val state = mutable.value
            val requestOwner = owner
            if (!state.settings.privacyAccepted) return
            mutable.update { it.copy(refreshing = true) }
            when (state.page) {
                "home" -> if (!homeHistory) queryVisible(nearbyPlatforms(), "home", 1)
                "station" -> state.platform?.let { fetchPlatform(it, requestOwner, state.city, 1) }
                "route" -> state.selection?.let { selection ->
                    try {
                        val detail = api.route(state.city, selection, owner = requestOwner, priority = 1)
                        store.cacheRoute(state.city.code, detail)
                        mutable.update { it.copy(detail = detail, detailCached = false, errors = it.errors - "route") }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error("route", e) }
                    selection.platform?.let { fetchPlatform(it, requestOwner, state.city, 1) }
                }
                "favorites" -> queryVisible(favoritePlatforms(), "favorites", 1)
            }
        } finally {
            mutable.update { it.copy(refreshing = false) }
            refreshMutex.unlock()
        }
    }

    fun refreshFavorites() {
        pageScope.launch {
            if (canQuery()) queryVisible(favoritePlatforms(), "favorites", 0, onlyMissing = true)
        }
    }

    fun message(text: String) { notices.tryEmit(text) }
    private fun error(key: String, failure: Exception) {
        val text = failure.message ?: "网络请求失败，请重试"
        mutable.update { it.copy(errors = it.errors + (key to text)) }
    }
    override fun onCleared() {
        api.cancelOwner(owner)
        pageScope.cancel()
        super.onCleared()
    }
}