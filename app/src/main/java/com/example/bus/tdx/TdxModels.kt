package com.example.bus.tdx

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- 新增: Station/NearBy API 的回應模型 ---

@Immutable
@Serializable
data class BusStation(
    @SerialName("StationUID")
    val stationUID: String,
    @SerialName("StationID")
    val stationID: String,
    @SerialName("StationName")
    val stationName: RouteName,
    @SerialName("StationPosition")
    val stationPosition: StopPosition,
    @SerialName("Stops")
    val stops: List<BusStationStop>
)

@Immutable
@Serializable
data class BusStationStop(
    @SerialName("StopUID")
    val stopUID: String,
    @SerialName("StopID")
    val stopID: String,
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteID")
    val routeID: String,
    @SerialName("RouteName")
    val routeName: RouteName
)

// --- 新增: 用於快取的特定資料模型 ---

@Immutable
@Serializable
data class CachedNearbyStation(
    @SerialName("StationUID")
    val stationUID: String,
    @SerialName("StationID")
    val stationID: String,
    @SerialName("StationName")
    val stationName: RouteName, // 保留 RouteName 物件以便顯示
    @SerialName("StationPosition")
    val stationPosition: StopPosition,
    @SerialName("Stops")
    val stops: List<CachedStationStopInfo>
)

@Immutable
@Serializable
data class CachedStationStopInfo(
    @SerialName("StopUID")
    val stopUID: String,
    @SerialName("StopID")
    val stopID: String,
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteID")
    val routeID: String,
    @SerialName("RouteName")
    val routeName: String // 僅儲存 Zh_tw
)


// --- 現有的通用模型 ---

@Immutable
@Serializable
data class RouteName(
    @SerialName("Zh_tw")
    val zhTw: String? = null,
    @SerialName("En")
    val en: String? = null
)

@Immutable
@Serializable
data class StopPosition(
    @SerialName("PositionLat")
    val positionLat: Double,
    @SerialName("PositionLon")
    val positionLon: Double
)

// For Bus Routes
@Immutable
@Serializable
data class BusRoute(
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName
)

// For Bus Stops (舊有模型，NearbyStopsViewModel 將不再使用)
@Immutable
@Serializable
data class BusStop(
    @SerialName("StopUID")
    val stopUID: String,
    @SerialName("StopID")
    val stopID: String? = null, // Add stopID field
    @SerialName("StopName")
    val stopName: RouteName,
    @SerialName("StopPosition")
    val stopPosition: StopPosition
)

// For Stop Of Route
@Immutable
@Serializable
data class StopOfRoute(
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName,
    @SerialName("Direction")
    val direction: Int,
    @SerialName("Stops")
    val stops: List<BusStop>
)

// For Route Shape
@Immutable
@Serializable
data class BusShape(
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName,
    @SerialName("Direction")
    val direction: Int,
    @SerialName("Geometry")
    val geometry: String,
    @SerialName("EncodedPolyline")
    val encodedPolyline: String
)

// N1 - Estimated Time of Arrival for a specific stop
@Immutable
@Serializable
data class BusArrival(
    @SerialName("StopUID")
    val stopUID: String,
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName,
    @SerialName("StopName")
    val stopName: RouteName? = null, // Can be null
    @SerialName("StopStatus")
    val stopStatus: Int,
    @SerialName("EstimateTime")
    val estimateTime: Int? = null,
    @SerialName("StopCountDown")
    val stopCountDown: Int? = null,
    @SerialName("NextBusTime")
    val nextBusTime: String? = null
)

// A1 - Real-time Bus Position
@Immutable
@Serializable
data class BusRealTimePosition(
    @SerialName("PlateNumb")
    val plateNumb: String,
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName,
    @SerialName("Direction")
    val direction: Int,
    @SerialName("BusPosition")
    val busPosition: StopPosition,
    @SerialName("Speed")
    val speed: Int,
    @SerialName("Azimuth")
    val azimuth: Float // Angle
)

// --- Other existing models ---

