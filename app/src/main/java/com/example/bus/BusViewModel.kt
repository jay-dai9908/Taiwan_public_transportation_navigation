package com.example.bus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bus.tdx.TdxClient
import com.example.bus.tdx.BusRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BusViewModel : ViewModel() {

    private val _routes = MutableStateFlow<List<BusRoute>>(emptyList())
    val routes = _routes.asStateFlow()

    fun searchRoutes(city: String, routeName: String) {
        viewModelScope.launch {
            try {
                _routes.value = TdxClient.getBusRoutes(city, "RouteName/Zh_tw sw '$routeName'")
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}
