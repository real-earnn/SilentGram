# SilentGram — route 1:1 call media through our VPS relay (SOCKS5)

Status: **design + fork patch spec, ready to apply. NOT yet implemented in a
shippable form** — every native change point lives inside the `tgcalls`
submodule, so it needs the `real-earnn/tgcalls` fork first (see §7).
What *is* done in this branch (`batch-z-calls`, v2.0.16): the stale v2.0.15
comment is corrected, and 1:1 + group-call diagnostics now log the facts the
patch needs (see §8). No behaviour changed, no version bump, no push.

## 0. Verdict

- **Chosen mechanism (1:1 calls): make the TCP reflector connection go
  through the existing SOCKS5 relay `sgrelay` (161.118.196.94:18080).**
  Three small native changes in a `tgcalls` fork + one line in the app's
  `CMakeLists.txt`. No new VPS service, no endpoint rewriting, no mapping
  tables, no protocol change. The reflector handshake and the DTLS-SRTP
  encryption are untouched — the relay only ever sees ciphertext (§4).
- **v2.0.15 did NOT do this.** Its `forceTcp` and its SOCKS5 proxy argument
  are both dead on the live path. The full root-cause chain is in §2 — read
  it before touching this code again, because the same two traps (dead
  `Descriptor.endpoints`, proxy never handed to the allocator) will bite any
  future "just pass the proxy" attempt.
- **Group calls / live streams are a different job** (§8): no relay factory,
  no proxy parameter, direct ICE to server-given candidates. Same VPS can
  serve them, but it needs a UDP forwarder + a join-payload rewrite + a
  small control plane. Designed in §8, not implemented here.

## 1. Verified facts (all re-checked from source 2026-11-20, not trusted)

Submodule pinned at `545f5ae599f8282e1ae3f38aaff166d179d75703`
(`TMessagesProj/jni/tgcalls`, empty in this checkout — source fetched from
`TelegramMessenger/tgcalls` at that exact commit for this investigation).

