import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

/**
 * Zero-dependency DSPay mock merchant, compatible with JDK 8+.
 * Create build/, compile with javac -d build src/DspayMockMerchant.java src/DspayTransport.java,
 * then run java -cp build DspayMockMerchant.
 */
public class DspayMockMerchant {
    static final int PORT = Integer.parseInt(System.getProperty("port",
            System.getenv("PORT") == null ? "3000" : System.getenv("PORT")));
    static final String DSPAY_BASE = apiBase(System.getProperty("dspayBase",
            System.getenv("DSPAY_BASE_URL") == null ? "https://pay-api.ds.pro" : System.getenv("DSPAY_BASE_URL")));
    static final String MERCHANT_NO = System.getProperty("merchantNo", "change-me");
    static final String API_SECRET = apiSecret();
    /** Serve Demo/front-end by default; -DfrontEndDir overrides FRONT_END_DIR. */
    static final Path FRONT_END_DIR = frontEndDir();

    static Path frontEndDir() {
        String configured = System.getProperty("frontEndDir",
                System.getenv("FRONT_END_DIR") == null ? "../../front-end" : System.getenv("FRONT_END_DIR"));
        if (configured.isEmpty()) throw new IllegalArgumentException("FRONT_END_DIR / -DfrontEndDir must contain index.html");
        return Paths.get(configured);
    }

    public static void main(String[] args) throws IOException {
        if (!Files.isRegularFile(FRONT_END_DIR.resolve("index.html"))) throw new IllegalArgumentException("FRONT_END_DIR / -DfrontEndDir must contain index.html");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", DspayMockMerchant::index);
        server.createContext("/create", DspayMockMerchant::create);
        server.createContext("/query", DspayMockMerchant::query);
        server.createContext("/notify", DspayMockMerchant::notify);
        server.createContext("/payment/return", DspayMockMerchant::landing);
        server.createContext("/payment/success", DspayMockMerchant::landing);
        server.start();
        System.out.println("Mock merchant: " + "http://localhost:" + PORT);
        System.out.println("DSPay API: " + DSPAY_BASE);
        System.out.println("Demo page: " + "http://localhost:" + PORT + "/  (served from " + FRONT_END_DIR.toAbsolutePath().normalize() + ")");
    }

