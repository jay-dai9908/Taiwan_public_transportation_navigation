package com.example.bus

import android.annotation.SuppressLint
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.bus.tdx.CachedNearbyStation
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun NearbyStopsScreen(
    modifier: Modifier = Modifier,
    viewModel: NearbyStopsViewModel,
    onRouteClick: (cityName: String, routeName: String, direction: Int) -> Unit,
    setPagerScrollEnabled: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val geocoder = remember { Geocoder(context, Locale.TAIWAN) }

    var userLocation by remember { mutableStateOf<Location?>(null) }
    var selectedStation by remember { mutableStateOf<CachedNearbyStation?>(null) }
    var isRefreshEnabled by remember { mutableStateOf(true) }
    var isRefreshButtonEnabled by remember { mutableStateOf(true) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(25.047924, 121.517082), 15f)
    }

    // 用於從 stationID 快速找到 group 的查詢 map
    val groupedNearbyStations by viewModel.groupedNearbyStations.collectAsState()
    val stationToGroupMap by remember(groupedNearbyStations) {
        derivedStateOf {
            groupedNearbyStations.flatMap { group ->
                group.stations.map { station -> station.stationID to group }
            }.toMap()
        }
    }

    val nearbyStations by viewModel.nearbyStations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val busArrivals by viewModel.busArrivals.collectAsState()
    val isFetchingArrivals by viewModel.isFetchingArrivals.collectAsState()
    val currentCity by viewModel.currentCity.collectAsState()

    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    fun getCityFromLocation(location: Location): String {
        return try {
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            addresses?.firstOrNull()?.adminArea ?: "Taichung"
        } catch (e: Exception) {
            Log.e("NearbyStopsScreen", "Failed to get city from location", e)
            "Taichung"
        }
    }

    fun forceLocationUpdate(forceApiRefresh: Boolean = false) {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: android.location.Location? ->
                if (location != null) {
                    userLocation = location
                    val city = getCityFromLocation(location)
                    viewModel.findNearbyStops(city, location, forceApiRefresh)
                    coroutineScope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 16f))
                    }
                }
            }
            .addOnFailureListener {
                Log.e("NearbyStopsScreen", "Failed to get current location", it)
            }
    }

    DisposableEffect(Unit) {
        forceLocationUpdate()
        onDispose { }
    }

    DisposableEffect(lifecycleOwner, selectedStation) {
        val observer = LifecycleEventObserver { _, event ->
            if (selectedStation != null) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        Log.d("NearbyStopsScreen", "Lifecycle: ON_RESUME, calling resumeUpdates")
                        viewModel.resumeUpdates()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        Log.d("NearbyStopsScreen", "Lifecycle: ON_PAUSE, calling pauseUpdates")
                        viewModel.pauseUpdates()
                    }
                    else -> {}
                }
            } else {
                 viewModel.pauseUpdates()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d("NearbyStopsScreen", "DisposableEffect onDispose, removing observer and calling pauseUpdates")
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseUpdates()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(0.6f)) {
            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                // 等待使用者手指按下地圖
                                awaitFirstDown(requireUnconsumed = false)
                                
                                // 禁用 Pager 的左右滑動
                                setPagerScrollEnabled(false)

                                // 保持禁用，直到所有手指都抬起
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })

                                // 重新啟用 Pager 的左右滑動
                                setPagerScrollEnabled(true)
                            }
                        }
                    },
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = true),
                uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false)
            ) {
                nearbyStations.forEach { station ->
                    val markerIconColor = if (station.stationID == selectedStation?.stationID) {
                        BitmapDescriptorFactory.HUE_RED
                    } else {
                        BitmapDescriptorFactory.HUE_AZURE
                    }
                    Marker(
                        state = MarkerState(position = LatLng(station.stationPosition.positionLat, station.stationPosition.positionLon)),
                        title = station.stationName.zhTw,
                        icon = BitmapDescriptorFactory.defaultMarker(markerIconColor),
                        onClick = {
                            val group = stationToGroupMap[station.stationID]
                            if (group != null) {
                                if (selectedStation?.stationID != station.stationID) {
                                    selectedStation = station
                                    viewModel.startFetchingArrivals(group, station.stationID)
                                }
                                coroutineScope.launch {
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngZoom(
                                            LatLng(station.stationPosition.positionLat, station.stationPosition.positionLon),
                                            17f
                                        ),
                                        durationMs = 500
                                    )
                                }
                            } else {
                                Log.w("NearbyStopsScreen", "Could not find group for station ${station.stationID}")
                            }
                            true
                        }
                    )
                 }
            }

            FloatingActionButton(
                onClick = { forceLocationUpdate() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.MyLocation, contentDescription = "重新定位")
                }
            }
        }

        if (selectedStation != null) {
            Column(
                modifier = Modifier.weight(0.4f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                         Text(
                            selectedStation!!.stationName.zhTw ?: "站牌",
                            style = MaterialTheme.typography.headlineSmall
                        )
                         Spacer(modifier = Modifier.weight(1f))
                         IconButton(
                            onClick = {
                                if (isRefreshButtonEnabled) {
                                    isRefreshButtonEnabled = false
                                    viewModel.refreshArrivals()
                                    coroutineScope.launch {
                                        delay(5000L)
                                        isRefreshButtonEnabled = true
                                    }
                                }
                            },
                            enabled = isRefreshButtonEnabled
                        ) {
                             Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                         }
                         IconButton(onClick = {
                             viewModel.stopFetchingArrivals()
                             selectedStation = null
                             userLocation?.let {
                                 coroutineScope.launch {
                                     cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16f))
                                 }
                             }
                         }) {
                             Icon(Icons.Default.Close, contentDescription = "Close")
                         }
                    }

                    if (isFetchingArrivals && busArrivals.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (busArrivals.isEmpty() && !isFetchingArrivals) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暫無班次資訊")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                             items(busArrivals) { arrivalInfo ->
                                val routeNameZhTw = arrivalInfo.routeName?.zhTw
                                val routeNameToDisplay = routeNameZhTw ?: arrivalInfo.routeName?.en ?: "未知路線"

                                val navigationData = remember(arrivalInfo, currentCity) {
                                    val cityObj = currentCity
                                    val routeName = arrivalInfo.routeName?.zhTw
                                    if (cityObj != null && routeName != null && routeName != "未知路線") {
                                        Triple(cityObj.tdxName, routeName, arrivalInfo.direction ?: 0)
                                    } else {
                                        null
                                    }
                                }

                                var firstLineText by remember { mutableStateOf<String?>(null) }
                                var secondLineText by remember { mutableStateOf<String?>(null) }
                                var firstLineColor by remember { mutableStateOf(Color.Unspecified) }
                                val defaultContentColor = LocalContentColor.current

                                when (arrivalInfo.stopStatus) {
                                    0 -> {
                                        firstLineText = arrivalInfo.estimateTime?.let { timeInSeconds ->
                                            when {
                                                timeInSeconds < 60 -> "進站中"
                                                timeInSeconds < 120 -> "1 分"
                                                else -> "${timeInSeconds / 60} 分"
                                            }
                                        } ?: "正常"

                                        firstLineColor = if (arrivalInfo.estimateTime != null && arrivalInfo.estimateTime < 120) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            defaultContentColor
                                        }

                                        arrivalInfo.estimates?.getOrNull(1)?.estimateTime?.let { nextEstimateTime ->
                                            val nextMinutes = nextEstimateTime / 60
                                            if (nextMinutes > 0) {
                                                secondLineText = "下一班 $nextMinutes 分"
                                            }
                                        }
                                    }
                                    1 -> {
                                        firstLineText = "未發車"
                                        firstLineColor = Color.Gray

                                        arrivalInfo.nextBusTime?.let { timeString ->
                                            try {
                                                val timePart = timeString.substringAfter('T').substring(0, 5)
                                                secondLineText = "預計 $timePart"
                                            } catch (e: Exception) {
                                                Log.w("NearbyStopsScreen", "Failed to parse NextBusTime: $timeString", e)
                                                secondLineText = "時間未知"
                                            }
                                        }
                                    }
                                    2 -> {
                                        firstLineText = "交管不停"
                                        firstLineColor = Color.Gray
                                    }
                                    3 -> {
                                        firstLineText = "末班已過"
                                        firstLineColor = Color.Gray
                                    }
                                    4 -> {
                                        firstLineText = "今日未營運"
                                        firstLineColor = Color.Gray
                                    }
                                    else -> {
                                         firstLineText = arrivalInfo.estimateTime?.let { timeInSeconds ->
                                            when {
                                                timeInSeconds < 60 -> "進站中"
                                                timeInSeconds < 120 -> "1 分"
                                                else -> "${timeInSeconds / 60} 分"
                                            }
                                        } ?: "狀態未知"
                                        firstLineColor = Color.Gray
                                    }
                                }

                                if (firstLineColor == Color.Unspecified) {
                                    firstLineColor = defaultContentColor
                                }

                                Card(
                                     modifier = Modifier
                                         .fillMaxWidth()
                                         .clickable(
                                             enabled = navigationData != null,
                                             onClick = {
                                                 navigationData?.let { (cityName, routeName, direction) ->
                                                     coroutineScope.launch {
                                                         onRouteClick(cityName, routeName, direction)
                                                     }
                                                 }
                                             }
                                         ),
                                     shape = MaterialTheme.shapes.medium,
                                     elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                 ) {
                                     Row(
                                         modifier = Modifier
                                             .padding(horizontal = 16.dp, vertical = 12.dp),
                                         verticalAlignment = Alignment.CenterVertically
                                     ) {
                                         Icon(
                                             imageVector = Icons.Default.DirectionsBus,
                                             contentDescription = "公車",
                                             modifier = Modifier.size(24.dp)
                                         )
                                         Spacer(modifier = Modifier.width(8.dp))

                                         Column(modifier = Modifier.weight(1f)) {
                                             val fullRouteName = arrivalInfo.routeName?.zhTw ?: routeNameToDisplay
                                             var routeNumberPart = fullRouteName
                                             var destinationPart: String? = null

                                             val startIndex = fullRouteName.indexOf('(')
                                             val endIndex = fullRouteName.lastIndexOf(')')

                                             if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                                                 routeNumberPart = fullRouteName.substring(0, startIndex).trim()
                                                 destinationPart = fullRouteName.substring(startIndex + 1, endIndex)
                                                 if (destinationPart.startsWith("往 ")) {
                                                     destinationPart = destinationPart.substring(2)
                                                 }
                                             }
                                             
                                             Text(
                                                 text = routeNumberPart,
                                                 style = MaterialTheme.typography.titleLarge
                                             )
                                             
                                             if (!destinationPart.isNullOrBlank()) {
                                                 Log.d("NearbyStopsScreen", "Route: $fullRouteName, Destination: $destinationPart")
                                                 Text(
                                                     text = "往 $destinationPart",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     color = Color.Gray
                                                 )
                                             }
                                         }
                                         Spacer(modifier = Modifier.width(8.dp))

                                         Column(
                                             horizontalAlignment = Alignment.End,
                                             modifier = Modifier.padding(start = 8.dp)
                                         ) {
                                             if (firstLineText != null) {
                                                 Text(
                                                     text = firstLineText!!,
                                                     style = MaterialTheme. typography.titleLarge,
                                                     color = firstLineColor
                                                 )
                                             }

                                             if (secondLineText != null) {
                                                 Text(
                                                     text = secondLineText!!,
                                                     style = MaterialTheme.typography.bodyMedium,
                                                     color = Color.Gray
                                                 )
                                             }
                                         }
                                     }
                                 }
                             }
                        }
                    }
                }
            }
        } else {
             LazyColumn(
                modifier = Modifier.weight(0.4f).background(MaterialTheme.colorScheme.surface)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "附近站牌",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (!isLoading && groupedNearbyStations.isEmpty()) {
                             IconButton(
                                 onClick = {
                                     userLocation?.let { loc ->
                                        isRefreshEnabled = false
                                        forceLocationUpdate(forceApiRefresh = true)
                                        coroutineScope.launch {
                                            delay(30_000L)
                                            isRefreshEnabled = true
                                        }
                                     }
                                 },
                                 enabled = isRefreshEnabled
                             ) {
                                Icon(Icons.Default.Refresh, contentDescription = "強制刷新")
                            }
                        }
                    }
                }
                if (isLoading && groupedNearbyStations.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (groupedNearbyStations.isEmpty()) {
                    item {
                        Text("正在搜尋或附近沒有站牌...", modifier = Modifier.padding(16.dp))
                    }
                } else {
                    items(groupedNearbyStations) { groupedStation ->
                         Card(
                             modifier = Modifier
                                 .fillMaxWidth()
                                 .padding(horizontal = 16.dp, vertical = 6.dp)
                                 .clickable {
                                     val stationToFetch = groupedStation.stations.find { it.stationID == groupedStation.closestStationIdBasedOnLastSearch }
                                         ?: groupedStation.stations.first()

                                     viewModel.startFetchingArrivals(groupedStation, stationToFetch.stationID)
                                     
                                     selectedStation = stationToFetch

                                     coroutineScope.launch {
                                         cameraPositionState.animate(
                                             CameraUpdateFactory.newLatLngZoom(
                                                 LatLng(stationToFetch.stationPosition.positionLat, stationToFetch.stationPosition.positionLon),
                                                 17f
                                             ),
                                             durationMs = 500
                                         )
                                     }
                                 },
                             shape = MaterialTheme.shapes.medium,
                             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                         ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    groupedStation.stationName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                         }
                    }
                }
             }
        }
    }
}
