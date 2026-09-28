package com.CodeBySonu.VoxSherpa;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates what the app is actually storing on disk, so the user can see
 * where the space went and delete it.
 *
 * Models land in filesDir/PiperModels (see ModelsFragmentActivity). Alongside
 * them sit the extracted espeak-ng and Chinese-helper data trees, plus the
 * generated WAVs under Music/VoxEngine. Upstream had a "delete all models"
 * buried in Settings with no sizes shown; this makes the same data legible.
 */
public class StorageScanner {

    public static class Entry {
        public final File file;
        public final String name;
        public final long sizeBytes;
        public final String kind;     // "model" | "data" | "audio" | "cache"
        public final boolean directory;

        Entry(File file, String name, long sizeBytes, String kind, boolean directory) {
            this.file = file;
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.kind = kind;
            this.directory = directory;
        }

        public String humanSize() {
            return human(sizeBytes);
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long total = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File f : kids) total += dirSize(f);
        }
        return total;
    }

    /**
     * Voice models, one entry per .onnx (with its sibling tokens file counted in).
     *
     * There are TWO model directories, not one: PiperModels holds the Piper/VITS
     * voices (ModelsFragmentActivity, and what Settings' delete-all wipes), while
     * secure_models holds the downloaded Kokoro/MMS voices. Scanning only the
     * first would have hidden half the disk usage.
     */
    public static List<Entry> scanModels(Context ctx) {
        List<Entry> out = new ArrayList<>();
        collectModels(new File(ctx.getFilesDir(), "PiperModels"), out);
        collectModels(new File(ctx.getFilesDir(), "secure_models"), out);
        out.sort((a, b) -> Long.compare(b.sizeBytes, a.sizeBytes));
        return out;
    }

    private static void collectModels(File modelsDir, List<Entry> out) {
        File[] kids = modelsDir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) continue;
            if (!f.getName().toLowerCase().endsWith(".onnx")) continue;
            // pair with <same-name>.txt if present
            String base = f.getName().substring(0, f.getName().length() - 5);
            File tokens = new File(modelsDir, base + ".txt");
            long size = f.length() + (tokens.exists() ? tokens.length() : 0);
            out.add(new Entry(f, prettyName(base), size, "model", false));
        }
    }

    /** Everything else that eats space, as coarse buckets. */
    public static List<Entry> scanOther(Context ctx) {
        List<Entry> out = new ArrayList<>();

        addDir(out, new File(ctx.getFilesDir(), "espeak-ng-data"), "espeak-ng data", "data");
        addDir(out, new File(ctx.getFilesDir(), "chinese_helper_data"), "Chinese helper data", "data");
        addDir(out, new File(ctx.getCacheDir(), "epub"), "Reader cache (EPUB)", "cache");
        addDir(out, ctx.getCacheDir(), "App cache", "cache");

        File audioDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC), "VoxEngine");
        addDir(out, audioDir, "Generated audio (WAV/M4B)", "audio");

        return out;
    }

    private static void addDir(List<Entry> out, File dir, String label, String kind) {
        if (dir == null || !dir.exists()) return;
        long size = dirSize(dir);
        if (size <= 0 && kind.equals("cache")) return;   // don't list empty caches
        out.add(new Entry(dir, label, size, kind, true));
    }

    public static long totalModelBytes(Context ctx) {
        long t = 0;
        for (Entry e : scanModels(ctx)) t += e.sizeBytes;
        return t;
    }

    /** Deletes one model plus its tokens sibling. Returns bytes freed. */
    public static long deleteModel(Context ctx, File onnx) {
        if (onnx == null || !onnx.exists()) return 0;
        long freed = onnx.length();
        String base = onnx.getName();
        if (base.toLowerCase().endsWith(".onnx")) {
            base = base.substring(0, base.length() - 5);
        }
        File tokens = new File(onnx.getParentFile(), base + ".txt");
        if (tokens.exists()) {
            freed += tokens.length();
            //noinspection ResultOfMethodCallIgnored
            tokens.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        onnx.delete();
        return freed;
    }

    /** Wipes every voice model in both model dirs. Returns bytes freed. */
    public static long deleteAllModels(Context ctx) {
        long freed = 0;
        for (Entry e : scanModels(ctx)) {
            freed += e.sizeBytes;
        }
        for (String dirName : new String[]{"PiperModels", "secure_models"}) {
            File models = new File(ctx.getFilesDir(), dirName);
            File[] kids = models.listFiles();
            if (kids != null) {
                for (File f : kids) {
                    if (f.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        }
        _clearSelectionPrefs(ctx);
        return freed;
    }

    /** Deletes generated audio, then prunes library rows pointing at dead files. */
    public static long deleteAllAudio(Context ctx) {
        long freed = 0;
        File audioDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC), "VoxEngine");
        File[] kids = audioDir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.isFile()) {
                    freed += f.length();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        _pruneLibrary(ctx);
        return freed;
    }

    /** Clears the unpacked-EPUB cache. */
    public static long clearReaderCache(Context ctx) {
        long freed = 0;
        File cache = ctx.getCacheDir();
        File[] kids = cache.listFiles();
        if (kids != null) {
            for (File f : kids) {
                String n = f.getName();
                if (n.startsWith("epub_") || n.startsWith("vox_epub_")) {
                    freed += dirSize(f);
                    EpubPacker.deleteRec(f);
                }
            }
        }
        return freed;
    }

    /** Drops library entries whose file no longer exists. */
    public static void _pruneLibrary(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("sp2", Context.MODE_PRIVATE);
            String raw = sp.getString("library_list", "[]");
            if (raw == null) return;
            java.util.ArrayList<java.util.HashMap<String, Object>> list =
                    new com.google.gson.Gson().fromJson(raw,
                            new com.google.gson.reflect.TypeToken<
                                    java.util.ArrayList<java.util.HashMap<String, Object>>>() {
                            }.getType());
            if (list == null) return;
            java.util.Iterator<java.util.HashMap<String, Object>> it = list.iterator();
            while (it.hasNext()) {
                java.util.HashMap<String, Object> item = it.next();
                Object p = item.get("path");
                if (p == null || !new File(p.toString()).exists()) {
                    it.remove();
                }
            }
            sp.edit().putString("library_list", new com.google.gson.Gson().toJson(list)).apply();
        } catch (Exception ignored) {
        }
    }

    /** Clears prefs that name a now-deleted model as selected. */
    private static void _clearSelectionPrefs(Context ctx) {
        try {
            for (String name : new String[]{"sp1", "sp2", "sp3", "spHistory"}) {
                SharedPreferences sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
                SharedPreferences.Editor e = sp.edit();
                e.remove("selected_onnx_uri");
                e.remove("selected_tokens_uri");
                e.remove("selected_model_name");
                e.remove("current_voice");
                e.apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static String prettyName(String base) {
        String n = base.replace('_', ' ').trim();
        if (n.isEmpty()) return base;
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
