package com.CodeBySonu.VoxSherpa;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Full-screen PDF viewer: pages rendered as bitmaps with pinch-zoom and
 * page-by-page navigation.
 *
 * Text selection is deliberately absent — you cannot select on a rasterised
 * page. "Speak" reads the current page's extracted text through the system TTS
 * engine instead, and scanned PDFs are flagged as such because they have no
 * text layer at all.
 */
public class PdfReaderActivity extends android.app.Activity {

    private static final String TAG = "PdfReader";

    public static final String EXTRA_URI = "pdf_uri";
    public static final String EXTRA_TITLE = "pdf_title";

    private ImageView image;
    private TextView pageTv, scannedNote, titleTv;
    private ProgressBar progress;
    private Button prevBtn, nextBtn;

    private PdfRasterizer.Doc doc;
    private int pageIndex = 0;
    private boolean scanned = false;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_pdf_reader);

        image = findViewById(R.id.pdf_image);
        pageTv = findViewById(R.id.txt_page);
        scannedNote = findViewById(R.id.txt_scanned_note);
        titleTv = findViewById(R.id.txt_pdf_title);
        progress = findViewById(R.id.pdf_progress);
        prevBtn = findViewById(R.id.btn_prev);
        nextBtn = findViewById(R.id.btn_next);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_speak_page).setOnClickListener(v -> speakPage());
        prevBtn.setOnClickListener(v -> step(-1));
        nextBtn.setOnClickListener(v -> step(1));

        String t = getIntent().getStringExtra(EXTRA_TITLE);
        titleTv.setText(t == null || t.isEmpty() ? "PDF" : t);

        Uri uri = getIntent().getParcelableExtra(EXTRA_URI);
        if (uri == null) { finish(); return; }
        openDoc(uri);
    }

    private void openDoc(final Uri uri) {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            PdfRasterizer.Doc d = null;
            String err = null;
            boolean isScanned = false;
            try {
                d = PdfRasterizer.open(getApplicationContext(), uri);
                isScanned = PdfRasterizer.looksScanned(d);
            } catch (Exception e) {
                err = e.getMessage();
                Log.w(TAG, "open failed", e);
            }
            final PdfRasterizer.Doc fd = d;
            final String ferr = err;
            final boolean fscanned = isScanned;
            runOnUiThread(() -> {
                if (fd == null) {
                    Toast.makeText(this,
                            ferr != null ? ("Could not open PDF: " + ferr) : "Could not open PDF",
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                doc = fd;
                scanned = fscanned;
                if (scanned) scannedNote.setVisibility(View.VISIBLE);
                renderPage(0);
            });
        }).start();
    }

    private void step(int delta) {
        if (doc == null) return;
        int next = pageIndex + delta;
        if (next < 0 || next >= doc.pageCount) return;
        renderPage(next);
    }

    private void renderPage(int index) {
        if (doc == null) return;
        if (index < 0) index = 0;
        if (index >= doc.pageCount) index = doc.pageCount - 1;
        pageIndex = index;

        pageTv.setText("Page " + (pageIndex + 1) + " of " + doc.pageCount);
        prevBtn.setEnabled(pageIndex > 0);
        nextBtn.setEnabled(pageIndex < doc.pageCount - 1);
        progress.setVisibility(View.VISIBLE);

        final int idx = pageIndex;
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                int widthPx = getResources().getDisplayMetrics().widthPixels;
                bmp = doc.renderPage(idx, widthPx);
            } catch (Exception e) {
                Log.w(TAG, "render failed", e);
            }
            final Bitmap fb = bmp;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (fb == null) {
                    Toast.makeText(this, "Could not render that page", Toast.LENGTH_SHORT).show();
                    return;
                }
                image.setImageBitmap(fb);
            });
        }).start();
    }

    /** Reads the current page's text layer out loud. */
    private void speakPage() {
        if (doc == null) return;
        if (scanned) {
            Toast.makeText(this,
                    "Scanned PDF — these pages are images with no text to read.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String text = doc.pageText(pageIndex);
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "No text on this page", Toast.LENGTH_SHORT).show();
            return;
        }
        final String clipped = text.length() > 25000 ? text.substring(0, 25000) : text;
        if (tts == null) {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS) {
                    Toast.makeText(this, "TTS engine unavailable", Toast.LENGTH_SHORT).show();
                    return;
                }
                try { tts.setLanguage(Locale.getDefault()); } catch (Exception ignored) {}
                tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, "page");
            });
        } else {
            tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, "page");
        }
        Toast.makeText(this, "Reading page " + (pageIndex + 1) + "…", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        if (doc != null) {
            doc.close();
            if (doc.pdfFile != null && doc.pdfFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                doc.pdfFile.delete();
            }
        }
        super.onDestroy();
    }
}
