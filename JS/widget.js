/**
 * VietBot Widget Loader v1.0.0
 *
 * Injects a floating chat button + iframe hosting VietBot Live.
 * For 3rd-party sites — drop this script tag:
 *   <script src="https://cdn.jsdelivr.net/gh/phanmemkhoinghiep/vietbot_client@main/JS/widget.js"></script>
 *
 * Users must register an agent + bind device at web.vietbot.vn or mobile.vietbot.vn first.
 *
 * @license MIT
 */
(function () {
    'use strict';

    // ---- Configuration (overridable via window.VIETBOT_WIDGET_CONFIG) ----
    const CONFIG = window.VIETBOT_WIDGET_CONFIG || {};
    const defaultOptions = {
        botUrl: 'https://live.vietbot.vn/embeddable_bot.html',
        buttonColor: '#64FFDA',
        width: '380px',
        height: '560px',
        position: 'bottom-right' // bottom-right, bottom-left
    };
    const options = { ...defaultOptions, ...CONFIG };

    // ---- Guard: don't inject twice ----
    if (document.getElementById('vietbot-widget-container')) {
        return;
    }

    // ---- Create container ----
    const container = document.createElement('div');
    container.id = 'vietbot-widget-container';
    Object.assign(container.style, {
        position: 'fixed',
        zIndex: '999999',
        bottom: '20px',
        right: options.position === 'bottom-left' ? 'auto' : '20px',
        left: options.position === 'bottom-left' ? '20px' : 'auto',
        fontFamily: 'sans-serif',
        display: 'flex',
        flexDirection: 'column',
        alignItems: options.position === 'bottom-left' ? 'flex-start' : 'flex-end'
    });

    // ---- Iframe container (hidden by default) ----
    const iframeContainer = document.createElement('div');
    Object.assign(iframeContainer.style, {
        width: options.width,
        height: options.height,
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
        border: '1px solid rgba(100, 255, 218, 0.3)'
    });

    // ---- Create iframe ----
    const iframe = document.createElement('iframe');
    iframe.src = options.botUrl;
    Object.assign(iframe.style, {
        width: '100%',
        height: '100%',
        border: 'none'
    });
    iframe.setAttribute('allow', 'microphone; camera; autoplay; clipboard-write; encrypted-media');
    iframe.setAttribute('loading', 'lazy');
    iframeContainer.appendChild(iframe);

    // ---- Launcher button ----
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
        transition: 'transform 0.2s, background-color 0.2s'
    });
    button.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>';

    // Icon styles
    const svg = button.querySelector('svg');
    if (svg) {
        svg.style.width = '30px';
        svg.style.height = '30px';
    }

    // ---- Toggle logic ----
    let isOpen = false;
    button.addEventListener('click', () => {
        isOpen = !isOpen;
        if (isOpen) {
            iframeContainer.style.display = 'block';
            iframeContainer.offsetHeight; // trigger reflow
            iframeContainer.style.opacity = '1';
            iframeContainer.style.transform = 'translateY(0)';
            button.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>';
        } else {
            iframeContainer.style.opacity = '0';
            iframeContainer.style.transform = 'translateY(20px)';
            setTimeout(() => {
                iframeContainer.style.display = 'none';
            }, 300);
            button.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>';
            const svg = button.querySelector('svg');
            if (svg) {
                svg.style.width = '30px';
                svg.style.height = '30px';
            }
        }
    });

    // Hover effects
    button.addEventListener('mouseenter', () => {
        button.style.transform = 'scale(1.1)';
    });
    button.addEventListener('mouseleave', () => {
        button.style.transform = 'scale(1)';
    });

    // ---- Assemble ----
    container.appendChild(iframeContainer);
    container.appendChild(button);
    document.body.appendChild(container);

})();
