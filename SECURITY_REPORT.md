# Security review

## Scope and threat model

Reviewed the DNS resolver, loopback proxy, HTTP request forwarding, chunked upload framing, WebSocket upgrade relay, CONNECT byte tunnel, WebView proxy setup/teardown, and download proxy lifecycle. Threats include hostile sites, malformed request framing, local-network access/SSRF, DNS fallback leaks, local clients reaching the proxy, on-path observation, invalid TLS certificates, and proxy lifecycle races.

The app does not claim anonymity. The visited site sees browser traffic, and Cloudflare sees names queried through DoH. HTTP remains unencrypted. This is a source review, not a penetration test.

## Controls observed in code

- The app shell alone receives the native JavaScript bridge. External WebViews disallow file/content access and mixed content; SSL errors are canceled.
- The local HTTP proxy binds to IPv4 loopback, limits workers, queue depth, header size, body size, and socket timeouts. It rejects malformed/ambiguous Host, Content-Length, and Transfer-Encoding framing.
- Chunked requests are validated and relayed with their chunk framing/trailers intact. TE+CL and unsupported transfer codings are rejected. Chunk count, aggregate body, framing, trailer sizes, and forbidden trailer names are bounded.
- WebSocket GET upgrade headers are reconstructed and the upgraded connection is relayed bidirectionally. The WebSocket and CONNECT tunnels preserve opaque bytes; HTTPS TLS is not terminated by this proxy.
- CONNECT names are resolved through the injected DoH resolver. Ordinary hostnames go through DoH; numeric IP literals bypass DNS by design. The resolver uses fixed Cloudflare IPs, TLS hostname verification for `cloudflare-dns.com`, query-ID/question checks, A/AAAA lookups, and primary/secondary retry for transport and DNS errors other than NXDOMAIN.
- DNS mode removes implicit WebView proxy bypass rules, installs only the loopback proxy rule, waits for `ProxyController` callbacks, and navigation is blocked if the route is unavailable. There is no configured direct proxy fallback. The AndroidX callback and emulator routing still need execution in CI.
- Proxy shutdown on Activity destruction now waits for the asynchronous WebView override-clear callback before closing the WebView proxy listener.

## Findings and limits

1. **High — no executed Android build or integration evidence.** Termux has no Java, Gradle, SDK, adb, or emulator. Java tests and the newly configured GitHub Actions workflow remain unexecuted. Do not represent proxy, TLS, or WebView behavior as verified until CI passes.
2. **High — DNS leak behavior is code-reviewed, not device-verified.** WebView traffic is configured to use a single explicit proxy rule with implicit bypass removed and no direct rule, while the proxy resolves target hostnames through DoH. Actual WebView routing, platform-specific proxy behavior, VPN/private-DNS interaction, and unsupported protocol behavior need emulator/device tests. Numeric IP destinations do not require DNS.
3. **Medium — public/reserved address policy is hand-maintained.** Common private, loopback, link-local, CGNAT, multicast, documentation, benchmarking, and IPv4-mapped IPv6 destinations are blocked. This list is not a complete or automatically maintained IANA special-purpose registry; review it as part of future security maintenance.
4. **Medium — HTTPS tunnel test is not a real TLS test.** Current unit coverage sends TLS-like bytes through CONNECT and asserts byte transparency. It does not validate certificate negotiation or a browser HTTPS page.
5. **Medium — integration tests are newly added but unrun.** Unit tests cover resolver fallback/cache/parser edges, chunked framing, WebSocket frames, CONNECT relay, and request smuggling. CI also contains an emulator WebView test for HTTP plus WebSocket routing. The GitHub Actions result is required before claiming these pass.
6. **Low — protocol limits and connection semantics.** Chunked upload forwarding preserves framing and bounds resource use. Proxy response streaming closes after ordinary HTTP responses; HTTP keep-alive/pipelining is not implemented. Unsupported expectations/upgrade protocols fail closed.

The current DNS mode is an opt-in compatibility/privacy measure, not a system-wide DNS firewall. No release security claim should be made before a successful CI build, instrumentation run, and physical-device validation.
