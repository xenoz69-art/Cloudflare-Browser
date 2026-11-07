package com.cloudflare.browser.dns;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class CloudflareDohResolver {
    private static final String DOH_HOST = "cloudflare-dns.com";
    private final String[] resolvers;
    private final SecureRandom random = new SecureRandom();
    private final SSLSocketFactory socketFactory;
    private final DohTransport transport;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CloudflareDohResolver(boolean secondaryFirst) throws IOException {
        this(secondaryFirst, null);
    }

    CloudflareDohResolver(boolean secondaryFirst, DohTransport testTransport) throws IOException {
        resolvers = secondaryFirst ? new String[]{"1.0.0.1", "1.1.1.1"} : new String[]{"1.1.1.1", "1.0.0.1"};
        try { socketFactory = SSLContext.getInstance("TLS").getSocketFactory(); }
        catch (Exception error) { throw new IOException("TLS initialization failed", error); }
        transport = testTransport == null ? this::postOverTls : testTransport;
    }

    public DnsResult resolve(String input) throws IOException {
        String host = normalizeHost(input);
        CacheEntry cached = cache.get(host);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) return new DnsResult(host, cached.addresses, cached.ttl, cached.resolver, true);
        List<String> addresses = new ArrayList<>();
        long ttl = Long.MAX_VALUE;
        Answer a = queryWithFallback(host, 1);
        addresses.addAll(a.addresses);
        if (!a.addresses.isEmpty()) ttl = Math.min(ttl, a.ttl);
        Answer aaaa = queryWithFallback(host, 28);
        addresses.addAll(aaaa.addresses);
        if (!aaaa.addresses.isEmpty()) ttl = Math.min(ttl, aaaa.ttl);
        if (addresses.isEmpty()) throw new java.net.UnknownHostException(host + " has no A or AAAA record");
        long ttlSeconds = ttl == Long.MAX_VALUE ? 0 : ttl;
        if (ttlSeconds > 0) cache.put(host, new CacheEntry(addresses, ttlSeconds, a.resolver, now + ttlSeconds * 1000L));
        return new DnsResult(host, addresses, ttlSeconds, a.resolver, false);
    }

    public List<String> resolveAddresses(String host) throws IOException { return resolve(host).addresses; }
    public void clearCache() { cache.clear(); }
    public int cachedHostCount() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet())
            if (entry.getValue().expiresAt <= now) cache.remove(entry.getKey(), entry.getValue());
        return cache.size();
    }

    private Answer queryWithFallback(String host, int type) throws IOException {
        IOException first;
        try { return query(resolvers[0], host, type); }
        catch (DnsFailure error) {
            if (error.code == 3) return new Answer(Collections.emptyList(), 0, resolvers[0]);
            first = error;
        } catch (IOException error) { first = error; }
        try { return query(resolvers[1], host, type); }
        catch (IOException second) { second.addSuppressed(first); throw second; }
    }

    private Answer query(String resolver, String host, int type) throws IOException {
        byte[] query = createQuery(host, type);
        return parse(transport.post(resolver, query), resolver, host, type, u16(query, 0));
    }

    private byte[] postOverTls(String resolver, byte[] message) throws IOException {
        Socket raw = new Socket();
        SSLSocket tls = null;
        try {
            raw.connect(new InetSocketAddress(resolver, 443), 5000);
            tls = (SSLSocket) socketFactory.createSocket(raw, DOH_HOST, 443, true);
            tls.setSoTimeout(6000);
            tls.startHandshake();
            SSLSession session = tls.getSession();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(DOH_HOST, session)) throw new IOException("Cloudflare certificate hostname did not match");
            BufferedOutputStream output = new BufferedOutputStream(tls.getOutputStream());
            String headers = "POST /dns-query HTTP/1.1\r\nHost: " + DOH_HOST
                    + "\r\nAccept: application/dns-message\r\nContent-Type: application/dns-message"
                    + "\r\nContent-Length: " + message.length + "\r\nConnection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(message); output.flush();
            BufferedInputStream input = new BufferedInputStream(tls.getInputStream());
            String status = readLine(input, 4096);
            if (status == null || !status.matches("HTTP/1\\.[01] 2[0-9]{2}.*")) throw new IOException("Cloudflare DoH HTTP request failed");
            int length = -1; String line;
            while ((line = readLine(input, 8192)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try { length = Integer.parseInt(line.substring(colon + 1).trim()); }
                    catch (NumberFormatException error) { throw new IOException("Invalid DoH response length", error); }
                }
            }
            if (length < 12 || length > 65536) throw new IOException("Invalid DoH response size");
            byte[] response = new byte[length]; int offset = 0;
            while (offset < length) {
                int count = input.read(response, offset, length - offset);
                if (count < 0) throw new IOException("Incomplete DoH response");
                offset += count;
            }
            return response;
        } finally {
            try { if (tls != null) tls.close(); else raw.close(); } catch (IOException ignored) { }
        }
    }

    private static String readLine(BufferedInputStream input, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int previous = -1, current;
        while ((current = input.read()) != -1) {
            bytes.write(current);
            if (bytes.size() > max) throw new IOException("DoH response header is too long");
            if (previous == '\r' && current == '\n') {
                byte[] line = bytes.toByteArray();
                return new String(line, 0, Math.max(0, line.length - 2), StandardCharsets.US_ASCII);
            }
            previous = current;
        }
        return null;
    }

    private byte[] createQuery(String host, int type) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512); DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(random.nextInt(65536)); out.writeShort(0x0100); out.writeShort(1);
        out.writeShort(0); out.writeShort(0); out.writeShort(0);
        for (String label : host.split("\\.")) {
            byte[] value = label.getBytes(StandardCharsets.US_ASCII); out.writeByte(value.length); out.write(value);
        }
        out.writeByte(0); out.writeShort(type); out.writeShort(1); out.flush(); return bytes.toByteArray();
    }

    private static Answer parse(byte[] bytes, String resolver, String expectedHost, int expectedType, int expectedId) throws IOException {
        if (bytes.length < 12 || u16(bytes, 0) != expectedId || (u16(bytes, 2) & 0x8000) == 0) throw new IOException("Invalid DNS response");
        int rcode = u16(bytes, 2) & 15;
        if (rcode == 3) throw new DnsFailure("Hostname does not exist", rcode);
        if (rcode != 0) throw new DnsFailure("DNS response code " + rcode, rcode);
        int questions = u16(bytes, 4), answers = u16(bytes, 6), cursor = 12;
        if (questions != 1) throw new IOException("Unexpected DNS question count");
        NameResult question = readName(bytes, cursor);
        cursor = question.nextOffset;
        if (cursor + 4 > bytes.length || !question.name.equalsIgnoreCase(expectedHost)
                || u16(bytes, cursor) != expectedType || u16(bytes, cursor + 2) != 1)
            throw new IOException("DoH response question did not match request");
        cursor = advance(bytes, cursor, 4);
        List<String> addresses = new ArrayList<>(); long ttl = Long.MAX_VALUE;
        for (int i = 0; i < answers; i++) {
            cursor = skipName(bytes, cursor);
            if (cursor + 10 > bytes.length) throw new IOException("Truncated DNS answer");
            int type = u16(bytes, cursor), recordClass = u16(bytes, cursor + 2);
            long recordTtl = u32(bytes, cursor + 4);
            int length = u16(bytes, cursor + 8), start = cursor + 10, end = advance(bytes, start, length);
            if (recordClass == 1 && type == expectedType && ((type == 1 && length == 4) || (type == 28 && length == 16))) {
                byte[] address = new byte[length]; System.arraycopy(bytes, start, address, 0, length);
                addresses.add(InetAddress.getByAddress(address).getHostAddress()); ttl = Math.min(ttl, recordTtl);
            }
            cursor = end;
        }
        return new Answer(addresses, ttl == Long.MAX_VALUE ? 0 : ttl, resolver);
    }


    private static NameResult readName(byte[] bytes, int offset) throws IOException {
        StringBuilder name = new StringBuilder();
        int cursor = offset, next = -1, jumps = 0, consumed = 0;
        while (cursor < bytes.length) {
            int length = bytes[cursor] & 255;
            if ((length & 0xc0) == 0xc0) {
                if (cursor + 1 >= bytes.length || ++jumps > 16) throw new IOException("Invalid DNS name pointer");
                int pointer = ((length & 0x3f) << 8) | (bytes[cursor + 1] & 255);
                if (pointer >= bytes.length) throw new IOException("DNS name pointer is out of bounds");
                if (next < 0) next = cursor + 2;
                cursor = pointer;
                continue;
            }
            if ((length & 0xc0) != 0 || length > 63) throw new IOException("Invalid DNS label");
            cursor++;
            if (length == 0) return new NameResult(name.toString(), next < 0 ? cursor : next);
            if (cursor + length > bytes.length) throw new IOException("Truncated DNS label");
            if (name.length() > 0) name.append('.');
            name.append(new String(bytes, cursor, length, StandardCharsets.US_ASCII));
            cursor += length;
            consumed += length + 1;
            if (consumed > 255) throw new IOException("DNS name too long");
        }
        throw new IOException("Truncated DNS name");
    }

    private static int skipName(byte[] bytes, int offset) throws IOException {
        int cursor = offset, size = 0;
        while (cursor < bytes.length) {
            int length = bytes[cursor] & 255;
            if ((length & 0xc0) == 0xc0) {
                if (cursor + 1 >= bytes.length) throw new IOException("Truncated DNS pointer");
                return cursor + 2;
            }
            if (length == 0) return cursor + 1;
            if ((length & 0xc0) != 0 || length > 63) throw new IOException("Invalid DNS label");
            cursor = advance(bytes, cursor + 1, length); size += length;
            if (size > 255) throw new IOException("DNS name too long");
        }
        throw new IOException("Truncated DNS name");
    }

    private static int advance(byte[] bytes, int offset, int amount) throws IOException {
        if (amount < 0 || offset < 0 || offset + amount > bytes.length) throw new IOException("Truncated DNS message");
        return offset + amount;
    }
    private static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }
    private static long u32(byte[] b, int i) { return ((long)(b[i]&255)<<24)|((long)(b[i+1]&255)<<16)|((long)(b[i+2]&255)<<8)|(long)(b[i+3]&255); }

    public static String normalizeHost(String input) throws IOException {
        if (input == null) throw new IOException("Hostname is empty");
        String host = input.trim(); if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253 || host.indexOf('/') >= 0 || host.indexOf('@') >= 0) throw new IOException("Invalid hostname");
        try { host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid hostname", error); }
        if (host.length() > 253) throw new IOException("Invalid hostname");
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) throw new IOException("Local hostnames are blocked by the proxy");
        for (String label : host.split("\\.")) if (label.isEmpty() || label.length() > 63) throw new IOException("Invalid hostname");
        return host;
    }

    public static final class DnsResult {
        public final String hostname, resolver; public final List<String> addresses;
        public final long ttlSeconds; public final boolean fromCache;
        DnsResult(String host, List<String> ips, long ttl, String resolver, boolean cached) {
            hostname = host; addresses = Collections.unmodifiableList(new ArrayList<>(ips)); ttlSeconds = ttl; this.resolver = resolver; fromCache = cached;
        }
    }
    interface DohTransport { byte[] post(String resolver, byte[] message) throws IOException; }

    private static final class NameResult { final String name; final int nextOffset; NameResult(String name, int next) { this.name = name; nextOffset = next; } }
    private static final class Answer {
        final List<String> addresses; final long ttl; final String resolver;
        Answer(List<String> ips, long ttl, String resolver) { addresses = ips; this.ttl = ttl; this.resolver = resolver; }
    }
    private static final class CacheEntry {
        final List<String> addresses; final long ttl, expiresAt; final String resolver;
        CacheEntry(List<String> ips, long ttl, String resolver, long expiresAt) { addresses = new ArrayList<>(ips); this.ttl = ttl; this.resolver = resolver; this.expiresAt = expiresAt; }
    }
    private static final class DnsFailure extends IOException {
        final int code; DnsFailure(String message, int code) { super(message); this.code = code; }
    }
}
