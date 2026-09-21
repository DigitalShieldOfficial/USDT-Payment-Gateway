import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Zero-dependency DSPay mock merchant. Run: java src/DspayMockMerchant.java */
public class DspayMockMerchant {
    static final int PORT = Integer.parseInt(System.getProperty("port", "3000"));
    static final String DSPAY_BASE = trimSlash(System.getProperty("dspayBase", ""));
    static final String PUBLIC_BASE = trimSlash(System.getProperty("publicBase", "http://localhost:" + PORT));
    static final String MERCHANT_NO = System.getProperty("merchantNo", "change-me");
    static final String API_SECRET = apiSecret();
    /** 前端页面目录：默认仓库内 Demo/front-end（相对于 back-end/java 运行目录）；可用 -DfrontEndDir 覆盖。 */
    static final Path FRONT_END_DIR = Paths.get(System.getProperty("frontEndDir", "../../front-end"));

    public static void main(String[] args) throws IOException {
        if (DSPAY_BASE.isEmpty()) throw new IllegalStateException("-DdspayBase is required");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", DspayMockMerchant::index);
        server.createContext("/create", DspayMockMerchant::create);
        server.createContext("/query", DspayMockMerchant::query);
        server.createContext("/notify", DspayMockMerchant::notify);
        server.createContext("/payment/return", DspayMockMerchant::landing);
        server.createContext("/payment/success", DspayMockMerchant::landing);
        server.start();
        System.out.println("Mock merchant: " + PUBLIC_BASE);
        System.out.println("DSPay API: " + DSPAY_BASE);
        System.out.println("Demo page: " + PUBLIC_BASE + "/  (served from " + FRONT_END_DIR.toAbsolutePath().normalize() + ")");
    }

    /** GET / 托管 front-end/index.html —— 页面与 API 同源，浏览器直接打开 PUBLIC_BASE 即完整 Demo。 */
    static void index(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Path root = FRONT_END_DIR.toAbsolutePath().normalize();
        Path file = ("/".equals(path) || "/index.html".equals(path))
                ? root.resolve("index.html")
                : root.resolve(path.substring(1)).normalize();
        // 防路径穿越：解析后的绝对路径必须仍在 front-end 目录内
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            send(exchange, 404, "{\"code\":\"NOT_FOUND\",\"path\":" + escJson(path) + "}");
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type",
                file.toString().endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static String escJson(String value) { return "\"" + esc(value) + "\""; }

    static String apiSecret() {
        String value = System.getenv("API_SECRET");
        return value == null || value.isEmpty() ? System.getProperty("apiSecret", "change-me") : value;
    }

    static void create(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
        String outOrderNo = q.getOrDefault("outOrderNo",
                "JAVA-DEMO-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", ""));
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
            String[] response = dspayPost("/dspay/public/order/create", body);
            int status = Integer.parseInt(response[0]);
            if (status / 100 != 2) { send(exchange, status, response[1]); return; }
            String checkoutUrl = jsonString(response[1], "checkoutUrl");
            if (checkoutUrl == null) { send(exchange, 502, response[1]); return; }
            exchange.getResponseHeaders().set("Location", checkoutUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        } catch (IOException e) {
            send(exchange, 502, "{\"code\":\"DSPAY_UNREACHABLE\",\"msg\":" + escJson(e.getMessage()) + "}");
        }
    }

    /** GET /query?orderNo=|outOrderNo= —— 与 nodejs/php 版对齐：服务端签名后调 /dspay/public/order/query 透传结果。 */
    static void query(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
        String orderNo = q.get("orderNo");
        String outOrderNo = q.get("outOrderNo");
        if ((orderNo == null || orderNo.isEmpty()) && (outOrderNo == null || outOrderNo.isEmpty())) {
            send(exchange, 400, "{\"code\":\"ORDER_NO_REQUIRED\"}");
            return;
        }
        long timestamp = System.currentTimeMillis();
        Map<String, String> signatureFields = new TreeMap<>();
        signatureFields.put("merchantNo", MERCHANT_NO);
        signatureFields.put("timestamp", String.valueOf(timestamp));
        if (orderNo != null && !orderNo.isEmpty()) signatureFields.put("orderNo", orderNo);
        if (outOrderNo != null && !outOrderNo.isEmpty()) signatureFields.put("outOrderNo", outOrderNo);
        String signature = hmac(canonicalFields(signatureFields), API_SECRET);

        StringBuilder body = new StringBuilder("{");
        body.append("\"merchantNo\":\"").append(esc(MERCHANT_NO)).append("\",");
        if (orderNo != null && !orderNo.isEmpty()) body.append("\"orderNo\":\"").append(esc(orderNo)).append("\",");
        if (outOrderNo != null && !outOrderNo.isEmpty()) body.append("\"outOrderNo\":\"").append(esc(outOrderNo)).append("\",");
        body.append("\"timestamp\":").append(timestamp).append(",\"signature\":\"").append(signature).append("\"}");
        try {
            String[] response = dspayPost("/dspay/public/order/query", body.toString());
            int status = Integer.parseInt(response[0]);
            send(exchange, status / 100 == 2 ? 200 : status, response[1]);
        } catch (IOException e) {
            send(exchange, 502, "{\"code\":\"DSPAY_UNREACHABLE\",\"msg\":" + escJson(e.getMessage()) + "}");
        }
    }

    static void notify(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        String raw = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
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

    /**
     * 支付回跳：returnUrl（未支付/取消返回）→ 商店首页；successRedirectUrl（支付成功）→ 订单查询。
     * 跳转本身不是支付证明，success 落到 /query 展示服务端查询到的真实订单状态。
     */
    static void landing(HttpExchange exchange) throws IOException {
        String outOrderNo = query(exchange.getRequestURI().getRawQuery()).getOrDefault("outOrderNo", "");
        boolean success = exchange.getRequestURI().getPath().endsWith("/success");
        String target = success ? "/query?outOrderNo=" + enc(outOrderNo) : "/";
        exchange.getResponseHeaders().set("Location", target);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /**
     * Java 8 compatible server-to-server POST (HttpURLConnection, zero-dependency).
     * Returns {statusCode, responseBody}; 4xx/5xx bodies are surfaced to the caller.
     */
    static String[] dspayPost(String path, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(DSPAY_BASE + path).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream err = conn.getErrorStream();
            String body = new String(readAll(err != null ? err : conn.getInputStream()), StandardCharsets.UTF_8);
            return new String[]{String.valueOf(status), body};
        } finally {
            conn.disconnect();
        }
    }

    /** Java 8 compatible full-read (InputStream.readAllBytes is Java 9+). */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
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
            if (i > 0) values.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
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
    /** Java 8 compatible URL encode/decode (Charset overloads are Java 10+; UTF-8 is always supported). */
    static String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    static String urlDecode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
    static String esc(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