| # | Fact | Source |
|---|------|--------|
| F1 | `Descriptor.endpoints` is **never read by any v2 implementation**. `grep endpoints` over `InstanceV2Impl.cpp`, `InstanceV2ReferenceImpl.cpp`, `InstanceV2CompatImpl.cpp` returns nothing. | tgcalls 545f5ae, `v2/*Impl.cpp` |
| F2 | The live reflector transport is `RtcServer.isTcp`, from `Endpoint.tcp`, from `TL_phoneConnection.tcp` (server-sent). | `org_telegram_messenger_voip_Instance.cpp:968-975` (JNI builds `RtcServer` from the `Endpoint`); `TLRPC.TL_phoneConnection` (`tcp` = flag 0) |
| F3 | **v2 never calls `_portAllocator->set_proxy()`.** `NativeNetworkingImpl` stores `_proxy` (`v2/NativeNetworkingImpl.cpp:497`) and uses it only to disable UDP/STUN (`:596`). `InstanceV2CompatImpl` / `InstanceV2ReferenceImpl` build their own `BasicPortAllocator` and never call it either. Only v1 `NetworkManager.cpp:143-149` calls it. | tgcalls 545f5ae |
| F4 | `ReflectorPort::CreateClientRawTcpSocket()` **takes `proxy_info` and ignores it**: it does a direct `socket->Connect(remote_address)` (`v2/ReflectorPort.cpp:62-98`). | tgcalls 545f5ae |
| F5 | The vendored WebRTC implements **PROXY_HTTPS only**. `BasicPacketSocketFactory::CreateClientTcpSocket` wraps the socket in `AsyncHttpsProxySocket` only for `PROXY_HTTPS` (`TMessagesProj/jni/voip/webrtc/p2p/base/basic_packet_socket_factory.cc:122-126`). There is no SOCKS5 socket code anywhere in the vendored tree (only the `PROXY_SOCKS5` enum value in `rtc_base/proxy_info.h`). | in-repo webrtc |
| F6 | `InstanceV2CompatImpl` (version **14.0.0**) and `InstanceV2ReferenceImpl` (10/11) **skip TCP servers entirely** when building their ICE server list (`if (server.isTcp) continue;` — `v2/InstanceV2CompatImpl.cpp`, `v2/InstanceV2ReferenceImpl.cpp:750`). Only UDP reflector servers become relay ports there. `tcp_candidate_policy` is also `kTcpCandidatePolicyDisabled` (host TCP candidates only — relay TCP is unaffected). | tgcalls 545f5ae |
| F7 | `InstanceV2Impl` (7/8/9/12/13) *does* include TCP servers as `PROTO_TCP` relay configs (`v2/NativeNetworkingImpl.cpp`, `turnServers` loop). So the negotiated version decides whether a TCP reflector port even exists. | tgcalls 545f5ae |
| F8 | Versions offered by the client (`getAllVersions()` = all registered `Meta`s): v1 `2.7.7`/`5.0.0`; V2Impl `7/8/9/12/13`; Reference `10/11`; Compat `14`. The server picks `privateCall.protocol.library_versions[0]`. Which one is live in Pakistan is **unknown until the new diagnostics run** (§8). | tgcalls 545f5ae `GetVersions()`; `VoIPService.java:3647` |
| F9 | Reflector pairing is by **peer_tag only** (first 12 bytes server-issued + 4 random bytes, `ReflectorPort` ctor) + server id (candidate hostname `reflector-<id>-<tag>.reflector`). Source IP plays no role. TCP hello = 16-byte peer_tag + `uint32(0)` (`SendReflectorHello`, `v2/ReflectorPort.cpp:322-348`); the reflector bridges the two sides that present the same tag. | tgcalls 545f5ae |
| F10 | `ReflectorPort::AllowedReflectorPort()` returns `true` for every port in this tgcalls version — no port restriction. | `v2/ReflectorPort.cpp:914-916` |
| F11 | `PORTALLOCATOR_DISABLE_UDP` (set whenever a proxy is configured, `NativeNetworkingImpl.cpp:596`) only gates host UDP ports (`CreateUDPPorts`); UDP *relay* ports are unaffected. So today both UDP-reflector (direct) and TCP-reflector (direct) ports are created — both to Telegram IPs. | vendored `p2p/client/basic_port_allocator.cc:1458` (`CreateUDPPorts`), `:1591` (`CreateRelayPorts` gated only by `DISABLE_RELAY`/`DISABLE_UDP_RELAY`) |
| F12 | `sgrelay` on the VPS is a **full SOCKS5 server, verified live**: `CONNECT` to any TCP destination, `UDP ASSOCIATE` advertising public IP + fixed UDP port, user/pass auth. Process: `/usr/local/bin/sgrelay -tcp-port 18080 -udp-port 18080 -public-ip 161.118.196.94 -creds /etc/sgrelay/relay.txt`. Source: `~/workspace/sgrelay/main.go`. **No VPS change is needed for 1:1 calls.** | VPS `161.118.196.94` (checked 2026-11-20); `~/workspace/sgrelay/main.go:182-222` |
| F13 | Group calls: `GroupNetworkManager::resetDtlsSrtpTransport` builds the allocator with relay factory `nullptr` and `SetConfiguration({}, {})` — no TURN/STUN/reflector at all. `NativeInstance.makeGroup` takes no proxy. Media is direct ICE to the candidates in the join payload (`transport.candidates[]` with `ip`/`port`/`protocol`/`type`/`tcptype`). | tgcalls 545f5ae `group/GroupNetworkManager.cpp:387-398`, `group/GroupJoinPayloadInternal.cpp:88-167,355-360` |
| F14 | The `network_use_mtproto` / `MtProtoDtlsTransport` path does **not** help: it encrypts RTP with the call key and sends it *through the ICE transport* (`v2/MtProtoDtlsTransport.cpp:71`), i.e. it still needs a working ICE/reflector path underneath, and it needs the peer to speak it too. It is not "media over the MTProto connection". | tgcalls 545f5ae |
| F15 | `turn:<host>:<port>?transport=tcp` parses to `PROTO_TCP` in the vendored WebRTC (`pc/ice_server_parsing.cc:199-202`, `StringToProto`). This is how Compat/Reference must be given TCP reflectors (§6, P3/P4). | in-repo webrtc |

## 2. Why v2.0.15 still fails (root-cause chain)

v2.0.15 did two things, and both are dead on the live path:

1. `forceTcp = true` sets `Instance.ENDPOINT_TYPE_TCP_RELAY` — but that only
   fills `Descriptor.endpoints`, which **no v2 implementation reads** (F1).
   The transport that actually gets used is `RtcServer.isTcp` ←
   `connection.tcp` from the server (F2). If the server sends `tcp=false`
   for a reflector, that reflector is UDP, direct, blocked.
2. The SOCKS5 proxy passed to `Instance.makeInstance` is stored in
   `descriptor.proxy` and then **never handed to the port allocator** on v2
   (F3), so every port's `proxy()` is `PROXY_NONE`. And even if it were
   handed over, the TCP reflector connect ignores it (F4), and the vendored
   socket factory has no SOCKS5 implementation at all (F5). On Compat (14)
   / Reference (10/11) there is not even a TCP relay port to proxy (F6).

Net effect in Pakistan: signalling works (MTProto via our proxy
161.118.196.94:443, so calls ring and accept), then media has only direct
paths to Telegram reflector IPs — UDP and TCP alike blocked — so the call
sits in "Connecting…" until the ~20s native timeout. The proxy-page
"call relay" status row only proves `sgrelay` itself answers; it says
nothing about the media path.

