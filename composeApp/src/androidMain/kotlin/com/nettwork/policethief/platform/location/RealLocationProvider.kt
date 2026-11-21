package com.nettwork.policethief.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.platform.LocationException
import com.nettwork.policethief.platform.LocationProvider
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.rules.LocationValidator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android 실제 GPS 위치(Google Play 서비스 Fused Location Provider).
 * 1) 권한 확인 → 2) 위치 서비스 켜짐 확인 → 3) 고정밀 현재 위치 1회 요청(최대 20초).
 */
class RealLocationProvider(private val context: Context) : LocationProvider {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // 아래에서 직접 권한을 확인한다.
    override suspend fun getCurrentLocation(): PlayerLocation {
        val hasPermission = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!hasPermission) throw LocationException.PermissionDenied()

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) throw LocationException.ServiceDisabled()

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(REQUEST_DURATION_MILLIS)
            .setMaxUpdateAgeMillis(MAX_CACHED_AGE_MILLIS)
            .build()

        val location = withTimeoutOrNull(REQUEST_DURATION_MILLIS + 5_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val cancellation = CancellationTokenSource()
                client.getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(LocationException.Unavailable(it.message ?: "알 수 없음")) }
                continuation.invokeOnCancellation { cancellation.cancel() }
            }
        } ?: throw LocationException.Timeout()

        return PlayerLocation(
            userId = null,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else LocationValidator.MAX_ACCURACY_METERS,
            // location.time 은 실제 측정 시각(UTC epoch ms). 현재 시각으로 바꾸지 않아야 오래된 캐시 위치를 서버가 걸러낼 수 있다.
            timestampEpochMillis = location.time,
            source = LocationSource.GPS,
        )
    }

    private companion object {
        const val REQUEST_DURATION_MILLIS = 15_000L
        const val MAX_CACHED_AGE_MILLIS = 10_000L
    }
}
