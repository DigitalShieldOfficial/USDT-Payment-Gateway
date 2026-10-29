public class DspayMockMerchantTest {
    public static void main(String[] args) {
        assertEquals("https://pay-api.ds.pro", DspayMockMerchant.apiBase("https://pay-api.ds.pro"), "production API origin");
        assertEquals("http://localhost:12345", DspayMockMerchant.apiBase("http://localhost:12345"), "custom API origin");
        for (String invalid : new String[]{"", " https://pay-api.ds.pro", "https://pay-api.ds.pro/"}) {
            try {
                DspayMockMerchant.apiBase(invalid);
                throw new AssertionError("Invalid API origin must be rejected");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().contains("DSPAY_BASE_URL")) throw new AssertionError("Missing field name");
            }
        }
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        System.setOut(new java.io.PrintStream(captured));
        try { DspayMockMerchant.logEvent("TEST", "{\"signature\": \"sensitive-signature\",\"payAmount\":\"0.01\"}"); }
        finally { System.setOut(original); }
        String logged = captured.toString();
        if (logged.contains("sensitive-signature") || !logged.contains("0.01")) throw new AssertionError("Log must redact signatures and retain parameters: " + logged);
        String canonical = "merchantNo=DSM001&outOrderNo=M001&payAmount=1.00&timestamp=1787700000000";
        assertEquals("e522c9b170a8e9389f86170ed3b5f05c8a52e14374ec6166799f97b5f409d68b",
                DspayMockMerchant.hmac(canonical, "demo-secret"), "create signature");

        String raw = "{\"status\":\"COMPLETED\",\"txHash\":null,\"notifyNo\":\"N001\",\"attach\":{\"z\":1.0,\"a\":true}}";
        String callbackCanonical = DspayMockMerchant.canonicalCallback(raw);
        assertEquals("attach={\"a\":true,\"z\":1}&notifyNo=N001&status=COMPLETED", callbackCanonical, "callback canonical");
        String signature = DspayMockMerchant.hmac(callbackCanonical, "demo-secret");
        assertEquals(signature, DspayMockMerchant.hmac(callbackCanonical, "demo-secret"), "callback signature");
        long now = 1787700000000L;
        String precise = "{\"timestamp\":1787700000000,\"amount\":1.2300,\"attach\":{\"x\":90071992547409931234567890,\"y\":1.234567890123456789}}";
        String expected = "amount=1.2300&attach={\"x\":90071992547409931234567890,\"y\":1.234567890123456789}&timestamp=1787700000000";
        assertEquals(expected, DspayMockMerchant.canonicalCallback(precise), "lossless numeric canonical");
        String signed = DspayMockMerchant.hmac(expected, DspayMockMerchant.API_SECRET);
        for (long clock : new long[]{now, now - 300000, now + 300000}) {
            if (!DspayMockMerchant.verifyCallback(precise, signed, clock)) throw new AssertionError("Valid callback rejected");
        }
        for (long clock : new long[]{now - 300001, now + 300001}) {
            if (DspayMockMerchant.verifyCallback(precise, signed, clock)) throw new AssertionError("Expired/future callback accepted");
        }
        System.out.println("DspayMockMerchantTest: PASS");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
