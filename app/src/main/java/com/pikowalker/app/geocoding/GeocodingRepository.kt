package com.pikowalker.app.geocoding

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume

class GeocodingRepository(private val context: Context) {

    suspend fun searchAddress(query: String): List<Address> {
        if (query.isBlank()) return emptyList()
        val geocoder = Geocoder(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, 5) { addresses ->
                    cont.resume(addresses)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 5) ?: emptyList()
            }
        }
    }
}
