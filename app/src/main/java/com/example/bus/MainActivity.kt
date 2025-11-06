package com.example.bus

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Signpost
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bus.tdx.TdxClient
import com.example.bus.ui.theme.BusTheme
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

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

    fun preloadStopsForCity(chineseCityName: String) {
        viewModelScope.launch {
            val normalizedCityName = chineseCityName.replace("臺", "台")
            val city = cities.find { normalizedCityName.startsWith(it.name) }
            if (city != null) {
                Log.i("MainViewModel", "Preloading stops for ${city.tdxName} in the background.")
                TdxClient.getAllBusStops(city.tdxName)
            } else {
                Log.w("MainViewModel", "Could not find TDX city for '$chineseCityName' to preload.")
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private var detectedCityName: String? by mutableStateOf(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            fetchLastLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) -> {
                fetchLastLocation()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        setContent {
            BusTheme {
                BusApp(detectedCityName = detectedCityName)
            }
        }
    }

    private fun fetchLastLocation() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val geocoder = Geocoder(this, Locale.TRADITIONAL_CHINESE)
                    try {
                        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        if (addresses != null && addresses.isNotEmpty()) {
                            val cityName = addresses[0].adminArea
                            detectedCityName = cityName
                            mainViewModel.preloadStopsForCity(cityName)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Geocoder failed", e)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Location permission not granted", e)
        }
    }
}

sealed class AppScreen(val route: String, val title: String, val icon: ImageVector) {
    data object Main : AppScreen("main", "主頁", Icons.Default.Home)
    data object Navigation : AppScreen("navigation", "路線導航", Icons.Default.Navigation)
    data object BusStatus : AppScreen("bus_status", "公車動態", Icons.Default.DirectionsBus)
    data object NearbyStops : AppScreen("nearby_stops", "附近站牌", Icons.Default.Signpost)

    object RouteStops {
        const val ARG_CITY_NAME = "city"
        const val ARG_ROUTE_NAME = "route"
        const val ARG_DIRECTION = "direction"

        const val route = "route_stops"
        val arguments = listOf(
            navArgument(ARG_CITY_NAME) { type = NavType.StringType },
            navArgument(ARG_ROUTE_NAME) { type = NavType.StringType },
            navArgument(ARG_DIRECTION) {
                type = NavType.IntType
                defaultValue = -1
            }
        )

        const val fullRoute =
            "$route/{$ARG_CITY_NAME}/{$ARG_ROUTE_NAME}?$ARG_DIRECTION={$ARG_DIRECTION}"

        fun createRoute(cityTdxName: String, routeName: String, direction: Int? = null): String {
            val encodedRouteName = URLEncoder.encode(routeName, StandardCharsets.UTF_8.name())
            val routeBase = "$route/$cityTdxName/$encodedRouteName"
            return if (direction != null) {
                "$routeBase?$ARG_DIRECTION=$direction"
            } else {
                routeBase
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BusApp(
    detectedCityName: String?,
) {
    val navController = rememberNavController()

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { 3 }
    val pages = listOf(AppScreen.Navigation, AppScreen.BusStatus, AppScreen.NearbyStops)

    val busStatusViewModel: BusStatusViewModel = hiltViewModel()
    val nearbyStopsViewModel: NearbyStopsViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = AppScreen.Main.route,
        modifier = Modifier,
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        composable(AppScreen.Main.route) {
            MainScreen(
                navController = navController,
                pagerState = pagerState,
                pages = pages,
                scope = scope,
                busStatusViewModel = busStatusViewModel,
                nearbyStopsViewModel = nearbyStopsViewModel,
                detectedCityName = detectedCityName
            )
        }

        composable(
            route = AppScreen.RouteStops.fullRoute,
            arguments = AppScreen.RouteStops.arguments,
            enterTransition = {
                slideInHorizontally(initialOffsetX = { 1000 }, animationSpec = tween(400))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(400))
            }
        ) { backStackEntry ->
            val city = backStackEntry.arguments?.getString(AppScreen.RouteStops.ARG_CITY_NAME) ?: ""
            val encodedRouteName = backStackEntry.arguments?.getString(AppScreen.RouteStops.ARG_ROUTE_NAME) ?: ""
            val routeName = URLDecoder.decode(encodedRouteName, StandardCharsets.UTF_8.name())
            val initialDirection = backStackEntry.arguments?.getInt(AppScreen.RouteStops.ARG_DIRECTION, -1)?.takeIf { it != -1 }

            if (city.isNotEmpty() && routeName.isNotEmpty()) {
                RouteStopsScreen(
                    cityTdxName = city,
                    routeName = routeName,
                    initialDirection = initialDirection,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    navController: NavHostController,
    pagerState: PagerState,
    pages: List<AppScreen>,
    scope: CoroutineScope,
    busStatusViewModel: BusStatusViewModel,
    nearbyStopsViewModel: NearbyStopsViewModel,
    detectedCityName: String?
) {
    var isPagerScrollEnabled by remember { mutableStateOf(true) }

    Scaffold(
        bottomBar = {
            val currentRoute = pages[pagerState.currentPage].route
            NavigationBar {
                pages.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            beyondViewportPageCount = 2,
            userScrollEnabled = isPagerScrollEnabled
        ) { page ->
            when (pages[page]) {
                AppScreen.Navigation -> {
                    NavigationScreen(
                        navController = navController,
                        detectedCityName = detectedCityName,
                        setPagerScrollEnabled = { isPagerScrollEnabled = it }
                    )
                }
                AppScreen.BusStatus -> {
                    BusStatusScreen(
                        viewModel = busStatusViewModel,
                        detectedCityName = detectedCityName,
                        onRouteClick = { route ->
                            val selectedCityTdxName = busStatusViewModel.selectedCity.value.tdxName
                            val routeName = route.routeName.zhTw ?: ""
                            navController.navigate(
                                AppScreen.RouteStops.createRoute(selectedCityTdxName, routeName)
                            )
                        },
                        setPagerScrollEnabled = { isPagerScrollEnabled = it }
                    )
                }
                AppScreen.NearbyStops -> {
                    val currentCity by nearbyStopsViewModel.currentCity.collectAsState()
                    NearbyStopsScreen(
                        viewModel = nearbyStopsViewModel,
                        onRouteClick = { _, routeNameStr, direction ->
                             val cityTdxName = currentCity?.tdxName
                             if (cityTdxName != null) {
                                 navController.navigate(
                                     AppScreen.RouteStops.createRoute(
                                         cityTdxName,
                                         routeNameStr,
                                         direction
                                     )
                                 )
                             } else {
                                 Log.e("BusApp", "Cannot navigate from NearbyStops: currentCity is null")
                             }
                        },
                        // 將 Pager 控制函式傳遞下去
                        setPagerScrollEnabled = { isPagerScrollEnabled = it }
                    )
                }
                else -> {}
            }
        }
    }
}
