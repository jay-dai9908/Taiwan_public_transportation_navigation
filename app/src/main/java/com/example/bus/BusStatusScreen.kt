package com.example.bus

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.bus.tdx.BusRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusStatusScreen(
    modifier: Modifier = Modifier,
    viewModel: BusStatusViewModel = hiltViewModel(),
    onRouteClick: (BusRoute) -> Unit,
    detectedCityName: String?,
    setPagerScrollEnabled: (Boolean) -> Unit
) {
    val cities by viewModel.cities.let { remember { derivedStateOf { it } } }
    val selectedCity by viewModel.selectedCity.collectAsState()
    val routes by viewModel.routes.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedRoute by viewModel.selectedRouteInfo.collectAsState()
    var isDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(detectedCityName) {
        if (detectedCityName != null) {
            viewModel.initializeWithCity(detectedCityName)
        }
    }

    val filteredRoutes = remember(searchText, routes) {
        if (searchText.isBlank()) {
            routes
        } else {
            routes.filter { it.routeName.zhTw?.contains(searchText, ignoreCase = true) == true }
        }
    }

    AnimatedContent(
        targetState = selectedRoute,
        label = "ListDetailsAnimation",
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        }
    ) { routeInfo ->
        if (routeInfo == null) {
            Column(modifier = modifier) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = isDropdownExpanded,
                            onExpandedChange = { isDropdownExpanded = it },
                        ) {
                            TextField(
                                value = selectedCity.name,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("選擇縣市") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.textFieldColors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = isDropdownExpanded,
                                onDismissRequest = { isDropdownExpanded = false }
                            ) {
                                cities.forEach { city ->
                                    DropdownMenuItem(
                                        text = { Text(city.name) },
                                        onClick = {
                                            viewModel.onCitySelected(city)
                                            isDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        TextField(
                            value = searchText,
                            onValueChange = { viewModel.onSearchTextChanged(it) },
                            label = { Text("搜尋路線") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            )
                        )
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(filteredRoutes) { route ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.onRouteSelected(
                                            cityTdxName = selectedCity.tdxName,
                                            routeName = route.routeName.zhTw ?: "",
                                            initialDirection = 0 // Default to direction 0
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(route.routeName.zhTw ?: "", modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            RouteStopsScreen(
                cityTdxName = routeInfo.cityTdxName,
                routeName = routeInfo.routeName,
                initialDirection = routeInfo.initialDirection,
                onBack = {
                    viewModel.onDetailsBack()
                },
                setPagerScrollEnabled = setPagerScrollEnabled
            )
        }
    }
}
