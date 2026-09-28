package com.CodeBySonu.VoxSherpa;

import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * Full-screen EPUB reader.
 *
 * Each spine chapter is rendered directly in a WebView, which is what buys us
 * real text selection (the OS blue handles) and inline images — neither is
 * possible with a flattened plain-text string. The "Speak" button hands the
 * current chapter's text to the system TTS engine; if VoxSherpa is the default
 * engine that is the neural voice, otherwise it falls back to the user's engine.
 */
public class EpubReaderActivity extends android.app.Activity {

    private static final String TAG = "EpubReader";

    public static final String EXTRA_URI = "epub_uri";
    public static final String EXTRA_TITLE = "epub_title";

    private WebView web;
    private TextView titleTv, chapterTv;
    private ProgressBar progress;
    private Button prevBtn, nextBtn;

    private EpubPacker.Packed packed;
    private int chapterIndex = 0;
    private int fontPx = 44;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_epub_reader);

        web = findViewById(R.id.reader_web);
        titleTv = findViewById(R.id.txt_reader_title);
        chapterTv = findViewById(R.id.txt_chapter);
        progress = findViewById(R.id.reader_progress);
        prevBtn = findViewById(R.id.btn_prev);
        nextBtn = findViewById(R.id.btn_next);
        ImageView speakBtn = findViewById(R.id.btn_speak);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_font_bigger).setOnClickListener(v -> { fontPx += 6; reload(); });
        findViewById(R.id.btn_font_smaller).setOnClickListener(v -> { fontPx = Math.max(28, fontPx - 6); reload(); });
        prevBtn.setOnClickListener(v -> step(-1));
        nextBtn.setOnClickListener(v -> step(1));
        speakBtn.setOnClickListener(v -> speakCurrent());

        fontPx = (int) (21 * getResources().getDisplayMetrics().scaledDensity);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);   // EPUB content is untrusted input
        s.setAllowFileAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setBackgroundColor(0xFF0B1120);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
        });

        String t = getIntent().getStringExtra(EXTRA_TITLE);
        titleTv.setText(t == null || t.isEmpty() ? "Reader" : t);

        Uri uri = getIntent().getParcelableExtra(EXTRA_URI);
        if (uri == null) { finish(); return; }
        loadBook(uri);
    }

    private void loadBook(final Uri uri) {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            EpubPacker.Packed p = null;
            String err = null;
            try {
                p = EpubPacker.unpack(getApplicationContext(), uri);
            } catch (Exception e) {
                err = e.getMessage();
                Log.w(TAG, "unpack failed", e);
            }
            final EpubPacker.Packed fp = p;
            final String ferr = err;
            runOnUiThread(() -> {
                if (fp == null || fp.chapters.isEmpty()) {
                    Toast.makeText(this,
                            ferr != null ? ("Could not open book: " + ferr) : "No readable chapters",
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                packed = fp;
                if (fp.title != null && !fp.title.isEmpty()) titleTv.setText(fp.title);
                showChapter(0);
            });
        }).start();
    }

    private void showChapter(int index) {
        if (packed == null) return;
        if (index < 0) index = 0;
        if (index >= packed.chapters.size()) index = packed.chapters.size() - 1;
        chapterIndex = index;

        File f = new File(packed.rootDir, packed.chapters.get(index));
        if (!f.exists()) {
            Toast.makeText(this, "Missing chapter file", Toast.LENGTH_SHORT).show();
            return;
        }
        final File chapter = f;
        final String baseDir = chapter.getParent() == null
                ? packed.rootDir.getAbsolutePath()
                : new File(chapter.getParent()).getAbsolutePath();

        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String html = "";
            try {
                html = new String(java.nio.file.Files.readAllBytes(chapter.toPath()), "UTF-8");
            } catch (Exception e) {
                Log.w(TAG, "chapter read failed", e);
            }
            final String doc = injectStyles(html);
            runOnUiThread(() ->
                    web.loadDataWithBaseURL("file://" + baseDir + "/", doc, "text/html", "utf-8", null));
        }).start();
        updateChrome();
    }

    /** Prepends a dark, readable stylesheet; the book's own markup is preserved. */
    private String injectStyles(String html) {
        String style =
                "<style>"
              + "body{background:#0B1120!important;color:#E2E8F0!important;margin:0;"
              + "padding:18px 18px 40px 18px!important;"
              + "font-family:Georgia,'Times New Roman',serif!important;"
              + "line-height:1.65!important;font-size:" + fontPx + "px!important;}"
              + "img{max-width:100%!important;height:auto!important;border-radius:8px;margin:12px 0;}"
              + "p{margin:0 0 1.05em 0;}"
              + "h1,h2,h3,h4{color:#fff!important;line-height:1.25;}"
              + "a{color:#5B8CFF!important;}"
              + "blockquote{border-left:3px solid #334155;margin:1em 0;padding-left:12px;color:#94A3B8;}"
              + "::selection{background:#2563EB!important;color:#fff!important;}"
              + "</style>";
        int headEnd = html.toLowerCase().indexOf("</head>");
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + style + html.substring(headEnd);
        }
        return style + html;
    }

    private void updateChrome() {
        int total = packed == null ? 0 : packed.chapters.size();
        chapterTv.setText("Chapter " + (chapterIndex + 1) + " of " + total);
        prevBtn.setEnabled(chapterIndex > 0);
        nextBtn.setEnabled(chapterIndex < total - 1);
    }

    private void step(int delta) {
        if (packed == null) return;
        int next = chapterIndex + delta;
        if (next < 0 || next >= packed.chapters.size()) return;
        showChapter(next);
    }

    private void reload() {
        if (packed != null) showChapter(chapterIndex);
    }

    /** Pulls the rendered chapter's text and hands it to the TTS engine. */
    private void speakCurrent() {
        if (packed == null) return;
        web.evaluateJavascript(
                "(function(){var b=document.body;return b?b.innerText:'';})()",
                value -> {
                    String text = unescapeJs(value);
                    if (text == null || text.trim().isEmpty()) {
                        Toast.makeText(this, "Nothing to read on this page", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startTts(text);
                });
    }

    private void startTts(String text) {
        final String clipped = text.length() > 25000 ? text.substring(0, 25000) : text;
        if (tts == null) {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                ttsReady = (status == TextToSpeech.SUCCESS);
                if (!ttsReady) {
                    Toast.makeText(this, "TTS engine unavailable", Toast.LENGTH_SHORT).show();
                    return;
                }
                try { tts.setLanguage(Locale.getDefault()); } catch (Exception ignored) {}
                tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, "chapter");
            });
        } else if (ttsReady) {
            tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, "chapter");
        }
        Toast.makeText(this, "Reading chapter…", Toast.LENGTH_SHORT).show();
    }

    /** evaluateJavascript returns a quoted, escaped JS string literal. */
    private static String unescapeJs(String v) {
        if (v == null || v.equals("null")) return null;
        String s = v;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\\\", "\\");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
        if (web != null) {
            try { web.stopLoading(); web.destroy(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