@Serializable
data class TdxToken(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Int
) {
    private val fetchedAtMillis: Long = System.currentTimeMillis()

    fun isExpired(): Boolean {
        val bufferSeconds = 300
        val expiryTimeMillis = fetchedAtMillis + (expiresIn - bufferSeconds) * 1000
        return System.currentTimeMillis() > expiryTimeMillis
    }
}
@Immutable
@Serializable
data class BusN1Estimate(
    @SerialName("StopUID")
    val stopUID: String,
    @SerialName("StopName")
    val stopName: RouteName,
    @SerialName("RouteName")
    val routeName: RouteName,
    @SerialName("EstimateTime")
    val estimateTime: Int? = null,
    @SerialName("StopStatus")
    val stopStatus: Int
)
@Immutable
@Serializable
data class BusRealTimeNearStop(
    @SerialName("PlateNumb")
    val plateNumb: String,
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName,
    @SerialName("StopUID")
    val stopUID: String,
    @SerialName("StopName")
    val stopName: RouteName,
    @SerialName("EstimateTime")
    val estimateTime: Int? = null,
    @SerialName("StopStatus")
    val stopStatus: Int
)
@Immutable
@Serializable
data class BusDailyTimetable(
    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName,
    @SerialName("Timetables")
    val timetables: List<TimetableInfo> = emptyList()
)
@Immutable
@Serializable
data class TimetableInfo(
    @SerialName("Direction")
    val direction: Int,
    @SerialName("StopTimes")
    val stopTimes: List<StopTimeInfo> = emptyList()
)
@Immutable
@Serializable
data class StopTimeInfo(
    @SerialName("StopUID")
    val stopUID: String,
    @SerialName("StopSequence")
    val stopSequence: Int,
    @SerialName("ArrivalTime")
    val arrivalTime: String // Format "HH:mm"
)

@Immutable
@Serializable
data class EstimateItem(
    @SerialName("PlateNumb")
    val plateNumb: String? = null,
    @SerialName("EstimateTime")
    val estimateTime: Int? = null,
    @SerialName("IsLastBus")
    val isLastBus: Boolean? = false
)

@Immutable
@Serializable
data class StationBusEstimateTime(
    @SerialName("StationUID")
    val stationUID: String? = null,
    @SerialName("StationName")
    val stationName: RouteName? = null,

    @SerialName("StopUID")
    val stopUID: String? = null,
    @SerialName("StopID")
    val stopID: String? = null,
    @SerialName("StopName")
    val stopName: RouteName? = null,

    @SerialName("RouteUID")
    val routeUID: String,
    @SerialName("RouteName")
    val routeName: RouteName? = null,
    @SerialName("Direction")
    val direction: Int? = null,
    @SerialName("EstimateTime")
    val estimateTime: Int? = null, // 最快到達的時間 (第一班)
    @SerialName("StopStatus")
    val stopStatus: Int? = null,
    @SerialName("NextBusTime")
    val nextBusTime: String? = null, // 格式可能是 "2025-10-24T21:11:00+08:00"

    @SerialName("Estimates")
    val estimates: List<EstimateItem>? = null, // 下一班、下下班...的預估列表

    @SerialName("SrcUpdateTime")
    val srcUpdateTime: String? = null,
    @SerialName("UpdateTime")
    val updateTime: String? = null,

    @SerialName("PlateNumb")
    val plateNumb: String? = null,
    @SerialName("SubRouteUID")
    val subRouteUID: String? = null,
    @SerialName("SubRouteID")
    val subRouteID: String? = null,
    @SerialName("SubRouteName")
    val subRouteName: RouteName? = null,
    @SerialName("StopSequence")
    val stopSequence: Int? = null
)

// Data classes moved from NearbyStopsViewModel
@Serializable
data class GroupedNearbyStation(
    val stationName: String,
    val stations: List<CachedNearbyStation>,
    val closestStationIdBasedOnLastSearch: String
)

@Serializable
data class DirectionStationMapping(
    val routeUID: String,
    val stationIdDirection0: String?,
    val stationIdDirection1: String?,
    val timestamp: Long
)