Do NOT "fix" this from Java by forcing `Endpoint.tcp = true`:
Compat/Reference skip TCP servers from their ICE server list, so forcing
TCP would leave those implementations with **zero** relay ports — strictly
worse than today (the UDP reflector at least works where direct UDP works).
The transport must be fixed in native code, where the proxy is applied.

## 3. Design decision (1:1 calls)

**Route the TCP reflector connection through `sgrelay` via SOCKS5 CONNECT.**

Flow after the patch (all three v2 families):

```
phone --TCP--> sgrelay 161.118.196.94:18080 --SOCKS5 CONNECT--> reflector IP:port (Telegram)
        (SOCKS5 greeting + user/pass auth + CONNECT, then raw byte pipe)
```

- The phone only ever opens a TCP connection to our VPS (reachable in
  Pakistan). The VPS opens the TCP connection to the reflector (unblocked
  from the VPS). Latency is +1 hop (phone→VPS→reflector) — same as any
  relay; media stays on one TCP connection end-to-end.
- The reflector sees the connection coming from the VPS IP. That is fine:
  pairing is by peer_tag + server id (F9); the reflector never checks the
  source IP. The other side (official Telegram, or another SilentGram) is
  unaffected — it connects to the same reflector directly (or via its own
  relay) and the reflector bridges the two by tag.
- Encryption is untouched: DTLS-SRTP is negotiated end-to-end between the
  two peers over the relayed byte stream. `sgrelay` sees only ciphertext,
  exactly as a TURN server would. No key material ever touches the VPS.
- No per-reflector configuration anywhere: SOCKS5 CONNECT takes the
  destination from the client, so **any** reflector IP/port the server hands
  out works with zero mapping maintenance.
- UDP stays disabled when the proxy is set (existing behaviour, F11) — the
  only media path is TCP-via-SOCKS5. That is the intended trade: a working
  TCP call beats a failed UDP one; quality is lower than UDP, same as
  v2.0.15 intended.

## 4. Alternatives considered and rejected

- **F2 — Java endpoint rewrite + dumb TCP forwarder on the VPS.** Rewrite
  each reflector endpoint's IP/port to `161.118.196.94:<mapped port>` in
  `VoIPService` and run a byte-forwarder per reflector on the VPS. This
  needs no native change and no fork — but the mapping is the problem: the
  reflector set is server-chosen per call and rotates, and the first bytes
  the client sends (peer_tag hello) do not identify the reflector, so one
  shared VPS port cannot demultiplex. It needs either a static map (silently
  misses new reflector IPs → call fails exactly where it fails today) or a
  new control plane (app → API → forwarder, per-call port allocation).
  Rejected as the primary route; keep as a fallback if the fork route is
  ever blocked.
- **F3 — TURN server on the VPS (coturn).** Does not work as a config-only
  change: a TURN allocation relays packets to an address, but the reflector
  protocol requires the 16-byte peer_tag hello to be sent *on the reflector
  connection* (`ReflectorPort::SendReflectorHello`) before it pairs anything.
  A generic TURN relay never sends that hello, so the reflector would drop
  the media. It would need the same native changes as F1, for no benefit.
- **MTProto transport (`network_use_mtproto`).** Still rides on ICE (F14);
  does not route media over the MTProto connection. Not a shortcut.
- **Forcing `Endpoint.tcp = true` in Java.** Regression on Compat/Reference
  (§2). Rejected.
- **SOCKS5 for UDP media.** WebRTC creates UDP sockets directly
  (`CreateUdpSocket`, no proxy hook); routing UDP through SOCKS5 would need
  a custom `PacketSocketFactory` wrapping every UDP socket in UDP ASSOCIATE
  — a much bigger native change than the TCP-only fix, and unnecessary: the
  TCP reflector path is sufficient for a working call.

## 5. The fork patch (exact)

Base: `TelegramMessenger/tgcalls` at `545f5ae599f8282e1ae3f38aaff166d179d75703`
(the pinned submodule commit). All paths are inside the fork unless marked
"app repo".

### P0 — new file: `tgcalls/tgcalls/v2/Socks5ProxySocket.h`

Async SOCKS5 client adapter, modelled line-for-line on
`rtc::AsyncHttpsProxySocket` (`rtc_base/socket_adapters.{h,cc}` — same
`BufferedReadAdapter` contract: `Connect()` stores the destination and
connects the underlying socket to the proxy; `OnConnectEvent` starts the
handshake; `ProcessInput(data, len)` consumes handshake bytes from the
front, memmoves the remainder, and on completion calls
`BufferInput(false)` + `SignalConnectEvent(this)`).

