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
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
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

        Map<String, String> signatureFields = new TreeMap<>();
        signatureFields.put("merchantNo", MERCHANT_NO);
        signatureFields.put("outOrderNo", outOrderNo);
        signatureFields.put("productPrice", productPrice);
        signatureFields.put("productPriceCurrency", "USD");
        signatureFields.put("productId", productId);
        signatureFields.put("attach", attach);
        signatureFields.put("payAmount", payAmount);
        signatureFields.put("allowedPaymentMethods", "");
        signatureFields.put("returnUrl", returnUrl);
        signatureFields.put("successRedirectUrl", successUrl);
        signatureFields.put("timestamp", String.valueOf(timestamp));
        String canonical = canonicalFields(signatureFields);
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
        String expected;
        try { expected = hmac(canonicalCallback(raw), API_SECRET); }
        catch (IllegalArgumentException ex) { send(exchange, 400, "{\"code\":\"FAIL\",\"msg\":\"invalid json\"}"); return; }
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

    static String canonicalFields(Map<String, String> fields) {
        Map<String, String> sorted = new TreeMap<>();
        fields.forEach((key, value) -> { if (value != null) sorted.put(key, value); });
        StringBuilder result = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (result.length() > 0) result.append('&');
            result.append(key).append('=').append(value);
        });
        return result.toString();
    }

    /** Demo-only zero-dependency parser for DSPay's compact callback JSON. Production code should use Jackson/Gson. */
    static String canonicalCallback(String json) {
        Map<String, String> fields = new TreeMap<>();
        int i = skipWhitespace(json, 0);
        if (i >= json.length() || json.charAt(i++) != '{') throw new IllegalArgumentException("JSON object required");
        while (true) {
            i = skipWhitespace(json, i);
            if (i < json.length() && json.charAt(i) == '}') break;
            if (i >= json.length() || json.charAt(i) != '"') throw new IllegalArgumentException("JSON key required");
            int keyEnd = stringEnd(json, i);
            String key = decodeJsonString(json.substring(i, keyEnd + 1));
            i = skipWhitespace(json, keyEnd + 1);
            if (i >= json.length() || json.charAt(i++) != ':') throw new IllegalArgumentException("JSON colon required");
            i = skipWhitespace(json, i);
            int valueStart = i;
            boolean quoted = i < json.length() && json.charAt(i) == '"';
            if (quoted) {
                i = stringEnd(json, i) + 1;
            } else {
                int depth = 0; boolean inString = false; boolean escaped = false;
                while (i < json.length()) {
                    char ch = json.charAt(i);
                    if (inString) {
                        if (escaped) escaped = false;
                        else if (ch == '\\') escaped = true;
                        else if (ch == '"') inString = false;
                    } else if (ch == '"') inString = true;
                    else if (ch == '{' || ch == '[') depth++;
                    else if (ch == '}' || ch == ']') { if (depth == 0) break; depth--; }
                    else if (ch == ',' && depth == 0) break;
                    i++;
                }
            }
            String rawValue = json.substring(valueStart, i).trim();
            if (!"null".equals(rawValue) && !"signature".equals(key)) {
                fields.put(key, quoted ? decodeJsonString(rawValue).trim()
                        : (rawValue.startsWith("{") || rawValue.startsWith("[")) ? canonicalJsonRaw(rawValue) : rawValue);
            }
            i = skipWhitespace(json, i);
            if (i < json.length() && json.charAt(i) == ',') { i++; continue; }
            if (i < json.length() && json.charAt(i) == '}') break;
            throw new IllegalArgumentException("JSON separator required");
        }
        return canonicalFields(fields);
    }

    static String canonicalJsonRaw(String raw) {
        raw = raw.trim();
        if (raw.startsWith("{")) {
            Map<String, String> values = new TreeMap<>();
            int i = 1;
            while (true) {
                i = skipWhitespace(raw, i);
                if (i < raw.length() && raw.charAt(i) == '}') break;
                if (i >= raw.length() || raw.charAt(i) != '"') throw new IllegalArgumentException("JSON key required");
                int keyEnd = stringEnd(raw, i);
                String key = decodeJsonString(raw.substring(i, keyEnd + 1));
                i = skipWhitespace(raw, keyEnd + 1);
                if (i >= raw.length() || raw.charAt(i++) != ':') throw new IllegalArgumentException("JSON colon required");
                i = skipWhitespace(raw, i);
                int end = jsonValueEnd(raw, i);
                values.put(key, canonicalJsonRaw(raw.substring(i, end)));
                i = skipWhitespace(raw, end);
                if (i < raw.length() && raw.charAt(i) == ',') { i++; continue; }
                if (i < raw.length() && raw.charAt(i) == '}') break;
                throw new IllegalArgumentException("JSON separator required");
            }
            StringBuilder out = new StringBuilder("{");
            values.forEach((key, value) -> {
                if (out.length() > 1) out.append(',');
                out.append(encodeJsonString(key)).append(':').append(value);
            });
            return out.append('}').toString();
        }
        if (raw.startsWith("[")) {
            StringBuilder out = new StringBuilder("[");
            int i = 1;
            while (true) {
                i = skipWhitespace(raw, i);
                if (i < raw.length() && raw.charAt(i) == ']') break;
                int end = jsonValueEnd(raw, i);
                if (out.length() > 1) out.append(',');
                out.append(canonicalJsonRaw(raw.substring(i, end)));
                i = skipWhitespace(raw, end);
                if (i < raw.length() && raw.charAt(i) == ',') { i++; continue; }
                if (i < raw.length() && raw.charAt(i) == ']') break;
                throw new IllegalArgumentException("JSON separator required");
            }
            return out.append(']').toString();
        }
        if (raw.startsWith("\"")) return encodeJsonString(decodeJsonString(raw));
        if ("true".equals(raw) || "false".equals(raw) || "null".equals(raw)) return raw;
        BigDecimal number = new BigDecimal(raw);
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }

    static int jsonValueEnd(String json, int start) {
        if (json.charAt(start) == '"') return stringEnd(json, start) + 1;
        int depth = 0; boolean inString = false; boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
            } else if (ch == '"') inString = true;
            else if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') {
                if (depth == 0) return i;
                depth--;
                if (depth == 0) return i + 1;
            } else if (ch == ',' && depth == 0) return i;
        }
        return json.length();
    }

    static String encodeJsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\b", "\\b").replace("\f", "\\f").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    static int stringEnd(String json, int start) {
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaped) escaped = false;
            else if (ch == '\\') escaped = true;
            else if (ch == '"') return i;
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    static String decodeJsonString(String quoted) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < quoted.length() - 1; i++) {
            char ch = quoted.charAt(i);
            if (ch != '\\') { out.append(ch); continue; }
            if (++i >= quoted.length() - 1) throw new IllegalArgumentException("Invalid JSON escape");
            ch = quoted.charAt(i);
            if (ch == 'u') {
                if (i + 4 >= quoted.length()) throw new IllegalArgumentException("Invalid unicode escape");
                out.append((char) Integer.parseInt(quoted.substring(i + 1, i + 5), 16)); i += 4;
            } else if (ch == 'n') out.append('\n');
            else if (ch == 'r') out.append('\r');
            else if (ch == 't') out.append('\t');
            else if (ch == 'b') out.append('\b');
            else if (ch == 'f') out.append('\f');
            else out.append(ch);
        }
        return out.toString();
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
