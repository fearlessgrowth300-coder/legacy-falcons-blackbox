// Per-guest transparent proxy: hooks libc connect() inside a BlackBox guest
// process and tunnels every outbound TCP connection through the proxy assigned to
// that guest's userId (HTTP CONNECT or SOCKS5). Because it runs INSIDE the guest
// process — where the BlackBox userId is known — each User/clone can use a
// different proxy, which the external (shared-UID) VpnService cannot do.
//
// Non-blocking sockets (IG/WhatsApp use these) are connected immediately to a
// process-local loopback relay. The relay performs the upstream proxy handshake
// on its own worker, so app event loops never block on SOCKS/HTTP round trips.
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <mutex>
#include <unordered_map>
#include <algorithm>
#include <cctype>
#include <poll.h>
#include <sys/time.h>
#include <thread>
#include <array>
#include <atomic>
#include <cstdint>
#include <android/log.h>
#include "./xdl.h"
#include "Dobby/dobby.h"

#define LOG_TAG "ProxyRedirect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

enum { PROXY_HTTP = 0, PROXY_SOCKS5 = 1 };

static std::mutex g_lock;
static bool g_enabled = false;
static bool g_installed = false;
static int g_type = PROXY_HTTP;
static std::string g_user, g_pass;
static sockaddr_storage g_proxy{};        // proxy as AF_INET
static socklen_t g_proxyLen = 0;
static sockaddr_in6 g_proxy6{};           // same proxy as a v4-mapped AF_INET6 addr
static bool g_haveProxy6 = false;

static int (*orig_connect)(int, const sockaddr *, socklen_t) = nullptr;
static int (*orig_getaddrinfo)(const char *, const char *, const addrinfo *, addrinfo **) = nullptr;
static int (*orig_android_getaddrinfofornet)(const char *, const char *, const addrinfo *,
                                             unsigned, unsigned, addrinfo **) = nullptr;
static int (*orig_android_getaddrinfofornetcontext)(const char *, const char *, const addrinfo *,
                                                     const void *, addrinfo **) = nullptr;
static ssize_t (*orig_sendto)(int, const void *, size_t, int, const sockaddr *, socklen_t) = nullptr;
static ssize_t (*orig_sendmsg)(int, const struct msghdr *, int) = nullptr;
static int (*orig_sendmmsg)(int, struct mmsghdr *, unsigned int, int) = nullptr;
static int g_udpBlocks = 0;
static std::mutex g_dnsLock;
static uint32_t g_nextSynthetic = 1;
static std::unordered_map<std::string, uint32_t> g_hostToSynthetic;
static std::unordered_map<uint32_t, std::string> g_syntheticToHost;
static std::atomic<uint64_t> g_routeGeneration{1};
static std::atomic<int> g_relayStarts{0};
static std::atomic<int> g_relayFailures{0};

struct ProxySnapshot {
    int type = PROXY_HTTP;
    sockaddr_storage proxy{};
    socklen_t proxyLen = 0;
    std::string user;
    std::string pass;
    uint64_t generation = 0;
};

struct ProxyTarget {
    sockaddr_storage address{};
    socklen_t addressLen = 0;
    std::string host;
    int port = 0;
    bool remoteDns = false;
};

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

// Only loopback may bypass the assigned proxy. LAN/link-local exceptions let apps discover the
// physical network and are therefore not safe in an isolated clone.
static bool isLoopback(const sockaddr *addr) {
    if (addr->sa_family == AF_INET) {
        uint32_t h = ntohl(((const sockaddr_in *) addr)->sin_addr.s_addr);
        return ((h >> 24) & 0xFF) == 127;
    } else if (addr->sa_family == AF_INET6) {
        auto *a = (const sockaddr_in6 *) addr;
        const uint8_t *p = a->sin6_addr.s6_addr;
        static const uint8_t lo[16] = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
        if (memcmp(p, lo, 16) == 0) return true;
        return IN6_IS_ADDR_V4MAPPED(&a->sin6_addr) && p[12] == 127;
    }
    return false;
}

