package com.cloudflare.browser.dns;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CloudflareDohResolverTest {
    @Test public void normalizesInternationalizedHostnames() throws Exception {
        assertEquals("xn--bcher-kva.de", CloudflareDohResolver.normalizeHost("bücher.de."));
    }

    @Test public void rejectsLocalNamesAndMalformedLabels() {
        assertThrows(IOException.class, () -> CloudflareDohResolver.normalizeHost("localhost"));
        assertThrows(IOException.class, () -> CloudflareDohResolver.normalizeHost("printer.local"));
        assertThrows(IOException.class, () -> CloudflareDohResolver.normalizeHost("bad..example"));
        assertThrows(IOException.class, () -> CloudflareDohResolver.normalizeHost("-bad.example"));
    }

    @Test public void fallsBackFromPrimaryTransportFailureAndCachesForMinimumTtl() throws Exception {
        List<String> requests = new ArrayList<>();
        CloudflareDohResolver resolver = new CloudflareDohResolver(false, (server, query) -> {
            requests.add(server + ":" + queryType(query));
            if (server.equals("1.1.1.1")) throw new IOException("simulated primary timeout");
            return answer(query, 0, queryType(query) == 1 ? new byte[]{1, 2, 3, 4} : InetAddress.getByName("2001:db8::5").getAddress(),
                    queryType(query) == 1 ? 90 : 30);
        });
        CloudflareDohResolver.DnsResult result = resolver.resolve("example.test");
        assertEquals("1.2.3.4", result.addresses.get(0));
        assertEquals(InetAddress.getByAddress(new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5}).getHostAddress(), result.addresses.get(1));
        assertEquals(30, result.ttlSeconds);
        assertEquals("1.0.0.1", result.resolver);
        int count = requests.size();
        assertTrue(resolver.resolve("example.test").fromCache);
        assertEquals(count, requests.size());
        assertEquals(Arrays.asList("1.1.1.1:1", "1.0.0.1:1", "1.1.1.1:28", "1.0.0.1:28"), requests);
    }

    @Test public void fallsBackOnDnsServFailButNotNxdomain() throws Exception {
        List<String> servfailRequests = new ArrayList<>();
        CloudflareDohResolver servfail = new CloudflareDohResolver(false, (server, query) -> {
            servfailRequests.add(server);
            if (server.equals("1.1.1.1")) return answer(query, 2, null, 0);
            return answer(query, 0, new byte[]{8, 8, 4, 4}, 45);
        });
        assertFalse(servfail.resolve("servfail.test").addresses.isEmpty());
        assertTrue(servfailRequests.contains("1.0.0.1"));

        List<String> nxdomainRequests = new ArrayList<>();
        CloudflareDohResolver nxdomain = new CloudflareDohResolver(false, (server, query) -> {
            nxdomainRequests.add(server);
            return answer(query, 3, null, 0);
        });
        assertThrows(java.net.UnknownHostException.class, () -> nxdomain.resolve("missing.test"));
        assertEquals(Arrays.asList("1.1.1.1", "1.1.1.1"), nxdomainRequests);
    }

    @Test public void rejectsMismatchedTransactionIdThenUsesSecondary() throws Exception {
        List<String> servers = new ArrayList<>();
        CloudflareDohResolver resolver = new CloudflareDohResolver(false, (server, query) -> {
            servers.add(server);
            byte[] response = answer(query, 0, new byte[]{9, 9, 9, 9}, 10);
            if (server.equals("1.1.1.1")) response[1] ^= 1;
            return response;
        });
        assertEquals("1.0.0.1", resolver.resolve("id.test").resolver);
        assertTrue(servers.contains("1.0.0.1"));
    }

    private static int queryType(byte[] query) {
        return ((query[query.length - 4] & 255) << 8) | (query[query.length - 3] & 255);
    }

    private static byte[] answer(byte[] query, int rcode, byte[] address, long ttl) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(((query[0] & 255) << 8) | (query[1] & 255));
        out.writeShort(0x8180 | rcode);
        out.writeShort(1);
        out.writeShort(address == null || rcode != 0 ? 0 : 1);
        out.writeShort(0); out.writeShort(0);
        out.write(query, 12, query.length - 12);
        if (address != null && rcode == 0) {
            out.writeShort(0xc00c);
            out.writeShort(queryType(query));
            out.writeShort(1);
            out.writeInt((int) ttl);
            out.writeShort(address.length);
            out.write(address);
        }
        out.flush();
        return bytes.toByteArray();
    }
}
