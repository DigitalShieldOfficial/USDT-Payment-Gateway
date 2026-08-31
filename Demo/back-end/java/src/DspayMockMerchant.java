import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Zero-dependency DSPay mock merchant. Run: java src/DspayMockMerchant.java */
public class DspayMockMerchant {
    static final int PORT = Integer.parseInt(System.getProperty("port", "3000"));
    static final String DSPAY_BASE = trimSlash(System.getProperty("dspayBase", ""));
    static final String PUBLIC_BASE = trimSlash(System.getProperty("publicBase", "http://localhost:" + PORT));
    static final String MERCHANT_NO = System.getProperty("merchantNo", "change-me");
    static final String API_SECRET = System.getProperty("apiSecret", "change-me");
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public static void main(String[] args) throws IOException {
        if (DSPAY_BASE.isEmpty()) throw new IllegalStateException("-DdspayBase is required");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/create", DspayMockMerchant::create);
        server.createContext("/notify", DspayMockMerchant::notify);
        server.createContext("/payment/return", DspayMockMerchant::landing);
        server.createContext("/payment/success", DspayMockMerchant::landing);
        server.start();
        System.out.println("Mock merchant: " + PUBLIC_BASE);
        System.out.println("DSPay API: " + DSPAY_BASE);
    }

    static void create(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
        String outOrderNo = q.getOrDefault("outOrderNo", "JAVA-DEMO-" + System.currentTimeMillis());
        String productPrice = q.getOrDefault("productPrice", "0.02");
        String productId = q.getOrDefault("productId", "NOVA-LIFETIME-001");
        String payAmount = q.getOrDefault("payAmount", "0.02");
        String attach = "{\"customerId\":\"CUST-1001\",\"demo\":\"java\"}"; // keys sorted
        String returnUrl = PUBLIC_BASE + "/payment/return?outOrderNo=" + enc(outOrderNo);
        String successUrl = PUBLIC_BASE + "/payment/success?outOrderNo=" + enc(outOrderNo);
        long timestamp = System.currentTimeMillis();

        String canonical = String.join("&",
                "merchantNo=" + MERCHANT_NO,
                "outOrderNo=" + outOrderNo,
                "productPrice=" + productPrice,
                "productPriceCurrency=USD",
                "productId=" + productId,
                "attach=" + attach,
                "payAmount=" + payAmount,
                "allowedPaymentMethods=",
                "returnUrl=" + returnUrl,
                "successRedirectUrl=" + successUrl,
                "timestamp=" + timestamp);
        String signature = hmac(canonical, API_SECRET);
        String body = "{" +
                "\"merchantNo\":\"" + esc(MERCHANT_NO) + "\"," +
                "\"outOrderNo\":\"" + esc(outOrderNo) + "\"," +
                "\"productPrice\":\"" + esc(productPrice) + "\"," +
                "\"productPriceCurrency\":\"USD\"," +
                "\"productId\":\"" + esc(productId) + "\"," +
                "\"attach\":" + attach + "," +
                "\"payAmount\":\"" + esc(payAmount) + "\"," +
                "\"allowedPaymentMethods\":[]," +
                "\"returnUrl\":\"" + esc(returnUrl) + "\"," +
                "\"successRedirectUrl\":\"" + esc(successUrl) + "\"," +
                "\"timestamp\":" + timestamp + "," +
                "\"signature\":\"" + signature + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(DSPAY_BASE + "/dspay/public/order/create"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) { send(exchange, response.statusCode(), response.body()); return; }
            String checkoutUrl = jsonString(response.body(), "checkoutUrl");
            if (checkoutUrl == null) { send(exchange, 502, response.body()); return; }
            exchange.getResponseHeaders().set("Location", checkoutUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); send(exchange, 500, "{\"code\":\"INTERRUPTED\"}");
        }
    }

    static void notify(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String signature = exchange.getRequestHeaders().getFirst("X-DSPay-Signature");
        String expected = hmac(raw, API_SECRET);
        boolean valid = signature != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        if (!valid) { send(exchange, 401, "{\"code\":\"FAIL\",\"msg\":\"signature invalid\"}"); return; }
        System.out.println("[NOTIFY verified] " + raw);
        // Production: idempotently commit local state before SUCCESS.
        send(exchange, 200, "{\"code\":\"SUCCESS\",\"msg\":\"ok\"}");
    }

    static void landing(HttpExchange exchange) throws IOException {
        String outOrderNo = query(exchange.getRequestURI().getRawQuery()).getOrDefault("outOrderNo", "");
        send(exchange, 200, "{\"message\":\"Redirect is not proof of payment; call POST /dspay/public/order/query\",\"outOrderNo\":\"" + esc(outOrderNo) + "\"}");
    }

    static String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null) return values;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) values.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/") : null;
    }
    static void send(HttpExchange e, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String esc(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