```cpp
#ifndef TGCALLS_SOCKS5_PROXY_SOCKET_H
#define TGCALLS_SOCKS5_PROXY_SOCKET_H

#include <string>

#include "rtc_base/proxy_info.h"
#include "rtc_base/socket_adapters.h"

namespace tgcalls {

// Async SOCKS5 (RFC 1928 + RFC 1929 username/password) client adapter.
// Connect(dest) opens TCP to the proxy, runs greeting -> auth -> CONNECT
// asynchronously, and signals connect once the tunnel to dest is up.
// After that it is a plain byte pipe. Used by ReflectorPort so a TCP
// reflector connection goes through the user's SOCKS5 relay instead of
// direct to Telegram (which is blocked for our users).
class AsyncSocks5ProxySocket : public rtc::BufferedReadAdapter {
public:
    AsyncSocks5ProxySocket(rtc::Socket* socket,
                           const rtc::SocketAddress& proxy,
                           const std::string& username,
                           const rtc::CryptString& password);
    ~AsyncSocks5ProxySocket() override;

    AsyncSocks5ProxySocket(const AsyncSocks5ProxySocket&) = delete;
    AsyncSocks5ProxySocket& operator=(const AsyncSocks5ProxySocket&) = delete;

    int Connect(const rtc::SocketAddress& addr) override;
    rtc::SocketAddress GetRemoteAddress() const override;
    int Close() override;
    rtc::Socket::ConnState GetState() const override;

protected:
    void OnConnectEvent(rtc::Socket* socket) override;
    void OnCloseEvent(rtc::Socket* socket, int err) override;
    void ProcessInput(char* data, size_t* len) override;

    void SendGreeting();
    void SendAuth();
    void SendConnectRequest();
    void Error(int error);

private:
    rtc::SocketAddress proxy_, dest_;
    std::string user_;
    rtc::CryptString pass_;
    enum ProxyState {
        PS_INIT,
        PS_GREETING,
        PS_AUTH,
        PS_CONNECT,
        PS_TUNNEL,
        PS_ERROR
    } state_;
    bool authOffered_ = false;
};

} // namespace tgcalls

#endif // TGCALLS_SOCKS5_PROXY_SOCKET_H
```

### P0 — new file: `tgcalls/tgcalls/v2/Socks5ProxySocket.cpp`

