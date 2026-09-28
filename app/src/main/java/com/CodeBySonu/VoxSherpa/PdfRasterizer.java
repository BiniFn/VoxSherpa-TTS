package com.CodeBySonu.VoxSherpa;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Rasterises PDF pages to PNG for display.
 *
 * We agreed to the rasterised approach: Android has no built-in PDF renderer, so
 * pages are drawn to bitmaps. The trade-off is explicit — you cannot select text
 * on a rasterised page, so the reader offers "send this page to TTS" via the
 * page's extracted text instead of on-image selection.
 */
public class PdfRasterizer {

    private static final String TAG = "PdfRasterizer";
    private static final long MAX_BYTES = 256L * 1024L * 1024L;

    public static class Doc implements AutoCloseable {
        public final File pdfFile;
        public final int pageCount;
        private final PDDocument document;

        Doc(File pdfFile, PDDocument document) {
            this.pdfFile = pdfFile;
            this.document = document;
            this.pageCount = document.getNumberOfPages();
        }

        public Bitmap renderPage(int index, int targetWidthPx) throws Exception {
            if (index < 0 || index >= pageCount) return null;
            PDFRenderer renderer = new PDFRenderer(document);
            // guard against absurd page dimensions blowing up the bitmap
            int w = Math.max(320, Math.min(targetWidthPx, 2048));
            Bitmap bmp = renderer.renderImageWithDPI(index, 96, ImageType.RGB);
            if (bmp.getWidth() > w) {
                float scale = (float) w / bmp.getWidth();
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, w,
                        Math.max(1, (int) (bmp.getHeight() * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            return bmp;
        }

        /** Text of one page, for the TTS handoff. */
        public String pageText(int index) {
            try {
                com.tom_roush.pdfbox.text.PDFTextStripper stripper =
                        new com.tom_roush.pdfbox.text.PDFTextStripper();
                stripper.setStartPage(index + 1);
                stripper.setEndPage(index + 1);
                return stripper.getText(document);
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public void close() {
            try { document.close(); } catch (Exception ignored) {}
        }
    }

    /** Copies the Uri to a temp file (PDDocument needs a seekable path) and opens it. */
    public static Doc open(Context ctx, Uri uri) throws Exception {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());

        File tmp = File.createTempFile("vox_pdf_", ".pdf", ctx.getCacheDir());
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IllegalStateException("Cannot open " + uri);
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > MAX_BYTES) throw new IllegalStateException("PDF too large");
                out.write(buf, 0, r);
            }
        }
        try {
            PDDocument doc = PDDocument.load(tmp);
            return new Doc(tmp, doc);
        } catch (Exception e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            throw e;
        }
    }

    /** True if the PDF has no extractable text — i.e. it's a scan. */
    public static boolean looksScanned(Doc d) {
        int probe = Math.min(3, d.pageCount);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < probe; i++) {
            sb.append(d.pageText(i));
        }
        // fewer than ~20 chars across the first pages means no real text layer
        return sb.toString().replaceAll("\\s+", "").length() < 20;
    }
}
