package com.example.bus

import com.example.bus.tdx.BusRoute
import com.google.android.gms.maps.model.LatLng
import java.time.Instant

data class DisplayRoute(
    val steps: List<RouteStep>,
    val totalDuration: String,
    val departureTime: String,
    val arrivalTime: String
)

sealed interface RouteStep {
    val distance: String
    val duration: String
    val instructions: String
    val polyline: List<LatLng>
    val startLocation: LatLng?
    val endLocation: LatLng?
    val departureTime: String?
    val arrivalTime: String?

    // 儲存原始秒數和時間戳，用於計算
    val durationInSeconds: Long
    val departureTimeInstant: Instant?
    val arrivalTimeInstant: Instant?

    // copyWith 輔助函式，用於 ViewModel 更新狀態
    fun copyWithNewTimes(newDeparture: Instant?, newArrival: Instant?): RouteStep

    data class Walking(
        override val distance: String,
        override val duration: String,
        override val instructions: String,
        override val polyline: List<LatLng>,
        override val startLocation: LatLng?,
        override val endLocation: LatLng?,
        override val departureTime: String?,
        override val arrivalTime: String?,
        override val durationInSeconds: Long,
        override val departureTimeInstant: Instant?,
        override val arrivalTimeInstant: Instant?
    ) : RouteStep {
        override fun copyWithNewTimes(newDeparture: Instant?, newArrival: Instant?): RouteStep {
            return this.copy(
                departureTimeInstant = newDeparture,
                arrivalTimeInstant = newArrival,
            )
        }
    }

    data class Transit(
        override val distance: String,
        override val duration: String,
        override val instructions: String,
        override val polyline: List<LatLng>,
        override val startLocation: LatLng?,
        override val endLocation: LatLng?,
        val departureStop: String,
        val arrivalStop: String,
        val lineName: String,
        val headsign: String,
        val stopCount: Int,
        val vehicleType: VehicleType,
        override val departureTime: String?,
        override val arrivalTime: String?,
        override val durationInSeconds: Long,
        override val departureTimeInstant: Instant?,
        override val arrivalTimeInstant: Instant?
    ) : RouteStep {
        override fun copyWithNewTimes(newDeparture: Instant?, newArrival: Instant?): RouteStep {
            return this.copy(
                departureTimeInstant = newDeparture,
                arrivalTimeInstant = newArrival,
            )
        }
    }
}
enum class VehicleType {
    BUS, SUBWAY, TRAIN, TRAM, RAIL, UNKNOWN
}

data class ValidatedRouteInfo(
    val route: BusRoute,
    val validDirection: Int
)
