import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DSPay mock merchant (Java version).
 *
 * <p>Zero dependencies — JDK built-in com.sun.net.httpserver + javax.crypto.
 * Run with Java 11+: {@code java DspayMockMerchant.java}
 *
 * <p>Two endpoints, matching the Node.js implementation:
 * <ul>
 *   <li>GET  /create?payAmount=&amp;productPrice=&amp;productPriceCurrency=&amp;productId=
 *       — create order + 302 redirect to cashier</li>
 *   <li>POST /notify
 *       — receive DSPay callback + verify signature</li>
 * </ul>
 */
public class DspayMockMerchant {

    // ==================== Configuration ====================

    private static final int PORT = Integer.parseInt(System.getProperty("port", "3000"));
    private static final String CASHIER_BASE =
            System.getProperty("cashierBase", "https://cashier.ds.pro/");

    // Credentials — hardcoded defaults, override via -DmerchantNo=xxx -DapiSecret=xxx
    private static final String MERCHANT_NO =
            System.getProperty("merchantNo", "change-me-to-your-merchantNo");
    private static final String API_SECRET =
            System.getProperty("apiSecret", "change-me-to-your-apiSecret");

    // Default business params (overridable via query string, same as Node.js FIXED_PARAMS)
    private static final String DEFAULT_PAY_AMOUNT = "0.01";
    private static final String DEFAULT_PRODUCT_PRICE = "0.01";
    private static final String DEFAULT_PRODUCT_PRICE_CURRENCY = "USD";
    private static final String DEFAULT_PRODUCT_ID = "NOVA-LIFETIME-001";

    // ==================== Logger ====================

    private static final Path LOG_DIR = Paths.get("logs");
    private static final Path LOG_FILE = LOG_DIR.resolve("server.log");
    private static PrintWriter logWriter;

