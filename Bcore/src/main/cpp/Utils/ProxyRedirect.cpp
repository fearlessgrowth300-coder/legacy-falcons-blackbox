// Per-guest transparent proxy: hooks libc connect() inside a BlackBox guest
// process and tunnels every outbound TCP connection through the proxy assigned to
// that guest's userId (HTTP CONNECT or SOCKS5). Because it runs INSIDE the guest
// process — where the BlackBox userId is known — each User/clone can use a
// different proxy, which the external (shared-UID) VpnService cannot do.
//
// Non-blocking sockets (IG/WhatsApp use these) are handled by briefly switching
// the fd to blocking for the proxy handshake, then restoring the flag.
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <mutex>
#include <android/log.h>
#include "./xdl.h"
#include "Dobby/dobby.h"

#define LOG_TAG "ProxyRedirect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

enum { PROXY_HTTP = 0, PROXY_SOCKS5 = 1 };

static std::mutex g_lock;
static bool g_enabled = false;
static bool g_installed = false;
static bool g_blockQuic = true;         // fail public UDP:443 so apps fall back to TCP (no QUIC leak)
static int g_type = PROXY_HTTP;
static std::string g_user, g_pass;
static sockaddr_storage g_proxy{};        // proxy as AF_INET
static socklen_t g_proxyLen = 0;
static sockaddr_in6 g_proxy6{};           // same proxy as a v4-mapped AF_INET6 addr
static bool g_haveProxy6 = false;

static int (*orig_connect)(int, const sockaddr *, socklen_t) = nullptr;
static int (*orig_getaddrinfo)(const char *, const char *, const addrinfo *, addrinfo **) = nullptr;
static ssize_t (*orig_sendto)(int, const void *, size_t, int, const sockaddr *, socklen_t) = nullptr;
static ssize_t (*orig_sendmsg)(int, const struct msghdr *, int) = nullptr;
static int (*orig_sendmmsg)(int, struct mmsghdr *, unsigned int, int) = nullptr;
static int g_quicBlocks = 0;

// ---- small helpers ---------------------------------------------------------

static bool writeAll(int fd, const void *buf, size_t n) {
    const char *p = (const char *) buf;
    size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, p + off, n - off);
        if (w > 0) { off += (size_t) w; continue; }
        if (w < 0 && (errno == EINTR)) continue;
        return false;
    }
    return true;
}

