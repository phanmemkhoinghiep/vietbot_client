# VietBot Live Widget — JavaScript Embed for 3rd-Party Sites

## Quick Start

Add this script tag to any page on your site:

```html
<script src="https://cdn.jsdelivr.net/gh/phanmemkhoinghiep/vietbot_client@main/JS/widget.js"></script>
```

A floating chat button appears at the bottom-right of the page. Click to open the chat iframe.

**Requirements:**
- Your site must be served over HTTPS (required for microphone access)
- Users must register an agent + bind a device at [web.vietbot.vn](https://web.vietbot.vn) or [mobile.vietbot.vn](https://mobile.vietbot.vn) before the widget activates

---

## How It Works

```
1. Your page loads widget.js
2. widget.js injects a floating button + an iframe
3. The iframe loads https://live.vietbot.vn/embeddable_bot.html
4. embeddable_bot.html runs the Pipecat RTVI client
5. If the user's device isn't bound → a bind modal appears with a 6-digit code
6. User enters the code at web.vietbot.vn or mobile.vietbot.vn to activate
7. Once bound, the user can talk to the bot
```

---

## Customization (Optional)

Configure the widget before loading by setting `window.VIETBOT_WIDGET_CONFIG`:

```html
<script>
  window.VIETBOT_WIDGET_CONFIG = {
    position: 'bottom-left',   // 'bottom-right' (default) | 'bottom-left'
    buttonColor: '#ff6b6b',    // button accent color (currently fixed to blue, future support)
    width: '400px',            // iframe width (default: 380px)
    height: '600px'            // iframe height (default: 560px)
  };
</script>
<script src="https://cdn.jsdelivr.net/gh/phanmemkhoinghiep/vietbot_client@main/JS/widget.js"></script>
```

---

## Files

| File | Description |
|------|-------------|
| `widget.js` | Widget loader — inject into 3rd-party pages |

---

## Architecture

```
┌─────────────────────────────┐
│  3rd-party website          │
│  ┌─────────────────────┐   │
│  │ <script> widget.js  │   │
│  │ → floating button   │   │
│  │ → iframe (380x560)  │   │
│  └─────────────────────┘   │
└────────────┬────────────────┘
             │ HTTPS
             ▼
┌─────────────────────────────────────┐
│ live.vietbot.vn (nginx)             │
│  ├─ /widget.js, /embeddable_bot.html │
│  ├─ /api/*       → REST API (7860)  │
│  ├─ /connect     → RTVI connect     │
│  ├─ /ws          → WebSocket (7861) │
│  └─ /* (static)  → /var/www/...     │
└────────────┬────────────────────────┘
             │
             ▼
┌─────────────────────────────────────┐
│ Manager API (port 8002)             │
│  ├─ Device bind check              │
│  └─ Private config (prompt, voice) │
└─────────────────────────────────────┘
```

---

## Testing

### 1. Verify widget.js is accessible

```bash
curl -I https://cdn.jsdelivr.net/gh/phanmemkhoinghiep/vietbot_client@main/JS/widget.js
# Expected: HTTP 200, Content-Type: application/javascript
```

### 2. Test on a local HTML page

```html
<!DOCTYPE html>
<html>
<head>
    <title>Widget Test</title>
</head>
<body>
    <h1>Test Page</h1>
    <script src="https://cdn.jsdelivr.net/gh/phanmemkhoinghiep/vietbot_client@main/JS/widget.js"></script>
</body>
</html>
```

Serve over HTTPS (e.g., `python -m http.server`) and open in browser.

### 3. End-to-end test

1. Load page with widget.js
2. Floating button should appear at bottom-right
3. Click button → chat iframe opens
4. See bind modal if device not registered
5. Go to web.vietbot.vn → enter 6-digit bind code
6. Return to page → widget activates, mic icon appears
7. Click mic → speak → bot responds with voice + text

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Button doesn't appear | Check console for errors; verify script URL is accessible |
| Iframe is blank | Verify HTTPS; check browser console for CSP errors |
| "Device bind required" | User must register at web.vietbot.vn or mobile.vietbot.vn |
| Mic not working | Ensure page is served over HTTPS; allow microphone permissions |
| CORS errors | live.vietbot.vn nginx configured with dynamic CORS for all origins |

---

## Notes

- The widget uses `X-Frame-Options: ALLOWALL` and `Content-Security-Policy: frame-ancestors *` on live.vietbot.vn to permit cross-origin iframe embedding
- CORS on live.vietbot.vn is dynamic: vietbot.vn family origins get credentialled CORS; other origins get `Access-Control-Allow-Origin: *`
- All communication goes through HTTPS/WSS (HSTS enforced)
- The widget does NOT collect or transmit any user data from the host page — it only loads the iframe from live.vietbot.vn