```cpp
#include "v2/Socks5ProxySocket.h"

#include "rtc_base/logging.h"

namespace tgcalls {

AsyncSocks5ProxySocket::AsyncSocks5ProxySocket(rtc::Socket* socket,
                                               const rtc::SocketAddress& proxy,
                                               const std::string& username,
                                               const rtc::CryptString& password)
    : BufferedReadAdapter(socket, 1024),
      proxy_(proxy),
      user_(username),
      pass_(password),
      state_(PS_ERROR) {
}

AsyncSocks5ProxySocket::~AsyncSocks5ProxySocket() {
}

int AsyncSocks5ProxySocket::Connect(const rtc::SocketAddress& addr) {
    RTC_LOG(LS_VERBOSE) << "AsyncSocks5ProxySocket::Connect("
                        << proxy_.ToSensitiveString() << " -> "
                        << addr.ToSensitiveString() << ")";
    dest_ = addr;
    state_ = PS_INIT;
    BufferInput(true);
    return BufferedReadAdapter::Connect(proxy_);
}

rtc::SocketAddress AsyncSocks5ProxySocket::GetRemoteAddress() const {
    return dest_;
}

int AsyncSocks5ProxySocket::Close() {
    state_ = PS_ERROR;
    dest_.Clear();
    return BufferedReadAdapter::Close();
}

rtc::Socket::ConnState AsyncSocks5ProxySocket::GetState() const {
    if (state_ < PS_TUNNEL) {
        return CS_CONNECTING;
    } else if (state_ == PS_TUNNEL) {
        return CS_CONNECTED;
    } else {
        return CS_CLOSED;
    }
}

void AsyncSocks5ProxySocket::OnConnectEvent(rtc::Socket* socket) {
    RTC_LOG(LS_VERBOSE) << "AsyncSocks5ProxySocket::OnConnectEvent";
    SendGreeting();
}

void AsyncSocks5ProxySocket::OnCloseEvent(rtc::Socket* socket, int err) {
    RTC_LOG(LS_VERBOSE) << "AsyncSocks5ProxySocket::OnCloseEvent(" << err << ")";
    BufferedReadAdapter::OnCloseEvent(socket, err);
}

void AsyncSocks5ProxySocket::SendGreeting() {
    // VER=5, NMETHODS=1, METHODS=[0x02 user/pass] or [0x00 no auth].
    // sgrelay requires auth (replies 0xff if none offered), so offer 0x02
    // whenever we have a username.
    uint8_t greeting[3];
    greeting[0] = 0x05;
    greeting[1] = 0x01;
    if (user_.empty()) {
        greeting[2] = 0x00;
        authOffered_ = false;
    } else {
        greeting[2] = 0x02;
        authOffered_ = true;
    }
    DirectSend(greeting, sizeof(greeting));
    state_ = PS_GREETING;
}

void AsyncSocks5ProxySocket::SendAuth() {
    // RFC 1929: VER=1, ULEN, UNAME, PLEN, PASSWD.
    std::string password;
    password.resize(pass_.GetLength());
    if (!password.empty()) {
        pass_.CopyTo(password.data(), false);
    }
    if (user_.size() > 255 || password.size() > 255) {
        RTC_LOG(LS_ERROR) << "SOCKS5 credentials too long";
        Error(EINVAL);
        return;
    }
    std::string request;
    request.push_back(0x01);
    request.push_back(static_cast<char>(user_.size()));
    request.append(user_);
    request.push_back(static_cast<char>(password.size()));
    request.append(password);
    DirectSend(request.data(), request.size());
    state_ = PS_AUTH;
}

void AsyncSocks5ProxySocket::SendConnectRequest() {
    // VER=5, CMD=1 (CONNECT), RSV=0, ATYP, DST.ADDR, DST.PORT.
    std::string request;
    request.push_back(0x05);
    request.push_back(0x01);
    request.push_back(0x00);
    if (!dest_.hostname().empty() && dest_.ipaddr().IsNil()) {
        // Unresolved hostname: domain form.
        if (dest_.hostname().size() > 255) {
            RTC_LOG(LS_ERROR) << "SOCKS5 destination hostname too long";
            Error(EINVAL);
            return;
        }
        request.push_back(0x03);
        request.push_back(static_cast<char>(dest_.hostname().size()));
        request.append(dest_.hostname());
    } else if (dest_.ipaddr().family() == AF_INET) {
        request.push_back(0x01);
        in_addr v4 = dest_.ipaddr().ipv4_address();
        request.append(reinterpret_cast<const char*>(&v4.s_addr), 4);
    } else {
        request.push_back(0x04);
        in6_addr v6 = dest_.ipaddr().ipv6_address();
        request.append(reinterpret_cast<const char*>(&v6.s6_addr), 16);
    }
    uint16_t port = rtc::HostToNetwork16(static_cast<uint16_t>(dest_.port()));
    request.append(reinterpret_cast<const char*>(&port), 2);
    DirectSend(request.data(), request.size());
    state_ = PS_CONNECT;
}

void AsyncSocks5ProxySocket::ProcessInput(char* data, size_t* len) {
    size_t pos = 0;
    while (state_ != PS_TUNNEL && state_ != PS_ERROR && pos < *len) {
        if (state_ == PS_GREETING) {
            if (*len - pos < 2) {
                break; // wait for the rest of the method-selection reply
            }
            if (data[pos] != 0x05) {
                RTC_LOG(LS_ERROR) << "SOCKS5: bad version in greeting reply";
                Error(ECONNREFUSED);
                break;
            }
            uint8_t method = static_cast<uint8_t>(data[pos + 1]);
            pos += 2;
            if (method == 0x00) {
                SendConnectRequest();
            } else if (method == 0x02) {
                SendAuth();
            } else {
                RTC_LOG(LS_ERROR) << "SOCKS5: no acceptable auth method (" << (int)method << ")";
                Error(EACCES);
            }
        } else if (state_ == PS_AUTH) {
            if (*len - pos < 2) {
                break;
            }
            if (data[pos] != 0x01 || data[pos + 1] != 0x00) {
                RTC_LOG(LS_ERROR) << "SOCKS5: auth failed";
                Error(EACCES);
                break;
            }
            pos += 2;
            SendConnectRequest();
        } else if (state_ == PS_CONNECT) {
            if (*len - pos < 5) {
                break;
            }
            if (data[pos] != 0x05 || data[pos + 1] != 0x00) {
                RTC_LOG(LS_ERROR) << "SOCKS5: CONNECT failed, rep=" << (int)(uint8_t)data[pos + 1];
                Error(ECONNREFUSED);
                break;
            }
            uint8_t atyp = static_cast<uint8_t>(data[pos + 3]);
            size_t addrLen;
            if (atyp == 0x01) {
                addrLen = 4;
            } else if (atyp == 0x04) {
                addrLen = 16;
            } else if (atyp == 0x03) {
                if (*len - pos < 5) {
                    break;
                }
                addrLen = 1 + static_cast<uint8_t>(data[pos + 4]);
            } else {
                RTC_LOG(LS_ERROR) << "SOCKS5: bad ATYP in CONNECT reply";
                Error(ECONNREFUSED);
                break;
            }
            size_t total = 4 + addrLen + 2;
            if (*len - pos < total) {
                break; // wait for the full reply
            }
            pos += total;
            state_ = PS_TUNNEL;
        } else {
            break;
        }
    }

    *len -= pos;
    if (*len > 0) {
        memmove(data, data + pos, *len);
    }

    if (state_ != PS_TUNNEL) {
        return;
    }

    bool remainder = (*len > 0);
    BufferInput(false);
    SignalConnectEvent(this);
    // If SignalConnect destroys this socket we are in trouble; same caveat
    // as AsyncHttpsProxySocket — remainder bytes are reflector traffic.
    if (remainder) {
        SignalReadEvent(this);
    }
}

void AsyncSocks5ProxySocket::Error(int error) {
    BufferInput(false);
    Close();
    SetError(error);
    SignalCloseEvent(this, error);
}

} // namespace tgcalls
```

