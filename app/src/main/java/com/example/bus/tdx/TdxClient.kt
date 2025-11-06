package com.example.bus.tdx

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.bus.BuildConfig
import com.google.android.gms.maps.model.LatLng
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

object TdxClient {

    private val TAG = "TdxClient"
    private val SEVEN_DAYS_IN_MILLIS = 7 * 24 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var cacheDir: File

    private const val STATION_GROUPING_DISTANCE_THRESHOLD = 150.0

    fun initialize(context: Context) {
        cacheDir = File(context.cacheDir, "tdx_cache").apply { mkdirs() }
    }

    private val authClient: TdxApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://tdx.transportdata.tw/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TdxApi::class.java)
    }
    
    private val apiClient: Retrofit by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

         Retrofit.Builder()
            .baseUrl("https://tdx.transportdata.tw/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
    
    private val apiService: TdxApi by lazy {
        apiClient.create(TdxApi::class.java)
    }

    private var token: TdxToken? = null
    
    private suspend fun getToken(): String {
        if (token == null || token!!.isExpired()) {
             val response = authClient.getToken(
                grantType = "client_credentials",
                clientId = BuildConfig.TDX_APP_ID,
                clientSecret = BuildConfig.TDX_APP_KEY
            )
            if (response.isSuccessful) {
                token = response.body()
            } else {
                throw Exception("Failed to get TDX token: ${response.errorBody()?.string()}")
            }
        }
        return "Bearer ${token?.accessToken}"
    }

    private suspend fun <T> getDataWithCache(
        cacheFileName: String,
        serializer: KSerializer<List<T>>,
        forceRefresh: Boolean,
        apiCall: suspend () -> Response<List<T>>
    ): List<T> {
        val cacheFile = File(cacheDir, cacheFileName)
        val isCacheValid = cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < SEVEN_DAYS_IN_MILLIS

        if (isCacheValid && !forceRefresh) {
            Log.d(TAG, "Loading from valid cache: $cacheFileName")
            val cachedData = readFromCache(cacheFile, serializer)
            if (cachedData != null) return cachedData
        }

        Log.d(TAG, "Cache missing, stale, or force-refreshed, fetching from API: $cacheFileName")
        try {
            val response = apiCall()
            if(response.isSuccessful) {
                val apiData = response.body()!!
                if (apiData.isNotEmpty()) {
                    saveToCache(cacheFile, serializer, apiData)
                } else {
                    Log.w(TAG, "API returned empty list for $cacheFileName. Cache will not be updated.")
                }
                return apiData
            } else {
                 throw Exception("API call not successful: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "API call failed for $cacheFileName", e)
            if (cacheFile.exists()) {
                Log.w(TAG, "API failed, using stale cache as fallback: $cacheFileName")
                return readFromCache(cacheFile, serializer) ?: emptyList()
            } else {
                return emptyList()
            }
        }
    }
    
    private fun <T> saveToCache(cacheFile: File, serializer: KSerializer<List<T>>, data: List<T>) {
        try {
            val jsonString = json.encodeToString(serializer, data)
            cacheFile.writeText(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache file ${cacheFile.name}", e)
        }
    }

    private fun <T> readFromCache(cacheFile: File, serializer: KSerializer<List<T>>): List<T>? {
        return try {
            val jsonString = cacheFile.readText()
            json.decodeFromString(serializer, jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cache file ${cacheFile.name}", e)
            null
        }
    }

    private fun transformApiStationsToCache(apiStations: List<BusStation>): List<CachedNearbyStation> {
        return apiStations.map { station ->
            CachedNearbyStation(
                stationUID = station.stationUID,
                stationID = station.stationID,
                stationName = station.stationName,
                stationPosition = station.stationPosition,
                stops = station.stops.map { stop ->
                    CachedStationStopInfo(
                        stopUID = stop.stopUID,
                        stopID = stop.stopID,
                        routeUID = stop.routeUID,
                        routeID = stop.routeID,
                        routeName = stop.routeName.zhTw ?: stop.routeName.en ?: "未知路線"
                    )
                }
            )
        }
    }

    private fun convertBusStationToCached(busStation: BusStation): CachedNearbyStation {
        return CachedNearbyStation(
            stationUID = busStation.stationUID ?: "",
            stationID = busStation.stationID ?: "",
            stationName = busStation.stationName,
            stationPosition = busStation.stationPosition,
            stops = emptyList()
        )
    }


    suspend fun getBusRoutes(city: String, filter: String, forceRefresh: Boolean = false): List<BusRoute> {
        val authorization = getToken()
        val cacheFileName = if (filter.contains("Stops/any")) {
            val stationId = filter.substringAfter("eq '").substringBefore("'")
            "routes_passing_station_${city}_$stationId.json"
        } else {
            "routes_${city}.json"
        }
        return getDataWithCache(cacheFileName, ListSerializer(BusRoute.serializer()), forceRefresh) {
            apiService.getBusRoutes(authorization, city, filter, 10000, "JSON")
        }
    }

    suspend fun getAllBusStops(city: String, forceRefresh: Boolean = false): List<BusStop> {
        val authorization = getToken()
        return getDataWithCache("stops_${city}.json", ListSerializer(BusStop.serializer()), forceRefresh) {
             apiService.getBusStops(authorization, city, null, 10000, "JSON")
        }
    }

    suspend fun getStopOfRoute(city: String, routeName: String, forceRefresh: Boolean = false): List<StopOfRoute> {
        val authorization = getToken()
        val filter = "RouteName/Zh_tw eq '$routeName'"
        val cacheFileName = "route_stops_${city}_${routeName}.json"
        return getDataWithCache(cacheFileName, ListSerializer(StopOfRoute.serializer()), forceRefresh) {
            apiService.getStopOfRoute(authorization, city, filter, "JSON")
        }
    }

    suspend fun getRouteShape(city: String, routeName: String, forceRefresh: Boolean = false): List<BusShape> {
        val authorization = getToken()
        val filter = "RouteName/Zh_tw eq '$routeName'"
        val cacheFileName = "route_shape_${city}_${routeName}.json"
        return getDataWithCache(cacheFileName, ListSerializer(BusShape.serializer()), forceRefresh) {
            apiService.getBusShape(authorization, city, filter, "JSON")
        }
    }

    suspend fun getAllStationsInCity(city: String, forceRefresh: Boolean = false): List<CachedNearbyStation> {
        val authorization = getToken()
        val cacheFileName = "stations_city_${city}.json"
        val cacheFile = File(cacheDir, cacheFileName)
        val serializer = ListSerializer(CachedNearbyStation.serializer())
        
        val isCacheValid = cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < SEVEN_DAYS_IN_MILLIS

        if (isCacheValid && !forceRefresh) {
            Log.d(TAG, "Loading all stations for $city from valid cache.")
            val cachedData = readFromCache(cacheFile, serializer)
            if (cachedData != null) return cachedData
        }

        Log.d(TAG, "Cache for all stations in $city is missing, stale, or force-refreshed. Fetching from API.")
        try {
            val response = apiService.getStationsInCity(authorization, city, 10000, "JSON")
            if (response.isSuccessful) {
                val apiData = response.body() ?: emptyList()
                Log.d(TAG, "Fetched ${apiData.size} total stations for city $city from API.")
                val transformedData = transformApiStationsToCache(apiData)
                
                if (transformedData.isNotEmpty()) {
                    saveToCache(cacheFile, serializer, transformedData)
                } else {
                    Log.w(TAG, "API returned empty list for all stations in $city. Cache will not be updated.")
                }
                return transformedData
            } else {
                throw Exception("Get all stations for city $city API call not successful: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get all stations for city $city API call failed", e)
            if (cacheFile.exists()) {
                Log.w(TAG, "API failed, using stale cache for all stations in $city as fallback.")
                return readFromCache(cacheFile, serializer) ?: emptyList()
            } else {
                return emptyList()
            }
        }
    }

    suspend fun getNearbyStations(latitude: Double, longitude: Double, radius: Int, forceRefresh: Boolean = false): List<CachedNearbyStation> {
        val authorization = getToken()
        val spatialFilter = "nearby(StationPosition, $latitude, $longitude, $radius)"
        
        val cacheFile = File(cacheDir, "nearby_stations.json")
        val serializer = ListSerializer(CachedNearbyStation.serializer())
        val isCacheValid = cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < SEVEN_DAYS_IN_MILLIS

        if (isCacheValid && !forceRefresh) {
            Log.d(TAG, "Loading nearby stations from valid cache: ${cacheFile.name}")
            val cachedData = readFromCache(cacheFile, serializer)
            if (cachedData != null) return cachedData
        }

        Log.d(TAG, "Cache missing, stale, or force-refreshed, fetching nearby stations from API.")
        try {
            val response = apiService.getNearbyStations(authorization, spatialFilter, 1000, "JSON")
            if(response.isSuccessful) {
                val apiData = response.body() ?: emptyList()
                Log.d(TAG, "Fetched ${apiData.size} stations from API.")
                val transformedData = transformApiStationsToCache(apiData)
                
                if (transformedData.isNotEmpty()) {
                    saveToCache(cacheFile, serializer, transformedData)
                } else {
                    Log.w(TAG, "API returned empty list for nearby stations. Cache will not be updated.")
                }
                return transformedData
            } else {
                 throw Exception("Nearby stations API call not successful: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nearby stations API call failed", e)
            if (cacheFile.exists()) {
                Log.w(TAG, "API failed, using stale cache for nearby stations as fallback.")
                return readFromCache(cacheFile, serializer) ?: emptyList()
            } else {
                return emptyList()
            }
        }
    }

    suspend fun getRealTimeBusPositions(city: String, routeName: String): List<BusRealTimePosition> {
        val authorization = getToken()
        val filter = "RouteName/Zh_tw eq '$routeName'"
        val response = apiService.getRealTimeBusPositions(authorization, city, filter, "JSON")
        return response.body() ?: emptyList()
    }

    suspend fun getEstimatedTimeOfArrivalForRoute(city: String, routeName: String): List<BusArrival> {
        val authorization = getToken()
        val filter = "RouteName/Zh_tw eq '$routeName'"
        val response = apiService.getEstimatedTimeOfArrivalForRoute(authorization, city, filter, "JSON")
        return response.body() ?: emptyList()
    }

    suspend fun getRoutesPassingStop(city: String, stopUID: String): List<BusRoute> {
        val authorization = getToken()
        val filter = "StopUID eq '$stopUID'"
        val response = apiService.getRoutesPassingStop(authorization, city, filter, 500, "JSON")
        val rawResponseBodyString = response.body()?.string()

        Log.d(TAG, "getRoutesPassingStop for $stopUID status: ${response.code()}")
        if (rawResponseBodyString != null) {
            Log.d(TAG, "getRoutesPassingStop Raw Response: $rawResponseBodyString")
            return if (response.isSuccessful && !rawResponseBodyString.isNullOrBlank()) {
                try {
                    json.decodeFromString(ListSerializer(BusRoute.serializer()), rawResponseBodyString)
                } catch (e: Exception) {
                    Log.e(TAG, "getRoutesPassingStop JSON parsing failed: ${e.message}")
                    emptyList()
                }
            } else {
                emptyList()
            }
        } else {
            Log.w(TAG, "getRoutesPassingStop Raw Response Body is null.")
            return emptyList()
        }
    }

    suspend fun getEstimatedArrivalsForRoutesAtStop(city: String, stopUID: String, routeUIDs: List<String>): List<BusArrival> {
        if (routeUIDs.isEmpty()) {
            return emptyList()
        }
        val authorization = getToken()
        val routeUIDsString = routeUIDs.joinToString(separator = "','", prefix = "'", postfix = "'")
        val filter = "StopUID eq '$stopUID' and RouteUID in ($routeUIDsString)"
        val response = apiService.getEstimatedArrivalsForRoutesAtStop(authorization, city, filter, 500, "JSON")
        val rawResponseBodyString = response.body()?.string()

        Log.d(TAG, "getEstimatedArrivalsForRoutesAtStop for $stopUID and routes status: ${response.code()}")
        if (rawResponseBodyString != null) {
            Log.d(TAG, "getEstimatedArrivalsForRoutesAtStop Raw Response: $rawResponseBodyString")
             return if (response.isSuccessful && rawResponseBodyString.isNotBlank()) {
                try {
                    json.decodeFromString(ListSerializer(BusArrival.serializer()), rawResponseBodyString)
                } catch (e: Exception) {
                    Log.e(TAG, "getEstimatedArrivalsForRoutesAtStop JSON parsing failed: ${e.message}")
                    emptyList()
                }
            } else {
                emptyList()
            }
        } else {
             Log.w(TAG, "getEstimatedArrivalsForRoutesAtStop Raw Response Body is null.")
             return emptyList()
        }
    }

    suspend fun getEstimatedArrivalsForStation(city: String, stationID: String): List<StationBusEstimateTime> {
        val authorization = getToken()
        val response = apiService.getEstimatedArrivalsForStation(authorization, city, stationID, 500, "JSON")
        return response.body() ?: emptyList()
    }

    suspend fun getBusEstimatedTimeOfArrival(city: String, routeName: String, filter: String? = null): List<StationBusEstimateTime> {
        val authorization = getToken()
        val response = apiService.getBusEstimatedTimeOfArrival(authorization, city, routeName, filter, "JSON")
        return response.body() ?: emptyList()
    }

    suspend fun getRoutesPassingThroughStation(city: String, stationID: String): List<BusRoute> {
        val authorization = getToken() // <-- 【新增】在呼叫前先獲取 Token
        return try {
            // 【修改】將 authorization 參數傳遞下去
            val response = apiService.getRoutesPassingThroughStation(authorization = authorization, city = city, stationId = stationID) 
            if (response.isSuccessful) {
                response.body() ?: emptyList()
            } else {
                Log.e("TdxClient", "Error fetching routes passing through station $stationID in $city. Code: ${response.code()}, Message: ${response.message()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("TdxClient", "Exception fetching routes passing through station $stationID in $city", e)
            emptyList()
        }
    }

    suspend fun findNearestStationGroup(
        targetLatLng: LatLng,
        cityStations: List<CachedNearbyStation>
    ): GroupedNearbyStation? {
        if (cityStations.isEmpty()) return null

        val targetLocation = Location("target").apply {
            latitude = targetLatLng.latitude
            longitude = targetLatLng.longitude
        }

        val stationsWithDistance = cityStations.mapNotNull { station ->
            if (station.stationPosition.positionLat == 0.0 && station.stationPosition.positionLon == 0.0) return@mapNotNull null
            if (station.stationName.zhTw.isNullOrBlank()) return@mapNotNull null

            val stationLocation = Location("station").apply {
                latitude = station.stationPosition.positionLat
                longitude = station.stationPosition.positionLon
            }
            val distance = targetLocation.distanceTo(stationLocation).toDouble()
            Triple(station, distance, stationLocation)
        }

        val closestTriple = stationsWithDistance.minByOrNull { it.second } ?: return null
        val closestStation = closestTriple.first
        val minDistance = closestTriple.second

        if (minDistance > STATION_GROUPING_DISTANCE_THRESHOLD * 1.5) {
            Log.w("TdxClient", "Nearest station ${closestStation.stationName.zhTw} (${closestStation.stationID}) is too far ($minDistance m). Cannot find group.")
            return null
        }

        val groupName = closestStation.stationName.zhTw!!
        val potentialGroupMembers = stationsWithDistance.filter { (station, _, _) ->
            station.stationName.zhTw == groupName
        }

        val closestStationLocation = closestTriple.third
        val groupMembers = potentialGroupMembers.filter { (_, distance, stationLocation) ->
            val distToClosest = stationLocation.distanceTo(closestStationLocation).toDouble()
            distToClosest <= STATION_GROUPING_DISTANCE_THRESHOLD
        }.map { it.first }

        if (groupMembers.isEmpty()) {
            Log.e("TdxClient", "Error: Group members list became empty for $groupName. This shouldn't happen.")
            return null
        }

        val closestInGroupToTarget = groupMembers.minByOrNull { cachedStation ->
            val stationLocation = Location("station").apply {
                latitude = cachedStation.stationPosition.positionLat
                longitude = cachedStation.stationPosition.positionLon
            }
            targetLocation.distanceTo(stationLocation)
        }?.stationID ?: closestStation.stationID

        Log.d("TdxClient", "Found group '$groupName' near target. Members: ${groupMembers.map { it.stationID }}. Closest in group to target: $closestInGroupToTarget")

        return GroupedNearbyStation(
            stationName = groupName,
            stations = groupMembers,
            closestStationIdBasedOnLastSearch = closestInGroupToTarget
        )
    }
}
