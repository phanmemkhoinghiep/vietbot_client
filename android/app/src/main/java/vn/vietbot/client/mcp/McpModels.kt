package vn.vietbot.client.mcp

import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject

/**
 * Data class representing an MCP tool definition
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val userOnly: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
        if (userOnly) {
            put("annotations", JSONObject().apply {
                put("audience", org.json.JSONArray().put("user"))
            })
        }
    }
}

/**
 * Data class representing an MCP tool call result
 */
data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false,
    val imageUri: String? = null,
    val videoUri: String? = null,
    val audioUri: String? = null
)

/**
 * Sealed class for MCP content types
 */
sealed class McpContent {
    data class TextContent(val text: String) : McpContent()
    data class ImageContent(val mimeType: String, val data: String) : McpContent()
}

/**
 * MCP tool property for input schema
 */
data class McpProperty(
    val type: String,
    val description: String = "",
    val default: Any? = null
)

/**
 * MCP input schema
 */
data class McpInputSchema(
    val type: String = "object",
    val properties: Map<String, McpProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * MCP tool definition for dynamic registration
 */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: McpInputSchema
)

/**
 * MCP call tool result (for dynamic registration)
 */
data class McpCallToolResult(
    val content: List<ContentItem>,
    val isError: Boolean = false
)

/**
 * Content item for MCP result
 */
data class ContentItem(
    val text: String? = null,
    val imageUrl: String? = null,
    val mimeType: String? = null
)

/**
 * MCP request message
 */
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JSONObject? = null
)

/**
 * MCP response message
 */
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: Int,
    val result: JSONObject? = null,
    val error: JSONObject? = null
)