import com.CodeBySonu.VoxSherpa.EpubHelper;
import java.lang.reflect.Method;

public class TestEpub {
    public static void main(String[] args) throws Exception {
        // Drive the real xhtmlToText + spine logic without an Android device.
        Method m = EpubHelper.class.getDeclaredMethod("xhtmlToText", byte[].class);
        m.setAccessible(true);

        String c1 = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style>p{color:red}</style></head><body><h1>Chapter One</h1><p>The quick brown fox jumps over the lazy dog.</p><p>Tom &amp; Jerry&nbsp;here.</p><script>var evil=\"SHOULD_NOT_APPEAR\";</script></body></html>";
        String c2 = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Chapter Two</h1><p>Second chapter content.</p><p>Line one<br/>Line two.</p></body></html>";

        System.out.println("=== chapter1 flattened ===");
        String t1 = (String) m.invoke(null, (Object) c1.getBytes("UTF-8"));
        System.out.println("[" + t1 + "]");
        System.out.println();
        System.out.println("=== chapter2 flattened ===");
        String t2 = (String) m.invoke(null, (Object) c2.getBytes("UTF-8"));
        System.out.println("[" + t2 + "]");

        System.out.println();
        boolean pass = true;
        check(pass, t1.contains("The quick brown fox"), "c1 keeps body text");
        check(pass, !t1.contains("SHOULD_NOT_APPEAR"), "c1 strips <script>");
        check(pass, !t1.contains("color:red"), "c1 strips <style>");
        check(pass, t1.contains("Tom & Jerry"), "c1 decodes &amp;");
        check(pass, !t1.contains("&nbsp;"), "c1 decodes &nbsp;");
        check(pass, !t1.contains("<h1>") && !t1.contains("</h1>"), "c1 strips tags");
        check(pass, t2.contains("Line one") && t2.contains("Line two"), "c2 keeps <br/> content");
        check(pass, t2.contains("\n"), "c2 <br/> became newline");

        System.out.println();
        System.out.println(pass ? "ALL PASS" : "FAILURES PRESENT");
        if (!pass) System.exit(1);
    }

    static void check(boolean pass, boolean cond, String label) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + label);
    }
}
