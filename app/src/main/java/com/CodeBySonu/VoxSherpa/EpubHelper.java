package com.CodeBySonu.VoxSherpa;

import android.content.Context;
import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * EPUB text extractor.
 *
 * Clean-room implementation of the EPUB 3 / OCF container spec:
 *   1. copy the content Uri to a temp file (ZipFile needs a seekable path)
 *   2. read META-INF/container.xml -> rootfile full-path (the OPF package document)
 *   3. parse the OPF: <manifest> id->href, <spine> idref reading order
 *   4. concatenate spine documents in order, stripping XHTML to plain text
 *
 * The OPF namespace is deliberately ignored (namespaceAware=false) so plain
 * getElementsByTagName("item") works across the varied OPF dialects in the wild.
 */
public class EpubHelper {

    /** Refuse to buffer an absurd archive; a legit EPUB is a few MB. */
    private static final long MAX_EPUB_BYTES = 256L * 1024L * 1024L;

    public static boolean isEpubName(String name) {
        return name != null && name.toLowerCase().endsWith(".epub");
    }

    public static boolean isEpubMime(String mime) {
        return mime != null && mime.toLowerCase().contains("epub");
    }

    /**
     * @param maxChars hard ceiling on returned characters, to match the
     *                 app-wide document import limit.
     */
    public static String extractText(Context context, Uri uri, int maxChars) throws Exception {
        File tmp = copyUriToTemp(context, uri);
        try {
            return extractFromFile(tmp, maxChars);
        } finally {
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    private static File copyUriToTemp(Context context, Uri uri) throws Exception {
        File tmp = File.createTempFile("voxsherpa_epub_", ".epub",
                context.getCacheDir());
        long written = 0;
        try (InputStream in = new BufferedInputStream(
                context.getContentResolver().openInputStream(uri));
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                written += r;
                if (written > MAX_EPUB_BYTES) {
                    throw new IllegalStateException("EPUB too large");
                }
                out.write(buf, 0, r);
            }
        }
        return tmp;
    }

    private static String extractFromFile(File epub, int maxChars) throws Exception {
        try (ZipFile zip = new ZipFile(epub)) {

            String opfPath = findRootfilePath(zip);
            if (opfPath == null) {
                throw new IllegalStateException("No OPF found in EPUB");
            }

            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) {
                throw new IllegalStateException("OPF entry missing: " + opfPath);
            }

            Document opf = parseXml(readEntry(zip, opfEntry));
            if (opf == null) {
                throw new IllegalStateException("OPF is not valid XML");
            }

            Map<String, String> manifest = new LinkedHashMap<>();
            NodeList items = opf.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                if (id != null && !id.isEmpty() && href != null && !href.isEmpty()) {
                    manifest.put(id, href);
                }
            }

            // base dir of the OPF, so relative hrefs resolve
            String baseDir = "";
            int slash = opfPath.lastIndexOf('/');
            if (slash >= 0) {
                baseDir = opfPath.substring(0, slash + 1);
            }

            // spine = reading order; fall back to manifest order if no spine
            List<String> ordered = new ArrayList<>();
            NodeList spineItems = opf.getElementsByTagName("itemref");
            for (int i = 0; i < spineItems.getLength(); i++) {
                String idref = ((Element) spineItems.item(i)).getAttribute("idref");
                String href = manifest.get(idref);
                if (href != null) {
                    ordered.add(baseDir + href);
                }
            }
            if (ordered.isEmpty()) {
                for (String href : manifest.values()) {
                    if (href.toLowerCase().endsWith(".xhtml")
                            || href.toLowerCase().endsWith(".html")
                            || href.toLowerCase().endsWith(".htm")) {
                        ordered.add(baseDir + href);
                    }
                }
            }

            StringBuilder out = new StringBuilder();
            for (String path : ordered) {
                if (out.length() >= maxChars) {
                    break;
                }
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) {
                    // href may be percent-encoded or use ./ prefixes
                    entry = zip.getEntry(path.replace("./", ""));
                }
                if (entry == null || entry.isDirectory()) {
                    continue;
                }
                // hard-cap inside the chapter too: a single huge chapter must
                // not balloon the String before the outer limit is applied.
                int budget = maxChars - out.length();
                if (budget <= 0) {
                    break;
                }
                String text = xhtmlToText(readEntry(zip, entry), budget);
                if (text.isEmpty()) {
                    continue;
                }
                out.append(text).append("\n\n");
            }
            if (out.length() > maxChars) {
                out.setLength(maxChars);
            }
            return out.toString();
        }
    }

    private static String findRootfilePath(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container != null) {
            Document doc = parseXml(readEntry(zip, container));
            if (doc != null) {
                NodeList rootfiles = doc.getElementsByTagName("rootfile");
                for (int i = 0; i < rootfiles.getLength(); i++) {
                    String path = ((Element) rootfiles.item(i)).getAttribute("full-path");
                    if (path != null && !path.isEmpty()) {
                        return path;
                    }
                }
            }
        }
        // Some malformed EPUBs omit container.xml; guess the OPF.
        java.util.Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            String name = it.nextElement().getName();
            if (name.toLowerCase().endsWith(".opf")) {
                return name;
            }
        }
        return null;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }

    private static Document parseXml(byte[] bytes) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            // EPUB content is untrusted input: no external entities.
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // best effort; not all parsers expose this
            }
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Flatten an XHTML document to speech-friendly plain text, capped at maxChars. */
    private static String xhtmlToText(byte[] bytes, int maxChars) {
        String html;
        try {
            html = new String(bytes, "UTF-8");
        } catch (Exception e) {
            return "";
        }

        // drop non-content elements
        html = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
        // block boundaries become newlines so paragraph gaps survive to the TTS
        html = html.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article)>", "\n");
        html = html.replaceAll("(?i)<(br|hr)[^>]*>", "\n");
        // strip every remaining tag
        html = html.replaceAll("(?s)<[^>]+>", "");
        // decode the entities that actually show up in books
        html = html.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&apos;", "'")
                   .replace("&#39;", "'")
                   .replace("&mdash;", "\u2014")
                   .replace("&ndash;", "\u2013")
                   .replace("&hellip;", "\u2026")
                   .replace("&ldquo;", "\u201c")
                   .replace("&rdquo;", "\u201d");
        html = html.replaceAll("&#(\\d+);", "");   // numeric refs: drop, avoids mojibake
        html = html.replaceAll("(?i)<!\\[CDATA\\[.*?\\]\\]>", " ");

        // normalize: collapse runs of spaces/tabs, cap blank lines
        html = html.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        html = html.replaceAll(" *\\n *", "\n");
        html = html.replaceAll("\\n{3,}", "\n\n");
        html = html.trim();
        if (maxChars > 0 && html.length() > maxChars) {
            html = html.substring(0, maxChars);
        }
        return html;
    }
}