static bool isNumericHost(const char *node) {
    if (!node || !*node) return true;
    in_addr v4{}; in6_addr v6{};
    return inet_pton(AF_INET, node, &v4) == 1 || inet_pton(AF_INET6, node, &v6) == 1;
}

static bool isLocalHostName(const char *node) {
    if (!node) return false;
    std::string h(node);
    std::transform(h.begin(), h.end(), h.begin(), [](unsigned char c) { return (char) std::tolower(c); });
    return h == "localhost" || (h.size() > 10 && h.compare(h.size() - 10, 10, ".localhost") == 0);
}

// Return an address in 198.18.0.0/15 (RFC 2544 benchmarking range). It is never dialled directly:
// connect() recognizes it and sends the original hostname in HTTP CONNECT / SOCKS5, giving us
// proxy-side DNS without asking Android/netd to resolve the guest's destination.
static std::string syntheticForHost(const char *node) {
    std::string host(node ? node : "");
    std::transform(host.begin(), host.end(), host.begin(), [](unsigned char c) { return (char) std::tolower(c); });
    std::lock_guard<std::mutex> lk(g_dnsLock);
    auto found = g_hostToSynthetic.find(host);
    uint32_t net;
    if (found != g_hostToSynthetic.end()) {
        net = found->second;
    } else {
        // 198.18.0.1 through 198.19.255.254; wrap only after exhausting the process-local pool.
        uint32_t offset = g_nextSynthetic++ % 131070u;
        if (offset == 0) offset = 1;
        uint32_t hostOrder = 0xC6120000u + offset;
        net = htonl(hostOrder);
        g_hostToSynthetic[host] = net;
        g_syntheticToHost[net] = host;
    }
    in_addr a{}; a.s_addr = net;
    char text[INET_ADDRSTRLEN]{};
    inet_ntop(AF_INET, &a, text, sizeof(text));
    return text;
}

static bool syntheticDomainFor(const sockaddr *addr, std::string *domain) {
    uint32_t net = 0;
    if (addr->sa_family == AF_INET) {
        net = ((const sockaddr_in *) addr)->sin_addr.s_addr;
    } else if (addr->sa_family == AF_INET6) {
        auto *a6 = (const sockaddr_in6 *) addr;
        if (!IN6_IS_ADDR_V4MAPPED(&a6->sin6_addr)) return false;
        memcpy(&net, &a6->sin6_addr.s6_addr[12], sizeof(net));
    } else return false;
    std::lock_guard<std::mutex> lk(g_dnsLock);
    auto found = g_syntheticToHost.find(net);
    if (found == g_syntheticToHost.end()) return false;
    *domain = found->second;
    return true;
}

// ---- proxy handshake on an already-proxy-connected fd -----------------------

static bool buildTarget(const sockaddr *dest, socklen_t len, ProxyTarget *out) {
    if (!dest || !out || len > sizeof(out->address)) return false;
    char ip[64]; int port = 0;
    if (!destToIpPort(dest, ip, sizeof(ip), &port)) return false;
    memcpy(&out->address, dest, len);
    out->addressLen = len;
    out->port = port;
    std::string domain;
    out->remoteDns = syntheticDomainFor(dest, &domain);
    out->host = out->remoteDns ? domain : std::string(ip);
    return !out->host.empty() && out->port > 0;
}