Notes for the reviewer (do not skip):
- The adapter lives in the **fork**, not in the vendored WebRTC, so the fork
  stays self-contained (it only uses public `rtc_base` API:
  `BufferedReadAdapter`, `SocketAddress`, `IPAddress::ipv4_address()` /
  `ipv6_address()`, `CryptString`, `HostToNetwork16`). The app-side change
  is only the one CMake line (P5).
- `rtc::HostToNetwork16` is used by the vendored WebRTC for the same
  purpose; if the fork's toolchain prefers `htons`, swap it.
- `dest_.hostname()` is non-empty only for unresolved addresses; reflector
  addresses arrive as IP literals, so the binary forms are the live path.
- No blocking calls anywhere: the whole handshake runs on the network
  thread through `ProcessInput`, exactly like `AsyncHttpsProxySocket`.
- `sgrelay` requires auth; the greeting offers `0x02` whenever a username
  is configured (it always is — `sgproxy`).

### P1 — `tgcalls/tgcalls/v2/ReflectorPort.cpp`: honour the proxy

`CreateClientRawTcpSocket` (the function currently ignores its
`proxy_info` argument — F4). Insert after the `OPT_NODELAY` block, before
`socket->Connect(remote_address)`:

```cpp
    // SilentGram: route the TCP reflector connection through the SOCKS5
    // relay when one is configured. The stock code ignores proxy_info
    // entirely, so without this the connection goes direct to Telegram.
    if (proxy_info.type == rtc::PROXY_SOCKS5) {
        socket = new AsyncSocks5ProxySocket(socket, proxy_info.address,
                                            proxy_info.username, proxy_info.password);
    }
```

and add `#include "v2/Socks5ProxySocket.h"` at the top of the file. Nothing
else in this function changes: `socket->Connect(remote_address)` on the
adapter connects to the proxy and handshakes to `remote_address`, and the
existing `new rtc::RawTcpSocket(socket)` wrap below works unchanged
(mirroring how `BasicPacketSocketFactory` wraps `PROXY_HTTPS` with
`AsyncHttpsProxySocket`). UDP branch untouched — UDP reflector ports still
use a direct UDP socket, but with a proxy configured the allocator disables
host UDP and the only relay path that matters is TCP (F11).

### P2 — `tgcalls/tgcalls/v2/NativeNetworkingImpl.cpp`: hand the proxy to the allocator

In `resetDtlsSrtpTransport()`, after `_portAllocator->Initialize();`
(mirror of v1 `NetworkManager.cpp:143-149`, which is the only place in the
tree that does this today):

```cpp
    if (_proxy) {
        rtc::ProxyInfo proxyInfo;
        proxyInfo.type = rtc::ProxyType::PROXY_SOCKS5;
        proxyInfo.address = rtc::SocketAddress(_proxy->host, _proxy->port);
        proxyInfo.username = _proxy->login;
        proxyInfo.password = rtc::CryptString(TgCallsCryptStringImpl(_proxy->password));
        _portAllocator->set_proxy("t/1.0", proxyInfo);
    }
```

Also copy the `TgCallsCryptStringImpl` helper verbatim from
`NetworkManager.cpp:36-76` into this file (it has no shared home), and make
sure `rtc_base/crypt_string.h` is included. This covers
`InstanceV2Impl` (versions 7/8/9/12/13) — the path where TCP reflector
servers are already included as `PROTO_TCP` relay configs (F7).

### P3 — `tgcalls/tgcalls/v2/InstanceV2CompatImpl.cpp`: same, plus stop dropping TCP servers

Two hunks. (a) After the `portAllocator` is created (before
`peerConnectionDependencies.allocator = std::move(portAllocator);`), add
the same `set_proxy` block as P2 (this file also needs the
`TgCallsCryptStringImpl` helper). (b) In the ICE-server mapping loop,
replace the blanket skip:

