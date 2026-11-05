package com.arya.maxai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** User-supplied key stays in app memory only. Mobile BYOK is not a secure backend. */
class GeminiClient {
 suspend fun generate(key: String, model: String, prompt: String): String = withContext(Dispatchers.IO) {
  require(key.isNotBlank()) { "Gemini API key enter karo." }
  val m = model.trim().ifBlank { "gemini-2.5-flash" }
  val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=${key.trim()}")
  val body = JSONObject().put("contents", org.json.JSONArray().put(
   JSONObject().put("role","user").put("parts",org.json.JSONArray().put(JSONObject().put("text",prompt)))
  )).toString()
  val c = url.openConnection() as HttpURLConnection
  try {
   c.requestMethod="POST"; c.connectTimeout=20000; c.readTimeout=60000; c.doOutput=true
   c.setRequestProperty("Content-Type","application/json")
   c.outputStream.use { it.write(body.toByteArray()) }
   val code=c.responseCode
   val stream=if(code in 200..299)c.inputStream else c.errorStream
   val raw=stream.bufferedReader().use{it.readText()}
   if(code !in 200..299) throw IllegalStateException("Gemini HTTP $code: "+runCatching{JSONObject(raw).getJSONObject("error").optString("message")}.getOrDefault(raw.take(250)))
   JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text")
  } finally { c.disconnect() }
 }
}
