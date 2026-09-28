package com.CodeBySonu.VoxSherpa;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * OPF/container plumbing shared by the text extractor (EpubHelper) and the
 * full reader (EpubPacker). Kept separate from EpubHelper so the reader can
 * reuse it without dragging in the Android Context/Uri path.
 */
public final class EpubReaderSupport {

    private EpubReaderSupport() {}

    /** Reads META-INF/container.xml; falls back to any *.opf in the archive. */
    public static String findRootfile(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container != null) {
            Document doc = parseXml(readEntry(zip, container));
            if (doc != null) {
                NodeList rootfiles = doc.getElementsByTagName("rootfile");
                for (int i = 0; i < rootfiles.getLength(); i++) {
                    String path = ((Element) rootfiles.item(i)).getAttribute("full-path");
                    if (path != null && !path.isEmpty()) return path;
                }
            }
        }
        Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            String name = it.nextElement().getName();
            if (name.toLowerCase().endsWith(".opf")) return name;
        }
        return null;
    }

    /**
     * Returns chapter paths relative to rootDir, in spine reading order.
     * The nav document and non-content items are skipped.
     */
    public static List<String> readSpine(File opf, File rootDir) {
        List<String> ordered = new ArrayList<>();
        Document doc = parseXmlFile(opf);
        if (doc == null) return ordered;

        Map<String, String> manifest = new LinkedHashMap<>();
        Map<String, String> props = new LinkedHashMap<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String href = item.getAttribute("href");
            if (id == null || id.isEmpty() || href == null || href.isEmpty()) continue;
            String rel = normalize(href);
            manifest.put(id, rel);
            props.put(id, item.getAttribute("properties"));
        }

        String baseDir = "";
        String opfPath = relToRoot(opf, rootDir);
        if (opfPath != null) {
            int slash = opfPath.lastIndexOf('/');
            if (slash >= 0) baseDir = opfPath.substring(0, slash + 1);
        }

        NodeList spineItems = doc.getElementsByTagName("itemref");
        for (int i = 0; i < spineItems.getLength(); i++) {
            Element ref = (Element) spineItems.item(i);
            String idref = ref.getAttribute("idref");
            String href = manifest.get(idref);
            if (href == null) continue;
            // skip the nav doc — it duplicates chapter titles
            String p = props.get(idref);
            if (p != null && p.toLowerCase().contains("nav")) continue;
            ordered.add(baseDir + href);
        }
        return ordered;
    }

    /** Best-effort title from dc:title, else the OPF filename. */
    public static String readTitle(File opf) {
        Document doc = parseXmlFile(opf);
        if (doc != null) {
            NodeList t = doc.getElementsByTagName("dc:title");
            for (int i = 0; i < t.getLength(); i++) {
                String v = t.item(i).getTextContent();
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        return opf.getName().replaceAll("\\.opf$", "");
    }

    /** Any xhtml under rootDir, sorted, used when the spine is unusable. */
    public static List<String> scanForXhtml(File rootDir) {
        List<String> out = new ArrayList<>();
        collectXhtml(rootDir, rootDir, out, 0);
        out.sort(String::compareTo);
        return out;
    }

    private static void collectXhtml(File dir, File root, List<String> out, int depth) {
        if (depth > 6) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                collectXhtml(f, root, out, depth + 1);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) {
                    String rel = root.toPath().relativize(f.toPath()).toString();
                    out.add(rel);
                }
            }
        }
    }

    /** href -> clean relative path (percent-decoded, ./ stripped, no leading /). */
    private static String normalize(String href) {
        String h = href;
        try {
            h = java.net.URLDecoder.decode(h, "UTF-8");
        } catch (Exception ignored) {
        }
        while (h.startsWith("./")) h = h.substring(2);
        while (h.startsWith("/")) h = h.substring(1);
        return h;
    }

    private static String relToRoot(File file, File root) {
        try {
            return root.toPath().relativize(file.toPath()).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }

    static byte[] readEntry(ZipFile zip, ZipEntry entry) throws Exception {
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }

    static Document parseXml(byte[] bytes) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
            }
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            return null;
        }
    }

    static Document parseXmlFile(File f) {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            return parseXml(all);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class InputSource extends org.xml.sax.InputSource {
        InputSource(ByteArrayInputStream in) {
            super(in);
        }
    }
}
