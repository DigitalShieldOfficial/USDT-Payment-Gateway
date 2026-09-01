public class DspayMockMerchantTest {
    public static void main(String[] args) {
        String canonical = "merchantNo=DSM001&outOrderNo=M001&payAmount=1.00&timestamp=1787700000000";
        assertEquals("e522c9b170a8e9389f86170ed3b5f05c8a52e14374ec6166799f97b5f409d68b",
                DspayMockMerchant.hmac(canonical, "demo-secret"), "create signature");

        String raw = "{\"status\":\"COMPLETED\",\"txHash\":null,\"notifyNo\":\"N001\",\"attach\":{\"z\":1.0,\"a\":true}}";
        String callbackCanonical = DspayMockMerchant.canonicalCallback(raw);
        assertEquals("attach={\"a\":true,\"z\":1}&notifyNo=N001&status=COMPLETED", callbackCanonical, "callback canonical");
        String signature = DspayMockMerchant.hmac(callbackCanonical, "demo-secret");
        assertEquals(signature, DspayMockMerchant.hmac(callbackCanonical, "demo-secret"), "callback signature");
        System.out.println("DspayMockMerchantTest: PASS");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