    static {
        try {
            Files.createDirectories(LOG_DIR);
            logWriter = new PrintWriter(Files.newBufferedWriter(
                    LOG_FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
        } catch (IOException e) {
            logWriter = new PrintWriter(System.out, true);
        }
    }

    private static void log(String level, String msg) {
        String line = "[" + Instant.now() + "] [" + level + "] " + msg;
        if ("ERROR".equals(level)) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        logWriter.println(line);
    }

    private static void info(String msg) { log("INFO", msg); }

    private static void error(String msg) { log("ERROR", msg); }

    // ==================== Startup ====================

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/create", new CreateHandler());
        server.createContext("/notify", new NotifyHandler());
        server.setExecutor(null);
        server.start();
        info("DSPay mock merchant running at http://localhost:" + PORT);
        info("  merchantNo: " + MERCHANT_NO);
        info("  GET  /create?payAmount=0.01&productPrice=0.01&productPriceCurrency=USD&productId=123");
        info("  POST /notify  -> Receive DSPay callback + verify signature (apiSecret hardcoded)");
        info("  cashier base: " + CASHIER_BASE);
    }

    // ==================== /create — create order + redirect to cashier ====================

    static class CreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"code\":\"FAIL\",\"msg\":\"method not allowed\"}");
                return;
            }
            Map<String, String> q = parseQuery(exchange.getRequestURI().getQuery());

            // Read optional params (query overrides defaults)
            String payAmount = q.getOrDefault("payAmount", DEFAULT_PAY_AMOUNT);
            String productPrice = q.getOrDefault("productPrice", DEFAULT_PRODUCT_PRICE);
            String productPriceCurrency = q.getOrDefault("productPriceCurrency", DEFAULT_PRODUCT_PRICE_CURRENCY);
            String productId = q.getOrDefault("productId", DEFAULT_PRODUCT_ID);
            String outOrderNo = String.valueOf(System.currentTimeMillis());
            long timestamp = System.currentTimeMillis();

            // Sign order (4-field canonical signature, field order matters)
            String signature = signOrder(MERCHANT_NO, outOrderNo, payAmount, timestamp, API_SECRET);

            // Build cashier redirect URL
            Map<String, String> urlParams = new LinkedHashMap<>();
            urlParams.put("merchantNo", MERCHANT_NO);
            urlParams.put("outOrderNo", outOrderNo);
            urlParams.put("payAmount", payAmount);
            urlParams.put("timestamp", String.valueOf(timestamp));
            urlParams.put("signature", signature);
            urlParams.put("productPrice", productPrice);
            urlParams.put("productPriceCurrency", productPriceCurrency);
            urlParams.put("productId", productId);

            StringBuilder url = new StringBuilder(CASHIER_BASE).append("?");
            boolean first = true;
            for (Map.Entry<String, String> e : urlParams.entrySet()) {
                if (!first) url.append("&");
                url.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
                url.append("=");
                url.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
            String redirectUrl = url.toString();

            info("[CREATE] merchantNo=" + MERCHANT_NO + " outOrderNo=" + outOrderNo
                    + " payAmount=" + payAmount + " timestamp=" + timestamp);
            info("[CREATE] signature=" + signature);
            info("[CREATE] redirect -> " + redirectUrl);

            byte[] body = ("Redirecting to " + redirectUrl).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", redirectUrl);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(302, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ==================== /notify — receive DSPay callback + verify signature ====================

    static class NotifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"code\":\"FAIL\",\"msg\":\"method not allowed\"}");
                return;
            }
            // Read raw body — must use raw bytes, not JSON parse-then-serialize
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = exchange.getRequestBody().read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            String rawBody = bos.toString(StandardCharsets.UTF_8);
            String signatureHeader = exchange.getRequestHeaders().getFirst("X-DSPay-Signature");
            if (signatureHeader == null) signatureHeader = "";

            info("[NOTIFY] received, length=" + rawBody.length());
            info("[NOTIFY] X-DSPay-Signature=" + signatureHeader);
            info("[NOTIFY] body=" + rawBody);

            if (API_SECRET == null || API_SECRET.isEmpty()) {
                error("[NOTIFY] API_SECRET not configured; set env var or -DapiSecret=xxx");
                sendJson(exchange, 500,
                        "{\"code\":\"FAIL\",\"msg\":\"API_SECRET not configured; set env var or -DapiSecret=xxx\"}");
                return;
            }

            boolean ok = verifyCallback(rawBody, signatureHeader, API_SECRET);
            info("[NOTIFY] verify=" + ok);

            if (!ok) {
                sendJson(exchange, 401, "{\"code\":\"FAIL\",\"msg\":\"signature invalid\"}");
                return;
            }

            // Log verified payload (best-effort parse)
            try {
                info("[NOTIFY] ✓ verified, rawBody=" + rawBody);
            } catch (Exception ignore) {
                info("[NOTIFY] body is not JSON, skipping parse");
            }

            // DSPay requires strictly uppercase "SUCCESS", otherwise it will retry
            sendJson(exchange, 200, "{\"code\":\"SUCCESS\",\"msg\":\"ok\"}");
        }
    }

    // ==================== Signature utilities ====================

    /**
     * Create order signature: canonical string -> HMAC-SHA256 -> lowercase hex.
     * Signed fields and order (order-sensitive):
     * merchantNo -> outOrderNo -> payAmount -> timestamp
     */
    static String signOrder(String merchantNo, String outOrderNo,
                            String payAmount, long timestamp, String apiSecret) {
        String canonical = String.join("&",
                "merchantNo=" + merchantNo,
                "outOrderNo=" + normalizeOpt(outOrderNo),
                "payAmount=" + payAmount,
                "timestamp=" + timestamp);
        return hmacSha256Hex(canonical, apiSecret);
    }

    /**
     * Verify callback signature: HMAC-SHA256 over raw body, constant-time compare
     * against X-DSPay-Signature header.
     */
    static boolean verifyCallback(String rawBody, String signature, String apiSecret) {
        if (apiSecret == null || apiSecret.isEmpty() || rawBody == null || signature == null) {
            return false;
        }
        String expected = hmacSha256Hex(rawBody, apiSecret);
        if (expected.isEmpty()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String normalizeOpt(String s) {
        return (s == null || s.trim().isEmpty()) ? "" : s.trim();
    }

    // ==================== HTTP helpers ====================

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
