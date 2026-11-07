package com.cloudflare.browser.proxy;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WebViewProxyInstrumentedTest {
    private LocalHttpProxy proxy;
    private WebView webView;
    private ServerSocket origin;
    private volatile boolean servedPage;
    private volatile boolean servedWebSocket;

    @After public void tearDown() throws Exception {
        if (webView != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView.stopLoading(); webView.destroy();
        });
        CountDownLatch cleared = new CountDownLatch(1);
        try { ProxyController.getInstance().clearProxyOverride(Runnable::run, cleared::countDown); cleared.await(5, TimeUnit.SECONDS); }
        catch (RuntimeException ignored) { }
        if (proxy != null) proxy.close();
        if (origin != null) origin.close();
    }

    @Test public void webViewHttpAndWebSocketUseDohProxy() throws Exception {
        Assume.assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE));
        origin = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        origin.setSoTimeout(10000);
        AtomicReference<String> resolved = new AtomicReference<>();
        proxy = new LocalHttpProxy(host -> { resolved.set(host); return Collections.singletonList("127.0.0.1"); }, true);
        int proxyPort = proxy.start();
        CountDownLatch proxySet = new CountDownLatch(1);
        ProxyController.getInstance().setProxyOverride(new ProxyConfig.Builder().removeImplicitRules()
                .addProxyRule("http://127.0.0.1:" + proxyPort).build(), Runnable::run, proxySet::countDown);
        assertTrue("ProxyController callback", proxySet.await(10, TimeUnit.SECONDS));

        Thread originThread = new Thread(this::servePageAndWebSocket, "instrumented-origin");
        originThread.start();
        CountDownLatch result = new CountDownLatch(1);
        AtomicReference<String> jsResult = new AtomicReference<>();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface public void done(String value) { jsResult.set(value); result.countDown(); }
            }, "TestBridge");
            webView.loadUrl("http://site.test:" + origin.getLocalPort() + "/");
        });
        assertTrue("WebView HTTP and WebSocket completion", result.await(20, TimeUnit.SECONDS));
        originThread.join(2000);
        assertEquals("http-ok|ws-echo", jsResult.get());
        assertTrue("HTTP request traversed proxy", proxy.getRequestCount() >= 2);
        assertEquals("WebSocket upgraded through proxy", 1, proxy.getTunnelCount());
        assertEquals("hostname resolved by injected DoH resolver", "site.test", resolved.get());
        assertTrue(servedPage && servedWebSocket);
    }

    private void servePageAndWebSocket() {
        try (Socket page = origin.accept()) {
            page.setSoTimeout(10000); readHeaders(page.getInputStream()); servedPage = true;
            String html = "<script>const ws=new WebSocket('ws://site.test:" + origin.getLocalPort()
                    + "/socket');ws.onopen=()=>ws.send('ws-ping');ws.onmessage=e=>TestBridge.done('http-ok|'+e.data);</script>";
            write(page, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + html.getBytes(StandardCharsets.UTF_8).length
                    + "\r\nConnection: close\r\n\r\n" + html);
        } catch (Exception error) { return; }
        try (Socket socket = origin.accept()) {
            socket.setSoTimeout(10000);
            String headers = new String(readHeaders(socket.getInputStream()), StandardCharsets.ISO_8859_1);
            String key = header(headers, "Sec-WebSocket-Key");
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            write(socket, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
            InputStream input = socket.getInputStream(); int first = input.read(), second = input.read();
            int length = second & 0x7f; byte[] mask = readExactly(input, 4), payload = readExactly(input, length);
            for (int i = 0; i < length; i++) payload[i] ^= mask[i % 4];
            assertEquals(0x81, first); assertEquals("ws-ping", new String(payload, StandardCharsets.UTF_8));
            byte[] reply = "ws-echo".getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(new byte[]{(byte) 0x81, (byte) reply.length}); socket.getOutputStream().write(reply); socket.getOutputStream().flush();
            servedWebSocket = true;
        } catch (Exception ignored) { }
    }

    private static byte[] readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int state = 0, b;
        while ((b = input.read()) >= 0) { out.write(b); if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++; else state = b == '\r' ? 1 : 0; if (state == 4) return out.toByteArray(); }
        throw new java.io.IOException("EOF in headers");
    }
    private static byte[] readExactly(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length]; int offset = 0; while (offset < length) { int n = input.read(bytes, offset, length-offset); if (n < 0) throw new java.io.IOException("EOF in frame"); offset += n; } return bytes;
    }
    private static String header(String block, String name) { for (String line : block.split("\r\n")) { int i=line.indexOf(':'); if (i>0 && line.substring(0,i).equalsIgnoreCase(name)) return line.substring(i+1).trim(); } throw new AssertionError("missing " + name); }
    private static void write(Socket socket, String text) throws Exception { socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); }
}
