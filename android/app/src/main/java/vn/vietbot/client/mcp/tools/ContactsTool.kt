package vn.vietbot.client.mcp.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import vn.vietbot.client.mcp.ContentItem
import vn.vietbot.client.mcp.McpCallToolResult
import vn.vietbot.client.mcp.McpProperty
import vn.vietbot.client.mcp.McpServer

/**
 * MCP Tool for contacts management
 */
class ContactsTool(private val context: Context) {
    private val TAG = "ContactsTool"

    private val contentResolver: ContentResolver = context.contentResolver

    // Hàm chuẩn hóa để xử lý lỗi phát âm
    private fun normalizeText(text: String): String {
        val accents = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ"
        val noAccents = "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd"
        var result = text.lowercase()
        for (i in accents.indices) {
            result = result.replace(accents[i], noAccents[i])
        }
        return result.replace(Regex("[^a-z0-9]"), "")
    }

    fun register(server: McpServer) {
        // Tool 1: Search contacts
        server.registerTool(
            name = "self.contacts.search",
            description = "Tìm kiếm danh bạ theo tên. BAT BUOC GOI KHI: Người dùng hỏi 'tìm', 'tìm kiếm', 'xem danh bạ', 'số điện thoại của'. Ví dụ: 'Tìm số của An', 'Số điện thoại của mẹ', 'Liên hệ với Nam'.",
            properties = mapOf(
                "name" to McpProperty(
                    type = "string",
                    description = "Tên người cần tìm trong danh bạ."
                ),
                "limit" to McpProperty(
                    type = "integer",
                    description = "Số lượng kết quả tối đa (mặc định: 10)."
                )
            ),
            required = listOf("name")
        ) { arguments ->
            val name = arguments["name"] as? String ?: ""
            val limit = when (val l = arguments["limit"]) {
                is Number -> l.toInt()
                is String -> l.toIntOrNull() ?: 10
                else -> 10
            }

            val result = searchContacts(name, limit)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }

        // Tool 2: Get contact details
        server.registerTool(
            name = "self.contacts.get_details",
            description = "Lấy thông tin chi tiết của một liên hệ. BAT BUOC GOI KHI: Người dùng muốn xem thông tin đầy đủ của một người trong danh bạ.",
            properties = mapOf(
                "name" to McpProperty(
                    type = "string",
                    description = "Tên người cần lấy thông tin chi tiết."
                )
            ),
            required = listOf("name")
        ) { arguments ->
            val name = arguments["name"] as? String ?: ""

            val result = getContactDetails(name)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }

        // Tool 3: Call contact
        server.registerTool(
            name = "self.contacts.call",
            description = "Gọi điện cho một người trong danh bạ. BAT BUOC GOI KHI: Người dùng yêu cầu gọi điện, gọi cho ai đó. Ví dụ: 'Gọi điện cho An', 'Gọi mẹ', 'Gọi số này', 'Kết nối cuộc gọi'.",
            properties = mapOf(
                "name" to McpProperty(
                    type = "string",
                    description = "Tên người cần gọi trong danh bạ."
                )
            ),
            required = listOf("name")
        ) { arguments ->
            val name = arguments["name"] as? String ?: ""

            val result = callContact(name)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }

        // Tool 4: Send SMS
        server.registerTool(
            name = "self.contacts.send_sms",
            description = "Gửi tin nhắn SMS cho một người trong danh bạ. BAT BUOC GOI KHI: Người dùng yêu cầu gửi tin nhắn, nhắn tin cho ai đó. Ví dụ: 'Gửi tin nhắn cho An', 'Nhắn mẹ', 'Soạn SMS'.",
            properties = mapOf(
                "name" to McpProperty(
                    type = "string",
                    description = "Tên người cần gửi tin nhắn."
                ),
                "message" to McpProperty(
                    type = "string",
                    description = "Nội dung tin nhắn cần gửi."
                )
            ),
            required = listOf("name", "message")
        ) { arguments ->
            val name = arguments["name"] as? String ?: ""
            val message = arguments["message"] as? String ?: ""

            val result = sendSms(name, message)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun searchContacts(name: String, limit: Int): String {
        if (!hasContactsPermission()) {
            return "Error: Cần quyền đọc danh bạ để thực hiện chức năng này."
        }

        return try {
            val normalizedName = normalizeText(name)

            val cursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER,
                    ContactsContract.Contacts.PHOTO_URI
                ),
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?",
                arrayOf("%$name%"),
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
            )

            val results = mutableListOf<String>()
            var count = 0

            cursor?.use {
                while (it.moveToNext() && count < limit) {
                    val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)) ?: ""
                    val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))

