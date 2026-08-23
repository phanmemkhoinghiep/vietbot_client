# VietBot Site Widget — JavaScript Embed cho 3rd-Party Sites

Widget cho phép admin (chủ sở hữu agent VietBot) nhúng trợ lý ảo AI vào bất kỳ website nào mà **không yêu cầu visitor phải đăng ký tài khoản**. Bot dùng config (prompt, voice, language) của admin.

> **Khác biệt với `JS/widget.js`:**
> - `JS/widget.js` — Widget cho **visitor đã đăng ký** (cần bind device trước)
> - `JS_SITE/widget.js` — Widget cho **visitor ẩn danh** (dùng config của admin, không cần đăng ký)

---

## 📋 Mục lục

1. [Tổng quan](#tổng-quan)
2. [Yêu cầu](#yêu-cầu)
3. [Hướng dẫn tích hợp](#hướng-dẫn-tích-hợp)
4. [Bảo mật](#bảo-mật)
5. [Tùy chỉnh](#tùy-chỉnh)
6. [Testing](#testing)
7. [Troubleshooting](#troubleshooting)
8. [Kiến trúc](#kiến-trúc)

---

## Tổng quan

Widget hoạt động theo flow **MAC-bound site-endpoint**:

```
┌──────────────────────────────────────────────────────────────────┐
│  SETUP (admin) - 1 lần                                           │
│  1. Admin tạo agent tại web.vietbot.vn                            │
│  2. Admin vào live.vietbot.vn → tạo thiết bị web mới              │
│     → nhận mã 6 số (bind code)                                   │
│  3. Admin vào web.vietbot.vn → nhập mã 6 số                       │
│     → thiết bị web được bind với Agent                            │
│  4. Admin copy MAC address của thiết bị web                        │
│     (ví dụ: 00:1A:2B:3C:4D:5E)                                   │
│  5. Admin vào partner.vietbot.vn → paste MAC + whitelist domain   │
│  6. Hệ thống sinh token (AES-ECB encoded MAC + whitelist)        │
│  7. Admin paste token vào widget.js → upload lên hosting           │
│  8. Admin nhúng <script src="/widget.js"></script> vào HTML       │
└──────────────────────────────────────────────────────────────────┘
                                ↓
┌──────────────────────────────────────────────────────────────────┐
│  RUNTIME (visitor mở website)                                    │
│  9.  Visitor mở website → widget inject floating button + iframe  │
│  10. Visitor click button → iframe mở                             │
│  11. Iframe load embeddable_bot.html?site-endpoint=<token>        │
│  12. JS mở WebSocket tới wss://live.vietbot.vn/ws                 │
│  13. Server decrypt AES-ECB token → MAC + whitelist (no DB!)      │
│  14. Server check Origin vs whitelist (token tự chứa)             │
│  15. Nếu OK → server query Java Manager API                       │
│      POST /config/agent-models với macAddress                     │
│      → response trả về full agent config (prompt, voice, …)      │
│  16. Bot pipeline start (Gemini Live) với config đúng             │
│  17. Visitor nói → bot trả lời bằng voice + text                  │
└──────────────────────────────────────────────────────────────────┘
```

### Tại sao dùng MAC thay vì Agent ID?

Java Manager API endpoint `GET /agent/{id}` yêu cầu user JWT (oauth2 filter), không chấp nhận server secret Bearer token. Trong khi `POST /config/agent-models` (với `macAddress`) **chấp nhận** server secret Bearer và trả về toàn bộ agent config (kèm `agent_id`). Vì vậy flow buộc phải có **bước bind thiết bị web với agent** trước khi generate token.

---

## Yêu cầu

### Phía admin
- Tài khoản VietBot tại [web.vietbot.vn](https://web.vietbot.vn)
- Đã tạo ít nhất 1 agent (trong Bước 1 bên dưới)
- Đã bind thiết bị web với agent (xem Bước 2-3 bên dưới) → có MAC address
- Website của admin phải được serve qua **HTTPS** (bắt buộc để dùng microphone)

### Phía visitor
- Browser hỗ trợ WebSocket + WebRTC (Chrome, Firefox, Edge, Safari 14+)
- Cho phép microphone access khi được hỏi
- **KHÔNG CẦN đăng ký tài khoản**

---

## Hướng dẫn tích hợp

### Bước 1: Tạo agent tại web.vietbot.vn

1. Đăng nhập vào [web.vietbot.vn](https://web.vietbot.vn)
2. Tạo agent mới (đặt tên, chọn voice, language, prompt, role)
3. Ghi nhớ **Agent ID** (UUID 32 ký tự, ví dụ: `f2606e842ce1479eaca805b0fb62ca03`)

### Bước 2: Lấy mã 6 số từ live.vietbot.vn

1. Mở [https://live.vietbot.vn](https://live.vietbot.vn) → đăng nhập (Firebase Auth)
2. Trong giao diện thiết bị → click **"Thêm thiết bị mới"**
3. Hệ thống sinh **mã 6 số** (bind code, ví dụ: `482931`) — mã này có hiệu lực trong vài phút

### Bước 3: Bind thiết bị web với Agent tại web.vietbot.vn

1. Quay lại [https://web.vietbot.vn](https://web.vietbot.vn)
2. Vào **Quản lý thiết bị** → nhập **mã 6 số** từ Bước 2
3. Chọn **Agent** muốn gán cho thiết bị web này (chính là agent ở Bước 1)
4. Submit → thiết bị giờ đã liên kết với agent
5. **Copy MAC address** của thiết bị web (ví dụ: `00:1A:2B:3C:4D:5E`)

### Bước 4: Sinh site-bound token tại partner.vietbot.vn

1. Mở [partner.vietbot.vn](https://partner.vietbot.vn)
2. Paste **MAC Address** từ Bước 3
3. Nhập **Whitelist Domains** (mỗi domain 1 dòng):
   ```
   example.com
   www.example.com
   *.example.com
   ```
   - `example.com` — chỉ match chính xác
   - `*.example.com` — match `example.com` và tất cả subdomain
   - `*` — cho phép mọi domain (KHÔNG AN TOÀN)
4. Click **"Generate Token"**
5. Copy token (chuỗi base64 dài, ví dụ: `bUEx04bA8jmGT6O1f+evqmtvVDH3eqSA6PGPTUW9DfwV/BMlr1m7nXj4XAqL6Vv1mljcvrhZVYMGqrbOmQXav4KsJQQHSa7kVHnCVNIw5JZeqVLwUn6Ae5s4OGkgQbTx`)

> **Không cần nhập MANAGER_API_SECRET** — partner.vietbot.vn nginx tự inject server-side. Nếu có popup hỏi secret → đó là bản cũ, hãy refresh (Ctrl+Shift+R).

### Bước 5: Cấu hình widget

Tải `widget.js` về:

```bash
curl -o widget.js https://raw.githubusercontent.com/phanmemkhoinghiep/vietbot_client/main/JS_SITE/widget.js
```

Mở file bằng editor, tìm dòng:

```javascript
const SITE_WS_ENDPOINT = '__SITE_WS_ENDPOINT__';
```

Thay `__SITE_WS_ENDPOINT__` bằng token bạn vừa copy:

```javascript
const SITE_WS_ENDPOINT = 'bUEx04bA8jmGT6O1f+evqmtvVDH3eqSA6PGPTUW9DfwV/BMlr1m7nXj4XAqL6Vv1mljcvrhZVYMGqrbOmQXav4KsJQQHSa7kVHnCVNIw5JZeqVLwUn6Ae5s4OGkgQbTx';
```

**⚠️ Lưu ý:**
- Token có thể chứa ký tự `+`, `/`, `=` — đây là ký tự hợp lệ của base64, **không cần encode** khi paste.
- KHÔNG commit file widget.js đã có token lên Git public — token泄露 cho phép người khác dùng bot của bạn.

### Bước 6: Upload widget.js lên hosting

Upload file `widget.js` đã sửa lên web server của bạn:

```bash
# Ví dụ với nginx
scp widget.js user@server:/var/www/html/js/widget.js

# Hoặc dùng bất kỳ CDN nào (Cloudflare, jsDelivr self-host, etc.)
```

### Bước 7: Nhúng widget vào HTML

Thêm vào cuối `<body>` trang web của bạn:

```html
<!DOCTYPE html>
<html>
<head>
    <title>My Website</title>
</head>
<body>
    <h1>Welcome to my site</h1>

    <!-- ... nội dung trang ... -->

    <!-- ⬇️ VietBot widget — đặt cuối body -->
    <script src="/js/widget.js"></script>
</body>
</html>
```

Xong! Floating button màu xanh sẽ xuất hiện ở góc dưới bên phải.

---

## Bảo mật

### Tại sao cần domain whitelist?

Nếu admin paste token trong `widget.js` lên GitHub public hoặc attacker đánh cắp token qua DevTools, attacker có thể nhúng token vào website của họ để dùng bot "free". Domain whitelist giúp giới hạn bot chỉ chạy trên domain admin đã đăng ký.

**Kịch bản tấn công:**
1. Admin nhúng widget lên `myshop.com`
2. Token bị lộ (qua DevTools, F12, hoặc public repo)
3. Attacker copy token, dán vào widget trên `attacker.com`
4. Nếu KHÔNG có whitelist → bot vẫn hoạt động trên `attacker.com` ❌
5. Nếu CÓ whitelist `["myshop.com"]` → request từ `attacker.com` bị server từ chối (HTTP 403) ✅

### Cơ chế bảo vệ

**Token tự chứa MAC + whitelist** — không cần lưu DB:
- Token = `Base64(AES-ECB(key, JSON{mac_address, whitelist}))`
- Khi visitor kết nối: server decrypt token → lấy MAC + whitelist ngay lập tức
- Check `Origin` header của visitor vs whitelist từ token
- Không match → 1008 WebSocket close code "domain_not_allowed"
- **Không thể sửa token:** AES-ECB + secret key chỉ VietBot backend biết

**Không cần MANAGER_API_SECRET ở frontend:**
- Token generation endpoint `/admin/site-token/generate` được nginx forward từ partner.vietbot.vn
- Nginx tự inject `Authorization: Bearer ${MANAGER_API_SECRET}` vào request upstream
- Frontend chỉ cần POST không có header → backend verify OK
- Hạn chế CORS: chỉ cho phép `Origin: https://partner.vietbot.vn`

### Check list bảo mật

- [ ] Đã nhập whitelist domain tại partner.vietbot.vn
- [ ] Token không bị commit lên Git public
- [ ] Website serve qua HTTPS
- [ ] Token được rotate định kỳ (re-bind thiết bị → generate token mới)
- [ ] Log truy cập được monitor thường xuyên

---

## Tùy chỉnh

Mặc định widget hiển thị ở góc dưới bên phải với kích thước 380×560px. Để tùy chỉnh, sửa trực tiếp trong file `widget.js`:

```javascript
const CONFIG = {
    botUrl: 'https://live.vietbot.vn/embeddable_bot.html',
    siteEndpoint: SITE_WS_ENDPOINT,
    width: '400px',        // Chiều rộng iframe
    height: '600px',       // Chiều cao iframe
    position: 'bottom-left', // 'bottom-right' (default) | 'bottom-left'
};
```

**Tùy chỉnh nâng cao** — thêm CSS cho `#vietbot-widget-container`:

```html
<style>
    #vietbot-widget-container button {
        background-color: #ff5722 !important;  /* Đổi màu button */
    }
    #vietbot-widget-container iframe {
        border-radius: 20px !important;        /* Bo góc iframe */
    }
</style>
```

---

## Testing

### Test 1: Widget xuất hiện đúng

```html
<!DOCTYPE html>
<html>
<head><title>Test Widget</title></head>
<body>
    <h1>My Test Page</h1>
    <script src="/path/to/widget.js"></script>
</body>
</html>
```

Serve qua HTTPS, mở browser. Phải thấy floating button màu xanh ở góc dưới phải.

### Test 2: WebSocket connect + bot chạy đúng role

1. Click button → iframe mở
2. Mở DevTools → Console
3. Phải thấy log "WebSocket connected" → "Bot ready"
4. Click microphone → nói thử → bot phản hồi **đúng role đã bind** ở Bước 3
5. Nếu bot trả lời sai role / persona → re-check Bước 3 (thiết bị đã bind đúng agent chưa)

### Test 3: Domain whitelist hoạt động

Trên domain KHÔNG có trong whitelist (ví dụ `localhost:8000` nếu whitelist chỉ có `myshop.com`):

```bash
curl -i "https://live.vietbot.vn/ws?site-endpoint=YOUR_TOKEN" \
     -H "Origin: http://evil.com" \
     -H "Upgrade: websocket" \
     -H "Connection: Upgrade" \
     -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     -H "Sec-WebSocket-Version: 13"
```

**Expected:** `HTTP/1.1 101 Switching Protocols` rồi server close với code `1008` ngay sau đó (hoặc close trước khi accept). Check log:
```bash
journalctl -u vietbot-live-voice -f | grep "domain_not_allowed"
```

---

## Troubleshooting

| Vấn đề | Nguyên nhân | Cách xử lý |
|--------|-------------|------------|
| Button không xuất hiện | Script load lỗi | Mở Console → check 404/CORS |
| Console: `SITE_WS_ENDPOINT chưa được cấu hình` | Chưa paste token | Mở widget.js → paste token |
| Iframe mở nhưng trắng | HTTPS chưa bật | Bắt buộc serve qua HTTPS để dùng mic |
| Iframe mở → 1008 close `agent_not_found` | MAC address không bind | Quay lại Bước 3, bind thiết bị với agent tại web.vietbot.vn |
| Iframe mở → 1008 close `domain_not_allowed` | Domain không trong whitelist | Generate token mới với whitelist đúng tại partner.vietbot.vn |
| **Bot trả lời sai role** (ví dụ: "my sói" thay vì "em gái miền tây") | MAC address trỏ tới device khác, hoặc device đã bind nhầm agent | Check device binding ở web.vietbot.vn, đảm bảo MAC đúng thiết bị đã bind với agent mong muốn |
| Mic không hoạt động | User từ chối permission | Click icon mic trên browser → allow |
| Bot không phản hồi | Token hết hạn / agent bị xóa | Bind lại thiết bị → generate token mới từ partner.vietbot.vn |
| `Mixed content` warning | Widget HTTP, page HTTPS | Đảm bảo `widget.js` cũng serve qua HTTPS |

### Debug mode

Mở DevTools → Console, kiểm tra log:

```bash
# Server side logs
journalctl -u vietbot-live-voice -f | grep -E "Site-bound|get_private_config"
```

Log mẫu flow thành công:
```
🌐 Site-bound WS from 1.2.3.4 → mac=00:1a:2b:3c:4d:5e, whitelist=['myshop.com']
✅ Domain myshop.com matches whitelist ['myshop.com']
🚀 Site-bound pipeline: agent=f2606e84... user=2087350989158060034
📝 Using prompt from Manager API (7149 chars)
🎙️ Bot config - Voice: Nữ gender=female, Language: vi-VN
```

---

## Kiến trúc

```
┌──────────────────────────────────────┐
│  3rd-party website (HTTPS)            │
│  ┌──────────────────────────────┐    │
│  │ <script> widget.js            │    │
│  │ → floating button + iframe    │    │
│  │ → iframe load ?site-endpoint= │    │
│  └──────────────────────────────┘    │
└──────────────┬───────────────────────┘
               │ HTTPS
               ▼
┌──────────────────────────────────────┐
│ partner.vietbot.vn (nginx)           │
│  ├─ / (static SPA)                    │
│  └─ /admin/site-token/generate → proxy│
│     inject Bearer MANAGER_API_SECRET  │
└──────────────┬───────────────────────┘
               │ HTTPS (w/ auth header)
               ▼
┌──────────────────────────────────────┐
│ live.vietbot.vn (nginx)              │
│  ├─ / (mobile frontend)              │
│  ├─ /admin/site-token/generate → 7860│
│  └─ /ws (WebSocket Pipecat → 7861)   │
└──────────────┬───────────────────────┘
               │
       ┌───────┴────────┐
       ▼                ▼
┌─────────────┐  ┌──────────────────┐
│  vietbot_api│  │ vietbot_server   │
│  (port 7860)│  │ (port 7861)      │
│             │  │                  │
│ /admin/...  │  │ WS site-bound:   │
│ AES-ECB     │  │ - decrypt token  │
│ token gen   │  │ - check Origin   │
│ (SQLite +   │  │ - call /config/  │
│  Redis)     │  │   agent-models   │
│             │  │ - run Pipecat    │
└─────────────┘  └──────┬───────────┘
                        │ Bearer MANAGER_API_SECRET
                        ▼
                ┌──────────────────┐
                │ vietbot-manager-api│
                │ (port 8002, Java) │
                │ /config/agent-    │
                │  models → returns │
                │ full agent config │
                └──────────────────┘
```

### Flow chi tiết (server-side)

1. **Widget load** → inject iframe với `?site-endpoint={token}`
2. **Iframe load embeddable_bot.html** → JS chạy Pipecat client
3. **Pipecat mở WebSocket** tới `wss://live.vietbot.vn/ws?site-endpoint={token}&client-id={uuid}`
4. **vietbot_server nhận WS request:**
   - Decrypt AES-ECB token → `mac_address` + `whitelist` (no DB lookup!)
   - Lấy `Origin` header từ request
   - Check `Origin` có match whitelist từ token không
   - Nếu FAIL → close code 1008 `domain_not_allowed`
   - Nếu OK → accept WS, call `manager.get_private_config_by_mac(mac_address)`
     - Manager client gọi `POST /config/agent-models` với Bearer `MANAGER_API_SECRET`
     - Java Manager API lookup device by MAC → lookup agent → return full config
     - Bao gồm: `agent_id`, `assistant` (agent_name), `prompt`, `bot_language`, `bot_gender`, `google_api_key`, `mcp_endpoint`, `plugins`, `voiceprint`, …
   - Build `user_data` từ config + start Pipecat pipeline (Gemini Live)
5. **Visitor nói** → Gemini Live → response → TTS → bot trả lời bằng voice + text

### Token format (self-contained)

```json
{
  "mac_address": "00:1a:2b:3c:4d:5e",
  "whitelist": ["myshop.com", "*.myshop.com"]
}
```

`Base64(AES-ECB(key, payload))` — key chỉ VietBot backend biết (`MCP_ENDPOINT_KEY` trong `/opt/vietbot.vn/live/vietbot_api/auth/.env`).

---

## API Reference

### Token generation endpoint (internal)

```
POST /admin/site-token/generate          (nginx forward từ partner.vietbot.vn)
Authorization: Bearer ${MANAGER_API_SECRET}   (nginx tự inject)
Content-Type: application/json

{
  "mac_address": "00:1A:2B:3C:4D:5E",
  "whitelist": ["example.com", "*.example.com"]
}
```

**Response 200:**
```json
{
  "success": true,
  "token": "bUEx04bA8jmGT6O1f+evqmtvVDH3eqSA6PGPTUW9DfwV/BMlr1m7nXj4XAqL6Vv1mljcvrhZVYMGqrbOmQXav4KsJQQHSa7kVHnCVNIw5JZeqVLwUn6Ae5s4OGkgQbTx",
  "mac_address": "00:1a:2b:3c:4d:5e",
  "whitelist": ["example.com", "*.example.com"]
}
```

**Errors:**
- `400` — `mac_address is required` / `Invalid MAC address format`
- `401` — Missing or invalid Bearer (do nginx handle, không lộ từ frontend)

### Validate domain pattern

Server chấp nhận các pattern sau:

| Pattern | Ý nghĩa | Match example |
|---------|---------|---------------|
| `"example.com"` | Exact match | `example.com` ✅, `www.example.com` ❌ |
| `"*.example.com"` | Wildcard subdomain | `example.com` ✅, `sub.example.com` ✅, `evil.com` ❌ |
| `"*"` | Allow all | Mọi origin ⚠️ |

### Backend: query agent config by MAC (server-to-server)

```
POST http://127.0.0.1:8002/api/config/agent-models
Authorization: Bearer ${server.secret}
Content-Type: application/json

{
  "macAddress": "00:1A:2B:3C:4D:5E",
  "clientId": "site_visitor",
  "selectedModule": {}
}
```

Returns full agent config: `agent_id`, `assistant`, `prompt`, `bot_language`, `bot_gender`, `google_api_key`, `mcp_endpoint`, `plugins`, `voiceprint`, `user_id`, `bot_mode`, etc.

---

## Files

| File | Mô tả |
|------|--------|
| `widget.js` | Script nhúng vào 3rd-party site (paste token vào file này) |
| `README.md` | File này — hướng dẫn tích hợp |

---

## So sánh với JS/widget.js

| Tính năng | `JS/widget.js` | `JS_SITE/widget.js` |
|-----------|----------------|---------------------|
| Visitor cần đăng ký? | ✅ Có (bind device) | ❌ Không |
| Visitor thấy bind modal? | ✅ Có | ❌ Không |
| Domain whitelist? | ❌ Không | ✅ Có (trong token) |
| Use case | Khách hàng VietBot | 3rd-party embed |
| Bot dùng config của | Visitor | Admin |
| Token storage | SQLite (MD5) | AES-ECB self-contained |
| Lookup mechanism | Device MAC per user | MAC bound to agent (admin-owned) |

---

## Liên hệ / Hỗ trợ

- 📧 Email: support@vietbot.vn
- 🌐 Website: [vietbot.vn](https://vietbot.vn)
- 📚 Docs: [docs.vietbot.vn](https://docs.vietbot.vn)

---

## License

MIT License — xem [LICENSE](../LICENSE)
