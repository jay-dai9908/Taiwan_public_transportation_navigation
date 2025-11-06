package com.example.bus

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bus.tdx.BusRealTimePosition
import com.example.bus.tdx.BusStop
import com.example.bus.tdx.StationBusEstimateTime
import com.example.bus.tdx.TdxClient
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class RouteDirectionData(
    val stops: List<BusStop> = emptyList(),
    val path: List<LatLng> = emptyList(),
    val destination: String? = null
)

class RouteStopsViewModel : ViewModel() {

    private val TAG = "RouteStopsViewModel"

    companion object {
        private const val UPDATE_INTERVAL_MS = 30000L // 30 seconds
    }

    // 靜態資料
    private val _directionAData = MutableStateFlow(RouteDirectionData())
    val directionAData: StateFlow<RouteDirectionData> = _directionAData.asStateFlow()

    private val _directionBData = MutableStateFlow(RouteDirectionData())
    val directionBData: StateFlow<RouteDirectionData> = _directionBData.asStateFlow()

    // 即時資料
    private val _busPositions = MutableStateFlow<List<BusRealTimePosition>>(emptyList())
    val busPositions: StateFlow<List<BusRealTimePosition>> = _busPositions.asStateFlow()

    private val _stopArrivals = MutableStateFlow<Map<String, StationBusEstimateTime>>(emptyMap())
    val stopArrivals: StateFlow<Map<String, StationBusEstimateTime>> = _stopArrivals.asStateFlow()

    // UI 狀態
    private val _selectedDirection = MutableStateFlow(0)
    val selectedDirection: StateFlow<Int> = _selectedDirection.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var realTimeUpdateJob: Job? = null
    private var trackedCity: String? = null
    private var trackedRouteName: String? = null
    private var lastUpdateTime: Long = 0
    private var initialDirectionSet = false

    /**
     * 為單一站牌提供專屬的到站時間 Flow
     * @param stopUID 站牌的唯一識別碼
     * @return 一個只會發出該特定站牌到站資訊的 Flow
     */
    fun getArrivalFlowForStop(stopUID: String): Flow<StationBusEstimateTime?> {
        return stopArrivals.map { arrivalsMap ->
            arrivalsMap[stopUID]
        }.distinctUntilChanged()
    }


    fun onDirectionSelected(index: Int) {
        _selectedDirection.value = index
    }

    fun setInitialDirection(direction: Int?) {
        if (!initialDirectionSet && direction != null && (direction == 0 || direction == 1)) {
            _selectedDirection.value = direction
            initialDirectionSet = true
            Log.d(TAG, "Initial direction set to: $direction")
        }
    }
    
    suspend fun fetchStaticRouteDataOnly(city: String, routeName: String?) {
        if (routeName.isNullOrBlank()) {
            Log.e(TAG, "fetchStaticRouteDataOnly called with null or blank routeName for city $city. Aborting.")
            _isLoading.value = false
             _directionAData.value = RouteDirectionData()
             _directionBData.value = RouteDirectionData()
             _busPositions.value = emptyList()
             _stopArrivals.value = emptyMap()
            return
        }
        
        if (trackedRouteName != routeName) {
            _isLoading.value = true
            _directionAData.value = RouteDirectionData()
            _directionBData.value = RouteDirectionData()
            trackedCity = city
            trackedRouteName = routeName
            initialDirectionSet = false
            Log.d(TAG, "Fetching static data for city='$city', route='$routeName'")
            try {
                fetchStaticRouteData(city, routeName)
            } catch (e: Exception) {
                 Log.e(TAG, "Error during fetchStaticRouteData for $routeName", e)
                 _directionAData.value = RouteDirectionData()
                 _directionBData.value = RouteDirectionData()
            } finally {
                _isLoading.value = false
                Log.d(TAG, "fetchStaticRouteDataOnly completed for $routeName")
            }
        } else {
             _isLoading.value = false
             Log.d(TAG, "fetchStaticRouteDataOnly skipped, route unchanged: $routeName")
        }
    }

    fun startRealTimeLoopOnly() {
        if (realTimeUpdateJob?.isActive == true) {
            Log.d(TAG, "startRealTimeLoopOnly: Loop is already active.")
            return
        }
        startRealTimeLoop()
    }
    
