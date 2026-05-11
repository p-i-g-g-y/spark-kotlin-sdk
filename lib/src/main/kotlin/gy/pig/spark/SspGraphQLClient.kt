package gy.pig.spark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SspGraphQLClient(private val httpClient: OkHttpClient, private val sspURL: String, private val getToken: suspend () -> String,) {
    suspend fun executeRaw(query: String, variables: Map<String, Any>? = null,): JSONObject {
        val token = getToken()
        return executeGraphQL(httpClient, sspURL, token, query, variables)
    }
}

suspend fun executeGraphQL(httpClient: OkHttpClient, url: String, token: String?, query: String, variables: Map<String, Any>?,): JSONObject =
    withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("query", query)
            if (variables != null) {
                put("variables", JSONObject(variables))
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string()
            ?: throw SparkError.GraphqlError("Empty response body")

        if (!response.isSuccessful) {
            throw SparkError.GraphqlError("HTTP ${response.code}")
        }

        val json = JSONObject(responseBody)

        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val messages = (0 until errors.length())
                .mapNotNull { errors.getJSONObject(it).optString("message") }
                .joinToString("; ")
            throw SparkError.GraphqlError(messages)
        }

        json.optJSONObject("data") ?: throw SparkError.GraphqlError("No data in response")
    }
