package com.example.bus

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bus.tdx.BusStop
import com.example.bus.tdx.CachedNearbyStation
import com.example.bus.tdx.StationBusEstimateTime
import com.example.bus.tdx.TdxClient
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private data class RealTimeUpdateParams(
    val cityTdxName: String,
    val departureCoords: LatLng,
    val alternatives: List<ValidatedRouteInfo>,
    val allCityStations: List<CachedNearbyStation>
)

class NavigationViewModel : ViewModel() {
    private val TAG = "NavViewModel"

    private var realTimeJob: Job? = null
    private var realTimeUpdateParams: RealTimeUpdateParams? = null

    // 格式化時間字串
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    // Google 原始路線資料
    private val _originalRoutes = MutableStateFlow<List<DisplayRoute>>(emptyList())

    // 顯示用的即時路線資料
    private val _displayedRoutes = MutableStateFlow<List<DisplayRoute>>(emptyList())
    val displayedRoutes: StateFlow<List<DisplayRoute>> = _displayedRoutes.asStateFlow()


    data class AlternativeArrivalInfo(
        val status: Status,
        val sortKey: Long, // 排序用：秒數
        val displayText: String // 顯示用文字
    ) : Comparable<AlternativeArrivalInfo> {
        enum class Status { COMING, SCHEDULED, NO_DATA }

        override fun compareTo(other: AlternativeArrivalInfo): Int {
            val statusCompare = this.status.compareTo(other.status)
            if (statusCompare != 0) return statusCompare
            return this.sortKey.compareTo(other.sortKey)
        }
    }

    private val _alternativeRoutes = MutableStateFlow<Map<String, List<ValidatedRouteInfo>>>(emptyMap())
    val alternativeRoutes: StateFlow<Map<String, List<ValidatedRouteInfo>>> = _alternativeRoutes.asStateFlow()

    private val _isLoadingAlternatives = MutableStateFlow<Set<String>>(emptySet())
    val isLoadingAlternatives: StateFlow<Set<String>> = _isLoadingAlternatives.asStateFlow()

    private val _alternativeRouteTimes = MutableStateFlow<Map<String, AlternativeArrivalInfo>>(emptyMap())
    val alternativeRouteTimes: StateFlow<Map<String, AlternativeArrivalInfo>> = _alternativeRouteTimes.asStateFlow()

    fun resumeUpdates() {
        if (realTimeJob?.isActive == true) {
            Log.d(TAG, "Real-time updates are already running.")
            return
        }
        realTimeUpdateParams?.let { params ->
            realTimeJob = viewModelScope.launch {
                Log.d(TAG, "Real-time updates resumed.")
                fetchRealTimeForAlternatives(
                    cityTdxName = params.cityTdxName,
                    departureCoords = params.departureCoords,
                    alternatives = params.alternatives,
                    allCityStations = params.allCityStations
                )
            }
        } ?: Log.w(TAG, "Cannot resume updates, parameters are not set.")
    }

    fun pauseUpdates() {
        realTimeJob?.cancel()
        realTimeJob = null
        Log.d(TAG, "Real-time updates paused.")
        _alternativeRouteTimes.update { emptyMap() }
    }

