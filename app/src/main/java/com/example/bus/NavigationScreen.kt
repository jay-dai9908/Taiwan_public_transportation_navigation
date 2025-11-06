package com.example.bus

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.bus.tdx.CachedNearbyStation
import com.example.bus.tdx.TdxClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.model.DirectionsResult
import com.google.maps.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 用於在 LazyRow 中顯示的簡化資訊
data class AlternativeDisplayInfo(
    val routeNumber: String,
    val stops: String,
    val totalDuration: String, // A-B 總行程時間
    val arrivalInfo: NavigationViewModel.AlternativeArrivalInfo?,
    val isOriginal: Boolean,
    val originalStep: RouteStep.Transit?,
    val validatedRouteInfo: ValidatedRouteInfo?
)

// 提取路線名稱中的數字部分
fun extractRouteNumber(fullRouteName: String?): String {
    if (fullRouteName.isNullOrBlank()) return ""
    val startIndex = fullRouteName.indexOf('(')
    return if (startIndex != -1) {
        fullRouteName.substring(0, startIndex).trim()
    } else {
        fullRouteName.trim()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("MissingPermission")
@Composable
fun NavigationScreen(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    detectedCityName: String?,
    setPagerScrollEnabled: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val busStatusViewModel: BusStatusViewModel = hiltViewModel()
    val navigationViewModel: NavigationViewModel = viewModel()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, navigationViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                navigationViewModel.resumeUpdates()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                navigationViewModel.pauseUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            navigationViewModel.pauseUpdates()
        }
    }

    val alternativeRoutes by navigationViewModel.alternativeRoutes.collectAsState()
    val isLoadingAlternatives by navigationViewModel.isLoadingAlternatives.collectAsState()
    val alternativeRouteTimes by navigationViewModel.alternativeRouteTimes.collectAsState()

    // 從 ViewModel 讀取顯示的路線
    val allRoutes by navigationViewModel.displayedRoutes.collectAsState()

    var cityStations by remember { mutableStateOf<List<CachedNearbyStation>>(emptyList()) }
    var potentialCityTdxName by remember { mutableStateOf<String?>(null) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var selectedRouteIndex by remember { mutableIntStateOf(0) }
    var isRouteDetailView by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(false) }
    var searchOverlayHeight by remember { mutableStateOf(0.dp) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(25.047924, 121.517082), 12f)
    }

    // currentRoute 現在依賴 allRoutes 的變化
    val currentRoute = allRoutes.getOrNull(selectedRouteIndex)?.steps
    var navigationTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(detectedCityName, busStatusViewModel.cities) {
        potentialCityTdxName = detectedCityName?.let { name ->
            val normalizedCityName = name.replace("臺", "台")
            busStatusViewModel.cities.find { normalizedCityName.startsWith(it.name) }?.tdxName
        }
        potentialCityTdxName?.let {
            if (cityStations.isEmpty()) { // 僅在尚未載入時擷取
                Log.d("NavigationScreen", "City detected: $it. Fetching all stations.")
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val stations = TdxClient.getAllStationsInCity(it)
                        withContext(Dispatchers.Main) {
                            cityStations = stations
                            Log.d("NavigationScreen", "Fetched ${stations.size} stations for $it.")
                        }
                    } catch(e: Exception) {
                        Log.e("NavigationScreen", "Failed to load city stations", e)
                    }
                }
            }
        }
    }

    LaunchedEffect(navigationTarget) {
        navigationTarget?.let { (googleRouteName, cityTdxName) ->
            try {
                val tdxRoutes = TdxClient.getBusRoutes(cityTdxName, "RouteName/Zh_tw ne null")

                var matchedTdxRouteName: String? = tdxRoutes.find { it.routeName.zhTw == googleRouteName }?.routeName?.zhTw

                if (matchedTdxRouteName == null) {
                    matchedTdxRouteName = tdxRoutes.find { it.routeName.en == googleRouteName }?.routeName?.zhTw
                }

                if (matchedTdxRouteName == null) {
                    val normalizedName = googleRouteName.uppercase()
                    if (normalizedName.endsWith("V")) {
                        val baseNum = normalizedName.removeSuffix("V")
                        matchedTdxRouteName = tdxRoutes.find { it.routeName.zhTw == "${baseNum}副" }?.routeName?.zhTw
                    } else if (normalizedName.endsWith("E")) {
                        val baseNum = normalizedName.removeSuffix("E")
                        matchedTdxRouteName = tdxRoutes.find { it.routeName.zhTw == "${baseNum}延" }?.routeName?.zhTw
                    } else if (normalizedName == "TIAOWA BUS") {
                        matchedTdxRouteName = tdxRoutes.find { it.routeName.zhTw?.contains("跳蛙") == true }?.routeName?.zhTw
                    }
                }

                if (matchedTdxRouteName != null) {
                    Log.d("NavigationScreen", "Mapped '$googleRouteName' to TDX '$matchedTdxRouteName'. Navigating...")
                    navController.navigate(
                        AppScreen.RouteStops.createRoute(cityTdxName, matchedTdxRouteName)
                    )
                } else {
                    Log.w("NavigationScreen", "Could not map Google route '$googleRouteName' to a TDX route in city '$cityTdxName'.")
                    Toast.makeText(context, "無法找到 '$googleRouteName' 的詳細路線資訊", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("NavigationScreen", "Navigation or mapping failed", e)
                Toast.makeText(context, "路線對應失敗: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                navigationTarget = null
            }
        }
    }

    if (!Places.isInitialized()) {
        Places.initialize(context.applicationContext, BuildConfig.MAPS_API_KEY)
    }
    val placesClient = remember { Places.createClient(context) }

    val geoApiContext = remember {
        GeoApiContext.Builder().apiKey(BuildConfig.MAPS_API_KEY).build()
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    fun forceLocationUpdate() {
        isLocating = true
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location: android.location.Location? ->
                    if (location != null) {
                        val newLatLng = LatLng(location.latitude, location.longitude)
                        userLocation = newLatLng
                        coroutineScope.launch {
                            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(newLatLng, 15f))
                        }
                    }
                    isLocating = false
                }
                .addOnFailureListener {
                    Log.e("NavigationScreen", "Failed to get current location for update", it)
                    isLocating = false
                }
        } catch (e: SecurityException) {
            Log.e("NavigationScreen", "Location permission error on update", e)
            isLocating = false
        }
    }

    DisposableEffect(hasPermission) {
        if (!hasPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            forceLocationUpdate()
        }
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { userLocation = LatLng(it.latitude, it.longitude) }
            }
        }
        if (hasPermission) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            geoApiContext.shutdown()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (hasPermission) {
                MapScreen(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    userLocation = userLocation,
                    allRoutes = allRoutes.map { it.steps },
                    selectedRouteIndex = selectedRouteIndex,
                    isDetailView = isRouteDetailView,
                    topPadding = searchOverlayHeight,
                    onRouteClick = { index -> if (!isRouteDetailView) selectedRouteIndex = index },
                    setPagerScrollEnabled = setPagerScrollEnabled
                )
                SearchOverlay(
                    modifier = Modifier.align(Alignment.TopCenter).onGloballyPositioned {
                        searchOverlayHeight = with(density) { it.size.height.toDp() }
                    },
                    userLocation = userLocation,
                    placesClient = placesClient,
                    onRouteRequested = { start, end ->
                        coroutineScope.launch {
                            val directionsResult = getDirections(geoApiContext, start, end)
                            if (directionsResult?.routes?.isNotEmpty() == true) {
                                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                                val parsedRoutes = directionsResult.routes.map { route ->
                                    val leg = route.legs.first()
                                    DisplayRoute(
                                        steps = parseRoute(route),
                                        totalDuration = leg.duration.humanReadable.replace("hours", "小時").replace("hour", "小時").replace("mins", "分鐘"),
                                        departureTime = leg.departureTime?.let { timeFormatter.format(it) } ?: "--:--",
                                        arrivalTime = leg.arrivalTime?.let { timeFormatter.format(it) } ?: "--:--"
                                    )
                                 }
                                // 將路線設定到 ViewModel 中
                                navigationViewModel.setInitialRoutePlan(parsedRoutes)
                                selectedRouteIndex = 0
                                isRouteDetailView = false
                            }
                        }
                    }
                )
                FloatingActionButton(
                    onClick = { forceLocationUpdate() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = "重新定位")
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                        Text("請求位置權限")
                    }
                }
            }
        }

        if (isRouteDetailView && currentRoute != null) {
            LazyColumn(modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surface)) {
                item {
                    IconButton(onClick = { isRouteDetailView = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回路線選擇")
                    }
                }
                items(currentRoute) { step ->
                    val stepKey = remember(step) { step.polyline.hashCode().toString() }
                    StepCard(
                        step = step,
                        onClick = { clickedStep ->
                            potentialCityTdxName?.let {
                                navigationTarget = clickedStep.lineName to it
                            }
                        },
                        alternativeRoutes = alternativeRoutes[stepKey],
                        alternativeRouteTimes = alternativeRouteTimes,
                        isLoadingAlternatives = isLoadingAlternatives.contains(stepKey),
                        findAlternatives = { stepToFind ->
                            potentialCityTdxName?.let { tdxName ->
                                if (cityStations.isNotEmpty()) {
                                    navigationViewModel.findAlternativeRoutes(stepToFind, tdxName, cityStations)
                                } else {
                                    Log.w("NavigationScreen", "Cannot find alternatives: City stations not loaded.")
                                }
                            }
                        },
                        onAlternativeClick = { selectedInfo ->
                            navigationViewModel.updateRouteWithAlternative(
                                routeIndex = selectedRouteIndex,
                                stepIndex = currentRoute.indexOf(step), // 傳入當前步驟的索引
                                selectedRouteInfo = selectedInfo
                            )
                        },
                        potentialCityTdxName = potentialCityTdxName
                    )
                }
            }
        } else if (allRoutes.isNotEmpty()) {
            RouteSelectorOverlay(
                routes = allRoutes,
                selectedIndex = selectedRouteIndex,
                onRouteSelected = { index -> selectedRouteIndex = index },
                onRouteConfirmed = { index ->
                    selectedRouteIndex = index
                    isRouteDetailView = true
                }
            )
        }
    }
}

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    cameraPositionState: CameraPositionState,
    userLocation: LatLng?,
    allRoutes: List<List<RouteStep>>,
    selectedRouteIndex: Int,
    isDetailView: Boolean,
    topPadding: Dp,
    onRouteClick: (Int) -> Unit,
    setPagerScrollEnabled: (Boolean) -> Unit
) {
    LaunchedEffect(allRoutes, selectedRouteIndex, isDetailView) {
        if (allRoutes.isNotEmpty()) {
            val boundsBuilder = LatLngBounds.builder()
            val routeToBound = if (isDetailView) allRoutes.getOrNull(selectedRouteIndex) else allRoutes.flatten()
            routeToBound?.flatMap { it.polyline }?.forEach { boundsBuilder.include(it) }
            try {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 100), 1000)
            } catch (e: IllegalStateException) {
                Log.e("MapScreen", "Cannot animate camera, bounds are empty.")
            }
        }
    }

    GoogleMap(
        modifier = modifier
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
            },
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = true),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
        contentPadding = PaddingValues(top = topPadding)
    ) {
        if (isDetailView) {
            allRoutes.getOrNull(selectedRouteIndex)?.forEach { step ->
                Polyline(
                    points = step.polyline,
                    color = if (step is RouteStep.Walking) Color.Gray else Color(0xFF4285F4),
                    width = if (step is RouteStep.Walking) 10f else 12f,
                    pattern = if (step is RouteStep.Walking) listOf(Dash(30f), Gap(20f)) else null
                )
            }
        } else {
            allRoutes.forEachIndexed { index, route ->
                val isSelected = index == selectedRouteIndex
                Polyline(
                    points = route.flatMap { it.polyline },
                    color = if (isSelected) Color.Blue else Color.Gray,
                    width = if (isSelected) 12f else 8f,
                    clickable = true,
                    onClick = { onRouteClick(index) },
                    zIndex = if (isSelected) 1f else 0f
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteSelectorOverlay(
    routes: List<DisplayRoute>,
    selectedIndex: Int,
    onRouteSelected: (Int) -> Unit,
    onRouteConfirmed: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) { listState.animateScrollToItem(selectedIndex) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).background(MaterialTheme.colorScheme.surface).padding(8.dp)
    ) {
        itemsIndexed(routes) { index, route ->
            val isSelected = index == selectedIndex
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onRouteSelected(index) },
                colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                CompositionLocalProvider(LocalContentColor provides if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Column(modifier = Modifier.padding(16.dp).clickable { onRouteConfirmed(index) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "${route.departureTime} - ${route.arrivalTime}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(text = route.totalDuration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            route.steps.forEachIndexed { stepIndex, step ->
                                val iconColor = LocalContentColor.current
                                when (step) {
                                    is RouteStep.Walking -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Text(text = step.duration.replace("mins", "分"), fontSize = 12.sp)
                                        }
                                    }
                                    is RouteStep.Transit -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)).padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            val icon = when (step.vehicleType) {
                                                VehicleType.BUS -> Icons.Default.DirectionsBus
                                                VehicleType.SUBWAY -> Icons.Default.Subway
                                                VehicleType.TRAIN, VehicleType.RAIL -> Icons.Default.Train
                                                else -> Icons.Default.Commute
                                            }
                                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = iconColor)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(text = step.lineName, fontSize = 12.sp, color = iconColor)
                                        }
                                    }
                                }
                                if (stepIndex < route.steps.lastIndex) {
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepCard(
    step: RouteStep,
    onClick: (RouteStep.Transit) -> Unit,
    alternativeRoutes: List<ValidatedRouteInfo>?,
    alternativeRouteTimes: Map<String, NavigationViewModel.AlternativeArrivalInfo>,
    isLoadingAlternatives: Boolean,
    findAlternatives: (RouteStep.Transit) -> Unit,
    onAlternativeClick: (selectedInfo: AlternativeDisplayInfo) -> Unit,
    potentialCityTdxName: String?
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {

            val icon = when (step) {
                is RouteStep.Walking -> Icons.AutoMirrored.Filled.DirectionsWalk
                is RouteStep.Transit -> when (step.vehicleType) {
                    VehicleType.BUS -> Icons.Default.DirectionsBus
                    VehicleType.SUBWAY -> Icons.Default.Subway
                    VehicleType.TRAIN, VehicleType.RAIL -> Icons.Default.Train
                    else -> Icons.Default.Commute
                }
            }
            Icon(icon, contentDescription = "Step mode", modifier = Modifier.size(40.dp).padding(top = 4.dp))
            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 標題和時間
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val title = when (step) {
                        is RouteStep.Transit -> step.lineName
                        is RouteStep.Walking -> HtmlCompat.fromHtml(step.instructions, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (step.departureTime != null && step.arrivalTime != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${step.departureTime} ~ ${step.arrivalTime}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 步驟詳細資訊
                if (step is RouteStep.Transit) {
                     Text(
                        text = "從 ${step.departureStop} 搭乘到 ${step.arrivalStop}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }


                if (step is RouteStep.Transit && step.vehicleType == VehicleType.BUS) {
                    LaunchedEffect(step) {
                        if (alternativeRoutes == null && !isLoadingAlternatives) {
                            findAlternatives(step)
                        }
                    }

                    val allRoutesInfo = remember(step, alternativeRoutes, alternativeRouteTimes) {
                        if (alternativeRoutes == null) {
                            emptyList()
                        } else {
                            val allInfo = alternativeRoutes.map { validatedInfo ->
                                val arrivalInfo = alternativeRouteTimes[validatedInfo.route.routeUID]
                                val isGoogleRoute = validatedInfo.route.routeName.zhTw == step.lineName

                                AlternativeDisplayInfo(
                                    routeNumber = extractRouteNumber(validatedInfo.route.routeName.zhTw),
                                    stops = "${step.stopCount} 站",
                                    totalDuration = step.duration.replace("mins", "分"),
                                    arrivalInfo = arrivalInfo,
                                    isOriginal = isGoogleRoute,
                                    originalStep = if (isGoogleRoute) step else null,
                                    validatedRouteInfo = validatedInfo
                                )
                            }
                            // 排序：Google 路線優先，其餘按到站時間
                            allInfo.sortedWith(
                                compareBy(
                                    { !it.isOriginal },
                                    { it.arrivalInfo }
                                )
                            )
                        }
                    }

                    if (isLoadingAlternatives && allRoutesInfo.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在查找替代路線...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (allRoutesInfo.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(allRoutesInfo, key = { it.routeNumber + it.isOriginal }) { info ->
                                AlternativeBusCard(info = info) {
                                    onAlternativeClick(info)
                                }
                            }
                        }
                    } else if (!isLoadingAlternatives && alternativeRoutes != null) {
                        Text("無公車資訊", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                } else if (step is RouteStep.Transit) {
                    Text(
                        text = "${step.stopCount} 站 (${step.duration})",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                } else { // Walking
                    Text(
                        text = "距離: ${step.distance}, 時間: ${step.duration}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AlternativeBusCard(
    info: AlternativeDisplayInfo,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.width(110.dp).clickable(enabled = info.isOriginal || info.validatedRouteInfo != null) { onClick() },
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (info.isOriginal) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            // 路線名稱
            Text(
                text = info.routeNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (info.isOriginal) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))

            // 站數 + A-B 總時間
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info.stops,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = info.totalDuration,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))

            // 即時到站時間
            val displayText = info.arrivalInfo?.displayText ?: "載入中..."

            val displayColor = if (info.arrivalInfo?.status == NavigationViewModel.AlternativeArrivalInfo.Status.COMING) {
                MaterialTheme.colorScheme.primary // 強調色
            } else {
                LocalContentColor.current.copy(alpha = 0.7f) // 普通顏色
            }

            Text(
                text = displayText,
                style = MaterialTheme.typography.bodySmall,
                color = displayColor,
                fontWeight = if (displayColor == MaterialTheme.colorScheme.primary) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

suspend fun getDirections(geoApiContext: GeoApiContext, start: com.google.maps.model.LatLng, end: com.google.maps.model.LatLng): DirectionsResult? {
    return withContext(Dispatchers.IO) {
        try {
            DirectionsApi.newRequest(geoApiContext)
                .origin(start)
                .destination(end)
                .mode(TravelMode.TRANSIT)
                .language("zh-TW")
                .region("tw")
                .alternatives(true)
                .await()
        } catch (e: Exception) {
            Log.e("BusApp", "Directions API failed", e)
            null
        }
    }
}

fun parseRoute(route: com.google.maps.model.DirectionsRoute): List<RouteStep> {
    val steps = mutableListOf<RouteStep>()
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    val translationMap = mapOf(
        "Tze-Chiang Limited Express" to "自強號",
        "High-Speed Rail" to "高鐵",
        "Fast Local Train" to "區間快",
        "Local Train" to "區間車",
        "Bus" to "公車",
        "Train" to "火車",
        "Walk" to "步行",
        "to" to "至",
        "Station" to "站",
        "Taichung" to "台中",
        "Tainan" to "台南",
        "Taipei" to "台北",
        "City" to "市",
        "District" to "區",
        "Rd" to "路",
        "Ln" to "巷",
        "Blvd" to "大道",
        "Rail" to "鐵路",
        "Park" to "公園",
        "Entrance" to "入口",
        "Parking" to "停車場",
        "Lot" to "場"
    )

    fun translate(text: String): String {
        var currentText = translationMap[text] ?: text
        if (translationMap[text] == null) {
             val words = text.split(Regex("[\\s,()\\-]+"))
             currentText = words.joinToString("") { word -> translationMap[word] ?: word }
        }
        return currentText
    }

    route.legs.forEach { leg ->
        var currentTime = leg.departureTime

        leg.steps.forEach { step ->
            val polyline = step.polyline.decodePath().map { LatLng(it.lat, it.lng) }
            val instructions = HtmlCompat.fromHtml(step.htmlInstructions, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            val startLatLng = step.startLocation?.let { LatLng(it.lat, it.lng) }
            val endLatLng = step.endLocation?.let { LatLng(it.lat, it.lng) }
            val durationInSeconds = step.duration.inSeconds

            if (step.travelMode == TravelMode.WALKING) {
                val stepStartTime = currentTime
                val stepEndTime = currentTime?.plusSeconds(durationInSeconds)
                currentTime = stepEndTime

                steps.add(
                    RouteStep.Walking(
                        distance = step.distance.humanReadable,
                        duration = step.duration.humanReadable,
                        instructions = instructions,
                        polyline = polyline,
                        startLocation = startLatLng,
                        endLocation = endLatLng,
                        departureTime = stepStartTime?.let { timeFormatter.format(it) },
                        arrivalTime = stepEndTime?.let { timeFormatter.format(it) },
                        durationInSeconds = durationInSeconds,
                        departureTimeInstant = stepStartTime?.toInstant(),
                        arrivalTimeInstant = stepEndTime?.toInstant()
                    )
                )
            } else if (step.travelMode == TravelMode.TRANSIT) {
                val transitDetails = step.transitDetails
                val vehicleType = when (transitDetails.line.vehicle.type.toString()) {
                    "BUS" -> VehicleType.BUS
                    "SUBWAY" -> VehicleType.SUBWAY
                    "TRAIN" -> VehicleType.TRAIN
                    "TRAM" -> VehicleType.TRAM
                    "RAIL" -> VehicleType.RAIL
                    else -> VehicleType.UNKNOWN
                }
                val originalLineName = transitDetails.line.shortName ?: transitDetails.line.name ?: ""

                val stepStartTime = transitDetails.departureTime
                val stepEndTime = transitDetails.arrivalTime
                currentTime = stepEndTime

                steps.add(
                    RouteStep.Transit(
                        distance = step.distance.humanReadable,
                        duration = step.duration.humanReadable,
                        instructions = instructions,
                        polyline = polyline,
                        departureStop = translate(transitDetails.departureStop.name),
                        arrivalStop = translate(transitDetails.arrivalStop.name),
                        lineName = translate(originalLineName),
                        headsign = transitDetails.headsign?.let { translate(it) } ?: "",
                        stopCount = transitDetails.numStops,
                        vehicleType = vehicleType,
                        startLocation = startLatLng,
                        endLocation = endLatLng,
                        departureTime = stepStartTime?.let { timeFormatter.format(it) },
                        arrivalTime = stepEndTime?.let { timeFormatter.format(it) },
                        durationInSeconds = durationInSeconds,
                        departureTimeInstant = stepStartTime?.toInstant(),
                        arrivalTimeInstant = stepEndTime?.toInstant()
                    )
                )
            }
        }
    }
    return steps
}

@Composable
fun SearchOverlay(
    modifier: Modifier = Modifier,
    userLocation: LatLng?,
    placesClient: PlacesClient,
    onRouteRequested: (start: com.google.maps.model.LatLng, end: com.google.maps.model.LatLng) -> Unit
) {
    var startPointQuery by remember(userLocation) { mutableStateOf(if (userLocation != null) "您的位置" else "") }
    var endPointQuery by remember { mutableStateOf("") }
    var startPointPredictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var endPointPredictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var showStartPredictions by remember { mutableStateOf(false) }
    var showEndPredictions by remember { mutableStateOf(false) }
    var selectedStartPlace by remember { mutableStateOf<Place?>(null) }
    var selectedEndPlace by remember { mutableStateOf<Place?>(null) }

    Card(
        modifier = modifier.padding(16.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = startPointQuery,
                onValueChange = {
                    startPointQuery = it
                    showStartPredictions = true
                    if (it == "您的位置" || it.isEmpty()) {
                        startPointPredictions = emptyList()
                        selectedStartPlace = null
                    } else {
                        getPlacePredictions(it, placesClient) { predictions -> startPointPredictions = predictions }
                    }
                },
                label = { Text("起點") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (userLocation != null) {
                        IconButton(onClick = {
                            startPointQuery = "您的位置"
                            selectedStartPlace = null
                            showStartPredictions = false
                        }) { Icon(Icons.Default.MyLocation, contentDescription = "使用目前位置") }
                    }
                }
            )
            if (showStartPredictions && startPointPredictions.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(startPointPredictions) { prediction ->
                        Text(
                            text = prediction.getFullText(null).toString(),
                            modifier = Modifier.fillMaxWidth().clickable {
                                startPointQuery = prediction.getPrimaryText(null).toString()
                                showStartPredictions = false
                                fetchPlaceDetails(prediction, placesClient) { place -> selectedStartPlace = place }
                            }.padding(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = endPointQuery,
                    onValueChange = {
                        endPointQuery = it
                        showEndPredictions = true
                        getPlacePredictions(it, placesClient) { predictions -> endPointPredictions = predictions }
                    },
                    label = { Text("目的地") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val startLatLng = if (startPointQuery == "您的位置" && userLocation != null) {
                            com.google.maps.model.LatLng(userLocation.latitude, userLocation.longitude)
                        } else {
                            selectedStartPlace?.latLng?.let { com.google.maps.model.LatLng(it.latitude, it.longitude) }
                        }
                        val endLatLng = selectedEndPlace?.latLng?.let { com.google.maps.model.LatLng(it.latitude, it.longitude) }

                        if (startLatLng != null && endLatLng != null) {
                            onRouteRequested(startLatLng, endLatLng)
                        }
                    }
                ) {
                    Text("規劃")
                }
            }
            if (showEndPredictions && endPointPredictions.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(endPointPredictions) { prediction ->
                        Text(
                            text = prediction.getFullText(null).toString(),
                            modifier = Modifier.fillMaxWidth().clickable {
                                endPointQuery = prediction.getPrimaryText(null).toString()
                                showEndPredictions = false
                                fetchPlaceDetails(prediction, placesClient) { place -> selectedEndPlace = place }
                            }.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

fun getPlacePredictions(query: String, placesClient: PlacesClient, onResult: (List<AutocompletePrediction>)-> Unit) {
    if (query.isEmpty()) { onResult(emptyList()); return }
    val request = FindAutocompletePredictionsRequest.builder().setQuery(query).build()
    placesClient.findAutocompletePredictions(request)
        .addOnSuccessListener { onResult(it.autocompletePredictions) }
        .addOnFailureListener { Log.e("BusApp", "Prediction failed", it); onResult(emptyList()) }
}

fun fetchPlaceDetails(prediction: AutocompletePrediction, placesClient: PlacesClient, onResult: (Place) -> Unit) {
    val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)
    val request = FetchPlaceRequest.builder(prediction.placeId, placeFields).build()
    placesClient.fetchPlace(request)
        .addOnSuccessListener { onResult(it.place) }
        .addOnFailureListener { Log.e("BusApp", "Place fetch failed", it) }
}
