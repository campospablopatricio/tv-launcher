package com.tvlauncher.clean;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Launcher minimalista estilo Apple TV con reloj y clima (Open-Meteo, sin API key). */
public class MainActivity extends Activity {

    // San Miguel de Tucumán. Cambia estas coordenadas para otra ciudad.
    private static final String CITY = "Tucumán";
    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=-26.8083&longitude=-65.2176"
            + "&current=temperature_2m,weather_code,is_day"
            + "&daily=temperature_2m_max,temperature_2m_min"
            + "&timezone=America%2FArgentina%2FTucuman&forecast_days=1";
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Locale es = new Locale("es", "AR");
    private TextView clockView, dateView, weatherView, titleView, subtitleView;
    private LinearLayout row;
    private long lastWeatherFetch = 0;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
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
        weatherView.setText(getSharedPreferences("w", 0).getString("t", ""));
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
                new int[]{0xFF15151B, 0xFF000000}));
        root.setClipChildren(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(36), dp(64), dp(28));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Barra superior: reloj/fecha a la izquierda, clima a la derecha
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clockView = tv(44, 0xFFFFFFFF, false);
        dateView = tv(18, 0xFF8E8E93, false);
        left.addView(clockView);
        left.addView(dateView);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        weatherView = tv(22, 0xFFFFFFFF, false);
        weatherView.setSingleLine(false);
        weatherView.setGravity(Gravity.END);
        top.addView(weatherView, new LinearLayout.LayoutParams(-2, -2));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // Zona central: nombre de la app enfocada
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.BOTTOM);
        titleView = tv(60, 0xFFFFFFFF, true);
        subtitleView = tv(20, 0xFF8E8E93, false);
        subtitleView.setText("Presiona OK para abrir");
        mid.addView(titleView);
        mid.addView(subtitleView);
        col.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Fila de apps
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(24), 0, dp(24));
        row = new LinearLayout(this);
        row.setClipChildren(false);
        hs.addView(row, new FrameLayout.LayoutParams(-2, -2));
        col.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(224), dp(126));
        lp.setMargins(dp(16), 0, dp(16), 0);
        tile.setLayoutParams(lp);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag(label);
        tile.setBackground(tileBg(false));
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18));
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
            iv.setPadding(dp(28), dp(18), dp(28), dp(18));
        }
        iv.setImageDrawable(art);
        tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                float s = has ? 1.12f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(150).start();
                v.setBackground(tileBg(has));
                v.setElevation(has ? dp(12) : 0);
                if (has) titleView.setText((String) v.getTag());
            }
        });
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

    private GradientDrawable tileBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1C1C22);
        g.setCornerRadius(dp(18));
        if (focused) g.setStroke(dp(3), 0xFFFFFFFF);
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
                    JSONObject j = new JSONObject(sb.toString());
                    JSONObject cur = j.getJSONObject("current");
                    JSONObject daily = j.getJSONObject("daily");
                    final String text = describe(cur.getInt("weather_code"), cur.getInt("is_day") == 1)
                            + "  " + Math.round(cur.getDouble("temperature_2m")) + "°"
                            + "\nMáx " + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)) + "°"
                            + " · Mín " + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "°"
                            + " · " + CITY;
                    getSharedPreferences("w", 0).edit().putString("t", text).apply();
                    ui.post(new Runnable() { @Override public void run() { weatherView.setText(text); } });
                } catch (Exception e) {
                    lastWeatherFetch = 0; // reintenta en el próximo tick
                }
            }
        }).start();
    }

    private String describe(int code, boolean day) {
        if (code == 0) return day ? "☀️ Despejado" : "🌙 Despejado";
        if (code == 1) return day ? "🌤️ Mayormente despejado" : "🌙 Mayormente despejado";
        if (code == 2) return "⛅ Parcialmente nublado";
        if (code == 3) return "☁️ Nublado";
        if (code == 45 || code == 48) return "🌫️ Niebla";
        if (code >= 51 && code <= 57) return "🌦️ Llovizna";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️ Lluvia";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️ Nieve";
        if (code >= 95) return "⛈️ Tormenta";
        return "🌡️ Clima";
    }
}
