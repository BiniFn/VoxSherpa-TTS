package com.CodeBySonu.VoxSherpa;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.util.List;

/**
 * Shows what the app is storing on disk and lets the user delete it.
 *
 * Answers the question the Settings tab never did: where did my storage go,
 * and which model is eating it.
 */
public class StorageActivity extends android.app.Activity {

    private LinearLayout modelsBox, otherBox;
    private TextView totalTv, modelSummaryTv, pathsTv;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_storage);

        modelsBox = findViewById(R.id.container_models);
        otherBox = findViewById(R.id.container_other);
        totalTv = findViewById(R.id.txt_total);
        modelSummaryTv = findViewById(R.id.txt_model_summary);
        pathsTv = findViewById(R.id.txt_paths);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_delete_all_models).setOnClickListener(v -> confirmDeleteAllModels());
        findViewById(R.id.btn_delete_all_audio).setOnClickListener(v -> confirmDeleteAudio());

        pathsTv.setText(
                "Piper models:  " + getFilesDir() + "/PiperModels\n"
              + "Kokoro/MMS:     " + getFilesDir() + "/secure_models\n"
              + "Engine data:    " + getFilesDir() + "\n"
              + "Audio:          " + android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC) + "/VoxEngine");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        modelsBox.removeAllViews();
        otherBox.removeAllViews();

        List<StorageScanner.Entry> models = StorageScanner.scanModels(this);
        List<StorageScanner.Entry> other = StorageScanner.scanOther(this);

        long modelTotal = 0;
        for (StorageScanner.Entry e : models) modelTotal += e.sizeBytes;

        long grand = modelTotal;
        for (StorageScanner.Entry e : other) grand += e.sizeBytes;

        totalTv.setText(StorageScanner.human(grand));
        modelSummaryTv.setText(models.isEmpty()
                ? "No voice models installed"
                : models.size() + (models.size() == 1 ? " model · " : " models · ")
                  + StorageScanner.human(modelTotal));

        if (models.isEmpty()) {
            modelsBox.addView(placeholder("No models yet. Download one from the Models tab."));
        } else {
            for (StorageScanner.Entry e : models) {
                modelsBox.addView(modelRow(e));
            }
        }

        if (other.isEmpty()) {
            otherBox.addView(placeholder("Nothing else stored yet."));
        } else {
            for (StorageScanner.Entry e : other) {
                otherBox.addView(otherRow(e));
            }
        }
    }

    private View modelRow(final StorageScanner.Entry e) {
        MaterialCardView card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(8), dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextColor(Color.parseColor("#E2E8F0"));
        name.setTextSize(14);
        name.setMaxLines(2);
        col.addView(name);

        TextView sub = new TextView(this);
        sub.setText(e.humanSize());
        sub.setTextColor(Color.parseColor("#64748B"));
        sub.setTextSize(12);
        col.addView(sub);

        row.addView(col);

        ImageView del = new ImageView(this);
        del.setImageResource(R.drawable.ic_delete);
        int tint = Color.parseColor("#F87171");
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            del.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        del.setPadding(dp(10), dp(10), dp(10), dp(10));
        del.setBackgroundResource(android.R.drawable.list_selector_background);
        del.setOnClickListener(v -> {
            long freed = StorageScanner.deleteModel(StorageActivity.this, e.file);
            refresh();
            Toast.makeText(this, "Freed " + StorageScanner.human(freed),
                    Toast.LENGTH_SHORT).show();
        });
        row.addView(del);

        card.addView(row);
        return card;
    }

    private View otherRow(final StorageScanner.Entry e) {
        MaterialCardView card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextColor(Color.parseColor("#E2E8F0"));
        name.setTextSize(14);
        col.addView(name);

        TextView sub = new TextView(this);
        sub.setText(e.humanSize() + (e.directory ? " · folder" : ""));
        sub.setTextColor(Color.parseColor("#64748B"));
        sub.setTextSize(12);
        col.addView(sub);

        row.addView(col);

        TextView act = new TextView(this);
        act.setText("Clear");
        act.setTextColor(Color.parseColor("#5B8CFF"));
        act.setTextSize(13);
        act.setPadding(dp(12), dp(8), dp(4), dp(8));
        act.setOnClickListener(v -> {
            long freed;
            if ("audio".equals(e.kind)) {
                freed = StorageScanner.deleteAllAudio(StorageActivity.this);
            } else if ("cache".equals(e.kind)) {
                freed = StorageScanner.clearReaderCache(StorageActivity.this);
            } else {
                // espeak / chinese helper are re-extracted from assets on demand
                EpubPacker.deleteRec(e.file);
                freed = StorageScanner.dirSize(e.file);
            }
            refresh();
            Toast.makeText(this, "Freed " + StorageScanner.human(freed),
                    Toast.LENGTH_SHORT).show();
        });
        row.addView(act);

        card.addView(row);
        return card;
    }

    private void confirmDeleteAllModels() {
        List<StorageScanner.Entry> models = StorageScanner.scanModels(this);
        if (models.isEmpty()) {
            Toast.makeText(this, "No models to delete", Toast.LENGTH_SHORT).show();
            return;
        }
        long total = 0;
        for (StorageScanner.Entry e : models) total += e.sizeBytes;
        new AlertDialog.Builder(this)
                .setTitle("Delete all models?")
                .setMessage(models.size() + " model(s), " + StorageScanner.human(total)
                        + " will be freed.\n\nYou can re-download them later from the Models tab.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    long freed = StorageScanner.deleteAllModels(StorageActivity.this);
                    refresh();
                    Toast.makeText(this, "Freed " + StorageScanner.human(freed),
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void confirmDeleteAudio() {
        new AlertDialog.Builder(this)
                .setTitle("Delete generated audio?")
                .setMessage("All WAV/M4B files in Music/VoxEngine will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    long freed = StorageScanner.deleteAllAudio(StorageActivity.this);
                    refresh();
                    Toast.makeText(this, "Freed " + StorageScanner.human(freed),
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private MaterialCardView card() {
        MaterialCardView c = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        c.setCardBackgroundColor(Color.parseColor("#131B2D"));
        c.setStrokeColor(Color.parseColor("#1E293B"));
        c.setStrokeWidth(dp(1));
        c.setRadius(dp(12));
        c.setCardElevation(0);
        return c;
    }

    private TextView placeholder(String msg) {
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(Color.parseColor("#64748B"));
        t.setTextSize(13);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
