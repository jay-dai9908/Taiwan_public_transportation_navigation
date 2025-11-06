package com.example.bus

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bus.tdx.BusRoute
import com.example.bus.tdx.TdxClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class City(val name: String, val tdxName: String)

class BusStatusViewModel(application: Application) : AndroidViewModel(application) {

    // 用於儲存被選中路線的詳細資訊
    data class SelectedRoute(
        val cityTdxName: String,
        val routeName: String,
        val initialDirection: Int?
    )

    private val TAG = "BusStatusViewModel"
    private val SEVEN_DAYS_IN_MILLIS = 7 * 24 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File(application.cacheDir, "route_cache").apply { mkdirs() }

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

    private val _selectedCity = MutableStateFlow(cities.first())
    val selectedCity: StateFlow<City> = _selectedCity.asStateFlow()

    private val _routes = MutableStateFlow<List<BusRoute>>(emptyList())
    val routes: StateFlow<List<BusRoute>> = _routes.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 用於控制顯示列表還是詳情頁
    private val _selectedRouteInfo = MutableStateFlow<SelectedRoute?>(null)
    val selectedRouteInfo: StateFlow<SelectedRoute?> = _selectedRouteInfo.asStateFlow()

    // 當使用者點擊列表中的路線時呼叫
    fun onRouteSelected(cityTdxName: String, routeName: String, initialDirection: Int?) {
        _selectedRouteInfo.value = SelectedRoute(cityTdxName, routeName, initialDirection)
    }

    // 當使用者從詳情頁按返回時呼叫
    fun onDetailsBack() {
        _selectedRouteInfo.value = null
    }

    private var userHasManuallySelectedCity = false

    init {
        // ViewModel 初始化時，取得預設城市的路線
        Log.i(TAG, "ViewModel initialized. Fetching routes for default city: ${_selectedCity.value.name}")
        fetchRoutesForCity(_selectedCity.value)
    }

    fun initializeWithCity(cityName: String?) {
        Log.i(TAG, "initializeWithCity called with: '$cityName'. userHasManuallySelectedCity: $userHasManuallySelectedCity")
        if (userHasManuallySelectedCity || cityName == null) {
            return
        }

        val normalizedCityName = cityName.replace("臺", "台")
        val city = cities.find { normalizedCityName.startsWith(it.name) }
        
        if (city != null && city != _selectedCity.value) {
            Log.i(TAG, "City found by location: ${city.name}. Fetching routes...")
            _selectedCity.value = city
            fetchRoutesForCity(city)
        }
    }

    fun onCitySelected(city: City) {
        Log.i(TAG, "onCitySelected called with: ${city.name}. Always reloading.")
        userHasManuallySelectedCity = true

        // 手動選擇城市時，一律重新載入
        _selectedCity.value = city
        fetchRoutesForCity(city)
    }

    fun onSearchTextChanged(text: String) {
        _searchText.value = text
    }

    private fun sortRoutes(routes: List<BusRoute>): List<BusRoute> {
        val regex = Regex("\\d+") // 尋找第一組數字
        return routes.sortedWith(compareBy<BusRoute> { route ->
            val routeName = route.routeName.zhTw ?: ""
            val match = regex.find(routeName)
            match?.value?.toInt() ?: Int.MAX_VALUE // 沒有數字的路線排在後面
        }.thenBy { route ->
            val routeName = route.routeName.zhTw ?: ""
            routeName.any { it > '\u4E00' } // 檢查是否包含中文字元
        })
    }

    private fun fetchRoutesForCity(city: City) {
        viewModelScope.launch {
            _isLoading.value = true
            _routes.value = emptyList()

            val cacheFile = File(cacheDir, "routes_${city.tdxName}.json")
            val isCacheValid = cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < SEVEN_DAYS_IN_MILLIS

            if (isCacheValid) {
                Log.i(TAG, "Attempting to load routes for ${city.name} from valid cache.")
                val cachedRoutes = readRoutesFromCache(cacheFile)
                if (cachedRoutes != null && cachedRoutes.isNotEmpty()) {
                    Log.i(TAG, "Successfully loaded ${cachedRoutes.size} routes from cache.")
                    _routes.value = sortRoutes(cachedRoutes)
                } else {
                    Log.w(TAG, "Cache for ${city.name} was valid but empty/null. Re-fetching from API.")
                    fetchRoutesFromApi(city, cacheFile)
                }
            } else {
                Log.i(TAG, "Cache for ${city.name} is missing or stale. Fetching from API.")
                fetchRoutesFromApi(city, cacheFile)
            }
            _isLoading.value = false
        }
    }

    private suspend fun fetchRoutesFromApi(city: City, cacheFile: File) {
        try {
            val fetchedRoutes = TdxClient.getBusRoutes(city.tdxName, "RouteName/Zh_tw ne null")
            Log.i(TAG, "Fetched ${fetchedRoutes.size} routes for ${city.tdxName} from API.")
            val sorted = sortRoutes(fetchedRoutes)
            _routes.value = sorted
            saveRoutesToCache(cacheFile, sorted) // 將排序後的列表存入快取
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch routes from API for ${city.name}", e)
            if (cacheFile.exists()) {
                Log.w(TAG, "API failed. Loading stale cache for ${city.name} as fallback.")
                _routes.value = readRoutesFromCache(cacheFile)?.let { sortRoutes(it) } ?: emptyList()
            }
        }
    }

    private suspend fun saveRoutesToCache(cacheFile: File, routes: List<BusRoute>) {
        withContext(Dispatchers.IO) {
            try {
                val jsonString = json.encodeToString(routes)
                cacheFile.writeText(jsonString)
                Log.i(TAG, "Successfully saved routes to ${cacheFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving cache file ${cacheFile.name}", e)
            }
        }
    }

    private suspend fun readRoutesFromCache(cacheFile: File): List<BusRoute>? {
        return withContext(Dispatchers.IO) {
            try {
                val jsonString = cacheFile.readText()
                json.decodeFromString<List<BusRoute>>(jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cache file ${cacheFile.name}", e)
                null
            }
        }
    }
}
