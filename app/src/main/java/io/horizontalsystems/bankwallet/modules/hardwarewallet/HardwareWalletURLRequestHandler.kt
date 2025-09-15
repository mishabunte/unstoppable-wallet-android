package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.util.Log
import com.google.gson.Gson
import io.horizontalsystems.bitcoincore.crypto.Base58
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class TokenResponse(val token: String, val status: String)
data class TokenCheckResponse(val edition: String, val status: String)

class HardwareWalletURLRequestHandler {
    private fun requestAuthToken(): String? {
        try {
            val url = URL("https://auth.hito.xyz/api/request_token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            val response = connection.inputStream.bufferedReader().use { it.readText() }

            val tokenResponse = Gson().fromJson(response, TokenResponse::class.java)

            if (tokenResponse.status != "ok") {
                Log.e("HitoAuth", "Token request failed: ${tokenResponse.status}")
                throw IOException("Token request failed: ${tokenResponse.status}")
            }
            return tokenResponse.token
        } catch (e: Exception) {
            Log.e("HitoAuth", "Error requesting token", e)
            return null
        }
    }

    fun createAuthPayload(): String {
        val token = requestAuthToken() ?: throw IOException("Failed to retrieve auth token")
        val ts = Instant.now().epochSecond
        val roundedTs = (ts - (ts % 600)).toString()
        return "hito.auth:$roundedTs.${token}"
    }

    fun authorizeToken(token: String): TokenCheckResponse {
        try {
            val url = URL("https://auth.hito.xyz/api/check_token?t=$token")
            Log.d("HitoAuth.AuthorizeToken", "Requesting URL: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            return Gson().fromJson(response, TokenCheckResponse::class.java)
        } catch (e: Exception) {
            Log.e("HitoAuth", "Error authorizing token", e)
            throw IOException("Error authorizing token: ${e.message}")
        }
    }

    fun getSolanaLatestBlockhash(endpoint: String? = null): String? {
        try {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val requestBody = """
                {
                "jsonrpc":"2.0",
                "id":1,
                "method":"getLatestBlockhash",
                "params":[]
                }
                """.trimIndent()
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                return json.getJSONObject("result").getJSONObject("value").getString("blockhash")
            } else {
                Log.e("HitoAuth", "Failed to get latest blockhash: ${connection.responseMessage}")
                return null
            }
        } catch (e: Exception) {
            Log.e("HitoAuth", "Error getting latest blockhash", e)
            return null
        }
    }

    fun sendSolanaRawTransaction(
        endpoint: String? = null,
        rawTransaction: String
    ): String? {
        try {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            Log.d("HitoAuth.SendTransaction", "Using RPC source: $endpoint")

            val requestBody = """
                {
                "jsonrpc":"2.0",
                "id":1,
                "method":"sendTransaction",
                "params": [
                  "$rawTransaction",
                  {
                    "encoding": "base64"
                  }
                ]
                }
                """.trimIndent()
            Log.d("HitoAuth.SendTransaction", "Request Body: $requestBody")
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                Log.d("HitoAuth.SendTransaction", "Response: $json")
                if (json.has("error")) {
                    Log.e("HitoAuth.SendTransaction", "Error in response: ${json.getJSONObject("error").getString("message")}")
                    return null
                }
                return json.getString("result")
            } else {
                Log.e("HitoAuth", "Failed to send raw transaction: ${connection.responseMessage}")
                return null
            }
        } catch (e: Exception) {
            Log.e("HitoAuth", "Error sending raw transaction", e)
            return null
        }
    }
}