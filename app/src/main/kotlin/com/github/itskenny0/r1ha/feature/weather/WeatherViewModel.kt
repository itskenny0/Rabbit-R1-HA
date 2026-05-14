package com.github.itskenny0.r1ha.feature.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the Weather surface. Pulls `weather.*` entities via
 * [HaRepository.listRawEntitiesByDomain] and decodes the attributes
 * HA's weather domain reports — condition (raw state, e.g.
 * "partlycloudy"), temperature, humidity, wind speed, pressure.
 *
 * No forecast handling yet: HA emits forecast via a dedicated
 * `weather.get_forecasts` service in 2024+ rather than the legacy
 * `forecast` attribute. We could call the service and render the
 * forecast as a horizontal strip; left as a follow-up since the
 * current readout is the right zeroth-iteration.
 */
class WeatherViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class ForecastDay(
        val whenIso: String,
        val condition: String,
        val tempHigh: Double?,
        val tempLow: Double?,
        val precipitation: Double?,
    )

    @androidx.compose.runtime.Stable
    data class Weather(
        val entityId: String,
        val name: String,
        val condition: String,
        val temperature: Double?,
        val temperatureUnit: String?,
        val humidity: Int?,
        val windSpeed: Double?,
        val windUnit: String?,
        val pressure: Double?,
        val pressureUnit: String?,
        /** Legacy `forecast` attribute — daily forecast entries. Empty
         *  on 2024+ HA installs since the legacy attribute was removed
         *  in favour of the weather.get_forecasts service-with-response.
         *  When non-empty, surfaces as a horizontal strip on each
         *  weather card. */
        val forecast: List<ForecastDay>,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val weathers: List<Weather> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("weather").fold(
                onSuccess = { rows ->
                    val list = rows.map { row ->
                        val attrs = row.attributes
                        val forecastArr = attrs["forecast"] as? kotlinx.serialization.json.JsonArray
                        val forecast = forecastArr?.mapNotNull { el ->
                            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            val whenIso = (obj["datetime"] as? JsonPrimitive)?.content
                                ?: return@mapNotNull null
                            ForecastDay(
                                whenIso = whenIso,
                                condition = (obj["condition"] as? JsonPrimitive)?.content ?: "",
                                tempHigh = (obj["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                                tempLow = (obj["templow"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                                precipitation = (obj["precipitation"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            )
                        }.orEmpty().take(7)
                        Weather(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            condition = row.state,
                            temperature = (attrs["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            temperatureUnit = (attrs["temperature_unit"] as? JsonPrimitive)?.content,
                            humidity = (attrs["humidity"] as? JsonPrimitive)?.content
                                ?.toDoubleOrNull()?.toInt(),
                            windSpeed = (attrs["wind_speed"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            windUnit = (attrs["wind_speed_unit"] as? JsonPrimitive)?.content,
                            pressure = (attrs["pressure"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            pressureUnit = (attrs["pressure_unit"] as? JsonPrimitive)?.content,
                            forecast = forecast,
                        )
                    }.sortedBy { it.name.lowercase() }
                    R1Log.i("Weather", "loaded ${list.size}")
                    _ui.value = _ui.value.copy(loading = false, weathers = list, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Weather", "list failed: ${t.message}")
                    Toaster.error("Weather load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { WeatherViewModel(haRepository) }
        }
    }
}
