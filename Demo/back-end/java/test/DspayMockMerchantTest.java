public class DspayMockMerchantTest {

    public static void main(String[] args) {
        assertEquals("100.12", DspayMockMerchant.normalizePayAmount("100.12"),
                "payAmount must preserve decimal string bytes");
        assertEquals("0.01", DspayMockMerchant.normalizePayAmount("0.01"),
                "smallest conventional payAmount must be accepted");

        assertThrows(() -> DspayMockMerchant.normalizePayAmount("1e2"),
                "scientific notation must be rejected");
        assertThrows(() -> DspayMockMerchant.normalizePayAmount("100.123"),
                "payAmount with more than 2 decimal places must be rejected");
        assertThrows(() -> DspayMockMerchant.normalizePayAmount("0"),
                "zero payAmount must be rejected");
        assertThrows(() -> DspayMockMerchant.signOrder("DSM1", "", "0.01", 1L, "secret"),
                "blank outOrderNo must be rejected");

        String signature = DspayMockMerchant.signOrder(
                "DSM1", "ORDER-001", "100.12", 1717689600000L, "secret");
        assertEquals("8864aa09c5fb8011ee615fe3e627d7e24214220fe18deec2f8c767411d284ae4",
                signature, "signature must use unmodified payAmount string");

        System.out.println("DspayMockMerchantTest: PASS");
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
