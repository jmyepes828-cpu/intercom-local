package co.intercom.local;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.text.InputType;
import android.widget.EditText;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.ArrayList;
import java.util.List;

/** Pantalla del anfitrión: encender el servidor, mostrar la dirección/QR y hablar. */
public class MainActivity extends Activity implements IntercomService.UiListener {

    private static final int BG = 0xFF14181A, PANEL = 0xFF1D2326, LINE = 0xFF2C3437, FG = 0xFFE7ECE9,
            DIM = 0xFF8E9A96, ACCENT = 0xFFF2A531, OK = 0xFF4FC47F, BAD = 0xFFE5594F, LCD = 0xFF0E1412, LCDFG = 0xFF9FE0B4;

    private TextView status, addr, ips, rx, help;
    private ImageView qr;
    private Button power, join, modePtt, modeOpen, ptt;
    private LinearLayout hostBox;
    private String pendingJoin;
    private String shownUrl = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { render(); h.postDelayed(this, 2000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(18), dp(28));

        TextView title = text("INTERCOM LOCAL", 15, FG, true);
        title.setLetterSpacing(0.14f);
        col.addView(title);

        status = text("", 14, LCDFG, false);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(box(LCD, LINE, 10));
        col.addView(status, lp(12));

        power = button("Encender servidor", true);
        power.setOnClickListener(v -> togglePower());
        col.addView(power, lp(12));
        join = button("Unirse a otro Android", false);
        join.setBackground(box(PANEL, LINE, 10));
        join.setOnClickListener(v -> askJoin());
        col.addView(join, lp(8));

        // Lo que ve el anfitrión: dirección y QR para los invitados
        hostBox = new LinearLayout(this);
        hostBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(hostBox, lp(0));
        LinearLayout outer = col;
        col = hostBox;

        TextView step = text("Invitados conectados a esta zona Wi‑Fi: el iPhone abre esta dirección en Safari; otro Android usa “Unirse a otro Android”.", 14, DIM, false);
        col.addView(step, lp(18));
        addr = text("—", 26, ACCENT, true);
        addr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        addr.setTextIsSelectable(true);
        col.addView(addr, lp(4));

        qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        qr.setAdjustViewBounds(true);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(220), dp(220));
        qp.topMargin = dp(10);
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(qr, qp);
        TextView qrNote = text("O escanea este código con la cámara del iPhone. La primera vez sigue los pasos para instalar el certificado.", 13, DIM, false);
        col.addView(qrNote, lp(8));