    private fun parseAndFormatArrival(arrival: StationBusEstimateTime): AlternativeArrivalInfo {
        val estimateTime = arrival.estimateTime
        if (estimateTime != null && estimateTime >= 0) {
            val status = AlternativeArrivalInfo.Status.COMING
            val displayText = when {
                estimateTime < 30 -> "進站中"
                estimateTime < 60 -> "即將進站"
                else -> "${estimateTime / 60} 分"
            }
            return AlternativeArrivalInfo(status, estimateTime.toLong(), displayText)
        }

        val nextBusTime = arrival.nextBusTime
        if (!nextBusTime.isNullOrBlank()) {
            try {
                val arrivalDateTime = LocalDateTime.parse(nextBusTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                val now = LocalDateTime.now(ZoneId.systemDefault())

                if (arrivalDateTime.isBefore(now)) {
                    return AlternativeArrivalInfo(AlternativeArrivalInfo.Status.NO_DATA, Long.MAX_VALUE -1, "末班已過")
                }

                val duration = Duration.between(now, arrivalDateTime)
                val sortKey = duration.seconds.coerceAtLeast(0)
                val displayText = "預計 ${arrivalDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"

                return AlternativeArrivalInfo(AlternativeArrivalInfo.Status.SCHEDULED, sortKey, displayText)

            } catch (e: DateTimeParseException) {
                Log.w(TAG, "[RealTimeParse] Failed to parse NextBusTime: $nextBusTime", e)
            }
        }

        val stopStatus = arrival.stopStatus
        val statusText = when (stopStatus) {
            2 -> "交管不停"
            3 -> "末班已過"
            4 -> "今日未營運"
            else -> "未發車"
        }
        val sortKey = if (stopStatus == 3) Long.MAX_VALUE -1 else Long.MAX_VALUE
        return AlternativeArrivalInfo(AlternativeArrivalInfo.Status.NO_DATA, sortKey, statusText)
    }

    /**
     * Google API 回傳路線時呼叫
     */
    fun setInitialRoutePlan(routes: List<DisplayRoute>) {
        _originalRoutes.value = routes
        _displayedRoutes.value = routes
    }

    /**
     * 使用者點擊替代路線時呼叫
     */
    fun updateRouteWithAlternative(
        routeIndex: Int, // DisplayRoute 索引
        stepIndex: Int,  // 公車步驟索引
        selectedRouteInfo: AlternativeDisplayInfo
    ) {
        viewModelScope.launch(Dispatchers.Default) { // 背景計算
            val arrivalInfo = selectedRouteInfo.arrivalInfo

            // 1. 檢查是否為可計算的即時時間
            if (arrivalInfo == null || arrivalInfo.status == AlternativeArrivalInfo.Status.NO_DATA) {
                // 若為「未發車」等，則重置路線
                resetToOriginalRoute(routeIndex)
                return@launch
            }

            // 2. 取得 Google 原始路線
            val originalRoute = _originalRoutes.value.getOrNull(routeIndex) ?: return@launch
            val steps = originalRoute.steps.toMutableList() // 建立可變副本
            val clickedStep = steps.getOrNull(stepIndex) as? RouteStep.Transit ?: return@launch

            // 3. 計算新時間
            // sortKey 是秒數
            val newStartTime = Instant.now().plusSeconds(arrivalInfo.sortKey)
            val newEndTime = newStartTime.plusSeconds(clickedStep.durationInSeconds)

            // 4. 更新點擊的步驟
            steps[stepIndex] = clickedStep.copy(
                departureTimeInstant = newStartTime,
                arrivalTimeInstant = newEndTime,
                departureTime = timeFormatter.format(newStartTime),
                arrivalTime = timeFormatter.format(newEndTime)
            )

            // 5. 回推計算之前的步驟
            for (i in (stepIndex - 1) downTo 0) {
                val currentStep = steps[i]
                val nextStep = steps[i + 1]

                val newArrival = nextStep.departureTimeInstant
                val newDeparture = newArrival?.minusSeconds(currentStep.durationInSeconds)

                steps[i] = currentStep.copyWithNewTimes(newDeparture, newArrival).let {
                    // 格式化時間字串
                    it.copyWithStrings(
                        newDeparture?.let { timeFormatter.format(it) },
                        newArrival?.let { timeFormatter.format(it) }
                    )
                }
            }

            // 6. 後推計算之後的步驟
            for (i in (stepIndex + 1) until steps.size) {
                val currentStep = steps[i]
                val prevStep = steps[i - 1]

                val newDeparture = prevStep.arrivalTimeInstant
                val newArrival = newDeparture?.plusSeconds(currentStep.durationInSeconds)

                steps[i] = currentStep.copyWithNewTimes(newDeparture, newArrival).let {
                    // 格式化時間字串
                    it.copyWithStrings(
                        newDeparture?.let { timeFormatter.format(it) },
                        newArrival?.let { timeFormatter.format(it) }
                    )
                }
            }

            // 7. 建立新的 DisplayRoute
            val newDisplayRoute = originalRoute.copy(
                steps = steps,
                departureTime = steps.first().departureTime ?: "--:--",
                arrivalTime = steps.last().arrivalTime ?: "--:--"
            )

            // 8. 更新 StateFlow 觸發 UI 重繪
            val newRoutesList = _displayedRoutes.value.toMutableList()
            newRoutesList[routeIndex] = newDisplayRoute
            _displayedRoutes.value = newRoutesList
        }
    }

    /**
     * 重置為 Google 原始路線
     */
    fun resetToOriginalRoute(routeIndex: Int) {
        val originalRoute = _originalRoutes.value.getOrNull(routeIndex) ?: return
        val newRoutesList = _displayedRoutes.value.toMutableList()
        newRoutesList[routeIndex] = originalRoute
        _displayedRoutes.value = newRoutesList
    }

    // 更新時間字串的輔助函式
    private fun RouteStep.copyWithStrings(depTime: String?, arrTime: String?): RouteStep {
        return when (this) {
            is RouteStep.Walking -> this.copy(departureTime = depTime, arrivalTime = arrTime)
            is RouteStep.Transit -> this.copy(departureTime = depTime, arrivalTime = arrTime)
        }
    }

    fun findAlternativeRoutes(
        step: RouteStep.Transit,
        cityTdxName: String,
        currentCityStations: List<CachedNearbyStation>
    ) {
        val stepKey = step.polyline.hashCode().toString()

        if (_isLoadingAlternatives.value.contains(stepKey) || _alternativeRoutes.value.containsKey(stepKey)) {
            Log.d(TAG, "Already loading or found alternatives for step $stepKey")
            return
        }

        viewModelScope.launch {
            _isLoadingAlternatives.update { it + stepKey }
            _alternativeRoutes.update { it - stepKey }

            val departureStopCoords = step.startLocation
            val arrivalStopCoords = step.endLocation
            val googleRouteName = step.lineName

            if (departureStopCoords == null || arrivalStopCoords == null) {
                Log.w(TAG, "Missing required location info in step to find alternatives.")
                _alternativeRoutes.update { it + (stepKey to emptyList()) }
                _isLoadingAlternatives.update { it - stepKey }
                return@launch
            }

            Log.d(TAG, "===== findTdxAlternativeRoutesInternal START =====")
            Log.d(TAG, "Finding alternatives for City='$cityTdxName', GoogleRoute='$googleRouteName', From: ${step.departureStop}, To: ${step.arrivalStop}")

            _alternativeRouteTimes.update { emptyMap() }

            val alternatives = findTdxAlternativeRoutesInternal(
                cityTdxName,
                departureStopCoords,
                arrivalStopCoords,
                googleRouteName,
                currentCityStations
            )

            Log.d(TAG, "===== findTdxAlternativeRoutesInternal END =====")

            _alternativeRoutes.update { it + (stepKey to alternatives) }
            _isLoadingAlternatives.update { it - stepKey }

            if (alternatives.isNotEmpty()) {
                this@NavigationViewModel.realTimeUpdateParams = RealTimeUpdateParams(
                    cityTdxName,
                    departureStopCoords,
                    alternatives,
                    currentCityStations
                )
                resumeUpdates()
            }
        }
    }

    private suspend fun fetchRealTimeForAlternatives(
        cityTdxName: String,
        departureCoords: LatLng,
        alternatives: List<ValidatedRouteInfo>,
        allCityStations: List<CachedNearbyStation>
    ) {
        val departureGroup = TdxClient.findNearestStationGroup(departureCoords, allCityStations)
        if (departureGroup == null) {
            Log.w(TAG, "[RealTimeCalc] Failed to find departure group.")
            return
        }

        val departureStationIDs = departureGroup.stations.map { it.stationID }.toSet()
        if (departureStationIDs.isEmpty()) {
            Log.w(TAG, "[RealTimeCalc] Departure group has no StationIDs.")
            return
        }

        Log.d(TAG, "[RealTimeCalc] Starting 30s loop for ${alternatives.size} routes at stations: $departureStationIDs")

        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                val allArrivalsDeferred = departureStationIDs.map { stationID ->
                    viewModelScope.async(Dispatchers.IO) {
                        try {
                            TdxClient.getEstimatedArrivalsForStation(cityTdxName, stationID)
                        } catch (e: Exception) {
                            Log.e(TAG, "[RealTimeCalc] API failed for station $stationID", e)
                            emptyList<StationBusEstimateTime>()
                        }
                    }
                }
                val allArrivals = allArrivalsDeferred.awaitAll().flatten()

                if (allArrivals.isEmpty()) {
                    Log.w(TAG, "[RealTimeCalc] No arrivals found for any station in group.")
                    kotlinx.coroutines.delay(30000L)
                    continue
                }

                val newRouteTimes = mutableMapOf<String, AlternativeArrivalInfo>()

                for (altRoute in alternatives) {
                    val routeName = altRoute.route.routeName.zhTw
                    val routeUID = altRoute.route.routeUID
                    val direction = altRoute.validDirection

                    val matchingArrivals = allArrivals.filter {
                        it.routeName?.zhTw == routeName && it.direction == direction
                    }

                    if (matchingArrivals.isEmpty()) {
                        newRouteTimes[routeUID] = AlternativeArrivalInfo(
                            AlternativeArrivalInfo.Status.NO_DATA,
                            Long.MAX_VALUE,
                            "未發車"
                        )
                        continue
                    }

                    val bestArrivalInfo = matchingArrivals
                        .map { parseAndFormatArrival(it) }
                        .minOrNull()

                    if (bestArrivalInfo != null) {
                        newRouteTimes[routeUID] = bestArrivalInfo
                    } else {
                        newRouteTimes[routeUID] = AlternativeArrivalInfo(
                            AlternativeArrivalInfo.Status.NO_DATA,
                            Long.MAX_VALUE,
                            "未發車"
                        )
                    }
                }

                _alternativeRouteTimes.update { newRouteTimes }
                Log.d(TAG, "[RealTimeCalc] Loop update complete. Found ${newRouteTimes.size} routes.")

            } catch (e: Exception) {
                Log.e(TAG, "[RealTimeCalc] Loop failed", e)
            }

            kotlinx.coroutines.delay(30000L)
        }
    }

    private suspend fun findTdxAlternativeRoutesInternal(
        cityTdxName: String,
        departureCoords: LatLng,
        arrivalCoords: LatLng,
        excludeGoogleRouteName: String,
        cityStations: List<CachedNearbyStation>
    ): List<ValidatedRouteInfo> {

        val departureGroup = TdxClient.findNearestStationGroup(departureCoords, cityStations)
        val arrivalGroup = TdxClient.findNearestStationGroup(arrivalCoords, cityStations)

        if (departureGroup == null || arrivalGroup == null || departureGroup.stations.isEmpty() || arrivalGroup.stations.isEmpty()) {
            Log.w(TAG, "[Step 1 FAILED] Could not find TDX station groups. DepGroup null? ${departureGroup == null}, ArrGroup null? ${arrivalGroup == null}")
            return emptyList()
        }
        if (departureGroup.stationName == arrivalGroup.stationName) {
            Log.d(TAG, "[Step 1 SKIPPED] Departure and arrival groups are the same: ${departureGroup.stationName}. No direct routes needed.")
            return emptyList()
        }

        val departureStopUIDs = departureGroup.stations
            .flatMap { it.stops.map { stopInfo -> stopInfo.stopUID } }
            .toSet()
        val arrivalStopUIDs = arrivalGroup.stations
            .flatMap { it.stops.map { stopInfo -> stopInfo.stopUID } }
            .toSet()

        Log.i(TAG, "[Step 1 SUCCESS] Mapped Departure Group: ${departureGroup.stationName} (StopUIDs: ${departureStopUIDs.joinToString()})")
        Log.i(TAG, "[Step 1 SUCCESS] Mapped Arrival Group: ${arrivalGroup.stationName} (StopUIDs: ${arrivalStopUIDs.joinToString()})")


        val departureRoutesDeferred = departureGroup.stations.map { it.stationID }.toSet().map { stationId ->
            viewModelScope.async {
                try { TdxClient.getRoutesPassingThroughStation(cityTdxName, stationId) } catch (e: Exception) { emptyList() }
            }
        }
        val departureRoutes = departureRoutesDeferred.awaitAll().flatten().distinctBy { it.routeUID }
        if (departureRoutes.isEmpty()) {
            Log.w(TAG, "[Step 2 FAILED] No routes found passing departure group.")
            return emptyList()
        }
        Log.i(TAG, "[Step 2 SUCCESS] Found ${departureRoutes.size} routes passing departure group: [${departureRoutes.map { it.routeName.zhTw }.joinToString()}]")
        Log.d(TAG, "[Step 3 START] Now validating stop order for ${departureRoutes.size} routes...")


        val validAlternativeRoutes = mutableListOf<ValidatedRouteInfo>()

        val validationJobs = departureRoutes.map { route ->
            viewModelScope.async {
                val routeName = route.routeName.zhTw ?: return@async null
                Log.d(TAG, "[Step 3] Validating route: '$routeName'")
                try {
                    val stopOfRouteList = TdxClient.getStopOfRoute(cityTdxName, routeName)
                    var isValidRoute = false
                    var validDirectionFound: Int? = null
                    var logMsg = ""
                    for (routeDirection in stopOfRouteList) {
                        val stops = routeDirection.stops
                        val firstDepartureIndex = stops.indexOfFirst { busStop: BusStop ->
                            busStop.stopUID in departureStopUIDs
                        }
                        if (firstDepartureIndex == -1) {
                            logMsg += "(Dir ${routeDirection.direction}: No Dep Stop) "
                            continue
                        }

                        val firstArrivalIndexAfterDeparture = stops.drop(firstDepartureIndex + 1)
                            .indexOfFirst { busStop: BusStop ->
                                busStop.stopUID in arrivalStopUIDs
                            }

                        if (firstArrivalIndexAfterDeparture != -1) {
                            isValidRoute = true
                            validDirectionFound = routeDirection.direction

                            Log.i(TAG, "[Step 3] --> Route '$routeName' (Dir ${routeDirection.direction}) is VALID: Dep Index=$firstDepartureIndex, Arr Index=${firstDepartureIndex + 1 + firstArrivalIndexAfterDeparture}")
                            break
                        } else {
                            logMsg += "(Dir ${routeDirection.direction}: No Arr Stop After Dep) "
                        }
                    }
                    if (!isValidRoute) {
                        Log.d(TAG, "[Step 3] --> Route '$routeName' is INVALID. $logMsg")
                    }
                    if (isValidRoute) ValidatedRouteInfo(route, validDirectionFound!!) else null
                } catch (e: Exception) {
                    Log.e(TAG, "[Step 3] Error validating route $routeName", e)
                    null
                }
            }
        }

        validAlternativeRoutes.addAll(validationJobs.awaitAll().filterNotNull())
        Log.i(TAG, "[Step 3 COMPLETE] Found ${validAlternativeRoutes.size} total valid routes (before filtering).")

        val finalRoutes = validAlternativeRoutes

        Log.i(TAG, "[Step 4 FILTER] Original Google route to include: '$excludeGoogleRouteName'.")
        Log.i(TAG, "[Step 4 FILTER] Found ${validAlternativeRoutes.size} routes before filtering (Now returning all).")
        Log.i(TAG, "[Step 5 FINAL] Returning ALL valid routes: [${finalRoutes.map { it.route.routeName.zhTw }.joinToString()}]")

        return finalRoutes
    }

    fun onMapTapped(offset: Offset) {
        TODO("Not yet implemented")
    }

    fun onMapLongPressed(offset: Offset) {
        TODO("Not yet implemented")
    }

    fun stopAutoCenter() {
        TODO("Not yet implemented")
    }
}
