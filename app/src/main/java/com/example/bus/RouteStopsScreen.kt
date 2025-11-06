package com.example.bus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.bus.tdx.BusStop
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun createBusIconWithBackground(
    context: Context,
    @DrawableRes iconResId: Int,
    backgroundColor: Color,
    iconTint: Color
): BitmapDescriptor? {
    val drawable = ContextCompat.getDrawable(context, iconResId) ?: return null
    val size = (48 * context.resources.displayMetrics.density).toInt()

    drawable.setBounds(0, 0, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = android.graphics.Paint().apply {
        this.color = backgroundColor.toArgb()
        isAntiAlias = true
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    val inset = (size * 0.2f).toInt()
    drawable.setBounds(inset, inset, size - inset, size - inset)
    DrawableCompat.setTint(drawable, iconTint.toArgb())
    drawable.draw(canvas)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

// 獨立的列表項 Composable，負責自身的狀態管理
@Composable
private fun StopListItem(
    stop: BusStop,
    viewModel: RouteStopsViewModel,
    modifier: Modifier = Modifier
) {
    val arrivalInfo by viewModel.getArrivalFlowForStop(stop.stopUID).collectAsState(initial = null)
    
    val (stopNameText, arrivalText) = remember(stop.stopName, arrivalInfo) {
        val name = stop.stopName.zhTw ?: ""
        val text = when (arrivalInfo?.stopStatus) {
            0 -> arrivalInfo?.estimateTime?.let {
                val minutes = it / 60
                if (minutes > 1) "$minutes 分" else "進站中"
            } ?: "---"
            1 -> "尚未發車"
            2 -> "交管不停"
            3 -> "末班已過"
            4 -> "今日未營運"
            else -> "---"
        }
        Pair(name, text)
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stopNameText,
                modifier = Modifier.weight(1f)
            )
            Text(arrivalText)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun RouteStopsScreen(
    modifier: Modifier = Modifier,
    viewModel: RouteStopsViewModel = hiltViewModel(),
    cityTdxName: String,
    routeName: String,
    initialDirection: Int?,
    onBack: () -> Unit,
    setPagerScrollEnabled: ((Boolean) -> Unit)? = null
) {
    val directionAData by viewModel.directionAData.collectAsState()
    val directionBData by viewModel.directionBData.collectAsState()
    val selectedDirection by viewModel.selectedDirection.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val busPositions by viewModel.busPositions.collectAsState()

    val cameraPositionState = rememberCameraPositionState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var hasAutoZoomedAndScrolled by remember { mutableStateOf(false) }

    val iconBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val iconTintColor = MaterialTheme.colorScheme.onSurfaceVariant

    val busIcon = remember(iconBackgroundColor, iconTintColor) {
        createBusIconWithBackground(
            context,
            R.drawable.ic_bus_marker,
            backgroundColor = iconBackgroundColor,
            iconTint = iconTintColor
        )
    }
    val stopIcon = remember {
        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
    }

    val currentDirectionData = if (selectedDirection == 0) directionAData else directionBData
    val currentBusPositions = remember(busPositions, selectedDirection) {
        busPositions.filter { it.direction == selectedDirection }
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    
    fun findAndScrollToClosestStop(location: Location) {
        val stops = currentDirectionData.stops
        if (stops.isEmpty()) return

        val closestStop = stops.minByOrNull { stop ->
            val stopLocation = Location("").apply {
                latitude = stop.stopPosition.positionLat
                longitude = stop.stopPosition.positionLon
            }
            location.distanceTo(stopLocation)
        }
        val closestStopIndex = closestStop?.let { stops.indexOf(it) }

        if (closestStopIndex != null && closestStopIndex >= 0) {
            coroutineScope.launch {
                val viewportHeight = listState.layoutInfo.viewportSize.height
                val itemHeightPx = with(density) { 80.dp.toPx() }
                val offset = if (viewportHeight > 0) {
                    (viewportHeight / 2 - itemHeightPx / 2).toInt().coerceAtLeast(0)
                } else {
                    0
                }
                listState.animateScrollToItem(index = closestStopIndex, scrollOffset = -offset)
            }
        }
    }

    fun forceLocationUpdate(zoomToUser: Boolean) {
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val userLatLng = LatLng(location.latitude, location.longitude)
                        userLocation = userLatLng
                        if (zoomToUser) {
                            coroutineScope.launch {
                                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(userLatLng, 14f))
                            }
                        }
                        findAndScrollToClosestStop(location)
                    }
                }
        } catch (e: SecurityException) {
            // Fails silently
        }
    }
    
    DisposableEffect(key1 = routeName, key2 = cityTdxName) {
        viewModel.setInitialDirection(initialDirection)
        coroutineScope.launch {
            delay(600L) 
            Log.d("RouteStopsScreen", "Calling fetchStaticRouteDataOnly with city='$cityTdxName', route='$routeName'")
            viewModel.fetchStaticRouteDataOnly(cityTdxName, routeName)
        }
        onDispose {
            viewModel.stopRouteTracking()
            Log.d("RouteStopsScreen", "DisposableEffect onDispose for $routeName")
        }
    }
    
    LaunchedEffect(directionAData, directionBData) {
        if (directionAData.stops.isNotEmpty() || directionBData.stops.isNotEmpty()) {
            delay(1000L) 
            viewModel.startRealTimeLoopOnly()
        }
    }
    
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d("RouteStopsScreen", "Lifecycle: ON_RESUME, calling resumeUpdates")
                    viewModel.resumeUpdates()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d("RouteStopsScreen", "Lifecycle: ON_PAUSE, calling pauseUpdates")
                    viewModel.pauseUpdates()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            Log.d("RouteStopsScreen", "Lifecycle: DisposableEffect onDispose, removing observer.")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(currentDirectionData.stops, userLocation, listState.layoutInfo.viewportSize) {
        if (!hasAutoZoomedAndScrolled && currentDirectionData.stops.isNotEmpty() && userLocation != null) {

            val userLoc = Location("").apply {
                latitude = userLocation!!.latitude
                longitude = userLocation!!.longitude
            }
            
            findAndScrollToClosestStop(userLoc)
            
            val closestStop = currentDirectionData.stops.minByOrNull { stop ->
                val stopLocation = Location("").apply {
                    latitude = stop.stopPosition.positionLat
                    longitude = stop.stopPosition.positionLon
                }
                userLoc.distanceTo(stopLocation)
            }
            closestStop?.let {
                val closestStopPosition = LatLng(it.stopPosition.positionLat, it.stopPosition.positionLon)
                 coroutineScope.launch {
                     cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(closestStopPosition, 15f))
                 }
            }

            hasAutoZoomedAndScrolled = true

        } else if (!hasAutoZoomedAndScrolled && currentDirectionData.stops.isNotEmpty()) {
            val firstStop = currentDirectionData.stops.first()
             coroutineScope.launch {
                 cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(firstStop.stopPosition.positionLat, firstStop.stopPosition.positionLon), 15f))
                 hasAutoZoomedAndScrolled = true
             }
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(routeName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading && directionAData.stops.isEmpty() && directionBData.stops.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = modifier.padding(innerPadding)) {

                val tabs = listOfNotNull(
                    directionAData.destination,
                    directionBData.destination?.takeIf { it != directionAData.destination }
                )

                if (tabs.isNotEmpty()) {
                    TabRow(selectedTabIndex = selectedDirection) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedDirection == index,
                                onClick = { viewModel.onDirectionSelected(index) },
                                text = { Text(title) }
                            )
                        }
                    }
                }
                
                Box(modifier = Modifier.weight(0.5f)) {
                    val mapModifier = if (setPagerScrollEnabled != null) {
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitFirstDown(requireUnconsumed = false)
                                        setPagerScrollEnabled(false)
                                        do {
                                            val event = awaitPointerEvent()
                                        } while (event.changes.any { it.pressed })
                                        setPagerScrollEnabled(true)
                                    }
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    }

                    GoogleMap(
                        modifier = mapModifier,
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(isMyLocationEnabled = true),
                        uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false)
                    ) {
                        Polyline(
                            points = currentDirectionData.path,
                            color = Color.Blue.copy(alpha = 0.7f),
                            width = 15f
                        )

                        currentDirectionData.stops.forEach { stop ->
                            key(stop.stopUID) { 
                                val stopPosition = remember(stop.stopUID) {
                                    LatLng(stop.stopPosition.positionLat, stop.stopPosition.positionLon)
                                }
                                Marker(
                                    state = rememberMarkerState(position = stopPosition),
                                    title = stop.stopName.zhTw,
                                    icon = stopIcon,
                                    alpha = 0.7f
                                )
                            }
                        }
                        
                        currentBusPositions.forEach { bus ->
                            val isValidPosition = bus.busPosition.positionLat != 0.0 || bus.busPosition.positionLon != 0.0
                            if (isValidPosition) {
                                key(bus.plateNumb) {
                                    val busLatLng = LatLng(bus.busPosition.positionLat, bus.busPosition.positionLon)
                                    val markerState = rememberMarkerState(position = busLatLng)
                                    markerState.position = busLatLng

                                    Marker(
                                        state = markerState,
                                        title = "車牌: ${bus.plateNumb}",
                                        icon = busIcon,
                                        zIndex = 1f
                                    )
                                }
                            } else {
                                 Log.w("RouteStopsScreen", "Invalid bus position received for Plate ${bus.plateNumb}: Lat=${bus.busPosition.positionLat}, Lon=${bus.busPosition.positionLon}")
                            }
                        }
                    }

                     FloatingActionButton(
                        onClick = {
                            viewModel.manualRefresh()
                            forceLocationUpdate(zoomToUser = true)
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Default.MyLocation, contentDescription = "定位到目前位置")
                        }
                    }
                } 

                LazyColumn(
                    modifier = Modifier.weight(0.5f).padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(currentDirectionData.stops, key = { _, stop -> stop.stopUID }) { index, stop ->
                        StopListItem(
                            stop = stop,
                            viewModel = viewModel,
                        )
                    }
                }

            }
        }
    } 
}
