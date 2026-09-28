package com.CodeBySonu.VoxSherpa;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks an EPUB into a cache directory so a WebView can render it properly.
 *
 * The WebView approach is what buys us real text selection (the OS blue handles)
 * plus inline images, neither of which a flattened plain-text string can give.
 * Images are served from the unpacked dir, so relative <img src> just works.
 */
public class EpubPacker {

    private static final String TAG = "EpubPacker";

    public static class Packed {
        public final File rootDir;       // unpacked OEBPS-ish root
        public final List<String> chapters; // ordered hrefs, relative to rootDir
        public final String title;
        Packed(File rootDir, List<String> chapters, String title) {
            this.rootDir = rootDir;
            this.chapters = chapters;
            this.title = title;
        }
    }

    /**
     * Copies the Uri to a temp file (ZipFile needs a seekable path), reads the
     * spine, then extracts every entry under a fresh cache dir.
     */
    public static Packed unpack(Context ctx, Uri uri) throws Exception {
        File tmp = File.createTempFile("vox_epub_", ".epub", ctx.getCacheDir());
        try {
            copyUriToFile(ctx, uri, tmp);

            File outRoot = new File(ctx.getCacheDir(), "epub_" + System.currentTimeMillis());
            if (!outRoot.exists() && !outRoot.mkdirs()) {
                throw new IllegalStateException("Cannot create unpack dir");
            }

            List<String> chapters = new ArrayList<>();
            String title = "";

            try (ZipFile zip = new ZipFile(tmp)) {
                String opfPath = EpubReaderSupport.findRootfile(zip);
                if (opfPath == null) throw new IllegalStateException("No OPF in EPUB");

                // Extract everything (images/css/fonts/xhtml all needed for rendering)
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    // zip-slip guard: never write outside outRoot
                    File dest = new File(outRoot, e.getName());
                    String destPath = dest.getCanonicalPath();
                    String rootPath = outRoot.getCanonicalPath();
                    if (!destPath.startsWith(rootPath + File.separator)) {
                        Log.w(TAG, "skipping zip-slip entry: " + e.getName());
                        continue;
                    }
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream in = zip.getInputStream(e);
                         OutputStream os = new FileOutputStream(dest)) {
                        copy(in, os);
                    }
                }

                File opf = new File(outRoot, opfPath);
                if (opf.exists()) {
                    chapters = EpubReaderSupport.readSpine(opf, outRoot);
                    title = EpubReaderSupport.readTitle(opf);
                }
            }

            if (chapters.isEmpty()) {
                // fall back to any xhtml we unpacked
                chapters = EpubReaderSupport.scanForXhtml(outRoot);
            }
            return new Packed(outRoot, chapters, title);
        } finally {
            if (!tmp.delete()) tmp.deleteOnExit();
        }
    }

    /** Returns a file:// URL for a chapter, or null if it vanished. */
    public static String chapterUrl(Packed p, int index) {
        if (p == null || index < 0 || index >= p.chapters.size()) return null;
        File f = new File(p.rootDir, p.chapters.get(index));
        if (!f.exists()) return null;
        return "file://" + f.getAbsolutePath();
    }

    public static void clearAll(Context ctx) {
        File[] kids = ctx.getCacheDir().listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.getName().startsWith("epub_") || f.getName().startsWith("vox_epub_")) {
                deleteRec(f);
            }
        }
    }

    public static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void copyUriToFile(Context ctx, Uri uri, File dest) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IllegalStateException("Cannot open " + uri);
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        out.flush();
    }
}
