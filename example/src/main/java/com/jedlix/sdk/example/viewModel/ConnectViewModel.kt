/*
 * Copyright 2022 Jedlix B.V. The Netherlands
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

package com.jedlix.sdk.example.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jedlix.sdk.JedlixSDK
import com.jedlix.sdk.connectSession.ConnectSessionType
import com.jedlix.sdk.example.authentication.Authentication
import com.jedlix.sdk.model.Charger
import com.jedlix.sdk.model.ChargingLocation
import com.jedlix.sdk.model.Vehicle
import com.jedlix.sdk.networking.Api
import com.jedlix.sdk.networking.Error
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ConnectViewModel(private val userIdentifier: String) : ViewModel() {

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val userIdentifier: String
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConnectViewModel(
                userIdentifier
            ) as T
        }
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val vehicle = MutableStateFlow<Vehicle?>(null)
    val vehicleText = vehicle.map { vehicle ->
        vehicle?.vehicleDetails?.let { "${it.brand} ${it.model}" } ?: "No vehicles found"
    }.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val vehicleButtonText = vehicle.map { vehicle ->
        if (vehicle != null) {
            "Remove"
        } else {
            "Connect"
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, "")

    private val chargingLocations = MutableStateFlow(emptyList<ChargingLocation>())
    val chargingLocationText = chargingLocations.map { locations ->
        locations.firstOrNull()?.let {
            listOfNotNull(it.address.street, it.address.houseNumber, it.address.city).joinToString(", ")
        } ?: "No charging locations found"
    }.stateIn(viewModelScope, SharingStarted.Lazily, "")

    private val charger = MutableStateFlow<Charger?>(null)
    val chargerText = charger.map { charger ->
        charger?.homeChargerDetail?.let { "${it.brand} ${it.model}" } ?: "No chargers found"
    }.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val chargerButtonText = charger.map { charger ->
        if (charger != null) {
            "Remove"
        } else {
            "Connect"
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, "")

    private val _didDeauthenticate = MutableSharedFlow<Unit>()
    val didDeauthenticate: SharedFlow<Unit> = _didDeauthenticate
    private val _didStartConnectSession = MutableSharedFlow<Pair<String, ConnectSessionType>>()
    val didStartConnectSession: SharedFlow<Pair<String, ConnectSessionType>> = _didStartConnectSession

    private var lastConnectSessionId: String? = null

    init {
        reloadData()
    }

    fun vehicleButtonPressed() {
        if (vehicle.value != null) {
            removeVehicle()
        } else {
            startVehicleConnectSession()
        }
    }

    fun chargerButtonPressed() {
        if (charger.value != null) {
            removeCharger()
        } else {
            chargingLocations.value.firstOrNull()?.let {
                startChargerConnectSession(it)
            }
        }
    }

    fun deauthenticate() {
        (JedlixSDK.authentication as Authentication).deauthenticate()
        viewModelScope.launch {
            _didDeauthenticate.emit(Unit)
        }
    }

    fun reloadData() {
        showLoaderDuring {
            val vehicleResponse = viewModelScope.async {
                when (val response =
                    JedlixSDK.api.request { Users().User(userIdentifier).Vehicles().Get() }) {
                    is Api.Response.Success -> {
                        vehicle.value = response.result.firstOrNull()
                        _errorMessage.value = null
                    }
                    is Api.Response.Failure -> response.showMessage()
                }
            }

            val chargingLocationResponse = viewModelScope.async {
                JedlixSDK.api.request { Users().User(userIdentifier).ChargingLocations().Get() }
            }

            when (val response = chargingLocationResponse.await()) {
                is Api.Response.Success -> {
                    chargingLocations.value = response.result
                    charger.value = response.result.firstOrNull()?.let { location ->
                        when (val chargerResponse = JedlixSDK.api.request { Users().User(userIdentifier).ChargingLocations().ChargingLocation(location.id).Chargers().Get() }) {
                            is Api.Response.Success -> {
                                _errorMessage.value = null
                                chargerResponse.result.firstOrNull()
                            }
                            is Api.Response.Failure -> {
                                chargerResponse.showMessage()
                                null
                            }
                        }
                    }
                }
                is Api.Response.Failure -> response.showMessage()
            }
            vehicleResponse.await()
        }
    }

    private fun removeVehicle() {
        showLoaderDuring {
            val vehicleId = vehicle.value?.id ?: return@showLoaderDuring
            when (
                val response = JedlixSDK.api.request {
                    Users().User(userIdentifier).Vehicles().Vehicle(vehicleId).Delete()
                }
            ) {
                is Api.Response.Success -> vehicle.value = null
                is Api.Response.Failure -> response.showMessage()
            }
        }
    }

    private fun removeCharger() {
        showLoaderDuring {
            val charger = charger.value ?: return@showLoaderDuring
            val chargerId = charger.id
            val chargingLocationId = charger.chargingLocationId
            when (
                val response = JedlixSDK.api.request {
                    Users().User(userIdentifier).ChargingLocations().ChargingLocation(chargingLocationId).Chargers().Charger(chargerId).Delete()
                }
            ) {
                is Api.Response.Success -> this.charger.value = null
                is Api.Response.Failure -> response.showMessage()
            }
        }
    }

    private fun startVehicleConnectSession() {
        viewModelScope.launch {
            _didStartConnectSession.emit(userIdentifier to ConnectSessionType.Vehicle)
        }
    }

    private fun startChargerConnectSession(chargingLocation: ChargingLocation) {
        viewModelScope.launch {
            _didStartConnectSession.emit(userIdentifier to ConnectSessionType.Charger(chargingLocation.id))
        }
    }

    private fun showLoaderDuring(action: suspend () -> Unit) {
        if (_isLoading.compareAndSet(expect = false, update = true)) {
            viewModelScope.launch {
                try {
                    action()
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun Api.Response.Failure<*>.showMessage() {
        _errorMessage.value = when (this) {
            is Api.Response.Error -> {
                when (error) {
                    is Error.Forbidden -> "Forbidden"
                    is Error.Unauthorized -> "Unauthorized"
                    is Error.ApiError -> (error as Error.ApiError).title
                }
            }
            is Api.Response.NetworkFailure -> "Please check your network"
            is Api.Response.InvalidResult -> "Unexpected response"
            is Api.Response.SDKNotInitialized -> "SDK Not initialized properly"
        }
    }
}