```cpp
            if (server.isTcp) {
                continue;
            }
```

with TCP servers mapped to a TCP TURN URL (the reflector case is always
`isTurn == true`; non-TURN TCP servers keep the old skip):

```cpp
            if (server.isTcp && !server.isTurn) {
                continue;
            }
```

and inside the `if (server.isTurn)` branch, change the URL line to:

```cpp
                mappedServer.urls.push_back(
                    "turn:" + address.HostAsURIString() + ":" + std::to_string(server.port) + (server.isTcp ? "?transport=tcp" : ""));
```

`?transport=tcp` parses to `PROTO_TCP` (F15), so the allocator creates a
`ReflectorPort` over TCP for that reflector, and P1 routes its connect
through SOCKS5. `tcp_candidate_policy` stays disabled — that only affects
host TCP candidates, not relay ports.

### P4 — `tgcalls/tgcalls/v2/InstanceV2ReferenceImpl.cpp`: same as P3

Apply the identical two hunks (this file's mapping loop is the same shape;
its skip is at line ~750). Covers versions 10/11.

### P5 — app repo: `TMessagesProj/jni/voip/CMakeLists.txt`

Add the new fork file to the `tgcalls` target (explicit file list, no glob
— a new file is otherwise silently not built), right after the
`ReflectorPort.cpp` line:

```cmake
        tgcalls/tgcalls/v2/Socks5ProxySocket.cpp
```

### P6 — v1 (`InstanceImpl` / `NetworkManager`, versions 2.7.7/5.0.0)

No change needed beyond P1: v1 already calls `set_proxy`, and P1 makes the
TCP reflector connect honour it. (v1 skips TCP servers from its TURN list,
so v1 TCP reflectors still would not be created — accepted, v1 is legacy
and not negotiated by current servers.)

### What deliberately does NOT change

- Java: no endpoint rewriting, no `tcp=true` forcing (§2), no new
  constants. `VoIPService` keeps passing the same proxy; after the patch
  the proxy finally does something.
- `sgrelay`: no change (F12). Same host/port/user/password.
- Signalling, reflector handshake, DTLS-SRTP, ICE candidate exchange:
  untouched. The other side of the call needs no update — an official
  Telegram client on the far end works unchanged.

## 6. Shipping route (exact steps)

The GitHub-API push flow **cannot push submodule content** (a submodule is
only a gitlink in the parent repo), so the native patch cannot land through
the normal SilentGram push. The route is:

1. **Create the fork** (needs the user's GitHub PAT — the parent agent does
   this; this subagent does not have it and must not ask for it):
   `POST /repos/TelegramMessenger/tgcalls/forks` → `real-earnn/tgcalls`.
   (A fork of a public repo is public; that is fine — upstream tgcalls is
   public, and the fork contains no secrets. The SOCKS5 password lives only
   in the app's Java constants and on the VPS, never in tgcalls.)
2. **In the fork**, at commit `545f5ae` (the currently pinned commit — do not
   rebase onto upstream master without re-verifying every finding above):
   apply P0–P4, commit, push to the fork's `master` (or a
   `silentgram` branch — either works as long as step 3 points at it).
3. **In SilentGram** (branch `batch-z-calls` or a fresh branch off it):
   - `.gitmodules`: change the `tgcalls` submodule URL from
     `https://github.com/TelegramMessenger/tgcalls.git` to
     `https://github.com/real-earnn/tgcalls.git`.
   - Update the gitlink: `git submodule sync` + `git submodule update
     --remote TMessagesProj/jni/tgcalls` (or checkout the fork commit
     inside the submodule and `git add TMessagesProj/jni/tgcalls`).
   - Apply P5 (CMakeLists line) — this is an app-repo change, normal push.
   - The VoIPService diagnostics from this branch are already in.
4. **Push the app branch and let CI build.** The native changes are
   **uncompiled until CI builds them** — there is no local NDK build in
   this environment, and the patch in §5 was verified by source review
   only. Budget for one fix-up cycle: if CI fails on the adapter
   (most likely a missing include or a `CryptString` API mismatch),
   fix in the fork, repoint the gitlink, rebuild.
5. **Do not touch `version.properties`** for this work (branch rule); the
   version bump rides with whatever release train picks this up.

## 7. Group calls / live streams

Different mechanism — the 1:1 patch does **not** cover them:

- `makeGroup` takes no endpoints and no proxy (F13). The group stack
  (`GroupNetworkManager`) builds an allocator with **no relay factory and
  no servers**, and media goes direct ICE to the candidates the server puts
  in the join payload (`transport.candidates[]`).
