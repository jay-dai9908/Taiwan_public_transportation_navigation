package com.example.bus

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bus.tdx.CachedNearbyStation
import com.example.bus.tdx.DirectionStationMapping
import com.example.bus.tdx.GroupedNearbyStation
import com.example.bus.tdx.StationBusEstimateTime
import com.example.bus.tdx.TdxClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs

class NearbyStopsViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "NearbyStopsViewModel"
    private val updateIntervalMs = 30000L

    val cities: List<City> = listOf(
        City("台北市", "Taipei"),
        City("新北市", "NewTaipei"),
        City("桃園市", "Taoyuan"),
        City("台中市", "Taichung"),
        City("台南市", "Tainan"),
        City("高雄市", "Kaohsiung"),
        City("基隆市", "Keelung"),
        City("新竹市", "Hsinchu"),
        City("新竹縣", "HsinchuCounty"),
        City("苗栗縣", "MiaoliCounty"),
        City("彰化縣", "ChanghuaCounty"),
        City("南投縣", "NantouCounty"),
        City("雲林縣", "YunlinCounty"),
        City("嘉義縣", "ChiayiCounty"),
        City("嘉義市", "Chiayi"),
        City("屏東縣", "PingtungCounty"),
        City("宜蘭縣", "YilanCounty"),
        City("花蓮縣", "HualienCounty"),
        City("台東縣", "TaitungCounty"),
        City("澎湖縣", "PenghuCounty"),
        City("金門縣", "KinmenCounty"),
        City("連江縣", "LienchiangCounty")
    )

    private val _busArrivals = MutableStateFlow<List<StationBusEstimateTime>>(emptyList())
    val busArrivals: StateFlow<List<StationBusEstimateTime>> = _busArrivals.asStateFlow()

    private val _isFetchingArrivals = MutableStateFlow(false)
    val isFetchingArrivals: StateFlow<Boolean> = _isFetchingArrivals.asStateFlow()

    private val _nearbyStations = MutableStateFlow<List<CachedNearbyStation>>(emptyList())
    val nearbyStations: StateFlow<List<CachedNearbyStation>> = _nearbyStations.asStateFlow()

    private val _groupedNearbyStations = MutableStateFlow<List<GroupedNearbyStation>>(emptyList())
    val groupedNearbyStations: StateFlow<List<GroupedNearbyStation>> = _groupedNearbyStations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var fetchArrivalsJob: Job? = null
    private var currentTargetStationID: String? = null
    private var currentGroup: GroupedNearbyStation? = null

    private val _currentCity = MutableStateFlow<City?>(null)
    val currentCity: StateFlow<City?> = _currentCity.asStateFlow()

    private var lastUpdateTimeMillis: Long = 0L

    // 用於儲存到站狀態、排序和顯示的資料類別
    data class NearbyArrivalInfo(
        val status: Status,
        val sortKey: Long,
    ) : Comparable<NearbyArrivalInfo> {
        // 排序優先級： 即時到站 < 預計發車 < 無資料
        enum class Status { COMING, SCHEDULED, NO_DATA }

        override fun compareTo(other: NearbyArrivalInfo): Int {
            val statusCompare = this.status.compareTo(other.status)
            if (statusCompare != 0) return statusCompare
            return this.sortKey.compareTo(other.sortKey)
        }
    }

    // --- 方位角快取 ---
    private val directionMappingCacheFile by lazy {
        File(getApplication<Application>().cacheDir, "direction_mapping_cache.json")
    }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var directionMappingCache: MutableMap<String, DirectionStationMapping> = mutableMapOf()
    private val SEVEN_DAYS_IN_MILLIS = 7 * 24 * 60 * 60 * 1000L

    init {
        loadDirectionMappingCache()
    }

    private fun loadDirectionMappingCache() {
        viewModelScope.launch {
            if (directionMappingCacheFile.exists()) {
                try {
                    val jsonString = directionMappingCacheFile.readText()
                    val mappings = json.decodeFromString(ListSerializer(DirectionStationMapping.serializer()), jsonString)
                    val now = System.currentTimeMillis()
                    directionMappingCache = mappings
                        .filter { (now - it.timestamp) < SEVEN_DAYS_IN_MILLIS }
                        .associateBy { it.routeUID }
                        .toMutableMap()
                    Log.d(TAG, "Loaded ${directionMappingCache.size} valid direction mappings from cache.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading direction mapping cache", e)
                    directionMappingCache = mutableMapOf()
                }
            } else {
                 directionMappingCache = mutableMapOf()
                 Log.d(TAG, "Direction mapping cache file does not exist.")
            }
        }
    }

    private fun saveDirectionMappingCache() {
        viewModelScope.launch {
            try {
                 val now = System.currentTimeMillis()
                 val validMappings = directionMappingCache.values.filter { (now - it.timestamp) < SEVEN_DAYS_IN_MILLIS }
                val jsonString = json.encodeToString(ListSerializer(DirectionStationMapping.serializer()), validMappings)
                directionMappingCacheFile.writeText(jsonString)
                Log.d(TAG, "Saved ${validMappings.size} direction mappings to cache.")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving direction mapping cache", e)
            }
        }
    }
    // --- 方位角快取結束 ---

    fun findNearbyStops(chineseCityName: String, userLocation: Location, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _nearbyStations.value = emptyList()
            _groupedNearbyStations.value = emptyList()
            try {
                val normalizedCityName = chineseCityName.replace("臺", "台")
                val city = cities.find { normalizedCityName.startsWith(it.name) }
                if (city == null) {
                    Log.e(TAG, "Could not find a matching TDX city for '$chineseCityName'")
                    _isLoading.value = false
                    return@launch
                }
                _currentCity.value = city
                Log.i(TAG, "Current city set to: ${city.tdxName}")

                val allStationsInCity = TdxClient.getAllStationsInCity(city.tdxName, forceRefresh)
                Log.i(TAG, "Loaded ${allStationsInCity.size} total stations for ${city.tdxName}.")

                val radius = 500
                val nearbyStationsList = allStationsInCity.filter { station ->
                    val stationLocation = Location("").apply {
                        latitude = station.stationPosition.positionLat
                        longitude = station.stationPosition.positionLon
                    }
                    userLocation.distanceTo(stationLocation) <= radius
                }
                Log.i(TAG, "Filtered to ${nearbyStationsList.size} stations within ${radius}m.")

                val sortedNearbyStations = nearbyStationsList.sortedBy { station ->
                    val stopLocation = Location("").apply {
                        latitude = station.stationPosition.positionLat
                        longitude = station.stationPosition.positionLon
                    }
                    userLocation.distanceTo(stopLocation)
                }

                _nearbyStations.value = sortedNearbyStations

                val groupedStations = sortedNearbyStations
                    .groupBy { it.stationName.zhTw ?: "未知站牌" }
                    .map { (name, stationsInGroup) ->
                        val closestStationInGroup = stationsInGroup.minByOrNull { station ->
                            val stationLoc = Location("").apply {
                                latitude = station.stationPosition.positionLat
                                longitude = station.stationPosition.positionLon
                            }
                            userLocation.distanceTo(stationLoc)
                        }
                        GroupedNearbyStation(
                            stationName = name,
                            stations = stationsInGroup,
                            closestStationIdBasedOnLastSearch = closestStationInGroup?.stationID ?: stationsInGroup.first().stationID
                        )
                    }

                _groupedNearbyStations.value = groupedStations
                Log.i(TAG, "Grouped into ${groupedStations.size} unique station names.")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to find nearby stops", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pauseUpdates()
    }

    // 將 API 回傳的 StationBusEstimateTime 轉換為用於排序的 NearbyArrivalInfo
    private fun parseAndFormatArrival(arrival: StationBusEstimateTime): NearbyArrivalInfo {
        // 即時到站
        val estimateTime = arrival.estimateTime
        if (estimateTime != null && estimateTime >= 0) {
            val status = NearbyArrivalInfo.Status.COMING
            return NearbyArrivalInfo(status, estimateTime.toLong())
        }

        // 預計發車
        val nextBusTime = arrival.nextBusTime
        if (!nextBusTime.isNullOrBlank()) {
            try {
                val arrivalDateTime = LocalDateTime.parse(nextBusTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                val now = LocalDateTime.now(ZoneId.systemDefault())

                if (arrivalDateTime.isBefore(now)) {
                    return NearbyArrivalInfo(NearbyArrivalInfo.Status.NO_DATA, Long.MAX_VALUE - 1)
                }

                val duration = Duration.between(now, arrivalDateTime)
                val sortKey = duration.seconds.coerceAtLeast(0)

                return NearbyArrivalInfo(NearbyArrivalInfo.Status.SCHEDULED, sortKey)

            } catch (e: DateTimeParseException) {
                Log.w(TAG, "[NearbySort] Failed to parse NextBusTime: $nextBusTime", e)
            }
        }

        // 末班車已過等狀態
        val stopStatus = arrival.stopStatus
        val sortKey = if (stopStatus == 3) Long.MAX_VALUE - 1 else Long.MAX_VALUE
        return NearbyArrivalInfo(NearbyArrivalInfo.Status.NO_DATA, sortKey)
    }
    
    private fun sortArrivals(arrivals: List<StationBusEstimateTime>): List<StationBusEstimateTime> {
         return arrivals
            .map { arrival -> parseAndFormatArrival(arrival) to arrival }
            .sortedBy { it.first }
            .map { it.second }
    }

    // --- 方位角計算 ---
    // 計算兩個地點之間的方位角
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val startLocation = Location("").apply {
            latitude = lat1
            longitude = lon1
        }
        val endLocation = Location("").apply {
            latitude = lat2
            longitude = lon2
        }
        var bearing = startLocation.bearingTo(endLocation)
        if (bearing < 0) {
            bearing += 360
        }
        return bearing
    }

    // 估算路線某方向的大致行駛方位角
    private fun estimateRouteBearing(stops: List<com.example.bus.tdx.BusStop>): Float? {
        if (stops.size < 2) return null
        
        val startIndex = (stops.size / 3).coerceAtLeast(0)
        val endIndex = (startIndex + 1).coerceAtMost(stops.size - 1)
        
        val startStop = if (startIndex == endIndex && stops.size >= 2) stops.first() else stops[startIndex]
        val endStop = if (startIndex == endIndex && stops.size >= 2) stops.last() else stops[endIndex]

        if (startStop.stopPosition.positionLat == 0.0 || endStop.stopPosition.positionLat == 0.0 ||
            (startStop.stopPosition.positionLat == endStop.stopPosition.positionLat &&
             startStop.stopPosition.positionLon == endStop.stopPosition.positionLon)) {
            Log.w(TAG, "Cannot estimate route bearing due to invalid or identical coordinates for stops: ${startStop.stopName.zhTw} and ${endStop.stopName.zhTw}.")
            if (stops.size >= 2 && stops[0].stopPosition.positionLat != 0.0 && stops[1].stopPosition.positionLat != 0.0 &&
                (stops[0].stopPosition.positionLat != stops[1].stopPosition.positionLat || stops[0].stopPosition.positionLon != stops[1].stopPosition.positionLon) ) {
                Log.d(TAG, "Fallback: Estimating bearing using first two stops.")
                return calculateBearing(
                    stops[0].stopPosition.positionLat, stops[0].stopPosition.positionLon,
                    stops[1].stopPosition.positionLat, stops[1].stopPosition.positionLon
                )
            }
            return null
        }

        return calculateBearing(
            startStop.stopPosition.positionLat, startStop.stopPosition.positionLon,
            endStop.stopPosition.positionLat, endStop.stopPosition.positionLon
        )
    }

    // 判斷給定路線的 Direction 0 和 1 應該對應到群組中的哪個 stationID
    private suspend fun determineDirectionMapping(
        cityTdxName: String,
        routeName: String,
        routeUID: String,
        group: GroupedNearbyStation
    ): Map<Int, String>? {
        if (group.stations.size != 2) {
            Log.w(TAG, "Cannot determine mapping for group '${group.stationName}' ($routeUID) with ${group.stations.size} stations.")
            return null
        }

        val station1 = group.stations[0]
        val station2 = group.stations[1]

        try {
            val stopOfRouteList = TdxClient.getStopOfRoute(cityTdxName, routeName)
            val routeInfoDir0 = stopOfRouteList.find { it.direction == 0 }
            val routeInfoDir1 = stopOfRouteList.find { it.direction == 1 }

            val bearingDir0 = routeInfoDir0?.stops?.let { estimateRouteBearing(it) }
            val bearingDir1 = routeInfoDir1?.stops?.let { estimateRouteBearing(it) }

            val bearingS1toS2 = calculateBearing(
                station1.stationPosition.positionLat, station1.stationPosition.positionLon,
                station2.stationPosition.positionLat, station2.stationPosition.positionLon
            )
            val bearingS2toS1 = (bearingS1toS2 + 180) % 360

            Log.d(TAG, "Determining mapping for $routeName ($routeUID): Bearings Dir0=$bearingDir0, Dir1=$bearingDir1, S1(${station1.stationID})->S2(${station2.stationID})=$bearingS1toS2")

            if (bearingDir0 == null || bearingDir1 == null) {
                 Log.w(TAG, "Could not estimate bearing for both directions of route $routeName ($routeUID).")
                 return null
            }

            fun normalizeAngleDiff(diff: Float): Float = if (diff > 180) diff - 360 else diff

            val normDiff0_S1 = normalizeAngleDiff((bearingS2toS1 - bearingDir0 + 360) % 360)
            val normDiff0_S2 = normalizeAngleDiff((bearingS1toS2 - bearingDir0 + 360) % 360)

            Log.d(TAG, "Normalized Angle Diffs for $routeName ($routeUID): Dir0: S1=$normDiff0_S1, S2=$normDiff0_S2")

            val s1_is_right_of_dir0 = normDiff0_S1 > 0
            val s2_is_right_of_dir0 = normDiff0_S2 > 0

            val stationForDir0: String?
            val stationForDir1: String?

            if (s1_is_right_of_dir0 && !s2_is_right_of_dir0) {
                stationForDir0 = station1.stationID
                stationForDir1 = station2.stationID
            } else if (!s1_is_right_of_dir0 && s2_is_right_of_dir0) {
                stationForDir0 = station2.stationID
                stationForDir1 = station1.stationID
            } else {
                Log.w(TAG, "Ambiguous right-side match for route $routeName ($routeUID) and stations ${station1.stationID}/${station2.stationID}. Cannot determine mapping via bearing.")
                return null
            }
            
            Log.i(TAG, "Determined mapping for $routeName ($routeUID): Dir 0 -> $stationForDir0, Dir 1 -> $stationForDir1")
            return mapOf(0 to stationForDir0, 1 to stationForDir1)

        } catch (e: Exception) {
            Log.e(TAG, "Error determining direction mapping for $routeName ($routeUID)", e)
            return null
        }
    }
    // --- 方位角計算結束 ---

    private suspend fun updateArrivals(cityTdxName: String, group: GroupedNearbyStation, targetStationID: String) {
        if (currentTargetStationID != targetStationID || currentGroup?.stationName != group.stationName) {
            Log.w(TAG, "updateArrivals check: Target changed, stopping update.")
            return
        }

        try {
            Log.d(TAG, "Updating ETA for Group: ${group.stationName} (City: $cityTdxName), Target: $targetStationID")

            val arrivalsWithOrigin = group.stations.map { station ->
                viewModelScope.async {
                    try {
                        val arrivals = TdxClient.getEstimatedArrivalsForStation(cityTdxName, station.stationID)
                        arrivals.map { arrival -> arrival to station.stationID }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch ETA for station ${station.stationID}", e)
                        emptyList<Pair<StationBusEstimateTime, String>>()
                    }
                }
            }.awaitAll().flatten()

            val groupedByRoute = arrivalsWithOrigin.groupBy { it.first.routeUID }
            val anomalousRouteUIDs = groupedByRoute
                .filter { entry ->
                    entry.value.map { it.first }.size > 1 &&
                            entry.value.mapNotNull { it.first.direction }.distinct().size > 1
                }
                .keys

            val correctedPairs = mutableListOf<Pair<String, StationBusEstimateTime>>()
            val processedArrivalKeys = mutableSetOf<String>()
            var cacheNeedsSave = false
            val now = System.currentTimeMillis()

            for ((arrival, originStationID) in arrivalsWithOrigin) {
                if (arrival.direction == null || arrival.routeName?.zhTw == null || arrival.stopUID == null) {
                    correctedPairs.add(originStationID to arrival)
                    continue
                }

                val routeUID = arrival.routeUID
                val direction = arrival.direction
                val stopUID = arrival.stopUID!!
                val routeName = arrival.routeName.zhTw!!
                val arrivalKey = "${routeUID}_${direction}_${stopUID}_${arrival.estimateTime ?: arrival.nextBusTime}"

                if (routeUID !in anomalousRouteUIDs || !processedArrivalKeys.add(arrivalKey)) {
                    correctedPairs.add(originStationID to arrival)
                    continue
                }

                var belongsToStationID: String? = null
                var mappingSource = "Origin"
    
                val cachedMapping = directionMappingCache[routeUID]
                var directionToStationMap: Map<Int, String>? = null
    
                if (cachedMapping != null && (now - cachedMapping.timestamp) < SEVEN_DAYS_IN_MILLIS) {
                    val tempMap = mutableMapOf<Int, String>()
                    cachedMapping.stationIdDirection0?.let { tempMap[0] = it }
                    cachedMapping.stationIdDirection1?.let { tempMap[1] = it }
    
                    val groupStationIDs = group.stations.map { it.stationID }.toSet()
                    if (tempMap.values.all { it in groupStationIDs }) {
                        directionToStationMap = tempMap
                        mappingSource = "Cache"
                        Log.v(TAG, "Using valid cached mapping for $routeName ($routeUID)")
                    } else {
                        Log.w(TAG, "Cached mapping for $routeName ($routeUID) points outside current group ${group.stationName}. Invalidating cache entry.")
                        directionMappingCache.remove(routeUID)
                        cacheNeedsSave = true
                    }
                }
    
                if (directionToStationMap == null) {
                    Log.d(TAG, "Cache miss or invalid for $routeName ($routeUID), determining mapping via bearing...")
                    val bearingMapResult = determineDirectionMapping(cityTdxName, routeName, routeUID, group)
    
                    if (bearingMapResult != null) {
                        val groupStationIDs = group.stations.map { it.stationID }.toSet()
                        if (bearingMapResult.values.all { it in groupStationIDs }) {
                            directionToStationMap = bearingMapResult
                            mappingSource = "Bearing"
                            directionMappingCache[routeUID] = DirectionStationMapping(
                                routeUID = routeUID,
                                stationIdDirection0 = directionToStationMap[0],
                                stationIdDirection1 = directionToStationMap[1],
                                timestamp = now
                            )
                            cacheNeedsSave = true
                            Log.i(TAG, "Successfully determined and cached mapping for $routeName ($routeUID)")
                        } else {
                             Log.w(TAG, "Bearing mapping determined for $routeName ($routeUID) but points outside current group ${group.stationName}. Fallback.")
                        }
                    } else {
                        Log.w(TAG, "Failed to determine mapping for $routeName ($routeUID) using bearing. Fallback.")
                    }
                }
    
                if (directionToStationMap != null) {
                    belongsToStationID = directionToStationMap[direction]
                    if (belongsToStationID == null) {
                         Log.w(TAG, "Mapping from $mappingSource found for $routeName ($routeUID) but missing entry for direction $direction. Fallback to origin.")
                         belongsToStationID = originStationID
                         mappingSource = "OriginFallback"
                    }
                } else {
                    Log.d(TAG, "Executing fallback StopUID logic for $routeName ($routeUID), Dir $direction, StopUID $stopUID")
                    val stopOfRouteList = TdxClient.getStopOfRoute(cityTdxName, routeName)
                    val correctRouteInfo = stopOfRouteList.find { it.direction == direction }
                    val correctStopUIDsForDirection = correctRouteInfo?.stops?.mapNotNull { it.stopUID }?.toSet() ?: emptySet()
    
                    if (stopUID in correctStopUIDsForDirection) {
                        val correctStationInGroup = group.stations.find { station ->
                            station.stationUID == stopUID || station.stationID == stopUID
                        }
                        if (correctStationInGroup != null) {
                            belongsToStationID = correctStationInGroup.stationID
                            mappingSource = "FallbackStopUID"
                        } else {
                            Log.w(TAG, "Correction Warning (Fallback StopUID Found): StopUID '$stopUID' in route seq for ${routeName}(Dir $direction), but no matching station *in this group* ('${group.stationName}'). Discarding.")
                            belongsToStationID = null
                            mappingSource = "FallbackDiscard"
                        }
                    } else {
                        val otherStationInGroup = group.stations.find { it.stationID != originStationID }
                        if (otherStationInGroup != null) {
                             val otherDirection = if (direction == 0) 1 else 0
                             val otherRouteInfo = stopOfRouteList.find { it.direction == otherDirection }
                             val correctStopUIDsForOtherDirection = otherRouteInfo?.stops?.mapNotNull { it.stopUID }?.toSet() ?: emptySet()
                            if (stopUID in correctStopUIDsForOtherDirection &&
                                (otherStationInGroup.stationUID == stopUID || otherStationInGroup.stationID == stopUID)) {
                                belongsToStationID = otherStationInGroup.stationID
                                mappingSource = "FallbackStopUID"
                            } else {
                                Log.w(TAG, "Correction Warning (Fallback StopUID Wrong Dir): StopUID '$stopUID' for ${routeName}(Dir $direction) not found in other seq or doesn't match other station. Discarding.")
                                belongsToStationID = null
                                mappingSource = "FallbackDiscard"
                            }
                        } else {
                            Log.w(TAG, "Correction Warning (Fallback StopUID Wrong Dir): Cannot move ${routeName}(Dir $direction, StopUID $stopUID), only one station in group. Discarding.")
                            belongsToStationID = null
                            mappingSource = "FallbackDiscard"
                        }
                    }
                }
    
                if (belongsToStationID != null && belongsToStationID != originStationID) {
                    Log.d(TAG, "Corrected ($mappingSource): Assigned ${routeName}(Dir $direction, StopUID $stopUID) from origin $originStationID to $belongsToStationID")
                } else if (belongsToStationID == null && mappingSource == "FallbackDiscard") {
                    Log.d(TAG, "Discarded ($mappingSource): ${routeName}(Dir $direction, StopUID $stopUID) from origin $originStationID as it doesn't belong to group ${group.stationName}")
                }
    
                if (belongsToStationID != null) {
                    correctedPairs.add(belongsToStationID to arrival)
                }

            }

            if (cacheNeedsSave) {
                saveDirectionMappingCache()
            }

            val finalDisplayList = correctedPairs
                .filter { (correctedStationID, _) -> correctedStationID == targetStationID }
                .map { (_, arrival) -> arrival }

            _busArrivals.value = sortArrivals(finalDisplayList)
            lastUpdateTimeMillis = System.currentTimeMillis()
            Log.d(TAG, "ETA updated for $targetStationID. Fetched pairs: ${arrivalsWithOrigin.size}, Corrected pairs: ${correctedPairs.size}, Final list size: ${finalDisplayList.size}")

        } catch (e: Exception) {
             Log.e(TAG, "Failed to update ETA for group ${group.stationName}", e)
             _busArrivals.value = emptyList()
        }
    }

    fun startFetchingArrivals(group: GroupedNearbyStation, targetStationID: String) {
        pauseUpdates()
    
        val city = _currentCity.value?.tdxName
        if (city == null) {
            Log.e(TAG, "Cannot start fetching: currentCity is null.")
            return
        }
    
        currentGroup = group
        currentTargetStationID = targetStationID
        _isFetchingArrivals.value = true
        _busArrivals.value = emptyList()
        lastUpdateTimeMillis = 0L
    
        Log.d(TAG, "Starting initial fetch for Group: ${group.stationName}, Target: $targetStationID")
    
        viewModelScope.launch {
            updateArrivals(city, group, targetStationID)
            _isFetchingArrivals.value = false
            resumeUpdates()
        }
    }

    fun resumeUpdates() {
        if (_isFetchingArrivals.value) {
            Log.d(TAG, "ResumeUpdates called, but initial fetch is in progress. Aborting resume.")
            return
        }
    
        val group = currentGroup
        val targetID = currentTargetStationID
        val city = _currentCity.value?.tdxName
    
        if (group != null && targetID != null && city != null && fetchArrivalsJob?.isActive != true) {
            Log.d(TAG, "Resuming updates for TargetStationID: $targetID")
    
            fetchArrivalsJob = viewModelScope.launch {
                val currentTime = System.currentTimeMillis()
                val elapsedTime = currentTime - lastUpdateTimeMillis
    
                if (lastUpdateTimeMillis == 0L || elapsedTime >= updateIntervalMs) {
                    Log.d(TAG, "Resuming: Last update too old or first time, updating immediately.")
    
                    if (!_isFetchingArrivals.value) {
                        _isFetchingArrivals.value = true
                         updateArrivals(city, group, targetID)
                        _isFetchingArrivals.value = false
                    } else {
                        Log.d(TAG, "Resuming: Initial fetch still in progress, skipping immediate update.")
                    }
                } else {
                    val remainingDelay = updateIntervalMs - elapsedTime
                    Log.d(TAG, "Resuming: Last update within interval, delaying for $remainingDelay ms.")
                    delay(remainingDelay)
                     if (isActive && currentTargetStationID == targetID) {
                        _isFetchingArrivals.value = true
                         updateArrivals(city, group, targetID)
                        _isFetchingArrivals.value = false
                     } else {
                        Log.d(TAG,"Resuming: Target ID changed during delay, cancelling.")
                        return@launch
                     }
                }
    
                while (isActive && currentTargetStationID == targetID) {
                    delay(updateIntervalMs)
                     if (isActive && currentTargetStationID == targetID) {
                         val loopGroup = currentGroup
                         val loopTargetID = currentTargetStationID
                         val loopCity = _currentCity.value?.tdxName
                         if (loopGroup != null && loopTargetID != null && loopCity != null) {
                             updateArrivals(loopCity, loopGroup, loopTargetID)
                         } else {
                             Log.d(TAG,"Loop: State became invalid, stopping loop.")
                             break
                         }
                     } else {
                        Log.d(TAG,"Loop: Target ID changed during delay, stopping loop.")
                        break
                     }
                }
                 Log.d(TAG, "Update loop finished or cancelled for TargetStationID: $targetID")
            }
        } else {
             Log.d(TAG, "Resume called but conditions not met (targetID=$targetID, group=${group?.stationName}, job active=${fetchArrivalsJob?.isActive})")
        }
    }

    fun pauseUpdates() {
        if (fetchArrivalsJob?.isActive == true) {
            Log.d(TAG, "Pausing updates for TargetStationID: $currentTargetStationID")
            fetchArrivalsJob?.cancel()
            fetchArrivalsJob = null
        }
         _isFetchingArrivals.value = false
    }

    fun refreshArrivals() {
        Log.d(TAG, "Manual refresh requested for $currentTargetStationID")
        val group = currentGroup
        val targetID = currentTargetStationID
        if (group != null && targetID != null) {
            startFetchingArrivals(group, targetID)
        } else {
            Log.w(TAG, "Refresh called but no current group/target.")
        }
    }

    fun stopFetchingArrivals() {
        Log.d(TAG, "Stopping fetching arrivals completely for $currentTargetStationID")
        pauseUpdates()
        currentTargetStationID = null
        currentGroup = null
        lastUpdateTimeMillis = 0L
        _busArrivals.value = emptyList()
    }
}
