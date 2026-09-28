import com.CodeBySonu.VoxSherpa.EpubHelper;
import com.CodeBySonu.VoxSherpa.TextImportHelper;

import java.io.File;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.FileOutputStream;

/** End-to-end checks against REAL files, driving the real app code. */
public class TestPipeline {
    static int fails = 0;

    public static void main(String[] a) throws Exception {
        // ---------- 1. PDF extraction (real PDFBox path) ----------
        System.out.println("=== PDF EXTRACTION (real file via real PDFBox) ===");
        Method pdf = TextImportHelper.class.getDeclaredMethod(
                "_extractTextFromPdf", android.content.Context.class, android.net.Uri.class);
        pdf.setAccessible(true);
        try {
            Method init = Class.forName("com.tom_roush.pdfbox.android.PDFBoxResourceLoader")
                    .getDeclaredMethod("init", android.content.Context.class);
            System.out.println("(PDFBoxResourceLoader needs real Android Context - skipped in JVM)");
        } catch (Throwable t) {
            System.out.println("(android resource loader unavailable on desktop JVM)");
        }

        // ---------- 2. EPUB full pipeline (zip -> container -> opf -> spine -> text) ----------
        System.out.println();
        System.out.println("=== EPUB FULL PIPELINE (real zip, real spine order) ===");
        File epub = buildEpub();
        Method fromFile = EpubHelper.class.getDeclaredMethod("extractFromFile", File.class, int.class);
        fromFile.setAccessible(true);
        String text = (String) fromFile.invoke(null, epub, 100000);

        System.out.println("[" + text.trim() + "]");
        System.out.println();

        check(text.contains("Chapter One"), "chapter 1 text present");
        check(text.contains("Chapter Two"), "chapter 2 text present");
        check(!text.contains("SHOULD_NOT_APPEAR"), "script content stripped");
        check(!text.contains("color:red"), "style content stripped");
        check(text.contains("Tom & Jerry"), "entities decoded");

        int i1 = text.indexOf("Chapter One");
        int i2 = text.indexOf("Chapter Two");
        check(i1 >= 0 && i2 > i1, "spine order respected (ch1 before ch2)");

        // ---------- 3. MAX_CHAR_LIMIT truncation ----------
        System.out.println();
        System.out.println("=== CHAR LIMIT ===");
        String capped = (String) fromFile.invoke(null, epub, 40);
        check(capped.length() <= 40, "respects maxChars ceiling exactly (got " + capped.length() + ")");
        check(capped.length() > 0, "still returns content when capped");

        // oversized single-chapter book must not exceed the cap
        File big = new File("/tmp/epubtest/big.epub");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) huge.append("<p>Filler sentence number ").append(i).append(".</p>");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(big))) {
            put(z, "META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"c.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            put(z, "c.opf",
                "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title>"
                + "<dc:identifier id=\"id\">x</dc:identifier><dc:language>en</dc:language></metadata>"
                + "<manifest><item id=\"c1\" href=\"big.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                + "<spine><itemref idref=\"c1\"/></spine></package>");
            put(z, "big.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" + huge + "</body></html>");
        }
        String bigText = (String) fromFile.invoke(null, big, 25000);
        check(bigText.length() <= 25000, "single huge chapter capped (got " + bigText.length() + ")");
        check(bigText.contains("Filler sentence number 0"), "huge chapter still yields real text");

        // ---------- 4. broken epub must not crash ----------
        System.out.println();
        System.out.println("=== MALFORMED INPUT ===");
        File bad = new File("/tmp/epubtest/broken.epub");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(bad))) {
            z.putNextEntry(new ZipEntry("random.txt"));
            z.write("not an epub".getBytes());
            z.closeEntry();
        }
        boolean threw = false;
        try {
            fromFile.invoke(null, bad, 100000);
        } catch (Exception e) {
            threw = true;
            System.out.println("  correctly rejected: " + e.getCause());
        }
        check(threw, "malformed epub raises instead of crashing");

        System.out.println();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURE(S)");
        if (fails > 0) System.exit(1);
    }

    static File buildEpub() throws Exception {
        File f = new File("/tmp/epubtest/sample.epub");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "mimetype", "application/epub+zip");
            put(z, "META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            // manifest lists c2 BEFORE c1 to prove spine order (not manifest order) is used
            put(z, "OEBPS/content.opf",
                "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title>"
                + "<dc:identifier id=\"id\">x</dc:identifier><dc:language>en</dc:language></metadata>"
                + "<manifest>"
                + "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                + "<item id=\"c2\" href=\"text/chapter2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"c1\" href=\"text/chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"css\" href=\"style/main.css\" media-type=\"text/css\"/>"
                + "</manifest><spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>");
            put(z, "OEBPS/nav.xhtml", "<html><body><nav>Chapter One Chapter Two</nav></body></html>");
            put(z, "OEBPS/text/chapter1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style>p{color:red}</style></head>"
                + "<body><h1>Chapter One</h1><p>Body of chapter one.</p>"
                + "<p>Tom &amp; Jerry&nbsp;here.</p><script>var x=\"SHOULD_NOT_APPEAR\";</script></body></html>");
            put(z, "OEBPS/text/chapter2.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Chapter Two</h1>"
                + "<p>Body of chapter two.</p></body></html>");
            put(z, "OEBPS/style/main.css", "p{margin:0}");
        }
        return f;
    }

    static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    static void check(boolean cond, String label) {
        if (!cond) fails++;
        System.out.println((cond ? "PASS  " : "FAIL  ") + label);
    }
}
