import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import java.io.File;

public class MakePdf {
    public static void main(String[] a) throws Exception {
        PDDocument doc = new PDDocument();
        for (int p = 1; p <= 3; p++) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Chapter " + p + " of the test document.");
                cs.newLine();
                cs.showText("The quick brown fox jumps over the lazy dog.");
                cs.endText();
            }
        }
        doc.save(new File("/tmp/epubtest/sample.pdf"));
        doc.close();
        System.out.println("PDF written");
    }
}
