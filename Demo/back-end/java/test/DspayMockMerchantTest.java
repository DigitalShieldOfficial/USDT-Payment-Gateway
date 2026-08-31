public class DspayMockMerchantTest {
    public static void main(String[] args) {
        String canonical = "merchantNo=DSM001&outOrderNo=M001&productPrice=&productPriceCurrency="
                + "&productId=&attach=&payAmount=1.00&allowedPaymentMethods=&returnUrl="
                + "&successRedirectUrl=&timestamp=1787700000000";
        assertEquals("9507a6d35f0df0eb4c909194652c53b0da52280452e7159f330b5ed2dd9581f1",
                DspayMockMerchant.hmac(canonical, "demo-secret"), "create signature");

        String raw = "{\"notifyNo\":\"N001\",\"status\":\"COMPLETED\"}";
        String signature = DspayMockMerchant.hmac(raw, "demo-secret");
        assertEquals(signature, DspayMockMerchant.hmac(raw, "demo-secret"), "raw callback signature");
        System.out.println("DspayMockMerchantTest: PASS");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
