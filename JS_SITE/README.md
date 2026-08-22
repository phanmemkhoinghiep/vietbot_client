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

Widget hoạt động theo flow:

```
┌─────────────────────────────────────────────────────────────┐
│  1. Admin tạo agent tại web.vietbot.vn                       │
│  2. Admin lấy "site-endpoint token" từ MCP Access Point UI  │
│  3. Admin paste token vào widget.js                          │
│  4. Admin upload widget.js lên hosting của họ                │
│  5. Admin nhúng <script src="widget.js"></script> vào HTML   │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  6. Visitor mở website                                       │
│  7. Widget inject floating button + iframe                    │
│  8. Visitor click button → iframe mở                         │
│  9. Iframe load embeddable_bot.html?site-endpoint=...        │
│ 10. WebSocket connect tới live.vietbot.vn/ws                 │
│ 11. Server decrypt token → agent_id                          │
│ 12. Server check Origin domain vs whitelist                  │
│ 13. Bot pipeline start (Gemini Live)                          │
│ 14. Visitor nói → bot trả lời bằng voice + text              │
└─────────────────────────────────────────────────────────────┘
```

---

## Yêu cầu

### Phía admin
- Tài khoản VietBot tại [web.vietbot.vn](https://web.vietbot.vn)
- Đã tạo ít nhất 1 agent
- Domain whitelist đã được set (khuyến nghị cho bảo mật)
- Website của admin phải được serve qua **HTTPS** (bắt buộc để dùng microphone)

### Phía visitor
- Browser hỗ trợ WebSocket + WebRTC (Chrome, Firefox, Edge, Safari 14+)
- Cho phép microphone access khi được hỏi
- **KHÔNG CẦN đăng ký tài khoản**

---

## Hướng dẫn tích hợp

### Bước 1: Lấy site-endpoint token

1. Đăng nhập vào [web.vietbot.vn](https://web.vietbot.vn)
2. Vào **MCP Access Point** (hoặc Manager Web UI)
3. Chọn agent bạn muốn nhúng
4. Click **"Generate Site Endpoint"**
5. Copy token (chuỗi base64 dài ~64 ký tự, ví dụ: `h7sDRjDHWLuxO9HOFH0wulRVsaRdHeH865Uy13UJ9FFL/r/eBojzxlKMIUgV+6Cl`)

### Bước 2: Cấu hình widget

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
const SITE_WS_ENDPOINT = 'h7sDRjDHWLuxO9HOFH0wulRVsaRdHeH865Uy13UJ9FFL/r/eBojzxlKMIUgV+6Cl';
```

**⚠️ Lưu ý:**
- Token có thể chứa ký tự `+`, `/`, `=` — đây là ký tự hợp lệ của base64, **không cần encode** khi paste.
- KHÔNG commit file widget.js đã có token lên Git public — token泄露 cho phép người khác dùng bot của bạn.

### Bước 3: Upload widget.js lên hosting

Upload file `widget.js` đã sửa lên web server của bạn:

```bash
# Ví dụ với nginx
scp widget.js user@server:/var/www/html/js/widget.js

# Hoặc dùng bất kỳ CDN nào (Cloudflare, jsDelivr tự host, etc.)
```

### Bước 4: Set domain whitelist (KHUYẾN NGHỊ)

Gọi API để set whitelist domain:

```bash
curl -X POST https://live.vietbot.vn/api/agent/{agent_id}/site-domains \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"domains": ["example.com", "www.example.com", "*.example.com"]}'
```

**Hỗ trợ wildcard:**
| Pattern | Match |
|---------|-------|
| `"example.com"` | Chỉ `example.com` |
| `"*.example.com"` | `example.com`, `sub.example.com`, `a.b.example.com` |
| `"*"` | Mọi domain (KHÔNG AN TOÀN) |

Nếu **không set whitelist**, bot sẽ chạy ở mọi domain — bất kỳ ai có token cũng có thể dùng.

### Bước 5: Nhúng widget vào HTML

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

Nếu admin paste token `widget.js` lên GitHub public hoặc attacker đánh cắp token qua DevTools, attacker có thể nhúng token vào website của họ để dùng bot "free". Domain whitelist giúp giới hạn bot chỉ chạy trên domain admin đã đăng ký.

**Kịch bản tấn công:**
1. Admin nhúng widget lên `myshop.com`
2. Token bị lộ (qua DevTools, F12, hoặc public repo)
3. Attacker copy token, dán vào widget trên `attacker.com`
4. Nếu KHÔNG có whitelist → bot vẫn hoạt động trên `attacker.com` ❌
5. Nếu CÓ whitelist `["myshop.com"]` → request từ `attacker.com` bị server từ chối (HTTP 403) ✅

### Check list bảo mật

- [ ] Đã set domain whitelist qua API
- [ ] Token không bị commit lên Git public
- [ ] Website serve qua HTTPS
- [ ] Token được rotate định kỳ (qua MCP Access Point UI)
- [ ] Log truy cập tại [web.vietbot.vn](https://web.vietbot.vn) được monitor thường xuyên

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

### Test 2: WebSocket connect

1. Click button → iframe mở
2. Mở DevTools → Console
3. Phải thấy log "WebSocket connected" hoặc "Bot ready"
4. Click microphone → nói thử → bot phản hồi

### Test 3: Domain whitelist hoạt động

Trên domain KHÔNG có trong whitelist (ví dụ `localhost:8000` nếu whitelist chỉ có `myshop.com`):

```bash
curl -i https://live.vietbot.vn/ws?site-endpoint=YOUR_TOKEN \
     -H "Origin: http://evil.com" \
     -H "Upgrade: websocket" \
     -H "Connection: Upgrade" \
     -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     -H "Sec-WebSocket-Version: 13"
```

**Expected:** `HTTP/1.1 403 Forbidden`

---

## Troubleshooting

| Vấn đề | Nguyên nhân | Cách xử lý |
|--------|-------------|------------|
| Button không xuất hiện | Script load lỗi | Mở Console → check 404/CORS |
| Console: `SITE_WS_ENDPOINT chưa được cấu hình` | Chưa paste token | Mở widget.js → paste token |
| Iframe mở nhưng trắng | HTTPS chưa bật | Bắt buộc serve qua HTTPS để dùng mic |
| Iframe mở → 403 Forbidden | Domain không trong whitelist | Set whitelist qua API |
| Mic không hoạt động | User từ chối permission | Click icon mic trên browser → allow |
| Bot không phản hồi | Token hết hạn / agent bị xóa | Generate token mới từ MCP Access Point |
| `Mixed content` warning | Widget HTTP, page HTTPS | Đảm bảo `widget.js` cũng serve qua HTTPS |

### Debug mode

Mở DevTools → Console, kiểm tra log:

```javascript
// Server side logs (qua WebSocket frame)
// Tại server: tail -f /var/log/vietbot-live-voice.log | grep "Site-bound"
```

---

## Kiến trúc

```
┌──────────────────────────────┐
│  3rd-party website           │
│  ┌──────────────────────┐   │
│  │ <script> widget.js   │   │
│  │ → floating button    │   │
│  │ → iframe (380×560)   │   │
│  └──────────────────────┘   │
└──────────────┬───────────────┘
               │ HTTPS
               ▼
┌──────────────────────────────────────┐
│ live.vietbot.vn (nginx)              │
│  ├─ /embeddable_bot.html             │
│  ├─ /api/agent/{id}/site-domains     │
│  └─ /ws (WebSocket Pipecat)          │
└──────────────┬───────────────────────┘
               │
       ┌───────┴────────┐
       ▼                ▼
┌─────────────┐  ┌──────────────────┐
│  vietbot_api│  │ vietbot_server   │
│  (port 7860)│  │ (port 7861)      │
│             │  │                  │
│ SQLite:     │  │ Pipecat pipeline:│
│ - agents    │  │ - AES decrypt    │
│ - whitelist │  │ - Origin check   │
│ - configs   │  │ - Gemini Live    │
└─────────────┘  └──────────────────┘
```

**Flow chi tiết:**

1. **Widget load** → inject iframe với `?site-endpoint={token}`
2. **Iframe load embeddable_bot.html** → JS chạy Pipecat client
3. **Pipecat mở WebSocket** tới `wss://live.vietbot.vn/ws?site-endpoint={token}&client-id={uuid}`
4. **vietbot_server nhận WS request:**
   - Decrypt AES-ECB token → `agent_id`
   - Lấy `Origin` header từ request
   - Query SQLite (qua vietbot_api) → `site_allowed_domains` của agent
   - Check `Origin` có match whitelist không
   - Nếu OK → accept WS, load agent config, start Pipecat pipeline
   - Nếu FAIL → close với code 1008 "domain_not_allowed"

---

## API Reference

### Set domain whitelist

```
POST /api/agent/{agent_id}/site-domains
Authorization: Bearer {JWT_TOKEN}
Content-Type: application/json

{
  "domains": ["example.com", "*.example.com"]
}
```

**Response:**
```json
{
  "success": true,
  "agent_id": "0052d168...",
  "site_allowed_domains": ["example.com", "*.example.com"]
}
```

### Get domain whitelist

```
GET /api/agent/{agent_id}/site-domains
Authorization: Bearer {JWT_TOKEN}
```

### Validate domain pattern

Server chấp nhận các pattern sau:

| Pattern | Ý nghĩa | Match example |
|---------|---------|---------------|
| `"example.com"` | Exact match | `example.com` ✅, `www.example.com` ❌ |
| `"*.example.com"` | Wildcard subdomain | `example.com` ✅, `sub.example.com` ✅, `evil.com` ❌ |
| `"*"` | Allow all | Mọi origin ⚠️ |

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
| Domain whitelist? | ❌ Không | ✅ Có (khuyến nghị) |
| Use case | Khách hàng VietBot | 3rd-party embed |
| Bot dùng config của | Visitor | Admin |

---

## Liên hệ / Hỗ trợ

- 📧 Email: support@vietbot.vn
- 🌐 Website: [vietbot.vn](https://vietbot.vn)
- 📚 Docs: [docs.vietbot.vn](https://docs.vietbot.vn)

---

## License

MIT License — xem [LICENSE](../LICENSE)