static bool readFull(int fd, void *buf, size_t n) {
    char *p = (char *) buf;
    size_t off = 0;
    while (off < n) {
        ssize_t r = read(fd, p + off, n - off);
        if (r > 0) { off += (size_t) r; continue; }
        if (r < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

// read a CRLF-terminated line (for HTTP), up to max-1 bytes
static int readLine(int fd, char *buf, int max) {
    int i = 0;
    while (i < max - 1) {
        char c;
        ssize_t r = read(fd, &c, 1);
        if (r <= 0) { if (r < 0 && errno == EINTR) continue; break; }
        if (c == '\n') break;
        if (c != '\r') buf[i++] = c;
    }
    buf[i] = '\0';
    return i;
}

static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static std::string base64(const std::string &in) {
    std::string out;
    int val = 0, bits = -6;
    for (unsigned char c : in) {
        val = (val << 8) + c; bits += 8;
        while (bits >= 0) { out.push_back(B64[(val >> bits) & 0x3F]); bits -= 6; }
    }
    if (bits > -6) out.push_back(B64[((val << 8) >> (bits + 8)) & 0x3F]);
    while (out.size() % 4) out.push_back('=');
    return out;
}

// dest -> "ip" + port; returns false if unsupported family
static bool destToIpPort(const sockaddr *addr, char *ip, size_t iplen, int *port) {
    if (addr->sa_family == AF_INET) {
        auto *a = (const sockaddr_in *) addr;
        inet_ntop(AF_INET, &a->sin_addr, ip, iplen);
        *port = ntohs(a->sin_port);
        return true;
    } else if (addr->sa_family == AF_INET6) {
        auto *a = (const sockaddr_in6 *) addr;
        if (IN6_IS_ADDR_V4MAPPED(&a->sin6_addr)) {
            // ::ffff:x.x.x.x -> emit the embedded IPv4 so the proxy dials v4
            struct in_addr v4{};
            memcpy(&v4, &a->sin6_addr.s6_addr[12], 4);
            inet_ntop(AF_INET, &v4, ip, iplen);
        } else {
            inet_ntop(AF_INET6, &a->sin6_addr, ip, iplen);
        }
        *port = ntohs(a->sin6_port);
        return true;
    }
    return false;
}

// Don't proxy loopback / LAN / link-local / the proxy server itself.
static bool isDirect(const sockaddr *addr) {
    if (addr->sa_family == AF_INET) {
        uint32_t h = ntohl(((const sockaddr_in *) addr)->sin_addr.s_addr);
        uint8_t a = (h >> 24) & 0xFF, b = (h >> 16) & 0xFF;
        if (a == 127) return true;                 // loopback
        if (a == 10) return true;                  // 10/8
        if (a == 172 && b >= 16 && b <= 31) return true; // 172.16/12
        if (a == 192 && b == 168) return true;     // 192.168/16
        if (a == 169 && b == 254) return true;     // link-local
        if (a == 0) return true;
    } else if (addr->sa_family == AF_INET6) {
        auto *a = (const sockaddr_in6 *) addr;
        const uint8_t *p = a->sin6_addr.s6_addr;
        static const uint8_t lo[16] = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
        if (memcmp(p, lo, 16) == 0) return true;   // ::1
        if (p[0] == 0xfe && (p[1] & 0xc0) == 0x80) return true; // fe80::/10
        if ((p[0] & 0xfe) == 0xfc) return true;    // fc00::/7 ULA
    }
    return false;
}

static bool sameAsProxy(const sockaddr *addr) {
    if (addr->sa_family != g_proxy.ss_family) return false;
    if (addr->sa_family == AF_INET) {
        auto *x = (const sockaddr_in *) addr;
        auto *p = (const sockaddr_in *) &g_proxy;
        return x->sin_addr.s_addr == p->sin_addr.s_addr && x->sin_port == p->sin_port;
    }
    return false;
}

// ---- proxy handshake on an already-proxy-connected fd -----------------------

static bool doHandshake(int fd, const sockaddr *dest) {
    char ip[64]; int port = 0;
    if (!destToIpPort(dest, ip, sizeof(ip), &port)) return false;

    if (g_type == PROXY_HTTP) {
        std::string req = "CONNECT ";
        req += ip; req += ":"; req += std::to_string(port);
        req += " HTTP/1.1\r\nHost: "; req += ip; req += ":"; req += std::to_string(port); req += "\r\n";
        if (!g_user.empty()) {
            req += "Proxy-Authorization: Basic ";
            req += base64(g_user + ":" + g_pass);
            req += "\r\n";
        }
        req += "\r\n";
        if (!writeAll(fd, req.data(), req.size())) return false;
        char line[512];
        if (readLine(fd, line, sizeof(line)) <= 0) return false;
        if (!strstr(line, " 200")) { LOGD("http connect refused: %s", line); return false; }
        // drain remaining headers until blank line
        while (readLine(fd, line, sizeof(line)) > 0) {}
        return true;
    }

    // SOCKS5
    bool auth = !g_user.empty();
    unsigned char greet[3] = {0x05, 0x01, (unsigned char) (auth ? 0x02 : 0x00)};
    if (!writeAll(fd, greet, 3)) return false;
    unsigned char sel[2];
    if (!readFull(fd, sel, 2) || sel[0] != 0x05) return false;
    if ((sel[1] & 0xFF) == 0x02) {
        std::string a;
        a.push_back(0x01);
        a.push_back((char) g_user.size()); a += g_user;
        a.push_back((char) g_pass.size()); a += g_pass;
        if (!writeAll(fd, a.data(), a.size())) return false;
        unsigned char ar[2];
        if (!readFull(fd, ar, 2) || ar[1] != 0x00) return false;
    } else if ((sel[1] & 0xFF) != 0x00) {
        return false;
    }
    // CONNECT by IP
    std::string r;
    r.push_back(0x05); r.push_back(0x01); r.push_back(0x00);
    if (dest->sa_family == AF_INET) {
        r.push_back(0x01);
        auto *a = (const sockaddr_in *) dest;
        r.append((const char *) &a->sin_addr, 4);
        r.append((const char *) &a->sin_port, 2);
    } else {
        auto *a = (const sockaddr_in6 *) dest;
        if (IN6_IS_ADDR_V4MAPPED(&a->sin6_addr)) {
            r.push_back(0x01);   // send the embedded IPv4
            r.append((const char *) &a->sin6_addr.s6_addr[12], 4);
            r.append((const char *) &a->sin6_port, 2);
        } else {
            r.push_back(0x04);
            r.append((const char *) &a->sin6_addr, 16);
            r.append((const char *) &a->sin6_port, 2);
        }
    }
    if (!writeAll(fd, r.data(), r.size())) return false;
    unsigned char head[4];
    if (!readFull(fd, head, 4) || head[1] != 0x00) return false;
    int skip = (head[3] == 0x01) ? 6 : (head[3] == 0x04) ? 18 : 0;
    if (head[3] == 0x03) { unsigned char l; if (!readFull(fd, &l, 1)) return false; skip = l + 2; }
    if (skip > 0) { char tmp[260]; if (!readFull(fd, tmp, skip)) return false; }
    return true;
}

// ---- the hook --------------------------------------------------------------

static int my_connect(int fd, const sockaddr *addr, socklen_t len) {
    bool enabled; int type;
    { std::lock_guard<std::mutex> lk(g_lock); enabled = g_enabled; type = 0; (void)type; }
    if (!enabled || !addr) return orig_connect(fd, addr, len);

    int stype = 0; socklen_t tl = sizeof(stype);
    getsockopt(fd, SOL_SOCKET, SO_TYPE, &stype, &tl);

    // QUIC leak guard: block public UDP:443 so the app retries over TCP (proxied).
    if (stype == SOCK_DGRAM) {
        if (g_blockQuic && (addr->sa_family == AF_INET || addr->sa_family == AF_INET6) && !isDirect(addr)) {
            char ip[64]; int port = 0;
            if (destToIpPort(addr, ip, sizeof(ip), &port) && port == 443) {
                errno = ECONNREFUSED;
                return -1;
            }
        }
        return orig_connect(fd, addr, len);
    }

    if (stype != SOCK_STREAM) return orig_connect(fd, addr, len);
    if (addr->sa_family != AF_INET && addr->sa_family != AF_INET6) return orig_connect(fd, addr, len);
    if (isDirect(addr) || sameAsProxy(addr)) return orig_connect(fd, addr, len);

    // Pick a proxy address whose family matches the guest SOCKET. IPv6 sockets are
    // fine as long as they carry a v4-mapped destination (::ffff:x.x.x.x) — we dial
    // the proxy via its v4-mapped form. A genuine IPv6 destination can't be proxied
    // by an IPv4 proxy, so refuse it (forces the app onto IPv4).
    const sockaddr *pxy;
    socklen_t pxyLen;
    if (addr->sa_family == AF_INET6) {
        auto *a6 = (const sockaddr_in6 *) addr;
        if (!IN6_IS_ADDR_V4MAPPED(&a6->sin6_addr) || !g_haveProxy6) {
            errno = ECONNREFUSED;
            return -1;
        }
        pxy = (const sockaddr *) &g_proxy6;
        pxyLen = sizeof(g_proxy6);
    } else {
        pxy = (const sockaddr *) &g_proxy;
        pxyLen = g_proxyLen;
    }

    // temporarily block for the handshake
    int fl = fcntl(fd, F_GETFL, 0);
    bool nb = (fl >= 0) && (fl & O_NONBLOCK);
    if (nb) fcntl(fd, F_SETFL, fl & ~O_NONBLOCK);

    int r = orig_connect(fd, pxy, pxyLen);
    if (r != 0) {
        if (nb) fcntl(fd, F_SETFL, fl);
        return -1;
    }

    bool ok = doHandshake(fd, addr);
    if (nb) fcntl(fd, F_SETFL, fl);
    if (!ok) { errno = ECONNREFUSED; return -1; }
    return 0;   // app sees an established connection to the original destination
}

// Force IPv4-only DNS while a proxy is active: our upstream proxy is IPv4, so we
// must not let the guest create IPv6 sockets we can't tunnel (they'd fail or leak).
static int my_getaddrinfo(const char *node, const char *service, const addrinfo *hints, addrinfo **res) {
    bool enabled;
    { std::lock_guard<std::mutex> lk(g_lock); enabled = g_enabled; }
    if (enabled) {
        addrinfo h;
        if (hints) h = *hints; else memset(&h, 0, sizeof(h));
        if (h.ai_family == AF_UNSPEC || h.ai_family == AF_INET6) h.ai_family = AF_INET;
        return orig_getaddrinfo(node, service, &h, res);
    }
    return orig_getaddrinfo(node, service, hints, res);
}

// QUIC leak guard for the DATAGRAM send path. The connect() hook only blocks *connected* UDP:443;
// but Chromium/cronet (Instagram, Chrome, etc.) send QUIC over UNCONNECTED UDP via sendmsg/sendmmsg
// (batched with GSO) — those never call connect(), so they'd bypass the proxy and leak the real IP
// (this is why IG showed "Lagos, Nigeria" while a TCP-only probe exited the proxy). Refuse UDP:443
// to any public dest so the app falls back to TCP, which IS tunnelled through the proxy.
static bool quic_leak(int fd, const sockaddr *dest) {
    if (!dest) return false;   // connected socket → connect() hook already handled :443
    { std::lock_guard<std::mutex> lk(g_lock); if (!g_enabled || !g_blockQuic) return false; }
    int stype = 0; socklen_t tl = sizeof(stype);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &stype, &tl) != 0 || stype != SOCK_DGRAM) return false;
    if (dest->sa_family != AF_INET && dest->sa_family != AF_INET6) return false;
    if (isDirect(dest) || sameAsProxy(dest)) return false;
    char ip[64]; int port = 0;
    if (destToIpPort(dest, ip, sizeof(ip), &port) && port == 443) {
        if (g_quicBlocks < 5) LOGD("blocked QUIC UDP:443 leak to %s (#%d) -> TCP fallback", ip, g_quicBlocks + 1);
        g_quicBlocks++;
        return true;
    }
    return false;
}

// Refuse QUIC datagrams with ECONNREFUSED. This makes Chromium/cronet mark the host's QUIC as
// BROKEN immediately and use TCP (which IS proxied) for the rest of the session — fast. (Silently
// DROPPING instead looks like packet loss, so cronet keeps retrying QUIC on every connection =
// slow.) The androidx-startup crash this once triggered is now caught by the SimpleCrashFix
// main-loop guard, so the app survives; net result: no leak, fast, no close.
static ssize_t my_sendto(int fd, const void *buf, size_t n, int flags,
                         const sockaddr *dest, socklen_t dlen) {
    if (quic_leak(fd, dest)) { errno = ECONNREFUSED; return -1; }
    return orig_sendto(fd, buf, n, flags, dest, dlen);
}

static ssize_t my_sendmsg(int fd, const struct msghdr *msg, int flags) {
    if (msg && msg->msg_name && quic_leak(fd, (const sockaddr *) msg->msg_name)) {
        errno = ECONNREFUSED; return -1;
    }
    return orig_sendmsg(fd, msg, flags);
}

static int my_sendmmsg(int fd, struct mmsghdr *msgs, unsigned int vlen, int flags) {
    if (msgs && vlen > 0 && msgs[0].msg_hdr.msg_name &&
        quic_leak(fd, (const sockaddr *) msgs[0].msg_hdr.msg_name)) {
        errno = ECONNREFUSED; return -1;
    }
    return orig_sendmmsg(fd, msgs, vlen, flags);
}

static void install_connect_hook() {
    if (g_installed) return;
    void *h = xdl_open("libc.so", XDL_DEFAULT);
    if (h) {
        void *t = xdl_dsym(h, "connect", nullptr);
        if (t && DobbyHook(t, (void *) my_connect, (void **) &orig_connect) == 0) {
            g_installed = true;
            LOGD("connect() hook installed");
        }
        void *g = xdl_dsym(h, "getaddrinfo", nullptr);
        if (g && DobbyHook(g, (void *) my_getaddrinfo, (void **) &orig_getaddrinfo) == 0) {
            LOGD("getaddrinfo hook installed (IPv4-only)");
        }
        // Block unconnected-UDP QUIC (the IG leak path) by DROPPING UDP:443 datagrams.
        void *st = xdl_dsym(h, "sendto", nullptr);
        if (st && DobbyHook(st, (void *) my_sendto, (void **) &orig_sendto) == 0)
            LOGD("sendto hook installed (QUIC guard)");
        void *sm = xdl_dsym(h, "sendmsg", nullptr);
        if (sm && DobbyHook(sm, (void *) my_sendmsg, (void **) &orig_sendmsg) == 0)
            LOGD("sendmsg hook installed (QUIC guard)");
        void *smm = xdl_dsym(h, "sendmmsg", nullptr);
        if (smm && DobbyHook(smm, (void *) my_sendmmsg, (void **) &orig_sendmmsg) == 0)
            LOGD("sendmmsg hook installed (QUIC guard)");
        xdl_close(h);
    }
}

// ---- configuration (called from BoxCore.cpp) -------------------------------

extern "C" void pr_set_proxy(int type, const char *host, int port, const char *user, const char *pass) {
    if (!host || port <= 0) return;
    struct addrinfo hints{}, *res = nullptr;
    hints.ai_family = AF_INET;   // SOAX/most proxies are IPv4; guests use IPv4 sockets
    hints.ai_socktype = SOCK_STREAM;
    char ports[16];
    snprintf(ports, sizeof(ports), "%d", port);
    // Resolve while the hook is idle (g_enabled=false), so DNS goes direct.
    if (getaddrinfo(host, ports, &hints, &res) != 0 || !res) {
        LOGD("proxy host resolve failed: %s", host);
        return;
    }
    {
        std::lock_guard<std::mutex> lk(g_lock);
        memcpy(&g_proxy, res->ai_addr, res->ai_addrlen);
        g_proxyLen = res->ai_addrlen;
        g_type = type;
        g_user = user ? user : "";
        g_pass = pass ? pass : "";
        // Build the v4-mapped IPv6 form so we can also serve AF_INET6 guest sockets.
        auto *p4 = (sockaddr_in *) &g_proxy;
        memset(&g_proxy6, 0, sizeof(g_proxy6));
        g_proxy6.sin6_family = AF_INET6;
        g_proxy6.sin6_port = p4->sin_port;
        g_proxy6.sin6_addr.s6_addr[10] = 0xff;
        g_proxy6.sin6_addr.s6_addr[11] = 0xff;
        memcpy(&g_proxy6.sin6_addr.s6_addr[12], &p4->sin_addr, 4);
        g_haveProxy6 = true;
    }
    freeaddrinfo(res);
    install_connect_hook();
    { std::lock_guard<std::mutex> lk(g_lock); g_enabled = true; }
    LOGD("proxy enabled %s:%d type=%d auth=%d", host, port, type, (int) (user && *user));
}

extern "C" void pr_disable() {
    std::lock_guard<std::mutex> lk(g_lock);
    g_enabled = false;
}
