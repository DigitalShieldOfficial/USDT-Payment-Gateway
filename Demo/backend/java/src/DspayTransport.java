import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Small synchronous HTTP/1.1 POST client using only Java 8 public APIs. */
final class DspayTransport {
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    private static final ExecutorService IO = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "dspay-http");
        thread.setDaemon(true);
        return thread;
    });

    static final class Response {
        final int status;
        final Map<String, String> headers;
        final byte[] body;
        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status; this.headers = headers; this.body = body;
        }
    }

    static Response post(URI uri, byte[] body) throws IOException {
        return post(uri, body, 10000, new Socket());
    }

    // The socket overload permits isolated connection and TLS tests without proxy configuration.
    static Response post(URI uri, byte[] body, int timeout, Socket socket) throws IOException {
        Future<Response> future = null;
        try {
            if (timeout <= 0 || body == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || !Arrays.asList("http", "https").contains(uri.getScheme())
                    || !StandardCharsets.US_ASCII.newEncoder().canEncode(uri.toString())
                    || uri.toString().matches("(?s).*[\\x00-\\x20\\x7f].*")) {
                throw new IOException("Invalid DSPay request URL, body, or timeout");
            }
            future = IO.submit(() -> exchange(uri, body, timeout, socket));
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new SocketTimeoutException("DSPay request timed out after " + timeout + " ms");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("DSPay request interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("DSPay request failed", cause);
        } finally {
            if (future != null) future.cancel(true);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static Response exchange(URI uri, byte[] body, int timeout, Socket raw) throws IOException {
        boolean secure = uri.getScheme().equals("https");
        String host = uri.getHost();
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        int port = uri.getPort() < 0 ? (secure ? 443 : 80) : uri.getPort();
        if (!raw.isConnected()) raw.connect(new InetSocketAddress(host, port), timeout);
        raw.setSoTimeout(timeout);
        Socket connection = raw;
        if (secure) {
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            List<String> protocols = new ArrayList<>();
            for (String protocol : tls.getEnabledProtocols()) {
                if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) protocols.add(protocol);
            }
            if (protocols.isEmpty()) throw new IOException("TLS 1.2 or newer is required");
            parameters.setProtocols(protocols.toArray(new String[0]));
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(timeout);
            tls.startHandshake();
            connection = tls;
        }
        try (Socket active = connection) {
            String target = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) target += "?" + uri.getRawQuery();
            String authority = uri.getRawAuthority();
            // Keep explicit framing and the verified request-header order; never rewrite JSON.
            String head = "POST " + target + " HTTP/1.1\r\nContent-Length: " + body.length
                    + "\r\nHost: " + authority + "\r\nAccept: application/json\r\nContent-Type: application/json"
                    + "\r\nUser-Agent: " + USER_AGENT + "\r\n\r\n";
            OutputStream output = active.getOutputStream();
            output.write(head.getBytes(StandardCharsets.US_ASCII));
            output.write(body); output.flush();
            return readResponse(new BufferedInputStream(active.getInputStream()));
        }
    }

    static Response readResponse(InputStream input) throws IOException {
        int interim = 0;
        while (true) {
            String statusLine = line(input);
            if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: [^\\r\\n]*)?")) throw new IOException("Invalid DSPay HTTP status line");
            int status = Integer.parseInt(statusLine.substring(9, 12));
            Map<String, String> headers = headers(input);
            if (status < 200) {
                if (status == 101 || ++interim > 8) throw new IOException("Unsupported DSPay interim response");
                continue;
            }
            String length = headers.get("content-length"), transfer = headers.get("transfer-encoding");
            if (length != null && transfer != null) throw new IOException("Conflicting DSPay response framing");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (status != 204 && status != 304) {
                if (transfer != null) {
                    if (!transfer.equalsIgnoreCase("chunked")) throw new IOException("Unsupported DSPay transfer encoding");
                    while (true) {
                        String chunk = line(input).split(";", 2)[0];
                        long size = length(chunk, 16);
                        if (size == 0) {
                            Map<String, String> trailers = headers(input);
                            if (trailers.containsKey("content-length") || trailers.containsKey("transfer-encoding")) throw new IOException("Invalid DSPay framing trailer");
                            break;
                        }
                        copy(input, body, size);
                        if (!line(input).isEmpty()) throw new IOException("Invalid DSPay chunk terminator");
                    }
                } else if (length != null) copy(input, body, length(length, 10));
                else copy(input, body, -1);
            }
            byte[] bytes = body.toByteArray();
            String encoding = headers.get("content-encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                InputStream decoder;
                if (encoding.equalsIgnoreCase("gzip")) decoder = new GZIPInputStream(new ByteArrayInputStream(bytes));
                else if (encoding.equalsIgnoreCase("deflate")) decoder = new InflaterInputStream(new ByteArrayInputStream(bytes));
                else throw new IOException("Unsupported DSPay content encoding: " + encoding);
                try (InputStream compressed = decoder) {
                    ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                    copy(compressed, decoded, -1); bytes = decoded.toByteArray();
                }
            }
            return new Response(status, headers, bytes);
        }
    }

    private static long length(String value, int radix) throws IOException {
        if (!value.matches(radix == 16 ? "[0-9a-fA-F]+" : "[0-9]+")) throw new IOException("Invalid DSPay body length");
        try { return Long.parseLong(value, radix); }
        catch (NumberFormatException error) { throw new IOException("DSPay body length is too large", error); }
    }

    private static void copy(InputStream input, OutputStream output, long count) throws IOException {
        byte[] buffer = new byte[4096];
        while (count != 0) {
            int read = input.read(buffer, 0, count < 0 ? buffer.length : (int) Math.min(count, buffer.length));
            if (read < 0) {
                if (count > 0) throw new EOFException("Truncated DSPay response body");
                return;
            }
            output.write(buffer, 0, read);
            if (count > 0) count -= read;
        }
    }

    private static Map<String, String> headers(InputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        int total = 0;
        while (true) {
            String line = line(input);
            total += line.length() + 2;
            if (total > 65536) throw new IOException("DSPay response headers are too large");
            if (line.isEmpty()) return headers;
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || line.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*")) throw new IOException("Invalid DSPay response header");
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            // HTTP permits optional spaces/tabs around field values; JSON fields are untouched.
            String value = line.substring(colon + 1).replaceAll("^[ \\t]+|[ \\t]+$", "");
            if (headers.containsKey(name) && Arrays.asList("content-length", "transfer-encoding", "content-encoding").contains(name)) throw new IOException("Duplicate DSPay framing header");
            if (!headers.containsKey(name)) headers.put(name, value);
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= 16384) {
            int c = input.read();
            if (c < 0) throw new EOFException("Incomplete DSPay response headers");
            if (c == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid DSPay HTTP line ending");
                return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (c == '\n') throw new IOException("Invalid DSPay HTTP line ending");
            bytes.write(c);
        }
        throw new IOException("DSPay response header line is too long");
    }
}
