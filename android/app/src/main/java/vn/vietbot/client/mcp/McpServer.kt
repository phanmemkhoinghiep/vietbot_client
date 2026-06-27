package vn.vietbot.client.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP Server for Android
 * Handles MCP protocol communication and tool execution
 */
class McpServer(
    context: Context,
    private val glassesManager: SmartGlassesManager? = null,
    private val settings: vn.vietbot.client.data.SettingsRepository? = null,
    private val protocol: vn.vietbot.client.protocol.Protocol? = null
) {

    companion object {
        private const val TAG = "McpServer"
        private const val PROTOCOL_VERSION = "2024-11-05"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraTools = CameraMcpTools(context)
    private val glassesCameraTools: GlassesCameraTools? = glassesManager?.let { GlassesCameraTools(context, it) }
    private val audioTools = AudioMcpTools(context)
    private var requestId = AtomicInteger(1)

    // Camera source cache - updated when settings change
    // This is a workaround to ensure camera source is read at execution time
    @Volatile
    private var cachedCameraSource: vn.vietbot.client.data.CameraSource = vn.vietbot.client.data.CameraSource.PHONE

    // Cache for last captured photo (base64) - for follow-up questions about the photo
    @Volatile
    private var lastCapturedPhotoBase64: String? = null
    private var lastCapturedPhotoTimestamp: Long = 0

    init {
        // Initialize cached camera source from settings
        cachedCameraSource = settings?.cameraSource ?: vn.vietbot.client.data.CameraSource.PHONE
    }

    /**
     * Cache the last captured photo base64 for follow-up questions
     */
    fun cacheLastPhoto(base64: String?) {
        if (base64 != null && base64.isNotEmpty()) {
            lastCapturedPhotoBase64 = base64
            lastCapturedPhotoTimestamp = System.currentTimeMillis()
            Log.i(TAG, "Cached last photo: ${base64.length} chars")
        }
    }

    /**
     * Get the last captured photo base64 (valid for 5 minutes)
     */
    fun getLastPhotoBase64(): String? {
        val age = System.currentTimeMillis() - lastCapturedPhotoTimestamp
        return if (age < 5 * 60 * 1000) { // 5 minutes
            lastCapturedPhotoBase64
        } else {
            lastCapturedPhotoBase64 = null
            null
        }
    }

    /**
     * Update cached camera source when settings change
     * Call this whenever camera source is changed in SettingsRepository
     */
    fun updateCameraSource(source: vn.vietbot.client.data.CameraSource) {
        cachedCameraSource = source
        Log.i(TAG, "Updated cached camera source to: $source")
    }

    // Dynamic tools registry
    private val tools = mutableMapOf<String, suspend (Map<String, Any>) -> McpCallToolResult>()
    private val toolDefinitions = mutableListOf<McpToolDef>()

    // Server info
    private val serverInfo = JSONObject().apply {
        put("name", "vietbot-android")
        put("version", "1.0.0")
    }

    // Event flows
    private val _toolCallResults = MutableSharedFlow<McpToolCallResult>()
    val toolCallResults = _toolCallResults.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<McpServerRequest>()
    val serverRequests = _serverRequests.asSharedFlow()

    /**
     * Register a tool with callback
     */
    fun registerTool(
        name: String,
        description: String,
        properties: Map<String, McpProperty>,
        required: List<String> = emptyList(),
        callback: suspend (Map<String, Any>) -> McpCallToolResult
    ) {
        val toolDef = McpToolDef(
            name = name,
            description = description,
            inputSchema = McpInputSchema(
                type = "object",
                properties = properties,
                required = required
            )
        )
        toolDefinitions.add(toolDef)
        tools[name] = callback
        Log.i(TAG, "Registered MCP tool: $name")
    }

    /**
     * Get all available MCP tools
     */
    fun getTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()

        // Add camera tools (phone) - always available
        tools.addAll(cameraTools.getTools())

        // Add glasses camera tools if glasses are connected
        glassesCameraTools?.let { tools.addAll(it.getTools()) }

        // Add audio tools
        tools.addAll(audioTools.getTools())

        // Add dynamically registered tools
        toolDefinitions.forEach { toolDef ->
            tools.add(toolDef.toMcpTool())
        }

        // Add system tools
        tools.add(McpTool(
            name = "self.get_system_info",
            description = "Get the device system information including model, Android version, and available features.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ))

        return tools
    }

    /**
     * Handle incoming MCP message from server
     */
    suspend fun handleMessage(json: JSONObject): JSONObject? {
        val method = json.optString("method")
        val id = json.optInt("id", -1)

        Log.i(TAG, "Handle MCP message: method=$method, id=$id")

        return when (method) {
            "initialize" -> handleInitialize(id)
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, json.optJSONObject("params"))
            else -> {
                Log.w(TAG, "Unknown MCP method: $method")
                createErrorResponse(id, -32601, "Method not found: $method")
            }
        }
    }

    /**
     * Handle initialize request
     */
    private fun handleInitialize(id: Int): JSONObject {
        Log.i(TAG, "MCP initialize request")

        return createResponse(id, JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject().apply {
                    put("listChanged", true)
                })
            })
            put("serverInfo", serverInfo)
        })
    }

    /**
     * Handle tools/list request
     */
    private fun handleToolsList(id: Int): JSONObject {
        Log.i(TAG, "MCP tools/list request")

        val tools = getTools()
        val toolsArray = JSONArray()
        tools.forEach { tool ->
            toolsArray.put(tool.toJson())
        }

        return createResponse(id, JSONObject().apply {
            put("tools", toolsArray)
        })
    }

    /**
     * Handle tools/call request
     */
    private suspend fun handleToolsCall(id: Int, params: JSONObject?): JSONObject {
        val toolName = params?.optString("name") ?: run {
            return createErrorResponse(id, -32602, "Missing tool name")
        }

        val argumentsJson = params?.optJSONObject("arguments") ?: JSONObject()
        val arguments = argumentsJson.toMap()

        Log.i(TAG, "MCP tools/call: $toolName with args: $arguments")

        return try {
            val result = executeTool(toolName, arguments)
            createToolResponse(id, result)
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed", e)
            createErrorResponse(id, -32603, "Tool execution failed: ${e.message}")
        }
    }

    /**
     * Execute a tool by name
     * Routes camera tools to phone or glasses based on cameraSource setting
     */
    private suspend fun executeTool(toolName: String, args: Map<String, Any>): McpToolResult {
        // Check if it's a camera tool
        if (toolName.startsWith("self.camera.")) {
            val jsonArgs = JSONObject(args.toJsonString())

            // Check if question asks about "previous photo" - if yes, use cached image
            val question = jsonArgs.optString("question", "")
            val userInput = args["_user_input"] as? String ?: ""
            val isFollowUpPhotoQuestion = isFollowUpQuestion(userInput, question)

            if (isFollowUpPhotoQuestion) {
                val cachedBase64 = getLastPhotoBase64()
                if (cachedBase64 != null) {
                    Log.i(TAG, ">>> Using CACHED photo for follow-up question via HTTP")
                    // FIX: Send cached image via HTTP instead of WebSocket to avoid 1007 error
                    try {
                        val imageBytes = android.util.Base64.decode(cachedBase64, android.util.Base64.NO_WRAP)
                        val aiResponse = cameraTools.uploadToVisionServerSync(imageBytes, question.ifEmpty { "Mô tả những gì bạn nhìn thấy trong ảnh này" })
                        return McpToolResult(
                            content = listOf(McpContent.TextContent("Phân tích ảnh đã chụp trước đó: $aiResponse"))
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to analyze cached photo via HTTP", e)
                        // Fall through to capture new photo
                    }
                }
                Log.i(TAG, ">>> Follow-up question but no cached photo, will capture new photo")
            }

            // Always re-read camera source from SharedPreferences at execution time
            val currentCameraSource = settings?.cameraSource ?: cachedCameraSource
            cachedCameraSource = currentCameraSource
            val useGlassesCamera = currentCameraSource == vn.vietbot.client.data.CameraSource.GLASSES
            val glassesConnected = glassesManager?.connectionState?.value == GlassesConnectionState.CONNECTED

            Log.i(TAG, "===========================================")
            Log.i(TAG, "Camera routing check:")
            Log.i(TAG, "  toolName: $toolName")
            Log.i(TAG, "  question: $question")
            Log.i(TAG, "  isFollowUpPhotoQuestion: $isFollowUpPhotoQuestion")
            Log.i(TAG, "  cameraSource from settings: $currentCameraSource")
            Log.i(TAG, "  useGlassesCamera: $useGlassesCamera")
            Log.i(TAG, "  glassesConnected: $glassesConnected")
            Log.i(TAG, "===========================================")

            if (useGlassesCamera && glassesConnected && glassesCameraTools != null) {
                Log.i(TAG, ">>> USING GLASSES CAMERA for $toolName")
                val result = glassesCameraTools.executeTool(toolName, jsonArgs)
                cacheImageFromResult(result)
                return result
            }
            Log.i(TAG, ">>> USING PHONE CAMERA for $toolName")
            val result = cameraTools.executeTool(toolName, jsonArgs)
            cacheImageFromResult(result)
            return result
        }

        // Check if it's an audio tool
        if (toolName.startsWith("self.audio.")) {
            val jsonArgs = JSONObject(args.toJsonString())
            return audioTools.executeTool(toolName, jsonArgs)
        }

        // Check if it's a translate tool (server-side handled, no client impl)
        if (toolName.startsWith("self.translate.")) {
            Log.w(TAG, "Translate tool called but translation is server-side: $toolName")
            return McpToolResult(
                content = listOf(McpContent.TextContent("Translate tool is no longer supported on client; server handles translation")),
                isError = true
            )
        }

        // Check dynamic tools first
        val dynamicCallback = tools[toolName]
        if (dynamicCallback != null) {
            return try {
                val result = dynamicCallback(args)
                McpToolResult(
                    content = result.content.map { contentItem ->
                        if (contentItem.text != null) {
                            McpContent.TextContent(contentItem.text)
                        } else if (contentItem.imageUrl != null && contentItem.mimeType != null) {
                            McpContent.ImageContent(contentItem.mimeType, contentItem.imageUrl)
                        } else {
                            McpContent.TextContent("")
                        }
                    },
                    isError = result.isError
                )
            } catch (e: Exception) {
                McpToolResult(
                    content = listOf(McpContent.TextContent("Error: ${e.message}")),
                    isError = true
                )
            }
        }

        // System tools
        return when (toolName) {
            "self.get_system_info" -> getSystemInfo()
            else -> McpToolResult(
                content = listOf(McpContent.TextContent("Unknown tool: $toolName")),
                isError = true
            )
        }
    }

    /**
     * Get system information
     */
    private fun getSystemInfo(): McpToolResult {
        val androidVersion = android.os.Build.VERSION.RELEASE
        val deviceModel = android.os.Build.MODEL
        val deviceManufacturer = android.os.Build.MANUFACTURER

        val info = JSONObject().apply {
            put("platform", "android")
            put("version", androidVersion)
            put("model", deviceModel)
            put("manufacturer", deviceManufacturer)
            put("hasCamera", true)
            put("hasMicrophone", true)
        }

        return McpToolResult(
            content = listOf(McpContent.TextContent("System Info:\n${info.toString(2)}"))
        )
    }

    /**
     * Cache base64 image from tool result for follow-up questions
     */
    private fun cacheImageFromResult(result: McpToolResult) {
        result.content.forEach { content ->
            if (content is McpContent.ImageContent) {
                cacheLastPhoto(content.data)
                return
            }
        }
    }

    /**
     * Create success response
     */
    private fun createResponse(id: Int, result: JSONObject): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
    }

    /**
     * Create tool call response
     */
    private fun createToolResponse(id: Int, result: McpToolResult): JSONObject {
        val contentArray = JSONArray()
        result.content.forEach { content ->
            when (content) {
                is McpContent.TextContent -> {
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", content.text)
                    })
                }
                is McpContent.ImageContent -> {
                    contentArray.put(JSONObject().apply {
                        put("type", "image")
                        put("mimeType", content.mimeType)
                        put("data", content.data)
                    })
                }
            }
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", JSONObject().apply {
                put("content", contentArray)
                put("isError", result.isError)
                result.imageUri?.let { put("imageUri", it) }
                result.videoUri?.let { put("videoUri", it) }
                result.audioUri?.let { put("audioUri", it) }
            })
        }
    }

    /**
     * Create error response
     */
    private fun createErrorResponse(id: Int, code: Int, message: String): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
    }

    /**
     * Generate a new request ID
     */
    fun generateRequestId(): Int = requestId.getAndIncrement()

    /**
     * Configure camera tools with vision server settings
     */
    fun configureCameraTools(visionUrl: String, deviceId: String, clientId: String) {
        cameraTools.visionServerUrl = visionUrl
        cameraTools.deviceId = deviceId
        cameraTools.clientId = clientId
        Log.i(TAG, "Camera tools configured: visionUrl=$visionUrl, deviceId=$deviceId")
    }

    /**
     * Release resources
     */
    fun release() {
        cameraTools.release()
        audioTools.release()
    }

    /**
     * Stop all recording operations immediately (for lifecycle events - synchronous)
     */
    fun stopAllRecordingImmediate() {
        audioTools.stopAllRecordingImmediate()
        cameraTools.stopAllVideoRecording()
    }

    /**
     * Stop all recording operations (for lifecycle events - async)
     */
    suspend fun stopAllRecording(): String {
        val audioResult = audioTools.stopAllRecording()
        val videoResult = cameraTools.stopAllVideoRecording()

        val messages = mutableListOf<String>()
        if (!audioResult.isError) {
            val textContent = audioResult.content.firstOrNull()
            if (textContent is McpContent.TextContent) {
                messages.add(textContent.text)
            }
        }
        if (!videoResult.isError) {
            val textContent = videoResult.content.firstOrNull()
            if (textContent is McpContent.TextContent) {
                messages.add(textContent.text)
            }
        }

        return if (messages.isEmpty()) "No recording to stop" else messages.joinToString("\n")
    }

    /**
     * Check if the question is a follow-up about a recently captured photo
     */
    private fun isFollowUpQuestion(userInput: String, question: String): Boolean {
        val combined = "$userInput $question".lowercase()
        val followUpPatterns = listOf(
            "vừa chụp", "ảnh vừa chụp", "hình vừa chụp",
            "ảnh trước đó", "hình trước đó",
            "ảnh kia", "hình kia", "bức ảnh kia",
            "phân tích ảnh vừa", "phân tích hình vừa",
            "ảnh đó", "hình đó", "bức ảnh đó",
            "mô tả ảnh vừa", "mô tả hình vừa",
            "xem ảnh vừa", "xem hình vừa",
            "trong ảnh vừa", "trong hình vừa"
        )
        return followUpPatterns.any { combined.contains(it) }
    }

    // Extension functions
    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = get(key)
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> {
                    val list = mutableListOf<Any>()
                    val arr = value
                    for (i in 0 until arr.length()) {
                        val item = arr.get(i)
                        if (item is JSONObject) {
                            list.add((item as JSONObject).toMap())
                        } else {
                            list.add(item)
                        }
                    }
                    list
                }
                is String -> value
                is Int -> value
                is Long -> value
                is Double -> value
                is Boolean -> value
                else -> value.toString()
            }
        }
        return map
    }

    private fun Map<String, Any>.toJsonString(): String {
        return JSONObject(this).toString()
    }
}

/**
 * MCP Tool Call Result data class
 */
data class McpToolCallResult(
    val toolName: String,
    val result: McpToolResult
)

/**
 * MCP Server Request data class
 */
data class McpServerRequest(
    val type: String,
    val data: JSONObject
)

/**
 * Extension to convert McpToolDef to McpTool
 */
fun McpToolDef.toMcpTool(): McpTool {
    val propertiesJson = JSONObject()
    inputSchema.properties.forEach { (name, prop) ->
        propertiesJson.put(name, JSONObject().apply {
            put("type", prop.type)
            put("description", prop.description)
            prop.default?.let { put("default", it) }
        })
    }
    val requiredJson = JSONArray()
    inputSchema.required.forEach { requiredJson.put(it) }

    return McpTool(
        name = name,
        description = description,
        inputSchema = JSONObject().apply {
            put("type", inputSchema.type)
            put("properties", propertiesJson)
            put("required", requiredJson)
        }
    )
}