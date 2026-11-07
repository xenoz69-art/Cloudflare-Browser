package com.cloudflare.browser.proxy;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LocalHttpProxyTest {
    private ServerSocket origin;
    private ExecutorService originWorker;
    private LocalHttpProxy proxy;

    @Before public void setUp() throws Exception {
        origin = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        origin.setSoTimeout(5000);
        originWorker = Executors.newCachedThreadPool();
        proxy = new LocalHttpProxy(host -> Collections.singletonList("127.0.0.1"), true);
        proxy.start();
    }

    @After public void tearDown() throws Exception {
        if (proxy != null) proxy.close();
        if (origin != null) origin.close();
        if (originWorker != null) originWorker.shutdownNow();
    }

    @Test public void forwardsChunkedBodyAndTrailersWithoutChangingFraming() throws Exception {
        Future<String[]> upstream = originWorker.submit(() -> {
            try (Socket socket = origin.accept()) {
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                String headers = new String(readHeader(input), StandardCharsets.ISO_8859_1);
                StringBuilder body = new StringBuilder();
                while (true) {
                    String sizeLine = readLine(input);
                    body.append(sizeLine).append("\r\n");
                    int size = Integer.parseInt(sizeLine.split(";", 2)[0], 16);
                    if (size == 0) {
                        String trailer;
                        while (!(trailer = readLine(input)).isEmpty()) body.append(trailer).append("\r\n");
                        body.append("\r\n");
                        break;
                    }
                    body.append(new String(readExactly(input, size), StandardCharsets.US_ASCII)).append("\r\n");
                    assertEquals(13, input.read()); assertEquals(10, input.read());
                }
                writeResponse(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK");
                return new String[]{headers, body.toString()};
            }
        });

        String request = "POST http://upload.test:" + origin.getLocalPort() + "/upload HTTP/1.1\r\n"
                + "Host: upload.test:" + origin.getLocalPort() + "\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n"
                + "4;part=one\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Upload-Check: passed\r\n\r\n";
        String response = sendHttp(request, true);
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response.endsWith("OK"));
        String[] seen = upstream.get();
        assertTrue(seen[0].toLowerCase().contains("transfer-encoding: chunked"));
        assertTrue(seen[0].contains("Host: upload.test:" + origin.getLocalPort()));
        assertFalse(seen[0].toLowerCase().contains("connection: keep-alive"));
        assertEquals("4;part=one\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Upload-Check: passed\r\n\r\n", seen[1]);
    }

    @Test public void relaysWebSocketUpgradeAndFramesBidirectionally() throws Exception {
        Future<String[]> upstream = originWorker.submit(() -> {
            try (Socket socket = origin.accept()) {
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                String headers = new String(readHeader(input), StandardCharsets.ISO_8859_1);
                String key = headerValue(headers, "Sec-WebSocket-Key");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                writeResponse(socket, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
                byte[] frame = readExactly(input, 11);
                int length = frame[1] & 0x7f;
                byte[] mask = new byte[]{frame[2], frame[3], frame[4], frame[5]};
                byte[] message = new byte[length];
                for (int i = 0; i < length; i++) message[i] = (byte) (frame[6 + i] ^ mask[i % 4]);
                OutputStreamWriter.writeFrame(socket, message);
                return new String[]{headers, new String(message, StandardCharsets.UTF_8)};
            }
        });

        String key = Base64.getEncoder().encodeToString("local-websocket-test".getBytes(StandardCharsets.US_ASCII));
        try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
            client.setSoTimeout(5000);
            String request = "GET ws://socket.test:" + origin.getLocalPort() + "/socket HTTP/1.1\r\n"
                    + "Host: socket.test:" + origin.getLocalPort() + "\r\n"
                    + "Upgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            String responseHeaders = new String(readHeader(client.getInputStream()), StandardCharsets.ISO_8859_1);
            assertTrue(responseHeaders.startsWith("HTTP/1.1 101"));
            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] mask = new byte[]{1, 2, 3, 4};
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81); frame.write(0x80 | payload.length); frame.write(mask);
            for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i % 4]);
            client.getOutputStream().write(frame.toByteArray()); client.getOutputStream().flush();
            InputStream response = client.getInputStream();
            assertEquals(0x81, response.read());
            int size = response.read();
            assertEquals(payload.length, size & 0x7f);
            assertArrayEquals(payload, readExactly(response, payload.length));
        }
        String[] seen = upstream.get();
        assertEquals("hello", seen[1]);
        assertTrue(seen[0].contains("Connection: Upgrade"));
        assertTrue(seen[0].contains("Upgrade: websocket"));
        assertEquals(1, proxy.getTunnelCount());
    }

    @Test public void connectTunnelPassesOpaqueTlsBytesUnchanged() throws Exception {
        Future<byte[]> upstream = originWorker.submit(() -> {
            try (Socket socket = origin.accept()) {
                socket.setSoTimeout(5000);
                byte[] bytes = readExactly(socket.getInputStream(), 8);
                socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
                return bytes;
            }
        });
        byte[] clientHelloPrefix = new byte[]{0x16, 0x03, 0x01, 0x00, 0x03, 0x01, 0x02, 0x03};
        try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
            client.setSoTimeout(5000);
            String connect = "CONNECT 127.0.0.1:" + origin.getLocalPort() + " HTTP/1.1\r\nHost: 127.0.0.1:" + origin.getLocalPort() + "\r\n\r\n";
            client.getOutputStream().write(connect.getBytes(StandardCharsets.US_ASCII)); client.getOutputStream().flush();
            String response = new String(readHeader(client.getInputStream()), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 200 Connection Established"));
            client.getOutputStream().write(clientHelloPrefix); client.getOutputStream().flush();
            assertArrayEquals(clientHelloPrefix, readExactly(client.getInputStream(), clientHelloPrefix.length));
        }
        assertArrayEquals(clientHelloPrefix, upstream.get());
        assertEquals(1, proxy.getTunnelCount());
    }

    @Test public void rejectsContentLengthAndTransferEncodingSmuggling() throws Exception {
        String request = "POST http://upload.test:" + origin.getLocalPort() + "/ HTTP/1.1\r\nHost: upload.test\r\n"
                + "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n";
        String response = sendHttp(request, false);
        assertTrue(response.startsWith("HTTP/1.1 400"));
        assertEquals(0, proxy.getRequestCount());
    }

    @Test public void deniesLoopbackDestinationsInProductionPolicy() throws Exception {
        assertTrue(LocalHttpProxy.isLocalOrReserved(InetAddress.getByName("127.0.0.1")));
        assertTrue(LocalHttpProxy.isLocalOrReserved(InetAddress.getByName("10.10.0.4")));
        assertTrue(LocalHttpProxy.isLocalOrReserved(InetAddress.getByName("100.64.0.1")));
        assertFalse(LocalHttpProxy.isLocalOrReserved(InetAddress.getByName("8.8.8.8")));
    }

    private String sendHttp(String request, boolean shutdownOutput) throws Exception {
        try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
            client.setSoTimeout(5000);
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            if (shutdownOutput) client.shutdownOutput();
            return new String(readAll(client.getInputStream()), StandardCharsets.ISO_8859_1);
        }
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int state = 0, value;
        while ((value = input.read()) >= 0) {
            bytes.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) return bytes.toByteArray();
        }
        throw new IOException("EOF before HTTP headers completed");
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value, previous = -1;
        while ((value = input.read()) >= 0) {
            if (previous == '\r' && value == '\n') {
                byte[] data = bytes.toByteArray();
                return new String(data, 0, data.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(value); previous = value;
        }
        throw new IOException("EOF in chunk line");
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] result = new byte[length]; int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new IOException("Truncated test payload");
            offset += count;
        }
        return result;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
        while ((count = input.read(buffer)) >= 0) result.write(buffer, 0, count);
        return result.toByteArray();
    }

    private static void writeResponse(Socket socket, String response) throws IOException {
        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
    }

    private static String headerValue(String headers, String wanted) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(wanted)) return line.substring(colon + 1).trim();
        }
        throw new AssertionError("Missing " + wanted);
    }

    private static final class OutputStreamWriter {
        static void writeFrame(Socket socket, byte[] payload) throws IOException {
            socket.getOutputStream().write(new byte[]{(byte) 0x81, (byte) payload.length});
            socket.getOutputStream().write(payload); socket.getOutputStream().flush();
        }
    }
}
