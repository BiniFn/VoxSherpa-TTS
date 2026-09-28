import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.File;

/** Verifies PDFBox can actually load+extract our real sample.pdf on a desktop JVM,
 *  mirroring exactly what TextImportHelper._extractTextFromPdf does. */
public class TestPdf {
    public static void main(String[] a) throws Exception {
        System.out.println("=== REAL PDF EXTRACTION (mirrors _extractTextFromPdf) ===");
        int fails = 0;
        StringBuilder sb = new StringBuilder();
        try (InputStream2 ignored = null) { }
        try (java.io.InputStream in = new java.io.FileInputStream("/tmp/epubtest/sample.pdf");
             PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = doc.getNumberOfPages();
            System.out.println("pages: " + pages);
            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                sb.append(stripper.getText(doc)).append("\n");
            }
        }
        String text = sb.toString();
        System.out.println("[" + text.trim() + "]");
        System.out.println();

        boolean ok1 = text.contains("Chapter 1");
        boolean ok2 = text.contains("quick brown fox");
        boolean ok3 = text.contains("Chapter 2");
        boolean ok4 = text.contains("liquor jugs");
        fails += ok1 ? 0 : 1;
        fails += ok2 ? 0 : 1;
        fails += ok3 ? 0 : 1;
        fails += ok4 ? 0 : 1;
        System.out.println((ok1 ? "PASS  " : "FAIL  ") + "page 1 text extracted");
        System.out.println((ok2 ? "PASS  " : "FAIL  ") + "sentence on page 1 present");
        System.out.println((ok3 ? "PASS  " : "FAIL  ") + "page 2 text extracted (multi-page loop)");
        System.out.println((ok4 ? "PASS  " : "FAIL  ") + "sentence on page 2 present");

        System.out.println();
        System.out.println(fails == 0 ? "PDF ALL PASS" : fails + " PDF FAILURE(S)");
        if (fails > 0) System.exit(1);
    }

    interface InputStream2 extends java.io.Closeable { void close(); }
}
