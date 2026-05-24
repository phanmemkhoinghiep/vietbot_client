package vn.vietbot.client

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import vn.vietbot.client.data.model.Activation
import vn.vietbot.client.data.model.DeviceInfo
import vn.vietbot.client.data.model.MqttConfig
import vn.vietbot.client.data.model.OtaResult
import vn.vietbot.client.data.model.fromJsonToOtaResult
import vn.vietbot.client.data.model.toJson
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Ota @Inject constructor(private val context: Context,
                              val deviceInfo: DeviceInfo
) {
    companion object {
        private const val TAG = "Ota"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()


    var otaResult: OtaResult? = null

    val headers = mutableMapOf<String, String>()
    val currentVersion = deviceInfo.application.version
    val firmwareUrl: String
        get() = otaResult?.firmware?.url ?: ""
    val hasActivationCode: Boolean
        get() = otaResult?.activation != null


    // Flow for upgrade progress and speed
    private val _upgradeState = MutableStateFlow(Pair(0, 0L)) // (progress, speed)
    val upgradeState: StateFlow<Pair<Int, Long>> = _upgradeState

    // Set HTTP Header
    fun setHeader(key: String, value: String) {
        headers[key] = value
    }

    init {
        setHeader("Device-Id", deviceInfo.mac_address);
        setHeader("Client-Id", deviceInfo.uuid);
        setHeader("X-Language", "Chinese");
    }



    // Check version
    suspend fun checkVersion(checkVersionUrl: String,): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Current version: $currentVersion")

        if (checkVersionUrl.length < 10) {
            Log.e(TAG, "Check version URL is not properly set")
            return@withContext false
        }

        val requestBuilder = Request.Builder()
            .url(checkVersionUrl)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        requestBuilder.addHeader("Content-Type", "application/json")
        val postDataJson = deviceInfo.toJson()
        val request = if (postDataJson.isNotEmpty()) {
            requestBuilder.post(
                postDataJson
                    .toRequestBody("application/json".toMediaTypeOrNull()!!)
            )
        } else {
            requestBuilder.get()
        }.build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to open HTTP connection: ${response.code}")
                return@withContext false
            }

            val responseBody = response.body?.string() ?: run {
                Log.e(TAG, "Empty response body")
                return@withContext false
            }
            Log.i(TAG, "Response: $responseBody")
            val json = JSONObject(responseBody)
            parseJsonResponse(json)
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "HTTP request failed: ${e.message}")
            return@withContext false
        }
    }

    // Parse JSON response
    private fun parseJsonResponse(json: JSONObject) {
        // Activation
        otaResult = fromJsonToOtaResult(json)
    }

    // Mark current version valid (simulated)
    suspend fun markCurrentVersionValid() {
        Log.i(TAG, "Marking current version as valid (Android simulation)")
        // Android does not need partition management, system verifies APK
    }

    // Upgrade firmware
    suspend fun upgrade(firmwareUrl: String = this.firmwareUrl) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Upgrading firmware from $firmwareUrl")

        val request = Request.Builder()
            .url(firmwareUrl)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download firmware: ${response.code}")
                return@withContext
            }

            val contentLength = response.body?.contentLength() ?: 0L
            if (contentLength == 0L) {
                Log.e(TAG, "Failed to get content length")
                return@withContext
            }

            val file = File(context.cacheDir, "firmware.apk")
            val sink = file.sink().buffer()
            val source = response.body?.source() ?: return@withContext

            var totalRead = 0L
            var recentRead = 0L
            var lastCalcTime = System.currentTimeMillis()

            while (true) {
                val read = source.read(sink.buffer, 512)
                if (read == -1L) break

                recentRead += read
                totalRead += read
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastCalcTime >= 1000 || read == 0L) {
                    val progress = (totalRead * 100 / contentLength).toInt()
                    val speed = recentRead * 1000 / (currentTime - lastCalcTime) // bytes/second
                    Log.i(TAG, "Progress: $progress% ($totalRead/$contentLength), Speed: $speed B/s")
                    _upgradeState.emit(Pair(progress, speed))
                    lastCalcTime = currentTime
                    recentRead = 0L
                }
            }

            sink.close()
            source.close()

            // Verify and install (Android APK example)
            val downloadedVersion = "1.0.0" // Assume from file metadata, actual parsing needed
            if (downloadedVersion == currentVersion) {
                Log.e(TAG, "Firmware version is the same, skipping upgrade")
                return@withContext
            }

            installFirmware(file)
            Log.i(TAG, "Firmware upgrade successful, restarting app...")
            delay(3000) // Simulate restart
            restartApp()

        } catch (e: Exception) {
            Log.e(TAG, "Upgrade failed: ${e.message}")
        }
    }

    // Start upgrade with callback
    suspend fun startUpgrade() {
        upgrade(firmwareUrl)
    }

    // Parse version number
    private fun parseVersion(version: String): List<Int> {
        return version.split(".").map { it.toInt() }
    }

    // Check for new version
    private fun isNewVersionAvailable(currentVersion: String, newVersion: String): Boolean {
        val current = parseVersion(currentVersion)
        val newer = parseVersion(newVersion)

        for (i in 0 until minOf(current.size, newer.size)) {
            if (newer[i] > current[i]) return true
            if (newer[i] < current[i]) return false
        }
        return newer.size > current.size
    }

    // Install firmware (Android APK example)
    private fun installFirmware(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
        }
        context.startActivity(intent)
    }

    // Restart application
    private fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
