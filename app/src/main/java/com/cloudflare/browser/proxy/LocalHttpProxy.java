package com.cloudflare.browser.proxy;

import com.cloudflare.browser.dns.CloudflareDohResolver;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalHttpProxy implements AutoCloseable {
    private static final int HEADER_LIMIT = 32768;
    private static final int SOCKET_TIMEOUT_MS = 15000;
    interface AddressResolver {
        List<String> resolve(String hostname) throws IOException;
        default void clearCache() { }
    }

    private final AddressResolver resolver;
    private final boolean allowLocalDestinations;
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong tunnelCount = new AtomicLong();
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<>());
    private ServerSocket listener;
    private ExecutorService workers;
    private Thread acceptThread;
    private volatile boolean running;

    public LocalHttpProxy(CloudflareDohResolver resolver) {
        this(new AddressResolver() {
            @Override public List<String> resolve(String hostname) throws IOException { return resolver.resolveAddresses(hostname); }
            @Override public void clearCache() { resolver.clearCache(); }
        }, false);
    }

    LocalHttpProxy(AddressResolver resolver, boolean allowLocalDestinations) {
        this.resolver = resolver;
        this.allowLocalDestinations = allowLocalDestinations;
    }

    public synchronized int start() throws IOException {
        if (running) return listener.getLocalPort();
        listener = new ServerSocket();
        listener.setReuseAddress(false);
        listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 32);
        workers = new ThreadPoolExecutor(2, 24, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(96), new ThreadPoolExecutor.AbortPolicy());
        running = true;
        acceptThread = new Thread(this::acceptConnections, "cf-browser-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return listener.getLocalPort();
    }

    public boolean isRunning() { return running && listener != null && !listener.isClosed(); }
    public int getPort() throws IOException {
        if (!isRunning()) throw new IOException("Proxy is not running");
        return listener.getLocalPort();
    }
    public long getRequestCount() { return requestCount.get(); }
    public long getTunnelCount() { return tunnelCount.get(); }
    public void clearDnsCache() { resolver.clearCache(); }

    private void acceptConnections() {
        while (running) {
            try {
                Socket client = listener.accept();
                if (!client.getInetAddress().isLoopbackAddress()) {
                    client.close();
                    continue;
                }
                clients.add(client);
                try { workers.execute(() -> handle(client)); }
                catch (RuntimeException rejected) { clients.remove(client); client.close(); }
            } catch (IOException error) {
                if (running) stop();
            }
        }
    }

    private void handle(Socket client) {
        try (Socket closeClient = client) {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream());
            byte[] rawHeaders = readHeaderBlock(input);
            if (rawHeaders == null) return;
            Request request;
            try { request = parseRequest(rawHeaders); }
            catch (IOException error) { writeResponse(output, 400, "Malformed proxy request"); return; }
            if (request.method.equalsIgnoreCase("CONNECT")) {
                if (request.chunked || request.contentLength != 0) { writeResponse(output, 400, "CONNECT request body is not supported"); return; }
                handleConnect(client, input, output, request.target);
            } else {
                handleHttp(client, input, output, request, rawHeaders);
            }
        } catch (Exception ignored) {
            try { client.close(); } catch (IOException closeError) { }
        } finally {
            clients.remove(client);
        }
    }

    private void handleConnect(Socket client, BufferedInputStream clientInput,
                               BufferedOutputStream clientOutput, String authority) throws IOException {
        HostPort destination;
        try { destination = HostPort.parse(authority, 443); }
        catch (IOException error) { writeResponse(clientOutput, 400, "Invalid CONNECT authority"); return; }
        Socket upstream;
        try { upstream = connect(destination.host, destination.port); }
        catch (IOException error) { writeResponse(clientOutput, 502, "DNS lookup or upstream connection failed"); return; }
        try {
            clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            clientOutput.flush();
            tunnelCount.incrementAndGet();
            client.setSoTimeout(0);
            upstream.setSoTimeout(0);
            relayTunnel(client, clientInput, clientOutput, upstream, new BufferedInputStream(upstream.getInputStream()));
        } finally {
            try { upstream.close(); } catch (IOException ignored) { }
        }
    }

    private void handleHttp(Socket client, BufferedInputStream clientInput, BufferedOutputStream clientOutput,
                            Request request, byte[] rawHeaders) throws IOException {
        URI target;
        try {
            String requestTarget = request.target;
            if (requestTarget.regionMatches(true, 0, "ws://", 0, 5)) requestTarget = "http" + requestTarget.substring(2);
            target = URI.create(requestTarget);
            if (!target.isAbsolute()) {
                String host = request.hostHeader;
                if (host == null) throw new IllegalArgumentException("Host header is missing");
                target = URI.create("http://" + host + requestTarget);
            }
        } catch (IllegalArgumentException error) {
            writeResponse(clientOutput, 400, "Invalid HTTP request target");
            return;
        }
        if (!("http".equalsIgnoreCase(target.getScheme()) || "ws".equalsIgnoreCase(target.getScheme()))
                || target.getHost() == null || target.getUserInfo() != null) {
            writeResponse(clientOutput, 400, "Only valid HTTP or WebSocket proxy requests are supported");
            return;
        }
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
        int port = target.getPort() < 0 ? 80 : target.getPort();
        Socket upstream;
        try { upstream = connect(target.getHost(), port); }
        catch (IOException error) { writeResponse(clientOutput, 502, "DNS lookup or upstream connection failed"); return; }
        try (Socket closeUpstream = upstream) {
            BufferedOutputStream outgoing = new BufferedOutputStream(upstream.getOutputStream());
            BufferedInputStream upstreamInput = new BufferedInputStream(upstream.getInputStream());
            String[] lines = new String(rawHeaders, StandardCharsets.ISO_8859_1).split("\r\n");
            outgoing.write((request.method + " " + path + " " + request.version + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                if (name.equalsIgnoreCase("Proxy-Connection") || name.equalsIgnoreCase("Proxy-Authorization")
                        || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Host")
                        || name.equalsIgnoreCase("Expect")) continue;
                if (request.websocket && name.equalsIgnoreCase("Upgrade")) continue;
                outgoing.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            }
            outgoing.write(("Host: " + target.getRawAuthority() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            if (request.websocket) outgoing.write("Connection: Upgrade\r\nUpgrade: websocket\r\n".getBytes(StandardCharsets.US_ASCII));
            else outgoing.write("Connection: close\r\n".getBytes(StandardCharsets.US_ASCII));
            outgoing.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            outgoing.flush();
            if (request.expectContinue) {
                clientOutput.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                clientOutput.flush();
            }
            if (request.chunked) copyChunkedBody(clientInput, outgoing);
            else if (request.contentLength > 0) copyExactly(clientInput, outgoing, request.contentLength);
            outgoing.flush();
            if (request.websocket) {
                byte[] responseHeaders = readHeaderBlock(upstreamInput);
                if (responseHeaders == null) throw new IOException("WebSocket upstream closed before handshake");
                clientOutput.write(responseHeaders); clientOutput.flush();
                String statusLine = new String(responseHeaders, StandardCharsets.ISO_8859_1).split("\r\n", 2)[0];
                if (statusLine.matches("HTTP/1\\.[01] 101(?: .*)?")) {
                    tunnelCount.incrementAndGet();
                    relayTunnel(client, clientInput, clientOutput, upstream, upstreamInput);
                    return;
                }
            }
            copy(upstreamInput, clientOutput);
        }
    }

    private void relayTunnel(Socket client, BufferedInputStream clientInput, BufferedOutputStream clientOutput,
                             Socket upstream, BufferedInputStream upstreamInput) throws IOException {
        client.setSoTimeout(0);
        upstream.setSoTimeout(0);
        Thread upstreamToClient = new Thread(() -> copyAndClose(upstreamInput, upstream, client, clientOutput), "cf-browser-proxy-tunnel");
        upstreamToClient.setDaemon(true);
        upstreamToClient.start();
        try { copy(clientInput, upstream.getOutputStream()); }
        finally {
            try { upstream.close(); } catch (IOException ignored) { }
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private static void copyChunkedBody(BufferedInputStream input, BufferedOutputStream output) throws IOException {
        long totalBody = 0, totalFraming = 0;
        int chunks = 0;
        while (true) {
            if (++chunks > 100000) throw new IOException("Too many HTTP chunks");
            byte[] sizeLine = readCrlfLine(input, 8192);
            totalFraming += sizeLine.length + 2L;
            if (totalFraming > HEADER_LIMIT * 32L) throw new IOException("Chunk framing exceeded its limit");
            String line = new String(sizeLine, StandardCharsets.US_ASCII);
            int extension = line.indexOf(';');
            String hex = (extension < 0 ? line : line.substring(0, extension)).trim();
            if (!hex.matches("[0-9A-Fa-f]{1,16}")) throw new IOException("Invalid HTTP chunk size");
            long size;
            try { size = Long.parseLong(hex, 16); }
            catch (NumberFormatException error) { throw new IOException("Invalid HTTP chunk size", error); }
            output.write(sizeLine); output.write('\r'); output.write('\n');
            if (size == 0) {
                int trailerBytes = 0;
                while (true) {
                    byte[] trailer = readCrlfLine(input, HEADER_LIMIT);
                    trailerBytes += trailer.length + 2;
                    if (trailerBytes > HEADER_LIMIT) throw new IOException("HTTP chunk trailers exceeded their limit");
                    if (trailer.length == 0) { output.write('\r'); output.write('\n'); return; }
                    String trailerLine = new String(trailer, StandardCharsets.ISO_8859_1);
                    int colon = trailerLine.indexOf(':');
                    if (colon <= 0 || Character.isWhitespace(trailerLine.charAt(0))) throw new IOException("Malformed HTTP chunk trailer");
                    String name = trailerLine.substring(0, colon).trim();
                    if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || name.equalsIgnoreCase("Content-Length")
                            || name.equalsIgnoreCase("Transfer-Encoding") || name.equalsIgnoreCase("Host")
                            || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Trailer"))
                        throw new IOException("Forbidden HTTP chunk trailer");
                    output.write(trailer); output.write('\r'); output.write('\n');
                }
            }
            if (size > 64L * 1024 * 1024 - totalBody) throw new IOException("Chunked request body exceeded its limit");
            totalBody += size;
            copyExactly(input, output, size);
            int cr = input.read(), lf = input.read();
            if (cr != '\r' || lf != '\n') throw new IOException("Malformed HTTP chunk terminator");
            output.write('\r'); output.write('\n');
        }
    }

    private static byte[] readCrlfLine(BufferedInputStream input, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= limit) {
            int value = input.read();
            if (value < 0) throw new IOException("Truncated HTTP chunk framing");
            if (value == '\n') throw new IOException("Malformed HTTP line ending");
            if (value == '\r') {
                int lf = input.read();
                if (lf != '\n') throw new IOException("Malformed HTTP line ending");
                return line.toByteArray();
            }
            if (value < 0x20 && value != '\t') throw new IOException("Control character in HTTP chunk framing");
            line.write(value);
        }
        throw new IOException("HTTP chunk line exceeded its limit");
    }

    private Socket connect(String host, int port) throws IOException {
        List<String> addresses = new ArrayList<>();
        if (isIpLiteral(host)) addresses.add(host);
        else addresses.addAll(resolver.resolve(host));
        IOException last = null;
        for (String address : addresses) {
            InetAddress resolved = InetAddress.getByName(address);
            if (!allowLocalDestinations && isLocalOrReserved(resolved)) { last = new IOException("Local and reserved network addresses are blocked"); continue; }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(resolved, port), 10000);
                socket.setSoTimeout(30000);
                requestCount.incrementAndGet();
                return socket;
            } catch (IOException error) {
                last = error;
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
        if (last != null) throw last;
        throw new IOException("No public IP address was returned by Cloudflare DoH");
    }

    private static boolean isIpLiteral(String host) {
        if (host == null) return false;
        if (host.indexOf(':') >= 0) return true;
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] octets = host.split("\\.");
        for (String octet : octets) {
            try { if (Integer.parseInt(octet) > 255) return false; }
            catch (NumberFormatException error) { return false; }
        }
        return true;
    }

    static boolean isLocalOrReserved(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            if (bytes.length == 16 && isIpv4Mapped(bytes)) {
                try {
                    byte[] ipv4 = new byte[4];
                    System.arraycopy(bytes, 12, ipv4, 0, 4);
                    return isLocalOrReserved(InetAddress.getByAddress(ipv4));
                } catch (IOException ignored) { return true; }
            }
            int first = bytes[0] & 255;
            return (first & 0xfe) == 0xfc || (first == 0x20 && (bytes[1] & 255) == 0x01
                    && (((bytes[2] & 255) == 0x00 && (bytes[3] & 255) == 0x00)
                    || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)));
        }
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a == 0 || a == 10 || a == 127 || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && (b == 0 || b == 168)) || (a == 198 && (b == 18 || b == 19 || b == 51))
                || (a == 203 && b == 0) || a >= 224;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) if (bytes[index] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static byte[] readHeaderBlock(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        while (out.size() < HEADER_LIMIT) {
            int value = input.read();
            if (value < 0) return out.size() == 0 ? null : out.toByteArray();
            out.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) return out.toByteArray();
        }
        throw new IOException("Proxy header exceeded its limit");
    }

    private static Request parseRequest(byte[] bytes) throws IOException {
        String headerText = new String(bytes, StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        String[] start = lines[0].trim().split(" +", 3);
        if (start.length != 3 || !start[2].matches("HTTP/1\\.[01]")) throw new IOException("Invalid proxy request line");
        String host = null, connection = "", upgrade = "";
        long contentLength = 0;
        boolean chunked = false, sawLength = false, sawHost = false, sawTransferEncoding = false, expectContinue = false;
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) continue;
            int colon = lines[index].indexOf(':');
            if (colon <= 0 || Character.isWhitespace(lines[index].charAt(0))) throw new IOException("Malformed proxy header");
            String fieldName = lines[index].substring(0, colon);
            if (!fieldName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IOException("Malformed proxy header name");
            String name = fieldName;
            String value = lines[index].substring(colon + 1).trim();
            for (int character = 0; character < value.length(); character++) {
                char c = value.charAt(character);
                if ((c < 0x20 && c != '\t') || c == 0x7f) throw new IOException("Control character in proxy header");
            }
            if (name.equalsIgnoreCase("Host")) {
                if (sawHost) throw new IOException("Duplicate Host header");
                sawHost = true; host = value;
            }
            if (name.equalsIgnoreCase("Content-Length")) {
                if (sawLength) throw new IOException("Duplicate Content-Length header");
                sawLength = true;
                try { contentLength = Long.parseLong(value); }
                catch (NumberFormatException error) { throw new IOException("Invalid content length", error); }
                if (contentLength < 0 || contentLength > 64L * 1024 * 1024) throw new IOException("Request body exceeded its limit");
            }
            if (name.equalsIgnoreCase("Transfer-Encoding")) {
                if (sawTransferEncoding || !value.equalsIgnoreCase("chunked")) throw new IOException("Unsupported Transfer-Encoding");
                sawTransferEncoding = true; chunked = true;
            }
            if (name.equalsIgnoreCase("Connection")) connection += "," + value;
            if (name.equalsIgnoreCase("Upgrade")) {
                if (!upgrade.isEmpty()) throw new IOException("Duplicate Upgrade header");
                upgrade = value;
            }
            if (name.equalsIgnoreCase("Expect")) {
                if (!value.equalsIgnoreCase("100-continue")) throw new IOException("Unsupported expectation");
                expectContinue = true;
            }
        }
        if (chunked && sawLength) throw new IOException("Content-Length with Transfer-Encoding is forbidden");
        boolean connectionUpgrade = hasToken(connection, "upgrade");
        boolean hasUpgrade = !upgrade.isEmpty();
        if (connectionUpgrade != hasUpgrade) throw new IOException("Invalid HTTP Upgrade headers");
        boolean websocket = connectionUpgrade && upgrade.equalsIgnoreCase("websocket");
        if (hasUpgrade && !websocket) throw new IOException("Unsupported HTTP Upgrade protocol");
        if (websocket && (!start[0].equalsIgnoreCase("GET") || chunked || contentLength != 0 || expectContinue))
            throw new IOException("Invalid WebSocket handshake request");
        return new Request(start[0], start[1], start[2], host, contentLength, chunked, websocket, expectContinue);
    }

    private static boolean hasToken(String commaList, String token) {
        for (String item : commaList.split(",")) if (item.trim().equalsIgnoreCase(token)) return true;
        return false;
    }

    private static void copyExactly(BufferedInputStream input, BufferedOutputStream output, long bytes) throws IOException {
        byte[] buffer = new byte[8192]; long remaining = bytes;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Truncated HTTP request body");
            output.write(buffer, 0, count); remaining -= count;
        }
    }

    private static void copy(java.io.InputStream source, java.io.OutputStream target) throws IOException {
        byte[] buffer = new byte[16384]; int count;
        while ((count = source.read(buffer)) >= 0) { target.write(buffer, 0, count); target.flush(); }
    }

    private static void copyAndClose(Socket source, Socket target) {
        try { copy(source.getInputStream(), target.getOutputStream()); }
        catch (IOException ignored) { }
        finally { try { target.close(); } catch (IOException ignored) { } try { source.close(); } catch (IOException ignored) { } }
    }

    private static void copyAndClose(BufferedInputStream sourceInput, Socket source, Socket target, BufferedOutputStream targetOutput) {
        try { copy(sourceInput, targetOutput); }
        catch (IOException ignored) { }
        finally { try { target.close(); } catch (IOException ignored) { } try { source.close(); } catch (IOException ignored) { } }
    }

    private static void writeResponse(BufferedOutputStream output, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " Error\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }

    private synchronized void stop() {
        running = false;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        synchronized (clients) {
            for (Socket client : clients) try { client.close(); } catch (IOException ignored) { }
            clients.clear();
        }
        if (workers != null) workers.shutdownNow();
    }

    @Override public void close() { stop(); }

    private static final class Request {
        final String method, target, version, hostHeader;
        final long contentLength;
        final boolean chunked, websocket, expectContinue;
        Request(String method, String target, String version, String host, long length, boolean chunked, boolean websocket, boolean expectContinue) {
            this.method = method; this.target = target; this.version = version; hostHeader = host; contentLength = length;
            this.chunked = chunked; this.websocket = websocket; this.expectContinue = expectContinue;
        }
    }

    private static final class HostPort {
        final String host; final int port;
        HostPort(String host, int port) { this.host = host; this.port = port; }
        static HostPort parse(String value, int fallbackPort) throws IOException {
            if (value == null || value.length() > 1024 || value.indexOf('@') >= 0) throw new IOException("Invalid CONNECT authority");
            String host; int port = fallbackPort;
            if (value.startsWith("[")) {
                int end = value.indexOf(']');
                if (end < 0) throw new IOException("Invalid IPv6 CONNECT authority");
                host = value.substring(1, end);
                if (host.indexOf(':') < 0 || (end + 1 < value.length() && value.charAt(end + 1) != ':')) throw new IOException("Invalid IPv6 CONNECT authority");
                if (end + 1 < value.length()) port = parsePort(value.substring(end + 2));
            } else {
                int colon = value.lastIndexOf(':');
                if (colon > 0 && value.indexOf(':') == colon) { host = value.substring(0, colon); port = parsePort(value.substring(colon + 1)); }
                else host = value;
            }
            if (port < 1 || port > 65535) throw new IOException("Invalid CONNECT port");
            if (!isIpLiteral(host)) host = CloudflareDohResolver.normalizeHost(host);
            return new HostPort(host, port);
        }
        private static int parsePort(String value) throws IOException {
            try { return Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new IOException("Invalid CONNECT port", error); }
        }
    }
}
