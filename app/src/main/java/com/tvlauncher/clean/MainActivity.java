package com.tvlauncher.clean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Launcher v2: tema claro estilo Apple TV, clima extendido por horas y botones redondos arriba. */
public class MainActivity extends Activity {

    // San Miguel de Tucumán. Cambia estas coordenadas para otra ciudad.
    private static final String CITY = "Tucumán";
    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=-26.8083&longitude=-65.2176"
            + "&current=temperature_2m,weather_code,is_day"
            + "&hourly=temperature_2m,weather_code"
            + "&daily=temperature_2m_max,temperature_2m_min"
            + "&timezone=America%2FArgentina%2FTucuman&forecast_days=2";
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;

    private static final int INK = 0xFF1C1C1E;
    private static final int MUTED = 0xFF6B7280;
    private static final int GLASS = 0xCCFFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Locale es = new Locale("es", "AR");
    private TextView greetView, dateView, clockView, titleView;
    private TextView wxIcon, wxTemp, wxDesc, wxRange;
    private LinearLayout hourly, row;
    private long lastWeatherFetch = 0;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            greetView.setText(h < 12 ? "Buenos días" : (h < 20 ? "Buenas tardes" : "Buenas noches"));
            clockView.setText(new SimpleDateFormat("HH:mm", es).format(now));
            String d = new SimpleDateFormat("EEEE d 'de' MMMM", es).format(now);
            dateView.setText(d.substring(0, 1).toUpperCase(es) + d.substring(1));
            maybeFetchWeather();
            ui.postDelayed(this, 10000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        String cached = getSharedPreferences("w", 0).getString("j", "");
        if (!cached.isEmpty()) renderWeather(cached);
        loadApps();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override protected void onResume() { super.onResume(); ui.post(clockTick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacksAndMessages(null); }
    @Override protected void onDestroy() { super.onDestroy(); unregisterReceiver(pkgReceiver); }
    @Override public void onBackPressed() { /* el launcher no se cierra */ }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFF2F4F8, 0xFFD3DAE6}));
        root.setClipChildren(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(26), dp(48), dp(18));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ---- Fila superior: saludo/fecha a la izquierda; reloj y botones redondos a la derecha
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greetView = tv(30, INK, true);
        dateView = tv(16, MUTED, false);
        left.addView(greetView);
        left.addView(dateView);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        clockView = tv(30, INK, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMargins(0, 0, dp(18), 0);
        top.addView(clockView, clp);
        top.addView(roundButton("📺", new View.OnClickListener() {
            @Override public void onClick(View v) { showInputs(); }
        }));
        top.addView(roundButton("📶", new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(Settings.ACTION_WIFI_SETTINGS); }
        }));
        top.addView(roundButton("⚙️", new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(Settings.ACTION_SETTINGS); }
        }));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- Tarjeta de clima extendido
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(30), dp(14), dp(30), dp(14));
        card.setBackground(box(GLASS, 26, false));

        wxIcon = tv(56, INK, false);
        wxIcon.setGravity(Gravity.CENTER);
        card.addView(wxIcon, new LinearLayout.LayoutParams(dp(84), -2));

        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        wxTemp = tv(60, INK, false);
        wxDesc = tv(20, INK, true);
        wxRange = tv(15, MUTED, false);
        now.addView(wxTemp);
        now.addView(wxDesc);
        now.addView(wxRange);
        card.addView(now, new LinearLayout.LayoutParams(-2, -2));

        View divider = new View(this);
        divider.setBackgroundColor(0x22000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(80));
        dlp.setMargins(dp(28), 0, dp(20), 0);
        card.addView(divider, dlp);

        hourly = new LinearLayout(this);
        hourly.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(hourly, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardLp.setMargins(0, dp(14), 0, 0);
        col.addView(card, cardLp);

        // ---- Nombre de la app enfocada
        titleView = tv(22, INK, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(dp(8), dp(12), 0, dp(6));
        col.addView(titleView, tlp);

        // ---- Dock de apps
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(10), dp(16), dp(10), dp(16));
        hs.setBackground(box(0x80FFFFFF, 26, false));
        row = new LinearLayout(this);
        row.setClipChildren(false);
        hs.addView(row, new FrameLayout.LayoutParams(-2, -2));
        col.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private View roundButton(String icon, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(icon);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(box(GLASS, 22, false));
        t.setOnClickListener(click);
        t.setOnFocusChangeListener(focusFx(1.18f, 22, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMargins(dp(6), 0, dp(6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        if (list.isEmpty()) {
            list = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        }
        Collections.sort(list, new ResolveInfo.DisplayNameComparator(pm));
        row.removeAllViews();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            row.addView(makeTile(pm, ri));
        }
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    private View makeTile(PackageManager pm, final ResolveInfo ri) {
        final String label = ri.loadLabel(pm).toString();
        FrameLayout tile = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(190), dp(107));
        lp.setMargins(dp(10), 0, dp(10), 0);
        tile.setLayoutParams(lp);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag(label);
        tile.setBackground(box(0xFFFFFFFF, 14, false));
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        tile.setClipToOutline(true);

        Drawable art = ri.activityInfo.loadBanner(pm);
        ImageView iv = new ImageView(this);
        if (art != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            art = ri.loadIcon(pm);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(26), dp(16), dp(26), dp(16));
        }
        iv.setImageDrawable(art);
        tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        tile.setOnFocusChangeListener(focusFx(1.1f, 14, true));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try { startActivity(i); } catch (Exception ignored) { }
            }
        });
        return tile;
    }

    private View.OnFocusChangeListener focusFx(final float scale, final int radiusDp, final boolean setTitle) {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                float s = has ? scale : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(150).start();
                v.setBackground(box(has ? 0xFFFFFFFF : (setTitle ? 0xFFFFFFFF : GLASS), radiusDp, has));
                v.setElevation(has ? dp(10) : 0);
                if (has && setTitle) titleView.setText((String) v.getTag());
            }
        };
    }

    private GradientDrawable box(int color, int radiusDp, boolean stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke) g.setStroke(dp(3), INK);
        return g;
    }

    private TextView tv(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif-light", Typeface.NORMAL));
        t.setSingleLine(true);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------- Entradas y ajustes

    private void showInputs() {
        try {
            TvInputManager m = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            final List<TvInputInfo> inputs = new ArrayList<TvInputInfo>();
            for (TvInputInfo info : m.getTvInputList()) {
                if (info.isPassthroughInput()) inputs.add(info);
            }
            if (inputs.isEmpty()) {
                Toast.makeText(this, "No se encontraron entradas", Toast.LENGTH_LONG).show();
                return;
            }
            String[] names = new String[inputs.size()];
            for (int k = 0; k < names.length; k++) {
                CharSequence c = inputs.get(k).loadCustomLabel(this);
                if (c == null || c.length() == 0) c = inputs.get(k).loadLabel(this);
                names[k] = c.toString();
            }
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Entradas")
                    .setItems(names, new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            openInput(inputs.get(which));
                        }
                    })
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "No pude abrir las entradas", Toast.LENGTH_LONG).show();
        }
    }

    private void openInput(TvInputInfo info) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(info.getId()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No pude cambiar de entrada", Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------- Clima

    private void maybeFetchWeather() {
        long now = System.currentTimeMillis();
        if (now - lastWeatherFetch < WEATHER_EVERY_MS) return;
        lastWeatherFetch = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(WEATHER_URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    final String raw = sb.toString();
                    new JSONObject(raw); // valida antes de guardar
                    getSharedPreferences("w", 0).edit().putString("j", raw).apply();
                    ui.post(new Runnable() { @Override public void run() { renderWeather(raw); } });
                } catch (Exception e) {
                    lastWeatherFetch = 0; // reintenta en el próximo tick
                }
            }
        }).start();
    }

    private void renderWeather(String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            JSONObject cur = j.getJSONObject("current");
            boolean day = cur.getInt("is_day") == 1;
            int code = cur.getInt("weather_code");
            wxIcon.setText(icon(code, day));
            wxTemp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            wxDesc.setText(desc(code));
            JSONObject d = j.getJSONObject("daily");
            wxRange.setText("Máx " + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "°"
                    + " · Mín " + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°"
                    + " · " + CITY);

            JSONObject h = j.getJSONObject("hourly");
            JSONArray ht = h.getJSONArray("time");
            JSONArray hc = h.getJSONArray("weather_code");
            JSONArray hT = h.getJSONArray("temperature_2m");
            String nowHour = cur.getString("time").substring(0, 13);
            int start = 0;
            for (int i = 0; i < ht.length(); i++) {
                if (ht.getString(i).startsWith(nowHour)) { start = i; break; }
            }
            hourly.removeAllViews();
            for (int i = start + 1; i < start + 7 && i < ht.length(); i++) {
                String time = ht.getString(i);
                int hh = Integer.parseInt(time.substring(11, 13));
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                TextView t1 = tv(14, MUTED, false);
                t1.setText(time.substring(11, 13) + "h");
                t1.setGravity(Gravity.CENTER);
                TextView t2 = tv(26, INK, false);
                t2.setText(icon(hc.getInt(i), hh >= 7 && hh < 20));
                t2.setGravity(Gravity.CENTER);
                TextView t3 = tv(18, INK, true);
                t3.setText(Math.round(hT.getDouble(i)) + "°");
                t3.setGravity(Gravity.CENTER);
                cell.addView(t1);
                cell.addView(t2);
                cell.addView(t3);
                hourly.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            }
        } catch (Exception ignored) { }
    }

    private String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1) return day ? "🌤️" : "🌙";
        if (code == 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }

    private String desc(int code) {
        if (code == 0) return "Despejado";
        if (code == 1) return "Mayormente despejado";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Niebla";
        if (code >= 51 && code <= 57) return "Llovizna";
        if (code >= 61 && code <= 67) return "Lluvia";
        if (code >= 80 && code <= 82) return "Chubascos";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Nieve";
        if (code >= 95) return "Tormenta";
        return "Clima variable";
    }
}