- The same VPS can serve group calls, but it needs a **UDP forwarder** on
  the VPS (a small Go service next to `sgrelay`: listen on UDP port X,
  forward to the group server's IP:port, return packets to the sender —
  stateless NAT by client address; DTLS-SRTP passes through untouched, and
  the server pairs by ICE ufrag, not source IP) **plus** a Java rewrite of
  the join payload before `setJoinResponsePayload`: replace each
  candidate's `ip` with `161.118.196.94` and its `port` with the forwarder
  port for that server. The candidates carry the real IP:port in the
  payload, so the mapping is known at join time — but the VPS listener for
  that server must exist first, which needs a control plane (app asks our
  API for a forward port for a given server IP:port; the API starts/ensures
  the listener). Protocol stays `udp` — no protocol rewrite, and no native
  change at all.
- Effort estimate (group only): VPS forwarder service ~0.5 day + API
  control endpoint ~0.5 day + Java payload rewrite ~0.5 day + device
  testing ~1 day. **Not started here on purpose**: it needs VPS deploys
  and a new service, which are outside this branch's scope (local commits
  only). The group diagnostics added in this branch (§8) are the
  prerequisite — they tell us the real candidate IPs/ports/protocols
  before anyone builds the forwarder.

## 8. What was done in this branch + what to test

Done here (local commits only, `batch-z-calls`, no push, no build):

- `VoIPService.java`: corrected the stale v2.0.15 comment (the
  `endpointType`/`forceTcp` no-op, the proxy gap, and the warning not to
  force `Endpoint.tcp`); the `SG-CALL-RELAY` init log now prints the
  negotiated tgcalls version and, per endpoint, `id@ip:port tcp= turn=
  stun= type= reflectorId=` (no peerTag, no passwords); new
  `logGroupJoinPayload()` logs group join candidates
  (`ip:port proto= type= tcptype=`, no ufrag/pwd). Verified with
  `javalang` (parses clean); native code was source-reviewed only.
- This file: `docs/sg-call-relay-via-socks5.md`.

Test plan once pushed + built (two phones in Pakistan, **no VPN**):

1. **1:1 call, SilentGram → official Telegram** (and back). Read the
   `SG-CALL-RELAY init` line from logcat first: it gives the negotiated
   `version=` (tells us which native impl is live: 2.7.7/5.0.0 = v1,
   7/8/9/12/13 = V2Impl, 10/11 = Reference, 14 = Compat) and each
   endpoint's `tcp=` flag. After the fork patch lands, the call must
   connect (ring → media both ways) where v2.0.15 stuck on
   "Connecting…" ~20s. If it still fails, the version + tcp flags in
   that one line say exactly which patch hunk to suspect.
2. **1:1 call, SilentGram → SilentGram** (both in Pakistan, no VPN).
   Same check; this is the case where both sides relay.
3. **Group call / live stream** (after §7 is built, not before): join from
   Pakistan with no VPN; read the `SG-CALL-RELAY group join
   candidates=[...]` line and confirm media flows.
4. **Regression check outside Pakistan / on VPN**: calls must still work
   exactly as before (the relay is in-path for all 1:1 TCP reflector
   connections once the patch lands; if `sgrelay` is ever down, TCP
   reflector connects fail — the proxy-page status row is the canary).
5. **sgrelay load**: watch VPS CPU/bandwidth during test calls; all 1:1
   media for proxied users now flows through `161.118.196.94:18080`.

## 9. Effort estimates

- Fork patch (§5, P0–P4) + app CMake line (P5): ~0.5–1 day of native work
  (mostly careful, since it cannot be compiled locally), + 1 CI build cycle
  (~40–80 min) + one likely fix-up cycle.
- Shipping (§6): ~1 hour once the PAT holder runs it (fork + repoint +
  push); no new infrastructure.
- Group calls (§7): ~2–3 days including VPS service + API + Java + device
  tests. Separate job, after 1:1 is proven.

## 10. Open questions / risks

- Which tgcalls version the server actually negotiates for 1:1 calls in
  production (the new `version=` log answers this on the next test build;
  the patch covers all four families, so this is diagnostic, not blocking).
- If CI rejects the adapter (includes / `CryptString` API), the fallback
  is to move `AsyncSocks5ProxySocket` into the app's vendored WebRTC
  (`rtc_base/socket_adapters.{h,cc}`, in-repo, normal push) and keep only
  the P1 call-site change in the fork — same behaviour, different split.
- `sgrelay` becomes a single point of failure for 1:1 calls once the patch
  lands (today it is only diagnostics). It already runs with
  `restart=always` semantics as a systemd service; confirm that, and
  consider a second relay host/port as a fallback `Proxy` if it ever goes
  down (native fallback would be a later change: try relay, fall back to
  direct on SOCKS5 failure — currently a SOCKS5 failure fails the TCP
  reflector port, and ICE falls back to the UDP reflector, which is
  blocked in Pakistan but works elsewhere).
