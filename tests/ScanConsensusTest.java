import com.labelscanner.offline.ScanConsensus;

public class ScanConsensusTest {
    private static void yes(boolean v, String msg) { if (!v) throw new AssertionError(msg); }
    public static void main(String[] args) {
        ScanConsensus c = new ScanConsensus();
        yes(!c.offer("91843", "ML Kit", 1, 1000), "one ML frame can't confirm");
        yes(!c.offer("91843", "Tesseract", 1, 1100), "same frame can't confirm with another engine");
        yes(c.offer("91843", "ML Kit", 2, 1200), "two independent ML frames should confirm");
        c.reset();
        yes(!c.offer("102790", "ML Kit", 3, 0), "first ML frame");
        yes(!c.offer("102790", "ML Kit", 4, 1900), "beyond fast window resets");
        yes(c.offer("102790", "ML Kit", 5, 1950), "second frame within fast window confirms");
        c.reset();
        yes(!c.offer("91843", "Tesseract", 6, 1000), "first slower OCR result");
        yes(c.offer("91843", "Tesseract", 7, 3999), "older phone gets longer fallback confirmation");
        c.reset();
        yes(!c.offer("91843", "Tesseract", 8, 1000), "first fallback result");
        yes(!c.offer("91843", "Tesseract", 9, 4300), "timeout beyond 3200 not accepted");
        c.reset();
        yes(!c.offer("1234", "ML Kit", 10, 1000), "one first value");
        yes(!c.offer("1235", "ML Kit", 11, 1200), "wrong next frame rejected");
        yes(c.offer("1235", "ML Kit", 12, 1400), "repeated stable value is accepted");
        c.reset();
        yes(!c.offer("142/П1/1", "QR", 13, 1000), "first QR frame");
        yes(c.offer("142/П1/1", "QR", 14, 1300), "QR stays two-frame confirmed");
        System.out.println("OK: two-frame confirmations, speed-dependent windows, deduplication, stale mismatches, QR");
    }
}