    /** GET / serves front-end/index.html from the same origin as the API. */
    static void index(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Path root = FRONT_END_DIR.toAbsolutePath().normalize();
        Path file = ("/".equals(path) || "/index.html".equals(path))
                ? root.resolve("index.html")
                : root.resolve(path.substring(1)).normalize();
        // Prevent path traversal: the resolved absolute path must remain inside the front-end directory.
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

    static String apiBase(String value) {
        return origin(value, "DSPAY_BASE_URL / -DdspayBase");
    }

    static String origin(String value, String field) {
        try {
            URI url = URI.create(value);
            if (("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
                    && url.getHost() != null && url.getRawUserInfo() == null
                    && (url.getRawPath() == null || url.getRawPath().isEmpty())
                    && url.getRawQuery() == null && url.getRawFragment() == null
                    && url.getPort() != 0 && url.getPort() <= 65535) return value;
        } catch (IllegalArgumentException ignored) {
            // Report the field requirement without rewriting the supplied value.
        }
        throw new IllegalArgumentException(field + " must be an http(s) origin without a trailing slash, path, query, credentials, or whitespace");
    }

    static String apiSecret() {
        String value = System.getenv("API_SECRET");
        return value == null || value.isEmpty() ? System.getProperty("apiSecret", "change-me") : value;
    }

    static void logEvent(String event, String data) {
        String safe = data.replaceAll("(?i)(\"(?:signature|apiSecret|API_SECRET)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"", "$1\"[REDACTED]\"");
        if (!API_SECRET.isEmpty()) safe = safe.replace(API_SECRET, "[REDACTED]").replace(esc(API_SECRET), "[REDACTED]");
        System.out.println("[" + event + "] " + escJson(safe));
    }

    static void logRequest(HttpExchange exchange) {
        logEvent("DEMO_REQUEST", exchange.getRequestMethod() + " " + exchange.getRequestURI().toString());
    }

    static String requestOrigin(HttpExchange exchange) {
        boolean local = exchange.getRemoteAddress().getAddress().isLoopbackAddress();
        String host = exchange.getRequestHeaders().getFirst("Host");
        String proto = "http";
        if (local) {
            String forwardedHost = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
            String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
            if (forwardedHost != null) host = forwardedHost;
            if (forwardedProto != null) proto = forwardedProto;
        }
        if (!("http".equals(proto) || "https".equals(proto))) throw new IllegalArgumentException("X-Forwarded-Proto must be http or https");
        return origin(proto + "://" + host, "Request origin");
    }

    static Map<String,String> parameters(HttpExchange exchange, String... allowed) {
        Map<String,String> fields = query(exchange.getRequestURI().getRawQuery());
        for (String key : fields.keySet()) if (!java.util.Arrays.asList(allowed).contains(key)) throw new IllegalArgumentException("Unsupported query field: " + key);
        return fields;
    }
    static String identifier(String value, String field) {
        if (!value.matches("outOrderNo".equals(field) ? "[A-Za-z0-9-]{1,64}" : "(?:DS)?[1-9][0-9]{14,18}")) throw new IllegalArgumentException(field + " has an invalid format; whitespace is not allowed");
        return value;
    }
    static String amount(String value, String field, int integers, int decimals, boolean minimum) {
        if (!value.matches("(?:0|[1-9][0-9]{0," + (integers - 1) + "})(?:\\.[0-9]{1," + decimals + "})?") || minimum && new BigDecimal(value).compareTo(new BigDecimal("0.01")) < 0) throw new IllegalArgumentException(field + " must be a plain nonnegative decimal with at most " + integers + " integer and " + decimals + " fractional digits, no leading zeros or whitespace" + (minimum ? "; minimum 0.01" : ""));
        return value;
    }
    static Object upstreamData(String path, String body) throws IOException {
        String[] response = dspayPost(path, body);
        Object parsed;
        try { parsed = parseJson(response[1]); }
        catch (IllegalArgumentException error) { throw new IOException("DSPay request failed or returned invalid JSON"); }
        if (!(parsed instanceof Map)) throw new IOException("DSPay response must contain a top-level integer code");
        Map<?,?> result = (Map<?,?>)parsed;
        Object code = result.get("code");
        if (!(code instanceof JsonNumber) || !((JsonNumber)code).integer) throw new IOException("DSPay response must contain a top-level integer code");
        if (((JsonNumber)code).value.signum() != 0) throw new IOException("DSPay business error: " + ((JsonNumber)code).plain());
        if (!result.containsKey("data")) throw new IOException("DSPay response is missing data");
        return result.get("data");
    }
    static void failure(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, "{\"code\":" + escJson(code) + ",\"message\":" + escJson(message) + "}");
    }
    static void create(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        try {
            Map<String,String> args = parameters(exchange, "outOrderNo", "payAmount", "productPrice", "productPriceCurrency", "productId");
            if (args.containsKey("productPriceCurrency") && !"USD".equals(args.get("productPriceCurrency"))) throw new IllegalArgumentException("productPriceCurrency must be USD for this storefront");
            if (!args.containsKey("payAmount")) throw new IllegalArgumentException("payAmount is required; minimum 0.01");
            String number = args.containsKey("outOrderNo") ? identifier(args.get("outOrderNo"), "outOrderNo") : "DEMO-" + UUID.randomUUID().toString().replace("-", "");
            String base = requestOrigin(exchange);
            Map<String,Object> order = new TreeMap<>();
            order.put("merchantNo", MERCHANT_NO); order.put("outOrderNo", number);
            order.put("payAmount", amount(args.get("payAmount"), "payAmount", 12, 2, true));
            Map<String,Object> attach = new TreeMap<>(); attach.put("demo", "java"); attach.put("customerId", "CUST-1001");
            order.put("attach", attach); order.put("allowedPaymentMethods", new java.util.ArrayList<>());
            order.put("timestamp", System.currentTimeMillis());
            order.put("returnUrl", base + "/payment/return?outOrderNo=" + enc(number));
            order.put("successRedirectUrl", base + "/payment/success?outOrderNo=" + enc(number));
            if (args.containsKey("productPrice")) { order.put("productPrice", amount(args.get("productPrice"), "productPrice", 14, 6, false)); order.put("productPriceCurrency", "USD"); }
            if (args.containsKey("productPriceCurrency")) order.put("productPriceCurrency", args.get("productPriceCurrency"));
            if (args.containsKey("productId")) {
                String product = args.get("productId");
                if (product.codePointCount(0, product.length()) > 64) throw new IllegalArgumentException("productId must be at most 64 characters");
                order.put("productId", product);
            }
            Map<String,Object> sign = new TreeMap<>(order); sign.put("allowedPaymentMethods", "");
            for (String key : new String[]{"productId", "productPriceCurrency"}) {
                Object value = sign.get(key);
                if (value instanceof String && ((String)value).codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85)) sign.remove(key);
            }
            order.put("signature", hmac(canonicalCallback(encodeJson(sign)), API_SECRET));
            Object data = upstreamData("/dspay/public/order/create", encodeJson(order));
            Object checkout = data instanceof Map ? ((Map<?,?>)data).get("checkoutUrl") : null;
            if (!(checkout instanceof String) || ((String)checkout).matches("(?s).*[\\s\\x00-\\x1f].*")) throw new IOException("DSPay create response must contain a complete checkoutUrl");
            try {
                URI url = URI.create((String)checkout);
                if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) { throw new IOException("DSPay checkoutUrl must be an http(s) URL"); }
            exchange.getResponseHeaders().set("Location", (String)checkout);
            logEvent("DEMO_RESPONSE", "path=/create status=302 location=" + checkout + " body=");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        } catch (IllegalArgumentException error) { failure(exchange, 400, "PARAM_ERROR", error.getMessage()); }
        catch (IOException error) { failure(exchange, 502, "DSPAY_ERROR", error.getMessage()); }
    }
    static void query(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        try {
            Map<String,String> args = parameters(exchange, "orderNo", "outOrderNo");
            if (args.getOrDefault("orderNo", "").isEmpty() && args.getOrDefault("outOrderNo", "").isEmpty()) throw new IllegalArgumentException("orderNo or outOrderNo is required");
            for (Map.Entry<String,String> e : args.entrySet()) if (!e.getValue().isEmpty()) identifier(e.getValue(), e.getKey());
            Map<String,Object> query = new TreeMap<>(); query.putAll(args); query.put("merchantNo", MERCHANT_NO); query.put("timestamp", System.currentTimeMillis());
            query.put("signature", hmac(canonicalCallback(encodeJson(query)), API_SECRET));
            send(exchange, 200, encodeJson(upstreamData("/dspay/public/order/query", encodeJson(query))));
        } catch (IllegalArgumentException error) { failure(exchange, 400, "PARAM_ERROR", error.getMessage()); }
        catch (IOException error) { failure(exchange, 502, "DSPAY_ERROR", error.getMessage()); }
    }
    static void notify(HttpExchange exchange) throws IOException {
        logRequest(exchange);
        if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, "{\"code\":\"FAIL\"}"); return; }
        String raw;
        try { raw = decodeUtf8(readAll(exchange.getRequestBody())); }
        catch (IllegalArgumentException error) { send(exchange, 400, "{\"code\":\"FAIL\",\"msg\":\"Callback must be UTF-8 JSON\"}"); return; }
        logEvent("CALLBACK_REQUEST", raw);
        if (!verifyCallback(raw, exchange.getRequestHeaders().getFirst("X-DSPay-Signature"), System.currentTimeMillis())) {
            send(exchange, 401, "{\"code\":\"FAIL\",\"msg\":\"Invalid signature, JSON, or timestamp (requires ±5 minutes)\"}"); return;
        }
        logEvent("CALLBACK_VERIFIED", "signature and timestamp valid");
        // Demo ACK only. Production must commit idempotent business state first.
        send(exchange, 200, "{\"code\":\"SUCCESS\",\"msg\":\"ok\"}");
    }

    /**
     * Payment redirects: returnUrl leads to the storefront; successRedirectUrl leads to the order query.
     * A redirect is not proof of payment; /query retrieves the authoritative order status from the server.
     */
    static void landing(HttpExchange exchange) throws IOException {
        try {
            boolean success = exchange.getRequestURI().getPath().equals("/payment/success");
            String target = "/";
            if (success) {
                Map<String,String> args = parameters(exchange, "outOrderNo");
                if (!args.containsKey("outOrderNo")) throw new IllegalArgumentException("outOrderNo is required");
                target = "/query?outOrderNo=" + enc(identifier(args.get("outOrderNo"), "outOrderNo"));
            }
            exchange.getResponseHeaders().set("Location", target);
            exchange.sendResponseHeaders(302, -1); exchange.close();
        } catch (IllegalArgumentException error) { failure(exchange, 400, "PARAM_ERROR", error.getMessage()); }
    }

    /** Java 8 standard-library HTTP/1.1 POST with explicit request framing. */
    static String[] dspayPost(String path, String jsonBody) throws IOException {
        logEvent("DSPAY_REQUEST", "POST " + DSPAY_BASE + path + " body=" + jsonBody);
        try {
            DspayTransport.Response response = DspayTransport.post(URI.create(DSPAY_BASE + path), jsonBody.getBytes(StandardCharsets.UTF_8));
            int status = response.status;
            String body = decodeUtf8(response.body);
            String ray = response.headers.get("cf-ray");
            String type = response.headers.get("content-type");
            logEvent("DSPAY_RESPONSE", "path=" + path + " status=" + status + " CF-Ray=" + ray + " contentType=" + type + " body=" + body);
            if (status < 200 || status >= 300) throw new IOException("DSPay HTTP " + status + (ray == null ? "" : "; CF-Ray: " + ray));
            return new String[]{String.valueOf(status), body};
        } catch (IllegalArgumentException error) {
            throw new IOException("DSPay request failed or returned invalid JSON", error);
        } catch (IOException error) {
            logEvent("DSPAY_ERROR", "path=" + path + " error=" + error.getMessage());
            throw error;
        }
    }

    /** Read an input stream without changing its bytes. */
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

    static final class JsonNumber {
        final BigDecimal value;
        final String raw;
        final boolean integer;
        JsonNumber(String raw) {
            this.raw = raw;
            integer = raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0;
            value = new BigDecimal(raw);
            if (value.precision() > 4096 || Math.abs((long)value.scale()) > 4096 || (long)value.precision() - value.scale() > 4096) throw new IllegalArgumentException("JSON number exceeds 4096-digit limit");
        }
        String plain() {
            String plain = value.toPlainString();
            return !integer && raw.startsWith("-") && value.signum() == 0 ? "-" + plain : plain;
        }
        String canonical() { return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString(); }
    }

    static final class JsonParser {
        final String raw;
        int index;
        JsonParser(String raw) { this.raw = raw; }
        void space() { while (index < raw.length() && " \t\r\n".indexOf(raw.charAt(index)) >= 0) index++; }
        Object parse() { Object result = value(); space(); if (index != raw.length()) throw new IllegalArgumentException("Trailing JSON data"); return result; }
        Object value() {
            space();
            if (index >= raw.length()) throw new IllegalArgumentException("JSON value required");
            char c = raw.charAt(index);
            if (c == '"') return string();
            if (c == '{' || c == '[') {
                boolean object = c == '{'; char end = object ? '}' : ']'; index++;
                Map<String, Object> map = new TreeMap<>(); java.util.List<Object> list = new java.util.ArrayList<>();
                space(); if (index < raw.length() && raw.charAt(index) == end) { index++; return object ? map : list; }
                while (true) {
                    if (object) {
                        space(); if (index >= raw.length() || raw.charAt(index) != '"') throw new IllegalArgumentException("JSON key required");
                        String key = string(); space();
                        if (index >= raw.length() || raw.charAt(index++) != ':') throw new IllegalArgumentException("JSON colon required");
                        if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON field: " + key);
                        map.put(key, value());
                    } else list.add(value());
                    space(); if (index >= raw.length()) throw new IllegalArgumentException("JSON separator required");
                    c = raw.charAt(index++); if (c == end) return object ? map : list;
                    if (c != ',') throw new IllegalArgumentException("JSON separator required");
                }
            }
            for (String token : new String[]{"true", "false", "null"}) {
                if (raw.startsWith(token, index)) { index += token.length(); return "null".equals(token) ? null : Boolean.valueOf(token); }
            }
            Matcher m = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matcher(raw.substring(index));
            if (!m.lookingAt()) throw new IllegalArgumentException("Invalid JSON value");
            index += m.group().length(); return new JsonNumber(m.group());
        }
        String string() {
            index++; StringBuilder out = new StringBuilder(); boolean closed = false;
            while (index < raw.length()) {
                char c = raw.charAt(index++);
                if (c == '"') { closed = true; break; }
                if (c < 32) throw new IllegalArgumentException("JSON string contains a control character");
                if (c == '\\') {
                    if (index >= raw.length()) throw new IllegalArgumentException("Invalid JSON escape");
                    c = raw.charAt(index++);
                    if (c == 'u') {
                        if (index + 4 > raw.length() || !raw.substring(index, index + 4).matches("[a-fA-F0-9]{4}")) throw new IllegalArgumentException("Invalid Unicode escape");
                        c = (char)Integer.parseInt(raw.substring(index, index + 4), 16); index += 4;
                    } else {
                        String escapes = "\"\\/bfnrt", values = "\"\\/\b\f\n\r\t";
                        int at = escapes.indexOf(c); if (at < 0) throw new IllegalArgumentException("Invalid JSON escape");
                        c = values.charAt(at);
                    }
                }
                out.append(c);
            }
            if (!closed) throw new IllegalArgumentException("Unterminated JSON string");
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= out.length() || !Character.isLowSurrogate(out.charAt(i))) throw new IllegalArgumentException("Invalid Unicode surrogate");
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode surrogate");
            }
            return out.toString();
        }
    }
    static Object parseJson(String raw) { return new JsonParser(raw).parse(); }
    static String encodeJsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
    static String encodeJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return encodeJsonString((String)value);
        if (value instanceof JsonNumber) return ((JsonNumber)value).canonical();
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            Map<String,Object> sorted = new TreeMap<>();
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) sorted.put((String)e.getKey(), e.getValue());
            for (Map.Entry<String,Object> e : sorted.entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(encodeJsonString((String)e.getKey())).append(':').append(encodeJson(e.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof java.util.List) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (java.util.List<?>)value) { if (out.length() > 1) out.append(','); out.append(encodeJson(item)); }
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }
    static String canonicalJsonRaw(String raw) { return encodeJson(parseJson(raw)); }
    static String canonicalCallback(String raw) {
        Object parsed = parseJson(raw);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("JSON object required");
        Map<String,String> fields = new TreeMap<>();
        for (Map.Entry<?,?> e : ((Map<?,?>)parsed).entrySet()) {
            Object v = e.getValue(); if (v == null || "signature".equals(e.getKey())) continue;
            fields.put((String)e.getKey(), v instanceof String ? (String)v : v instanceof JsonNumber ? ((JsonNumber)v).plain() : encodeJson(v));
        }
        return canonicalFields(fields);
    }
    static boolean verifyCallback(String raw, String signature, long now) {
        if (signature == null || !signature.matches("[0-9a-fA-F]{64}")) return false;
        try {
            Object parsed = parseJson(raw);
            if (!(parsed instanceof Map)) return false;
            Object timestamp = ((Map<?,?>)parsed).get("timestamp");
            if (!(timestamp instanceof JsonNumber) || !((JsonNumber)timestamp).integer) return false;
            BigDecimal stamp = ((JsonNumber)timestamp).value;
            if (stamp.signum() < 0 || stamp.subtract(BigDecimal.valueOf(now)).abs().compareTo(BigDecimal.valueOf(300000)) > 0) return false;
            String expected = hmac(canonicalCallback(raw), API_SECRET);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) { return false; }
    }

    static String decodeUtf8(byte[] raw) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString(); }
        catch (java.nio.charset.CharacterCodingException error) { throw new IllegalArgumentException("Input must be valid UTF-8"); }
    }
    static Map<String,String> query(String raw) {
        Map<String,String> fields = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return fields;
        String[] pairs = raw.split("&");
        if (pairs.length > 20) throw new IllegalArgumentException("At most 20 query fields are allowed");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int at = pair.indexOf('=');
            String key = urlDecode(at < 0 ? pair : pair.substring(0, at));
            String value = at < 0 ? "" : urlDecode(pair.substring(at + 1));
            if (fields.containsKey(key)) throw new IllegalArgumentException(key + " must occur exactly once");
            fields.put(key, value);
        }
        return fields;
    }
    static void send(HttpExchange e, int status, String body) throws IOException {
        logEvent("DEMO_RESPONSE", "path=" + e.getRequestURI().getPath() + " status=" + status + " body=" + body);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
    /** Encode and decode URL fields using UTF-8. */
    static String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    static String urlDecode(String value) {
        try { String decoded = URLDecoder.decode(value, "UTF-8");
            if (decoded.indexOf('\ufffd') >= 0) throw new IllegalArgumentException("Query must be valid UTF-8");
            return decoded; }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
    static String esc(String value) { String quoted = encodeJsonString(value); return quoted.substring(1, quoted.length() - 1); }
}
