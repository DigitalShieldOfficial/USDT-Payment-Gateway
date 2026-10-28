import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Standalone Java 8 tests; temporary certificate generation uses the JDK keytool. */
public class DspayTransportTest {
    interface Action { void run() throws Exception; }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void fails(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Malformed response was accepted");
    }
    static DspayTransport.Response parse(String value) throws IOException {
        return DspayTransport.readResponse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
    static String text(DspayTransport.Response value) { return new String(value.body, StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        String json = "{\"orderNo\":90071992547409931234567890}";
        check(text(parse("HTTP/1.1 200 OK\r\nContent-Length: " + json.length() + "\r\n\r\n" + json)).equals(json));
        check(text(parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(json.length()) + ";test=yes\r\n" + json + "\r\n0\r\nX-Test: trailer\r\n\r\n")).equals(json));
        check(text(parse("HTTP/1.1 103 Early Hints\r\nLink: test\r\n\r\nHTTP/1.1 200 OK\r\n\r\n" + json)).equals(json));
        check(parse("HTTP/1.1 307 Temporary Redirect\r\nLocation: https://invalid.example\r\nContent-Length: 0\r\n\r\n").status == 307);
        check(parse("HTTP/1.1 204 No Content\r\n\r\n").body.length == 0);
        for (String bad : Arrays.asList(
                "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\nx",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nxyz\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx!\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
                "HTTP/1.1 200 OK\n\n", "HTTP/1.1 101 Switching Protocols\r\n\r\n")) fails(() -> parse(bad));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(json.getBytes(StandardCharsets.UTF_8)); }
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + compressed.size() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        wire.write(compressed.toByteArray());
        check(text(DspayTransport.readResponse(new ByteArrayInputStream(wire.toByteArray()))).equals(json));
        requestAndDeadline(json);
        tlsChecks();
        System.out.println("DspayTransportTest: PASS");
    }

    static void readRequest(Socket socket, String expected) throws Exception {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            head.write(value);
            if (head.toString("US-ASCII").endsWith("\r\n\r\n")) break;
        }
        String headers = head.toString("US-ASCII");
        check(headers.indexOf("Content-Length:") < headers.indexOf("Host:"));
        check(headers.indexOf("Host:") < headers.indexOf("Accept:"));
        check(headers.indexOf("Accept:") < headers.indexOf("Content-Type:"));
        check(headers.contains(DspayTransport.USER_AGENT));
        byte[] bytes = new byte[expected.getBytes(StandardCharsets.UTF_8).length];
        new DataInputStream(input).readFully(bytes);
        check(new String(bytes, StandardCharsets.UTF_8).equals(expected));
    }

    static void requestAndDeadline(String json) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            FutureTask<Void> peer = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    readRequest(socket, json);
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nCF-Ray: test-ray\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            new Thread(peer).start();
            DspayTransport.Response result = DspayTransport.post(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/query"), json.getBytes(StandardCharsets.UTF_8));
            check(result.status == 403 && result.headers.get("cf-ray").equals("test-ray"));
            peer.get(3, TimeUnit.SECONDS);
        }
        // A peer trickling bytes must not extend the total request deadline.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    for (int i = 0; i < 50; i++) { socket.getOutputStream().write('H'); socket.getOutputStream().flush(); Thread.sleep(40); }
                } catch (Exception ignored) { }
            });
            peer.setDaemon(true); peer.start();
            long start = System.nanoTime();
            try {
                DspayTransport.post(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/query"), json.getBytes(StandardCharsets.UTF_8), 200, new Socket());
                throw new AssertionError("Deadline was not enforced");
            } catch (SocketTimeoutException expected) { check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500); }
            peer.join(2000);
        }
    }

    static void tlsChecks() throws Exception {
        Path directory = Files.createTempDirectory("dspay-java-tls-");
        Path store = directory.resolve("test.jks");
        File tool = new File(System.getProperty("java.home"), "bin/keytool");
        if (!tool.isFile()) tool = new File(new File(System.getProperty("java.home")).getParentFile(), "bin/keytool");
        SSLContext previous = SSLContext.getDefault();
        try {
            Process process = new ProcessBuilder(tool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-keystore", store.toString(), "-storetype", "JKS", "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
            check(process.waitFor() == 0);
            KeyStore keys = KeyStore.getInstance("JKS");
            try (InputStream input = Files.newInputStream(store)) { keys.load(input, "changeit".toCharArray()); }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "changeit".toCharArray());
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
            SSLContext serverContext = SSLContext.getInstance("TLS"); serverContext.init(km.getKeyManagers(), null, null);
            SSLContext trusted = SSLContext.getInstance("TLS"); trusted.init(null, tm.getTrustManagers(), null);
            // Reject an untrusted chain, then accept the trusted localhost certificate.
            tlsExchange(serverContext, "localhost", false);
            SSLContext.setDefault(trusted);
            tlsExchange(serverContext, "localhost", true);
            // The chain is now trusted, but the IP does not match the DNS-only SAN.
            tlsExchange(serverContext, "127.0.0.1", false);
        } finally {
            SSLContext.setDefault(previous);
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }

    static void tlsExchange(SSLContext context, String host, boolean success) throws Exception {
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            FutureTask<Void> peer = new FutureTask<>(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(3000);
                    readRequest(socket, "{}");
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException error) { if (success) throw error; }
                return null;
            });
            new Thread(peer).start();
            // Connect explicitly to loopback so localhost IPv6 preferences cannot affect the test.
            Socket connected = new Socket("127.0.0.1", server.getLocalPort());
            try {
                DspayTransport.Response result = DspayTransport.post(URI.create("https://" + host + ":" + server.getLocalPort() + "/query"), "{}".getBytes(StandardCharsets.UTF_8), 3000, connected);
                check(success && result.status == 200);
            } catch (SSLException error) { if (success) throw error; }
            peer.get(4, TimeUnit.SECONDS);
        }
    }
}