        ips = text("", 12, DIM, false);
        ips.setTypeface(Typeface.MONOSPACE);
        col.addView(ips, lp(6));
        col = outer;

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setBackground(box(PANEL, LINE, 10));
        modes.setPadding(dp(3), dp(3), dp(3), dp(3));
        modePtt = button("Pulsar para hablar", false);
        modeOpen = button("Manos libres", false);
        modePtt.setOnClickListener(v -> { IntercomService s = IntercomService.instance; if (s != null) s.setHandsFree(false); render(); });
        modeOpen.setOnClickListener(v -> { IntercomService s = IntercomService.instance; if (s != null) s.setHandsFree(true); render(); });
        modes.addView(modePtt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modes.addView(modeOpen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(modes, lp(22));

        ptt = new Button(this);
        ptt.setAllCaps(true);
        ptt.setTextColor(FG);
        ptt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(220), dp(220));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(18);
        col.addView(ptt, pp);
        ptt.setOnTouchListener(this::onPtt);

        rx = text("—", 14, DIM, false);
        rx.setTypeface(Typeface.MONOSPACE);
        rx.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(rx, lp(12));

        help = text("Sigue funcionando con la pantalla apagada. Si se corta la Wi‑Fi, se reconecta solo.", 12, DIM, false);
        help.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(help, lp(14));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); IntercomService.ui = this; h.post(tick); }
    @Override protected void onPause() { super.onPause(); IntercomService.ui = null; h.removeCallbacks(tick); }
    @Override public void onState() { render(); }

    // ---------- Acciones ----------
    private void togglePower() {
        if (IntercomService.instance != null) {
            stopService(new Intent(this, IntercomService.class));
            h.postDelayed(this::render, 300);
            return;
        }
        begin(null);
    }

    private void askJoin() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setSingleLine(true);
        String gw = gatewayIp();
        String saved = prefs().getString("host", null);
        et.setText(gw != null ? gw : saved != null ? saved : "");
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Unirse a otro Android")
                .setMessage("Primero conéctate a la zona Wi‑Fi del otro Android. Su dirección aparece en su pantalla"
                        + (gw != null ? " (ya la detecté)." : "."))
                .setView(et)
                .setPositiveButton("Unirse", (d, w) -> {
                    String host = cleanHost(et.getText().toString());
                    if (host.isEmpty()) return;
                    prefs().edit().putString("host", host).apply();
                    begin(host);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private SharedPreferences prefs() { return getSharedPreferences("intercom", Context.MODE_PRIVATE); }

    private static String cleanHost(String t) {
        t = t.trim().replaceFirst("^[a-zA-Z]+://", "");
        int i = t.indexOf('/');
        if (i >= 0) t = t.substring(0, i);
        i = t.indexOf(':');
        if (i >= 0) t = t.substring(0, i);
        return t;
    }

    /** IP de la zona Wi‑Fi a la que está conectado este teléfono (es el anfitrión). */
    private String gatewayIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int g = wm.getDhcpInfo().gateway;
            if (g == 0) return null;
            return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >>> 24) & 0xFF);
        } catch (Exception e) {
            return null;
        }
    }

    private void launch() {
        Intent i = new Intent(this, IntercomService.class);
        if (pendingJoin != null) i.putExtra(IntercomService.EXTRA_JOIN, pendingJoin);
        startForegroundService(i);
    }

    private void begin(String joinHost) {
        pendingJoin = joinHost;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), 1); return; }
        launch();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launch();
        } else {
            IntercomService.lastError = "Sin permiso de micrófono no se puede hablar. Actívalo en Ajustes → Apps → Intercom Local → Permisos.";
            render();
        }
    }

    private boolean onPtt(View v, MotionEvent e) {
        IntercomService s = IntercomService.instance;
        if (s == null) return true;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (s.handsFree) s.setTalking(!s.talking); else s.setTalking(true);
            v.performClick();
        } else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !s.handsFree) {
            s.setTalking(false);
        }
        return true;
    }

    // ---------- Pintar estado ----------
    private void render() {
        IntercomService s = IntercomService.instance;
        boolean on = s != null;

        String ip = Io.preferredIp();
        String url = ip == null ? null : "http://" + ip + ":8080";
        addr.setText(url == null ? "Activa la zona Wi‑Fi" : ip + ":8080");
        if (url != null && !url.equals(shownUrl)) { qr.setImageBitmap(makeQr(url)); shownUrl = url; }
        qr.setVisibility(url == null ? View.GONE : View.VISIBLE);

        StringBuilder all = new StringBuilder();
        for (String[] x : Io.localIps()) all.append(x[0]).append("  ").append(x[1]).append('\n');
        ips.setText(all.length() == 0 ? "No hay red Wi‑Fi local. Enciende la zona Wi‑Fi en Ajustes." : "Redes: \n" + all.toString().trim());

        boolean joined = on && s.joinHost != null;
        boolean linked = on && (joined ? s.joinConnected : s.clients > 0);
        String err = IntercomService.lastError;
        String st;
        int stColor;
        if (!on) { st = err != null ? err : "● Apagado"; stColor = err != null ? BAD : DIM; }
        else if (joined) {
            if (s.joinConnected) { st = "● Unido a " + s.joinHost; stColor = OK; }
            else { st = err != null ? err : "● Buscando a " + s.joinHost + "…"; stColor = ACCENT; }
        } else if (s.clients == 0) { st = "● Servidor encendido · esperando invitados"; stColor = ACCENT; }
        else { st = "● Conectado · " + s.clients + (s.clients == 1 ? " invitado" : " invitados"); stColor = OK; }
        status.setText(st);
        status.setTextColor(stColor);

        hostBox.setVisibility(joined ? View.GONE : View.VISIBLE);
        join.setVisibility(on ? View.GONE : View.VISIBLE);
        power.setText(joined ? "Desconectar" : on ? "Apagar servidor" : "Encender servidor");
        power.setBackground(on ? box(PANEL, LINE, 10) : box(ACCENT, ACCENT, 10));
        power.setTextColor(on ? FG : 0xFF1A1405);

        boolean hf = on && s.handsFree;
        styleMode(modePtt, !hf);
        styleMode(modeOpen, hf);

        boolean talking = on && s.talking;
        ptt.setText(!on ? "Apagado" : hf ? (talking ? "Micrófono\nabierto" : "Silenciado") : "Mantén\npara hablar");
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(talking ? 0xFF2A2516 : 0xFF1F2528);
        g.setStroke(dp(talking ? 4 : 2), talking ? ACCENT : LINE);
        ptt.setBackground(g);
        ptt.setTextColor(talking ? ACCENT : on ? FG : DIM);

        boolean remote = on && s.remoteTalking;
        rx.setText(remote ? "◉ El otro está hablando" : linked ? "Escuchando" : "—");
        rx.setTextColor(remote ? OK : DIM);
    }

    private void styleMode(Button b, boolean active) {
        b.setBackground(active ? box(LINE, LINE, 8) : null);
        b.setTextColor(active ? ACCENT : FG);
    }

    private Bitmap makeQr(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400);
            int w = m.getWidth(), hh = m.getHeight();
            int[] px = new int[w * hh];
            for (int y = 0; y < hh; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, hh);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Ayudas de vista ----------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(primary ? 0xFF1A1405 : FG);
        b.setBackground(primary ? box(ACCENT, ACCENT, 10) : null);
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
}