                    // Check if name normalized matches
                    val normalizedDisplayName = normalizeText(displayName)
                    if (normalizedName.isEmpty() || normalizedDisplayName.contains(normalizedName) || normalizedName.contains(normalizedDisplayName)) {
                        val phoneNumber = if (hasPhone > 0) {
                            getPrimaryPhoneNumber(contactId)
                        } else {
                            "Không có số điện thoại"
                        }

                        count++
                        results.add("$count. $displayName - $phoneNumber")
                    }
                }
            }

            if (results.isEmpty()) {
                "Không tìm thấy liên hệ nào khớp với '$name'"
            } else {
                "Kết quả tìm kiếm '$name' (${results.size} liên hệ):\n${results.joinToString("\n")}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts", e)
            "Error tìm kiếm danh bạ: ${e.message}"
        }
    }

    private fun getPrimaryPhoneNumber(contactId: String): String {
        val phoneCursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )

        var phoneNumber = "Không có số"
        phoneCursor?.use {
            if (it.moveToFirst()) {
                phoneNumber = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: phoneNumber
            }
        }

        return phoneNumber
    }

    private fun getContactDetails(name: String): String {
        if (!hasContactsPermission()) {
            return "Error: Cần quyền đọc danh bạ để thực hiện chức năng này."
        }

        return try {
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                ),
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?",
                arrayOf("%$name%"),
                null
            )

            var result = "Không tìm thấy liên hệ '$name'"

            cursor?.use {
                while (it.moveToNext()) {
                    val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)) ?: ""

                    // Get all phone numbers
                    val phoneNumbers = mutableListOf<String>()
                    val phoneCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(contactId),
                        null
                    )
                    phoneCursor?.use { pc ->
                        while (pc.moveToNext()) {
                            val phone = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            phoneNumbers.add(phone)
                        }
                    }

                    // Get email if available
                    val emails = mutableListOf<String>()
                    val emailCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                        arrayOf(contactId),
                        null
                    )
                    emailCursor?.use { ec ->
                        while (ec.moveToNext()) {
                            val email = ec.getString(ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))
                            emails.add(email)
                        }
                    }

                    result = buildString {
                        appendLine("Thông tin liên hệ: $displayName")
                        appendLine("Số điện thoại:")
                        phoneNumbers.forEachIndexed { index, phone ->
                            appendLine("  ${index + 1}. $phone")
                        }
                        if (emails.isNotEmpty()) {
                            appendLine("Email:")
                            emails.forEachIndexed { index, email ->
                                appendLine("  ${index + 1}. $email")
                            }
                        }
                    }
                    break // Only show first match
                }
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact details", e)
            "Error lấy thông tin liên hệ: ${e.message}"
        }
    }

    private fun callContact(name: String): String {
        if (!hasContactsPermission()) {
            return "Error: Cần quyền đọc danh bạ để thực hiện chức năng này."
        }

        return try {
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?",
                arrayOf("%$name%"),
                null
            )

            var phoneNumber: String? = null

            cursor?.use {
                while (it.moveToNext()) {
                    val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)) ?: ""
                    val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))

                    if (hasPhone > 0) {
                        phoneNumber = getPrimaryPhoneNumber(contactId)
                        if (phoneNumber != "Không có số điện thoại") {
                            break
                        }
                    }
                }
            }

            if (phoneNumber != null && phoneNumber != "Không có số điện thoại") {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                "Đang gọi cho $name ($phoneNumber)..."
            } else {
                "Error: Không tìm thấy số điện thoại của '$name'"
            }
        } catch (e: SecurityException) {
            "Error: Không có quyền gọi điện. Vui lòng cấp quyền CALL_PHONE."
        } catch (e: Exception) {
            Log.e(TAG, "Error calling contact", e)
            "Error gọi điện: ${e.message}"
        }
    }

    private fun sendSms(name: String, message: String): String {
        if (!hasContactsPermission()) {
            return "Error: Cần quyền đọc danh bạ để thực hiện chức năng này."
        }

        return try {
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?",
                arrayOf("%$name%"),
                null
            )

            var phoneNumber: String? = null

            cursor?.use {
                while (it.moveToNext()) {
                    val contactId = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))

                    if (hasPhone > 0) {
                        phoneNumber = getPrimaryPhoneNumber(contactId)
                        if (phoneNumber != "Không có số điện thoại") {
                            break
                        }
                    }
                }
            }

            if (phoneNumber != null && phoneNumber != "Không có số điện thoại") {
                val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$phoneNumber")
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(smsIntent)
                "Đang soạn tin nhắn cho $name ($phoneNumber)"
            } else {
                "Error: Không tìm thấy số điện thoại của '$name'"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            "Error gửi tin nhắn: ${e.message}"
        }
    }
}