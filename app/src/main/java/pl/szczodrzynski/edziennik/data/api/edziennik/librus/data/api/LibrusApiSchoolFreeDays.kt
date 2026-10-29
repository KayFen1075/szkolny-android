/*
 * Copyright (c) KayFen 2026-10-29.
 */

package pl.szczodrzynski.edziennik.data.api.edziennik.librus.data.api

import com.google.gson.JsonArray
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_INCORRECT_ENDPOINT
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_RESOURCE_ACCESS_DENIED
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_RESOURCE_NOT_FOUND
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.DataLibrus
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.ENDPOINT_LIBRUS_API_CLASS_FREE_DAYS
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.ENDPOINT_LIBRUS_API_SCHOOL_FREE_DAYS
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.data.LibrusApi
import pl.szczodrzynski.edziennik.data.enums.FeatureType
import pl.szczodrzynski.edziennik.ext.*

/**
 * Downloads the school's (or the class's) free days - holidays, breaks, etc.
 *
 * They are stored in the profile's student data (see [pl.szczodrzynski.edziennik.core.manager.SkipManager.FreeDay])
 * and used for estimating the remaining lessons in the semester.
 */
class LibrusApiSchoolFreeDays(override val data: DataLibrus,
                              override val lastSync: Long?,
                              val endpointId: Int,
                              val onSuccess: (endpointId: Int) -> Unit
) : LibrusApi(data, lastSync) {
    companion object {
        const val TAG = "LibrusApiSchoolFreeDays"
    }

    init {
        val (endpoint, key) = when (endpointId) {
            ENDPOINT_LIBRUS_API_CLASS_FREE_DAYS -> "ClassFreeDays" to "classFreeDays"
            else -> "SchoolFreeDays" to "schoolFreeDays"
        }

        // some schools deny access to the free days or don't have them at all
        apiGet(TAG, endpoint, ignoreErrors = listOf(
            ERROR_LIBRUS_API_RESOURCE_ACCESS_DENIED,
            ERROR_LIBRUS_API_RESOURCE_NOT_FOUND,
            ERROR_LIBRUS_API_INCORRECT_ENDPOINT,
        )) { json ->
            val freeDays = json.getJsonArray(endpoint)?.asJsonObjectList()

            if (freeDays != null) {
                val result = JsonArray()
                freeDays.forEach { freeDay ->
                    val dateFrom = freeDay.getString("DateFrom") ?: return@forEach
                    val dateTo = freeDay.getString("DateTo") ?: dateFrom
                    result.add(JsonObject(
                        "dateFrom" to dateFrom,
                        "dateTo" to dateTo,
                        "name" to freeDay.getString("Name"),
                    ))
                }
                profile?.set(key, result)
            }

            data.setSyncNext(endpointId, 2 * DAY, FeatureType.AGENDA)
            onSuccess(endpointId)
        }
    }
}
