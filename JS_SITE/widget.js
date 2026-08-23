/**
 * VietBot Site Widget — SITE-BOUND (cho admin nhúng vào web của họ)
 *
 * ⚠️ Workflow cho admin (NEW flow - self-contained token):
 *   1. Đăng ký agent tại web.vietbot.vn
 *   2. Vào live.vietbot.vn → tạo thiết bị web mới → lấy mã 6 số (bind code)
 *   3. Vào web.vietbot.vn → nhập mã 6 số để bind thiết bị web với Agent
 *   4. Vào partner.vietbot.vn → nhập MAC Address thiết bị web + Whitelist domains
 *   5. Hệ thống sinh token (AES-ECB encoded mac_address + whitelist)
 *   6. Copy token → paste vào dòng bên dưới (thay __SITE_WS_ENDPOINT__)
 *   7. Upload file widget.js đã sửa lên hosting của website admin
 *   8. Trong HTML website admin, thêm:
 *      <script src="/path/to/widget.js"></script>
 *
 * 🔒 Bảo mật (NEW):
 *   - Token TỰ CHỨA whitelist (không cần lưu DB, không thể sửa đổi)
 *   - Backend decrypt token → lấy mac_address + whitelist ngay lập tức
 *   - Check Origin domain của visitor vs whitelist từ token
 *   - Không match → 403 Forbidden, bot KHÔNG bao giờ chạy
 *   - Whitelist được mã hóa AES-ECB + secret key chỉ VietBot backend biết
 *   - Hỗ trợ wildcard: "example.com", "*.example.com", "*"
 *
 * Visitor KHÔNG cần đăng ký — bot dùng config của admin từ Java Manager API.
 */
(function () {
    'use strict';

    // ============================================================
    // ⬇️⬇️⬇️ ADMIN: Paste WS endpoint token ở đây ⬇️⬇️⬇️
    // Ví dụ: const SITE_WS_ENDPOINT = 'abc123...xyz=';
    // Hỗ trợ config qua window.VIETBOT_SITE_CONFIG hoặc window.SITE_WS_ENDPOINT
    const EXTERNAL_CONFIG = window.VIETBOT_SITE_CONFIG || {};
    const EXTERNAL_ENDPOINT = window.SITE_WS_ENDPOINT || '';
    const SITE_WS_ENDPOINT = EXTERNAL_ENDPOINT || '__SITE_WS_ENDPOINT__';
    // ⬆️⬆️⬆️ ADMIN: Paste WS endpoint token ở đây ⬆️⬆️⬆️
    // ============================================================

    if (SITE_WS_ENDPOINT === '__SITE_WS_ENDPOINT__') {
        console.error(
            '[VietBot Widget] SITE_WS_ENDPOINT chưa được cấu hình. ' +
            'Set window.SITE_WS_ENDPOINT trước khi load widget.js, hoặc paste token vào file này.'
        );
    }

    const CONFIG = {
        botUrl: 'https://live.vietbot.vn/embeddable_bot.html',
        siteEndpoint: SITE_WS_ENDPOINT,
        width: EXTERNAL_CONFIG.width || '380px',
        height: EXTERNAL_CONFIG.height || '560px',
        position: EXTERNAL_CONFIG.position || 'bottom-right',
        bottom: EXTERNAL_CONFIG.bottom || '80px',  // Nâng cao hơn mặc định 80px (thay vì 20px)
    };

    if (document.getElementById('vietbot-widget-container')) return;

    // Container
    const container = document.createElement('div');
    container.id = 'vietbot-widget-container';
    Object.assign(container.style, {
        position: 'fixed',
        zIndex: '999999',
        bottom: CONFIG.bottom,
        right: CONFIG.position === 'bottom-left' ? 'auto' : '20px',
        left: CONFIG.position === 'bottom-left' ? '20px' : 'auto',
        fontFamily: 'sans-serif',
        display: 'flex',
        flexDirection: 'column',
        alignItems: CONFIG.position === 'bottom-left' ? 'flex-start' : 'flex-end',
    });

    // Iframe container
    const iframeContainer = document.createElement('div');
    Object.assign(iframeContainer.style, {
        width: CONFIG.width,
        height: CONFIG.height,
        maxHeight: '80vh',
        maxWidth: '90vw',
        backgroundColor: '#0d1117',
        borderRadius: '12px',
        boxShadow: '0 4px 20px rgba(0,0,0,0.5)',
        overflow: 'hidden',
        marginBottom: '16px',
        display: 'none',
        opacity: '0',
        transform: 'translateY(20px)',
        transition: 'opacity 0.3s, transform 0.3s',
        border: '1px solid rgba(100, 255, 218, 0.3)',
    });

    // Iframe — gắn site-endpoint vào query string
    const iframe = document.createElement('iframe');
    iframe.src = CONFIG.botUrl + '?site-endpoint=' + encodeURIComponent(CONFIG.siteEndpoint);
    iframe.setAttribute('allow', 'microphone; camera; autoplay; clipboard-write; encrypted-media');
    iframe.setAttribute('loading', 'lazy');
    Object.assign(iframe.style, {
        width: '100%',
        height: '100%',
        border: 'none',
    });
    iframeContainer.appendChild(iframe);

    // Launcher button
    const button = document.createElement('button');
    Object.assign(button.style, {
        width: '60px',
        height: '60px',
        borderRadius: '50%',
        backgroundColor: '#1f6feb',
        border: 'none',
        boxShadow: '0 4px 12px rgba(0,0,0,0.3)',
        cursor: 'pointer',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: 'white',
        transition: 'transform 0.2s, background-color 0.2s',
    });
    button.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>';
    const svg = button.querySelector('svg');
    if (svg) { svg.style.width = '30px'; svg.style.height = '30px'; }

    // Toggle logic
    let isOpen = false;
    button.addEventListener('click', () => {
        isOpen = !isOpen;
        if (isOpen) {
            iframeContainer.style.display = 'block';
            iframeContainer.offsetHeight; // force reflow
            iframeContainer.style.opacity = '1';
            iframeContainer.style.transform = 'translateY(0)';
            button.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>';
        } else {
            iframeContainer.style.opacity = '0';
            iframeContainer.style.transform = 'translateY(20px)';
            setTimeout(() => { iframeContainer.style.display = 'none'; }, 300);
            button.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>';
            const s = button.querySelector('svg');
            if (s) { s.style.width = '30px'; s.style.height = '30px'; }
        }
    });

    // Hover
    button.addEventListener('mouseenter', () => { button.style.transform = 'scale(1.1)'; });
    button.addEventListener('mouseleave', () => { button.style.transform = 'scale(1)'; });

    // Assemble
    container.appendChild(iframeContainer);
    container.appendChild(button);
    document.body.appendChild(container);
})();