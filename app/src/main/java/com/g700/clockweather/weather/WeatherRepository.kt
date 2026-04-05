package com.g700.clockweather.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.g700.clockweather.overlay.OverlayWeatherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class WeatherFetchResult(
    val state: OverlayWeatherState? = null,
    val status: String,
    val errorMessage: String? = null,
    val outdoorTemp: Float? = null,
    val vehicleTemperatureDiagnostic: String? = null
)

class WeatherRepository(private val context: Context) {
    private val vehicleTemperatureSource = VehicleTemperatureSource(context.applicationContext)

    val vehicleTemperatureUpdates: StateFlow<WeatherFetchResult?> = vehicleTemperatureSource.updates

    val hasVehicleTemperatureSubscription: Boolean
        get() = vehicleTemperatureSource.hasActiveSubscription

    fun start() {
        vehicleTemperatureSource.start()
    }

    fun stop() {
        vehicleTemperatureSource.stop()
    }

    suspend fun refresh(preferInternetWeather: Boolean): WeatherFetchResult = withContext(Dispatchers.IO) {
        val vehicleWeather = vehicleTemperatureSource.latestResult()
        val vehicleFailure = vehicleTemperatureSource.diagnosticMessage

        if (!preferInternetWeather) {
            return@withContext vehicleWeather ?: WeatherFetchResult(
                status = "Vehicle temperature is unavailable.",
                errorMessage = vehicleFailure ?: "The car did not return an exterior temperature value.",
                vehicleTemperatureDiagnostic = vehicleFailure ?: "Vehicle temperature is unavailable."
            )
        }

        if (!hasWorkingInternet()) {
            return@withContext vehicleWeather?.copy(
                status = "No internet connection. Using vehicle temperature."
            ) ?: WeatherFetchResult(
                status = "No internet connection.",
                errorMessage = vehicleFailure ?: "Internet weather is enabled, but no network is connected and the vehicle temperature is unavailable.",
                vehicleTemperatureDiagnostic = vehicleFailure ?: "Vehicle temperature is unavailable."
            )
        }

        if (!hasAnyLocationPermission()) {
            return@withContext vehicleWeather?.copy(
                status = "Location permission missing. Using vehicle temperature."
            ) ?: WeatherFetchResult(
                status = "Location permission is required for internet weather.",
                errorMessage = vehicleFailure ?: "Grant location access to fetch internet weather.",
                vehicleTemperatureDiagnostic = vehicleFailure ?: "Vehicle temperature is unavailable."
            )
        }

        val location = bestLastKnownLocation()
        if (location == null) {
            return@withContext vehicleWeather?.copy(
                status = "Waiting for a GPS fix. Using vehicle temperature."
            ) ?: WeatherFetchResult(
                status = "Waiting for a GPS fix.",
                errorMessage = vehicleFailure ?: "No cached location is available yet.",
                vehicleTemperatureDiagnostic = vehicleFailure ?: "Vehicle temperature is unavailable."
            )
        }

        return@withContext runCatching {
            fetchInternetWeather(location).copy(
                status = "Internet weather active.",
                outdoorTemp = vehicleWeather?.outdoorTemp,
                vehicleTemperatureDiagnostic = vehicleFailure ?: vehicleWeather?.vehicleTemperatureDiagnostic
            )
        }.getOrElse { error ->
            vehicleWeather?.copy(
                status = "Internet weather failed. Using vehicle temperature."
            ) ?: WeatherFetchResult(
                status = "Internet weather failed.",
                errorMessage = vehicleFailure ?: error.message ?: error.javaClass.simpleName,
                vehicleTemperatureDiagnostic = vehicleFailure ?: "Vehicle temperature is unavailable."
            )
        }
    }

    private fun fetchInternetWeather(location: Location): WeatherFetchResult {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast?")
            append("latitude=${location.latitude}")
            append("&longitude=${location.longitude}")
            append("&current=temperature_2m,weather_code")
            append("&timezone=auto")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val current = JSONObject(body).getJSONObject("current")
        val temperature = current.optDouble("temperature_2m").takeIf { !it.isNaN() }?.toFloat()
        val weatherCode = current.optInt("weather_code", Int.MIN_VALUE)
        val label = weatherLabel(weatherCode)
        return WeatherFetchResult(
            state = OverlayWeatherState(
                conditionLabel = label,
                outsideTemperatureC = temperature,
                latitude = location.latitude,
                longitude = location.longitude,
                sourceLabel = "Open-Meteo"
            ),
            status = "Weather updated from Open-Meteo."
        )
    }

    private fun hasWorkingInternet(): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("api.open-meteo.com", 443), 1_500)
            }
            true
        }.getOrDefault(false)
    }

    private fun hasAnyLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(): Location? {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        return providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun weatherLabel(code: Int): String {
        return when (code) {
            0 -> "Clear"
            1, 2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Storm / hail"
            else -> "Weather"
        }
    }
}
