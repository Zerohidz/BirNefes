package com.zerohidz.whatsapppause;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows apps by name and icon only. Package names stay out of everything on screen. */
public final class ExceptionsActivity extends Activity {
    private static final int EXPORT_REQUEST = 1, IMPORT_REQUEST = 2;
    private static final int ICON_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int ICON_DP = 40;
    private static final int HEADER_TYPE = 0, APP_TYPE = 1;

    private static final class App {
        final String pkg, name;
        final boolean system;
        App(String pkg, String name, boolean system) { this.pkg = pkg; this.name = name; this.system = system; }
    }
    /** Exactly one of title and app is set. */
    private static final class Row {
        final String title;
        final App app;
        final boolean toggles;
        Row(String title, boolean toggles) { this.title = title; this.app = null; this.toggles = toggles; }
        Row(App app) { this.title = null; this.app = app; this.toggles = false; }
    }
    private static final class Holder {
        ImageView icon;
        TextView name, tag;
        Switch toggle;
    }

    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(ICON_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final ExecutorService iconLoader = Executors.newSingleThreadExecutor();
    private final List<App> apps = new ArrayList<>();
    private final List<App> chosen = new ArrayList<>(), restricted = new ArrayList<>(), system = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private AppPolicy policy;
    private boolean systemOpen;
    private String query = "";
    private Set<String> beforeImport;
    private TextView emptyResult, lockNote;
    private LinearLayout undoBar;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BACKGROUND));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setFocusableInTouchMode(true); // Keeps the keyboard closed until the search is tapped.
        Ui.applyInsets(root, 16);

        LinearLayout header = Ui.header(this, "İstisnalar");
        TextView more = Ui.text(this, "⋮", 24, true);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Diğer seçenekler");
        more.setOnClickListener(this::showMenu);
        header.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(header);

        undoBar = new LinearLayout(this);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        undoBar.setBackground(Ui.shape(this, Ui.SECONDARY, Ui.STROKE));
        undoBar.setVisibility(View.GONE);
        undoBar.addView(Ui.text(this, "Liste değişti", 15, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView undo = Ui.text(this, "Geri al", 15, true);
        undo.setTextColor(Ui.PRIMARY);
        undo.setGravity(Gravity.CENTER);
        undo.setOnClickListener(v -> undoImport());
        undoBar.addView(undo, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 44)));
        root.addView(undoBar, Ui.topMargin(this, 8));
        lockNote = Ui.muted(this, "Kardeş kilidi devrede: istisna eklenemez ve içe aktarılamaz. Çıkarmak ve dışa aktarmak serbest.", 14);
        root.addView(lockNote, Ui.topMargin(this, 8));

        EditText search = new EditText(this);
        search.setHint("Uygulama ara");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(Ui.shape(this, Ui.SECONDARY, Ui.STROKE));
        search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { query = s.toString().trim(); showRows(); }
        });
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        field.topMargin = Ui.dp(this, 8);
        root.addView(search, field);

        emptyResult = Ui.muted(this, "", 16);
        emptyResult.setVisibility(View.GONE);
        root.addView(emptyResult, Ui.topMargin(this, 16));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        loadApps();
    }
    private void loadApps() {
        new Thread(() -> {
            AppPolicy loaded = new AppPolicy(this);
            PackageManager packages = getPackageManager();
            Map<String, App> found = new LinkedHashMap<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : packages.queryIntentActivities(launcher, 0)) {
                ApplicationInfo application = info.activityInfo.applicationInfo;
                if (!found.containsKey(application.packageName))
                    found.put(application.packageName, new App(application.packageName,
                        packages.getApplicationLabel(application).toString(), loaded.essential(application.packageName)));
            }
            Collator order = Collator.getInstance(Locale.forLanguageTag("tr-TR"));
            List<App> sorted = new ArrayList<>(found.values());
            sorted.sort((a, b) -> order.compare(a.name, b.name));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                policy = loaded;
                apps.addAll(sorted);
                arrange();
            });
        }, "exceptions-load").start();
    }
    /** Fixes section membership. Runs on open and import only, so a switch never moves its row. */
    private void arrange() {
        chosen.clear(); restricted.clear(); system.clear();
        for (App app : apps) (app.system ? system : policy.allowed(app.pkg) ? chosen : restricted).add(app);
        showRows();
    }
    private void addSection(String title, List<App> section) {
        boolean searching = !query.isEmpty();
        int start = rows.size();
        for (App app : section) if (!searching || ExceptionRules.matches(app.name, query)) rows.add(new Row(app));
        if (!searching || rows.size() > start) rows.add(start, new Row(title, false));
    }
    private void showRows() {
        if (policy == null) return;
        rows.clear();
        addSection("İstisnalar", chosen);
        addSection("Kısıtlı uygulamalar", restricted);
        if (query.isEmpty()) {
            rows.add(new Row((systemOpen ? "▾ " : "▸ ") + "Sistem · her zaman açık", true));
            if (systemOpen) for (App app : system) rows.add(new Row(app));
        } else addSection("Sistem · her zaman açık", system);
        boolean empty = rows.isEmpty();
        emptyResult.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyResult.setText("“" + query + "” adında uygulama yok.");
        adapter.notifyDataSetChanged();
    }
    private void showMenu(View anchor) {
        if (policy == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Dışa aktar").setOnMenuItemClickListener(item -> { exportList(); return true; });
        // Importing can add exceptions, so the guardian lock closes it.
        menu.getMenu().add(locked() ? "İçe aktar · kilitli" : "İçe aktar").setEnabled(!locked())
            .setOnMenuItemClickListener(item -> { importList(); return true; });
        menu.show();
    }
    private boolean locked() { return Account.locked(this); }
    private void openPicker(Intent intent, int request) {
        PickerPass.start(SystemClock.elapsedRealtime());
        try { startActivityForResult(intent, request); }
        catch (ActivityNotFoundException missing) {
            PickerPass.end();
            Toast.makeText(this, "Dosya seçici açılamadı.", Toast.LENGTH_LONG).show();
        }
    }
    private void exportList() {
        openPicker(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, ExceptionRules.fileName(LocalDate.now())), EXPORT_REQUEST);
    }
    private void importList() {
        openPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST);
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        PickerPass.end();
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT_REQUEST) writeExport(data.getData());
        else if (request == IMPORT_REQUEST) readImport(data.getData());
    }
    private void writeExport(Uri target) {
        TreeSet<String> packages = new TreeSet<>();
        for (String pkg : policy.selected()) if (!policy.essential(pkg)) packages.add(pkg);
        String content = ExceptionRules.export(packages, Instant.now().toString());
        new Thread(() -> {
            String message = "Liste kaydedildi.";
            try { ExceptionsFile.write(this, target, content); }
            catch (IOException | SecurityException failed) { message = "Dosya yazılamadı."; }
            String shown = message;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
        }, "exceptions-export").start();
    }
    private void readImport(Uri source) {
        new Thread(() -> {
            ExceptionRules.Result read = null;
            try { read = ExceptionsFile.read(this, source); }
            catch (IOException | SecurityException failed) { /* Reported below as an unreadable file. */ }
            ExceptionRules.Result result = read;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (result != null && result.ok()) previewImport(result.packages);
                else refuseImport(result == null ? "Dosya okunamadı." : result.error);
            });
        }, "exceptions-import").start();
    }
    private void refuseImport(String reason) {
        Ui.show(new AlertDialog.Builder(this).setTitle("İçe aktarılamadı")
            .setMessage(reason + " Listen değişmedi.").setPositiveButton("Tamam", null));
    }
    private void previewImport(List<String> packages) {
        Set<String> installed = new HashSet<>();
        for (App app : apps) installed.add(app.pkg);
        int missing = 0;
        for (String pkg : packages) if (!installed.contains(pkg)) missing++;
        Ui.show(new AlertDialog.Builder(this).setTitle("Liste değiştirilsin mi?")
            .setMessage("Dosyada " + packages.size() + " uygulama var. Bu telefonda yüklü olmayan: " + missing
                + " (yine de saklanır). Mevcut listen bununla değiştirilir.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Listeyi değiştir", (dialog, which) -> {
                if (locked()) { refuseImport("Kardeş kilidi devrede."); return; }
                beforeImport = policy.selected();
                policy.replaceSelected(new HashSet<>(packages));
                undoBar.setVisibility(View.VISIBLE);
                arrange();
            }));
    }
    private void undoImport() {
        policy.replaceSelected(beforeImport);
        beforeImport = null;
        undoBar.setVisibility(View.GONE);
        arrange();
    }
    @Override public void onResume() {
        super.onResume();
        PickerPass.end();
        lockNote.setVisibility(locked() ? View.VISIBLE : View.GONE);
    }
    @Override public void onDestroy() {
        iconLoader.shutdownNow();
        super.onDestroy();
    }
    private void showIcon(String pkg, ImageView view) {
        Bitmap cached = icons.get(pkg);
        view.setTag(pkg);
        view.setImageBitmap(cached);
        if (cached != null) return;
        int size = Ui.dp(this, ICON_DP);
        iconLoader.execute(() -> {
            Bitmap bitmap = icons.get(pkg);
            if (bitmap == null) {
                try {
                    Drawable icon = getPackageManager().getApplicationIcon(pkg);
                    bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    icon.setBounds(0, 0, size, size);
                    icon.draw(new Canvas(bitmap));
                    icons.put(pkg, bitmap);
                } catch (PackageManager.NameNotFoundException removed) { return; }
            }
            Bitmap ready = bitmap;
            runOnUiThread(() -> { if (pkg.equals(view.getTag())) view.setImageBitmap(ready); });
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.get(position).app == null ? HEADER_TYPE : APP_TYPE; }
        @Override public boolean isEnabled(int position) { return false; } // Rows handle their own touches.
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Row row = rows.get(position);
            return row.app == null ? headerView(row, (TextView) recycled) : appView(row.app, recycled);
        }
        private View headerView(Row row, TextView view) {
            if (view == null) {
                view = Ui.muted(ExceptionsActivity.this, "", 14);
                view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                view.setPadding(0, Ui.dp(ExceptionsActivity.this, 16), 0, Ui.dp(ExceptionsActivity.this, 8));
            }
            view.setText(row.title);
            view.setOnClickListener(row.toggles ? v -> { systemOpen = !systemOpen; showRows(); } : null);
            view.setClickable(row.toggles);
            return view;
        }
        private View appView(App app, View recycled) {
            Holder holder = recycled == null ? null : (Holder) recycled.getTag();
            if (holder == null) {
                recycled = newAppView();
                holder = (Holder) recycled.getTag();
            }
            Switch toggle = holder.toggle;
            holder.name.setText(app.name);
            showIcon(app.pkg, holder.icon);
            // The recycled listener belongs to another app and must not see this change.
            toggle.setOnCheckedChangeListener(null);
            holder.tag.setVisibility(app.system ? View.VISIBLE : View.GONE);
            toggle.setVisibility(app.system ? View.GONE : View.VISIBLE);
            if (app.system) {
                recycled.setOnClickListener(null);
                recycled.setClickable(false);
                return recycled;
            }
            toggle.setChecked(policy.allowed(app.pkg));
            toggle.jumpDrawablesToCurrentState();
            toggle.setContentDescription(app.name);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                // Under the guardian lock an exception can be removed but not added.
                if (checked && locked()) {
                    button.setChecked(false);
                    Toast.makeText(ExceptionsActivity.this, "Kilitliyken istisna eklenemez.", Toast.LENGTH_SHORT).show();
                    return;
                }
                policy.setAllowed(app.pkg, checked);
            });
            recycled.setOnClickListener(v -> toggle.toggle());
            return recycled;
        }
        private View newAppView() {
            Activity context = ExceptionsActivity.this;
            Holder holder = new Holder();
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(context, 56));
            holder.icon = new ImageView(context);
            row.addView(holder.icon, new LinearLayout.LayoutParams(Ui.dp(context, ICON_DP), Ui.dp(context, ICON_DP)));
            holder.name = Ui.text(context, "", 16, false);
            holder.name.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0);
            row.addView(holder.name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            holder.tag = Ui.muted(context, "Sistem", 14);
            row.addView(holder.tag);
            holder.toggle = new Switch(context);
            holder.toggle.setFocusable(false);
            int[][] states = {{android.R.attr.state_checked}, {}};
            holder.toggle.setThumbTintList(new ColorStateList(states, new int[] {Ui.PRIMARY, Ui.TEXT}));
            holder.toggle.setTrackTintList(new ColorStateList(states, new int[] {Ui.PRIMARY, Ui.MUTED}));
            row.addView(holder.toggle);
            row.setTag(holder);
            return row;
        }
    }
}