static bool doHandshake(int fd, const ProxyTarget &target, const ProxySnapshot &proxy) {
    if (proxy.type == PROXY_HTTP) {
        std::string req = "CONNECT ";
        req += target.host; req += ":"; req += std::to_string(target.port);
        req += " HTTP/1.1\r\nHost: "; req += target.host; req += ":";
        req += std::to_string(target.port); req += "\r\n";
        if (!proxy.user.empty()) {
            req += "Proxy-Authorization: Basic ";
            req += base64(proxy.user + ":" + proxy.pass);
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
    bool auth = !proxy.user.empty();
    unsigned char greet[3] = {0x05, 0x01, (unsigned char) (auth ? 0x02 : 0x00)};
    if (!writeAll(fd, greet, 3)) return false;
    unsigned char sel[2];
    if (!readFull(fd, sel, 2) || sel[0] != 0x05) return false;
    if ((sel[1] & 0xFF) == 0x02) {
        std::string a;
        a.push_back(0x01);
        if (proxy.user.size() > 255 || proxy.pass.size() > 255) return false;
        a.push_back((char) proxy.user.size()); a += proxy.user;
        a.push_back((char) proxy.pass.size()); a += proxy.pass;
        if (!writeAll(fd, a.data(), a.size())) return false;
        unsigned char ar[2];
        if (!readFull(fd, ar, 2) || ar[1] != 0x00) return false;
    } else if ((sel[1] & 0xFF) != 0x00) {
        return false;
    }
    // CONNECT by domain for synthetic DNS entries; numeric destinations stay numeric.
    std::string r;
    r.push_back(0x05); r.push_back(0x01); r.push_back(0x00);
    if (target.remoteDns) {
        if (target.host.empty() || target.host.size() > 255) return false;
        r.push_back(0x03);
        r.push_back((char) target.host.size());
        r.append(target.host);
        uint16_t networkPort = htons((uint16_t) target.port);
        r.append((const char *) &networkPort, 2);
    } else if (target.address.ss_family == AF_INET) {
        r.push_back(0x01);
        auto *a = (const sockaddr_in *) &target.address;
        r.append((const char *) &a->sin_addr, 4);
        r.append((const char *) &a->sin_port, 2);
    } else {
        auto *a = (const sockaddr_in6 *) &target.address;
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

static void resetAndClose(int fd) {
    if (fd < 0) return;
    linger reset{1, 0};
    setsockopt(fd, SOL_SOCKET, SO_LINGER, &reset, sizeof(reset));
    close(fd);
}

static int connectUpstream(const ProxySnapshot &proxy) {
    if (proxy.proxyLen == 0) return -1;
    int fd = socket(proxy.proxy.ss_family, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    if (fd < 0) return -1;

    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    int result = orig_connect(fd, (const sockaddr *) &proxy.proxy, proxy.proxyLen);
    if (result != 0 && (errno == EINPROGRESS || errno == EALREADY)) {
        pollfd pfd{fd, POLLOUT, 0};
        do { result = poll(&pfd, 1, 2500); } while (result < 0 && errno == EINTR);
        if (result > 0) {
            int socketError = 0;
            socklen_t errorLen = sizeof(socketError);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socketError, &errorLen) != 0
                    || socketError != 0) {
                if (socketError != 0) errno = socketError;
                result = -1;
            } else {
                result = 0;
            }
        } else {
            if (result == 0) errno = ETIMEDOUT;
            result = -1;
        }
    }
    if (result != 0) {
        close(fd);
        return -1;
    }

    if (flags >= 0) fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);
    timeval handshakeTimeout{2, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &handshakeTimeout, sizeof(handshakeTimeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &handshakeTimeout, sizeof(handshakeTimeout));
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
    return fd;
}

struct RelayBuffer {
    std::array<char, 32768> bytes{};
    size_t begin = 0;
    size_t end = 0;
    bool eof = false;
    bool shutdownSent = false;
};

static bool hasData(const RelayBuffer &buffer) {
    return buffer.end > buffer.begin;
}

static bool makeRoom(RelayBuffer *buffer) {
    if (buffer->end == buffer->bytes.size() && buffer->begin > 0) {
        memmove(buffer->bytes.data(), buffer->bytes.data() + buffer->begin,
                buffer->end - buffer->begin);
        buffer->end -= buffer->begin;
        buffer->begin = 0;
    }
    return buffer->end < buffer->bytes.size();
}

static bool readInto(int fd, RelayBuffer *buffer) {
    if (buffer->eof || !makeRoom(buffer)) return true;
    ssize_t count = recv(fd, buffer->bytes.data() + buffer->end,
                         buffer->bytes.size() - buffer->end, 0);
    if (count > 0) {
        buffer->end += (size_t) count;
        return true;
    }
    if (count == 0) {
        buffer->eof = true;
        return true;
    }
    return errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK;
}

static bool writeFrom(int fd, RelayBuffer *buffer) {
    if (!hasData(*buffer)) return true;
    ssize_t count = send(fd, buffer->bytes.data() + buffer->begin,
                         buffer->end - buffer->begin, MSG_NOSIGNAL);
    if (count > 0) {
        buffer->begin += (size_t) count;
        if (buffer->begin == buffer->end) buffer->begin = buffer->end = 0;
        return true;
    }
    return count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK);
}

static void relayBidirectional(int client, int upstream, uint64_t generation) {
    int clientFlags = fcntl(client, F_GETFL, 0);
    int upstreamFlags = fcntl(upstream, F_GETFL, 0);
    if (clientFlags >= 0) fcntl(client, F_SETFL, clientFlags | O_NONBLOCK);
    if (upstreamFlags >= 0) fcntl(upstream, F_SETFL, upstreamFlags | O_NONBLOCK);
    timeval noTimeout{0, 0};
    setsockopt(upstream, SOL_SOCKET, SO_RCVTIMEO, &noTimeout, sizeof(noTimeout));
    setsockopt(upstream, SOL_SOCKET, SO_SNDTIMEO, &noTimeout, sizeof(noTimeout));

    RelayBuffer clientToProxy;
    RelayBuffer proxyToClient;
    bool healthy = true;
    while (healthy) {
        if (g_routeGeneration.load(std::memory_order_acquire) != generation) break;

        pollfd fds[2]{};
        fds[0].fd = client;
        fds[1].fd = upstream;
        if (!clientToProxy.eof && makeRoom(&clientToProxy)) fds[0].events |= POLLIN;
        if (hasData(proxyToClient)) fds[0].events |= POLLOUT;
        if (!proxyToClient.eof && makeRoom(&proxyToClient)) fds[1].events |= POLLIN;
        if (hasData(clientToProxy)) fds[1].events |= POLLOUT;

        int ready;
        do { ready = poll(fds, 2, 30000); } while (ready < 0 && errno == EINTR);
        if (ready < 0) break;
        if (ready > 0) {
            if (fds[0].revents & (POLLERR | POLLNVAL)) healthy = false;
            if (fds[1].revents & (POLLERR | POLLNVAL)) healthy = false;
            if (healthy && (fds[0].revents & POLLOUT)) healthy = writeFrom(client, &proxyToClient);
            if (healthy && (fds[1].revents & POLLOUT)) healthy = writeFrom(upstream, &clientToProxy);
            if (healthy && (fds[0].revents & (POLLIN | POLLHUP))) healthy = readInto(client, &clientToProxy);
            if (healthy && (fds[1].revents & (POLLIN | POLLHUP))) healthy = readInto(upstream, &proxyToClient);
        }

        if (clientToProxy.eof && !hasData(clientToProxy) && !clientToProxy.shutdownSent) {
            shutdown(upstream, SHUT_WR);
            clientToProxy.shutdownSent = true;
        }
        if (proxyToClient.eof && !hasData(proxyToClient) && !proxyToClient.shutdownSent) {
            shutdown(client, SHUT_WR);
            proxyToClient.shutdownSent = true;
        }
        if (clientToProxy.shutdownSent && proxyToClient.shutdownSent) break;
    }
    close(upstream);
    close(client);
}

static void relayWorker(int listener, ProxyTarget target, ProxySnapshot proxy) {
    int client;
    do { client = accept4(listener, nullptr, nullptr, SOCK_CLOEXEC); }
    while (client < 0 && errno == EINTR);
    close(listener);
    if (client < 0) return;
    if (g_routeGeneration.load(std::memory_order_acquire) != proxy.generation) {
        resetAndClose(client);
        return;
    }

    int upstream = connectUpstream(proxy);
    if (upstream < 0 || !doHandshake(upstream, target, proxy)) {
        if (upstream >= 0) resetAndClose(upstream);
        resetAndClose(client);
        int failure = g_relayFailures.fetch_add(1) + 1;
        if (failure <= 8) LOGD("async proxy relay failed (#%d)", failure);
        return;
    }

    int started = g_relayStarts.fetch_add(1) + 1;
    if (started <= 5) LOGD("async proxy relay established (#%d)", started);
    relayBidirectional(client, upstream, proxy.generation);
}

static int startLoopbackRelay(int fd, const sockaddr *destination,
                              const ProxyTarget &target, const ProxySnapshot &proxy) {
    int family = destination->sa_family;
    int listener = socket(family, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    if (listener < 0) return -1;
    int one = 1;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    sockaddr_storage local{};
    socklen_t localLen;
    if (family == AF_INET) {
        auto *v4 = (sockaddr_in *) &local;
        v4->sin_family = AF_INET;
        v4->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        v4->sin_port = 0;
        localLen = sizeof(*v4);
    } else {
        auto *v6 = (sockaddr_in6 *) &local;
        v6->sin6_family = AF_INET6;
        v6->sin6_addr = in6addr_loopback;
        v6->sin6_port = 0;
        localLen = sizeof(*v6);
        setsockopt(listener, IPPROTO_IPV6, IPV6_V6ONLY, &one, sizeof(one));
    }
    if (bind(listener, (sockaddr *) &local, localLen) != 0 || listen(listener, 1) != 0) {
        close(listener);
        return -1;
    }
    if (getsockname(listener, (sockaddr *) &local, &localLen) != 0) {
        close(listener);
        return -1;
    }

    int result = orig_connect(fd, (const sockaddr *) &local, localLen);
    int connectErrno = errno;
    if (result != 0 && connectErrno != EINPROGRESS && connectErrno != EALREADY
            && connectErrno != EISCONN) {
        close(listener);
        errno = connectErrno;
        return -1;
    }
    try {
        std::thread(relayWorker, listener, target, proxy).detach();
    } catch (...) {
        close(listener);
        errno = EAGAIN;
        return -1;
    }
    if (result != 0) errno = connectErrno;
    return result;
}

static int my_connect(int fd, const sockaddr *addr, socklen_t len) {
    ProxySnapshot proxy;
    bool enabled;
    {
        std::lock_guard<std::mutex> lk(g_lock);
        enabled = g_enabled;
        if (enabled) {
            proxy.type = g_type;
            proxy.proxy = g_proxy;
            proxy.proxyLen = g_proxyLen;
            proxy.user = g_user;
            proxy.pass = g_pass;
            proxy.generation = g_routeGeneration.load(std::memory_order_acquire);
        }
    }
    if (!enabled || !addr) return orig_connect(fd, addr, len);

    int stype = 0; socklen_t tl = sizeof(stype);
    getsockopt(fd, SOL_SOCKET, SO_TYPE, &stype, &tl);

    // This redirector has no UDP relay. Fail every non-loopback Internet datagram closed;
    // allowing even one port would expose DNS/STUN/custom telemetry on the phone's real route.
    if (stype == SOCK_DGRAM) {
        if ((addr->sa_family == AF_INET || addr->sa_family == AF_INET6) && !isLoopback(addr)) {
            errno = ECONNREFUSED;
            return -1;
        }
        return orig_connect(fd, addr, len);
    }

    if (stype != SOCK_STREAM) return orig_connect(fd, addr, len);
    if (addr->sa_family != AF_INET && addr->sa_family != AF_INET6) return orig_connect(fd, addr, len);
    if (isLoopback(addr)) return orig_connect(fd, addr, len);

    // Pick a proxy address whose family matches the guest SOCKET. IPv6 sockets are
    // fine as long as they carry a v4-mapped destination (::ffff:x.x.x.x) — we dial
    // the proxy via its v4-mapped form. A genuine IPv6 destination can't be proxied
    // by an IPv4 proxy, so refuse it (forces the app onto IPv4).
    if (addr->sa_family == AF_INET6) {
        auto *a6 = (const sockaddr_in6 *) addr;
        if (!IN6_IS_ADDR_V4MAPPED(&a6->sin6_addr)) {
            errno = ECONNREFUSED;
            return -1;
        }
    }

    ProxyTarget target;
    if (!buildTarget(addr, len, &target)) {
        errno = ECONNREFUSED;
        return -1;
    }
    return startLoopbackRelay(fd, addr, target, proxy);
}

static bool useRemoteDns(const char *node, const addrinfo *hints) {
    bool enabled;
    { std::lock_guard<std::mutex> lk(g_lock); enabled = g_enabled; }
    if (!enabled || !node || !*node || isNumericHost(node) || isLocalHostName(node)) return false;
    return !hints || (hints->ai_flags & AI_NUMERICHOST) == 0;
}

static addrinfo syntheticHints(const addrinfo *hints) {
    addrinfo h{};
    if (hints) h = *hints;
    h.ai_family = AF_INET;
    h.ai_flags |= AI_NUMERICHOST;
    return h;
}

// Never ask Android/netd to resolve guest destination names. Both ordinary native callers and
// libcore's network-aware resolver receive a synthetic IPv4 address; doHandshake later converts
// it back to the hostname and sends it to the assigned proxy for remote DNS.
static int my_getaddrinfo(const char *node, const char *service, const addrinfo *hints, addrinfo **res) {
    if (useRemoteDns(node, hints)) {
        std::string synthetic = syntheticForHost(node);
        addrinfo h = syntheticHints(hints);
        return orig_getaddrinfo(synthetic.c_str(), service, &h, res);
    }
    return orig_getaddrinfo(node, service, hints, res);
}

static int my_android_getaddrinfofornet(const char *node, const char *service,
                                        const addrinfo *hints, unsigned netid,
                                        unsigned mark, addrinfo **res) {
    if (useRemoteDns(node, hints)) {
        std::string synthetic = syntheticForHost(node);
        addrinfo h = syntheticHints(hints);
        return orig_android_getaddrinfofornet(
                synthetic.c_str(), service, &h, netid, mark, res);
    }
    return orig_android_getaddrinfofornet(node, service, hints, netid, mark, res);
}

// QUIC leak guard for the DATAGRAM send path. The connect() hook only blocks *connected* UDP:443;
// but Chromium/cronet (Instagram, Chrome, etc.) send QUIC over UNCONNECTED UDP via sendmsg/sendmmsg
// (batched with GSO) — those never call connect(), so they'd bypass the proxy and leak the real IP
// (this is why IG showed "Lagos, Nigeria" while a TCP-only probe exited the proxy). Refuse UDP:443
// to any public dest so the app falls back to TCP, which IS tunnelled through the proxy.
static bool udp_leak(int fd, const sockaddr *suppliedDest) {
    const sockaddr *dest = suppliedDest;
    if (!dest) return false;   // connected socket → connect() hook already handled :443
    { std::lock_guard<std::mutex> lk(g_lock); if (!g_enabled) return false; }
    int stype = 0; socklen_t tl = sizeof(stype);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &stype, &tl) != 0 || stype != SOCK_DGRAM) return false;
    if (dest->sa_family != AF_INET && dest->sa_family != AF_INET6) return false;
    if (isLoopback(dest)) return false;
    char ip[64]; int port = 0;
    if (destToIpPort(dest, ip, sizeof(ip), &port)) {
        if (g_udpBlocks < 5) LOGD("blocked direct UDP leak (#%d), port=%d", g_udpBlocks + 1, port);
        g_udpBlocks++;
    }
    return true;
}

// Refuse QUIC datagrams with ECONNREFUSED. This makes Chromium/cronet mark the host's QUIC as
// BROKEN immediately and use TCP (which IS proxied) for the rest of the session — fast. (Silently
// DROPPING instead looks like packet loss, so cronet keeps retrying QUIC on every connection =
// slow.) The androidx-startup crash this once triggered is now caught by the SimpleCrashFix
// main-loop guard, so the app survives; net result: no leak, fast, no close.
static ssize_t my_sendto(int fd, const void *buf, size_t n, int flags,
                         const sockaddr *dest, socklen_t dlen) {
    if (udp_leak(fd, dest)) { errno = ECONNREFUSED; return -1; }
    return orig_sendto(fd, buf, n, flags, dest, dlen);
}

static ssize_t my_sendmsg(int fd, const struct msghdr *msg, int flags) {
    if (msg && udp_leak(fd, (const sockaddr *) msg->msg_name)) {
        errno = ECONNREFUSED; return -1;
    }
    return orig_sendmsg(fd, msg, flags);
}

static int my_sendmmsg(int fd, struct mmsghdr *msgs, unsigned int vlen, int flags) {
    if (msgs) {
        for (unsigned int i = 0; i < vlen; i++) {
            if (udp_leak(fd, (const sockaddr *) msgs[i].msg_hdr.msg_name)) {
                errno = ECONNREFUSED; return -1;
            }
        }
    }
    return orig_sendmmsg(fd, msgs, vlen, flags);
}

static bool install_connect_hook() {
    if (g_installed) {
        return orig_connect && orig_getaddrinfo && orig_android_getaddrinfofornet
                && orig_sendto && orig_sendmsg && orig_sendmmsg;
    }
    void *h = xdl_open("libc.so", XDL_DEFAULT);
    if (h) {
        void *t = xdl_dsym(h, "connect", nullptr);
        if (t && DobbyHook(t, (void *) my_connect, (void **) &orig_connect) == 0) {
            g_installed = true;
            LOGD("connect() hook installed");
        }
        void *g = xdl_dsym(h, "getaddrinfo", nullptr);
        if (g && DobbyHook(g, (void *) my_getaddrinfo, (void **) &orig_getaddrinfo) == 0) {
            LOGD("getaddrinfo hook installed (remote DNS)");
        }
        void *gn = xdl_dsym(h, "android_getaddrinfofornet", nullptr);
        if (gn && DobbyHook(gn, (void *) my_android_getaddrinfofornet,
                            (void **) &orig_android_getaddrinfofornet) == 0)
            LOGD("android_getaddrinfofornet hook installed (remote DNS)");
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
    return g_installed && orig_connect && orig_getaddrinfo && orig_android_getaddrinfofornet
            && orig_sendto && orig_sendmsg && orig_sendmmsg;
}

// ---- configuration (called from BoxCore.cpp) -------------------------------

extern "C" void pr_disable();

extern "C" bool pr_set_proxy(int type, const char *host, int port, const char *user, const char *pass) {
    // Never retain an earlier route if applying a replacement fails.
    pr_disable();
    if (!host || !*host || port <= 0 || port > 65535) return false;
    struct addrinfo hints{}, *res = nullptr;
    hints.ai_family = AF_INET;   // SOAX/most proxies are IPv4; guests use IPv4 sockets
    hints.ai_socktype = SOCK_STREAM;
    char ports[16];
    snprintf(ports, sizeof(ports), "%d", port);
    // Resolve while the hook is idle (g_enabled=false), so DNS goes direct.
    if (getaddrinfo(host, ports, &hints, &res) != 0 || !res) {
        LOGD("proxy host resolve failed");
        return false;
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
    if (!install_connect_hook()) {
        LOGD("proxy hook installation incomplete");
        return false;
    }
    { std::lock_guard<std::mutex> lk(g_lock); g_enabled = true; }
    LOGD("proxy enabled type=%d auth=%d", type, (int) (user && *user));
    return true;
}

extern "C" void pr_disable() {
    {
        std::lock_guard<std::mutex> lk(g_lock);
        g_enabled = false;
    }
    {
        std::lock_guard<std::mutex> lk(g_dnsLock);
        g_hostToSynthetic.clear();
        g_syntheticToHost.clear();
        g_nextSynthetic = 1;
    }
}
