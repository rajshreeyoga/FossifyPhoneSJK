package org.fossify.phone.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.telecom.Call
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossify.phone.extensions.isConference
import org.fossify.phone.models.CallContact
import java.io.IOException
import android.util.Log
import okhttp3.Cache
import okhttp3.CacheControl
import java.io.File
import java.util.concurrent.TimeUnit

fun getCallContactOld(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call.isConference()) {
        callback(CallContact(context.getString(R.string.conference), "", "", ""))
        return
    }

    val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
    ensureBackgroundThread {
        val callContact = CallContact("", "", "", "")
        val handle = try {
            call?.details?.handle?.toString()
        } catch (e: NullPointerException) {
            null
        }

        if (handle == null) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        val uri = Uri.decode(handle)
        if (uri.startsWith("tel:")) {
            val number = uri.substringAfter("tel:")
            ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
                val numbersToContactIDMap = HashMap<String, Int>()
                contactsWithMultipleNumbers.forEach { contact ->
                    contact.phoneNumbers.forEach { phoneNumber ->
                        numbersToContactIDMap[phoneNumber.value] = contact.contactId
                        numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
                    }
                }

                callContact.number = if (context.config.formatPhoneNumbers) {
                    number.formatPhoneNumber()
                } else {
                    number
                }

                val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                if (contact != null) {
                    callContact.name = contact.getNameToDisplay()
                    callContact.photoUri = contact.photoUri

                    if (contact.phoneNumbers.size > 1) {
                        val specificPhoneNumber = contact.phoneNumbers.firstOrNull { it.value == number }
                        if (specificPhoneNumber != null) {
                            callContact.numberLabel = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                        }
                    }
                } else {
                    callContact.name = callContact.number
                }

                callback(callContact)
            }
        }
    }
}





fun getCallContactNew(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call.isConference()) {
        callback(CallContact(context.getString(R.string.conference), "", "", ""))
        return
    }

    val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)

    ensureBackgroundThread {
        val callContact = CallContact("", "", "", "")
        val handle = try {
            call?.details?.handle?.toString()
        } catch (e: NullPointerException) {
            null
        }

        if (handle == null) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        val uri = Uri.decode(handle)
        if (uri.startsWith("tel:") && isInternetAvailable(context)) {


                val number = uri.substringAfter("tel:").removePrefix("+")
            val cacheSize = (5 * 1024 * 1024).toLong()
            val cacheDirectory = File(context.cacheDir, "http_cache")
            val cache = Cache(cacheDirectory, cacheSize)

            val client = OkHttpClient.Builder()
                .cache(cache)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val cacheControl = CacheControl.Builder()
                        .maxAge(31, TimeUnit.DAYS) // Cache for 31 days
                        .build()
                    response.newBuilder()
                        .header("Cache-Control", cacheControl.toString())
                        .build()
                }
                .build()

                val request = Request.Builder()
                    .url("https://apiv2.rajshreeyoga.com/client-name-search/?mobile_number=$number")
                    .get()
                    .addHeader("Accept", "*/*")
                    .addHeader("Authorization", "Token 8ed0ce2523e723c838f4fc3eb15d2db2812156ee")
                    .build()
                Log.d("MyTag", "getCallContact: $request")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")


                    val responseBody = response.body?.string()
                    // Assuming the server response contains a JSON object with a "name" field
                    val contactName = JSONObject(responseBody).getString("name")

                    if (contactName.equals("unknown number", ignoreCase = true)) {
                        val number = uri.substringAfter("tel:")
                        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
                            val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                            if (privateContacts.isNotEmpty()) {
                                contacts.addAll(privateContacts)
                            }

                            val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
                            val numbersToContactIDMap = HashMap<String, Int>()
                            contactsWithMultipleNumbers.forEach { contact ->
                                contact.phoneNumbers.forEach { phoneNumber ->
                                    numbersToContactIDMap[phoneNumber.value] = contact.contactId
                                    numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
                                }
                            }

                            callContact.number = if (context.config.formatPhoneNumbers) {
                                number.formatPhoneNumber()
                            } else {
                                number
                            }

                            val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                            if (contact != null) {
                                callContact.name = contact.getNameToDisplay()
                                callContact.photoUri = contact.photoUri

                                if (contact.phoneNumbers.size > 1) {
                                    val specificPhoneNumber = contact.phoneNumbers.firstOrNull { it.value == number }
                                    if (specificPhoneNumber != null) {
                                        callContact.numberLabel = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                                    }
                                }
                            } else {
                                callContact.name = callContact.number
                            }
                        }
                    }



                    callContact.name = contactName
                    callContact.number = if (context.config.formatPhoneNumbers) {
                        number.formatPhoneNumber()
                    } else {
                        number
                    }

                    callback(callContact)
                }

        }else{
            val number = uri.substringAfter("tel:")
            ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
                val numbersToContactIDMap = HashMap<String, Int>()
                contactsWithMultipleNumbers.forEach { contact ->
                    contact.phoneNumbers.forEach { phoneNumber ->
                        numbersToContactIDMap[phoneNumber.value] = contact.contactId
                        numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
                    }
                }

                callContact.number = if (context.config.formatPhoneNumbers) {
                    number.formatPhoneNumber()
                } else {
                    number
                }

                val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                if (contact != null) {
                    callContact.name = contact.getNameToDisplay()
                    callContact.photoUri = contact.photoUri

                    if (contact.phoneNumbers.size > 1) {
                        val specificPhoneNumber = contact.phoneNumbers.firstOrNull { it.value == number }
                        if (specificPhoneNumber != null) {
                            callContact.numberLabel = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                        }
                    }
                } else {
                    callContact.name = callContact.number
                }

                callback(callContact)
            }
        }

    }
}






private fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

    val isInternetConnected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isVpnActive = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    Log.d("MyTag", "isInternetAvailable: Internet=$isInternetConnected, VPN=$isVpnActive")

    return isInternetConnected && !isVpnActive // Return true only if internet is available and no VPN is active
}