    fun pauseUpdates() {
        stopRouteTracking()
        Log.d(TAG, "Updates paused.")
    }
    
    fun resumeUpdates() {
        Log.d(TAG, "Updates resumed.")
        startRealTimeLoopOnly()
        manualRefresh()
    }

    private fun startRealTimeLoop() {
        realTimeUpdateJob?.cancel()
        val city = trackedCity ?: return
        val routeName = trackedRouteName ?: return

        realTimeUpdateJob = viewModelScope.launch {
            Log.i(TAG, "Starting real-time loop for $routeName")
            val elapsedTime = System.currentTimeMillis() - lastUpdateTime
            if (lastUpdateTime > 0 && elapsedTime < UPDATE_INTERVAL_MS) {
                val remainingTime = UPDATE_INTERVAL_MS - elapsedTime
                Log.d(TAG, "Resuming with delay of ${remainingTime}ms")
                delay(remainingTime)
            }

            while (isActive) {
                updateRealTimeData(city, routeName)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    fun manualRefresh() {
        viewModelScope.launch {
            val city = trackedCity ?: return@launch
            val routeName = trackedRouteName ?: return@launch

            _isRefreshing.value = true
            try {
                updateRealTimeData(city, routeName)
                startRealTimeLoop()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchStaticRouteData(city: String, routeName: String) {
       try {
            val stopOfRouteList = TdxClient.getStopOfRoute(city, routeName)
            val routeShapeList = TdxClient.getRouteShape(city, routeName)

            stopOfRouteList.find { it.direction == 0 }?.let {
                val pathA = routeShapeList.find { s -> s.direction == 0 }?.let { parseLineString(it.geometry) } ?: emptyList()
                _directionAData.value = RouteDirectionData(
                    stops = it.stops,
                    path = pathA,
                    destination = it.stops.lastOrNull()?.stopName?.zhTw?.let { "往 $it" } ?: "A向"
                )
            }

            stopOfRouteList.find { it.direction == 1 }?.let {
                val pathB = routeShapeList.find { s -> s.direction == 1 }?.let { parseLineString(it.geometry) } ?: emptyList()
                _directionBData.value = RouteDirectionData(
                    stops = it.stops,
                    path = pathB,
                    destination = it.stops.lastOrNull()?.stopName?.zhTw?.let { "往 $it" } ?: "B向"
                )
            }
        } catch (e: Exception) {
             Log.e(TAG, "Failed to fetch static route data for $routeName", e)
        }
    }

    private suspend fun updateRealTimeData(city: String, routeName: String) {
        try {
            _busPositions.value = TdxClient.getRealTimeBusPositions(city, routeName)
            val arrivals = TdxClient.getBusEstimatedTimeOfArrival(city, routeName)
            _stopArrivals.value = arrivals.filter { it.stopUID != null }.associateBy { it.stopUID!! } 
            lastUpdateTime = System.currentTimeMillis()
            Log.d(TAG, "Updated real-time data for $routeName. Positions: ${_busPositions.value.size}, Arrivals: ${_stopArrivals.value.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update real-time data for $routeName", e)
        }
    }

    fun stopRouteTracking() {
        realTimeUpdateJob?.cancel()
        realTimeUpdateJob = null
        initialDirectionSet = false
        Log.i(TAG, "Stopped route tracking for $trackedRouteName.")
    }

    override fun onCleared() {
        super.onCleared()
        stopRouteTracking()
    }

    private fun parseLineString(lineString: String?): List<LatLng> {
        if (lineString.isNullOrEmpty() || !lineString.startsWith("LINESTRING")) return emptyList()
        val coordinates = mutableListOf<LatLng>()
        try {
            val content = lineString.substringAfter('(').substringBeforeLast(')').trim()
            val pairs = content.split(',')
            for (pair in pairs) {
                val lonLat = pair.trim().split(Regex("\\s+"))
                if (lonLat.size == 2) {
                    try {
                        val lon = lonLat[0].toDouble()
                        val lat = lonLat[1].toDouble()
                        coordinates.add(LatLng(lat, lon))
                    } catch (nfe: NumberFormatException) {
                         Log.w(TAG, "Could not parse coordinate pair: '$pair'", nfe)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LINESTRING: $lineString", e)
        }
        return coordinates
    }
}
