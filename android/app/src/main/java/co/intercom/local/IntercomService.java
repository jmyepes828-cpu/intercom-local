package co.intercom.local;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Servicio en primer plano: servidor de voz + micrófono + altavoz/audífonos.
 * Sigue funcionando con la pantalla apagada.
 */
public class IntercomService extends Service implements MiniServer.Listener {

    public interface UiListener { void onState(); }

    static volatile IntercomService instance;
    static volatile UiListener ui;
    static volatile String lastError;
    static final String EXTRA_JOIN = "join";

    private static final int RATE = 16000;
    private static final int FRAME_BYTES = 640; // 20 ms

    volatile boolean talking;
    volatile boolean handsFree;
    volatile boolean remoteTalking;
    volatile int clients;
    /** null = este teléfono es el anfitrión; si no, la IP del Android al que se une. */
    volatile String joinHost;
    volatile boolean joinConnected;
    private final WsClient wsc = new WsClient();

    private MiniServer server;
    private AudioManager am;
    private AudioRecord rec;
    private AudioTrack track;
    private final LinkedBlockingQueue<byte[]> playQ = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioDeviceCallback deviceCallback;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            joinHost = intent == null ? null : intent.getStringExtra(EXTRA_JOIN);
            try {
                goForeground();
                startAll();
                lastError = null;
            } catch (Exception e) {
                lastError = "No se pudo iniciar: " + e.getMessage();
                stopSelf();
            }
            notifyUi();
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("intercom", "Intercomunicador", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "intercom")
                .setContentTitle("Intercom Local encendido")
                .setContentText(joinHost == null ? "Servidor de voz activo en la zona Wi‑Fi" : "Unido a " + joinHost)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, n);
    }

    private void startAll() throws Exception {
        instance = this;
        running = true;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intercom:audio");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "intercom:wifi");
        wifiLock.acquire();

        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.setSpeakerphoneOn(false);
        routeAudio();
        deviceCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { routeAudio(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { routeAudio(); }
        };
        am.registerAudioDeviceCallback(deviceCallback, main);

        if (joinHost == null) {
            Pki pki = new Pki(getFilesDir());
            pki.load();
            server = new MiniServer(pki, this::asset, this);
            server.start(8080, 8443);
        } else {
            startJoin(joinHost);
        }

        startRecording();
        startPlayback();
        Thread ping = new Thread(() -> {
            while (running) {
                sendText("{\"t\":\"p\"}");
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "ping");
        ping.setDaemon(true);
        ping.start();
    }

    private byte[] asset(String path) {
        try (InputStream in = getAssets().open(path)) { return Io.readAll(in); }
        catch (Exception e) { return null; }
    }

    /** Envía el audio a los audífonos (Bluetooth o con cable) si hay; si no, al auricular del teléfono. */
    private void routeAudio() {
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                List<AudioDeviceInfo> devs = am.getAvailableCommunicationDevices();
                int[] order = {AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE};
                for (int type : order) {
                    for (AudioDeviceInfo d : devs) {
                        if (d.getType() == type) { am.setCommunicationDevice(d); return; }
                    }
                }
            } else {
                boolean bt = false;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) bt = true;
                }
                if (bt && am.isBluetoothScoAvailableOffCall()) {
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                } else if (am.isBluetoothScoOn()) {
                    am.setBluetoothScoOn(false);
                    am.stopBluetoothSco();
                }
            }
        } catch (Exception ignored) { }
    }

    private void startRecording() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_BYTES * 10));
        } catch (SecurityException e) {
            lastError = "Falta el permiso de micrófono";
            return;
        }
        int sid = rec.getAudioSessionId();
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid).setEnabled(true); } catch (Exception ignored) { }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid).setEnabled(true); } catch (Exception ignored) { }
        rec.startRecording();
        Thread t = new Thread(() -> {
            byte[] buf = new byte[FRAME_BYTES];
            while (running) {
                int n = rec.read(buf, 0, FRAME_BYTES);
                if (n == FRAME_BYTES && talking) sendAudio(buf.clone());
                else if (n <= 0) { try { Thread.sleep(10); } catch (InterruptedException e) { return; } }
            }
        }, "mic");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private void startPlayback() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    byte[] b = playQ.poll(200, TimeUnit.MILLISECONDS);
                    if (b == null) continue;
                    while (playQ.size() > 12) playQ.poll(); // no acumular más de ~250 ms de retraso
                    track.write(b, 0, b.length);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) { }
            }
        }, "speaker");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    // ---------- Envío según el papel (anfitrión o invitado) ----------
    private void sendAudio(byte[] pcm) {
        if (server != null) server.broadcastAudio(pcm, null);
        else wsc.sendAudio(pcm);
    }

    private void sendText(String msg) {
        if (server != null) server.broadcastText(msg, null);
        else wsc.sendText(msg);
    }

    /** Modo invitado: conecta al Android anfitrión y reconecta solo si se corta. */
    private void startJoin(String host) {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    wsc.runOnce(host, 8080, new WsClient.Listener() {
                        @Override public void onOpen() {
                            joinConnected = true;
                            lastError = null;
                            wsc.sendText("{\"t\":\"talk\",\"on\":" + talking + "}");
                            main.post(IntercomService.this::routeAudio);
                            notifyUi();
                        }
                        @Override public void onAudio(byte[] pcm) { playQ.offer(pcm); }
                        @Override public void onText(String msg) {
                            if (msg.contains("\"talk\"")) { remoteTalking = msg.contains("\"on\":true"); notifyUi(); }
                        }
                        @Override public void onClose() {
                            joinConnected = false;
                            remoteTalking = false;
                            notifyUi();
                        }
                    });
                } catch (Exception e) {
                    if (running) { lastError = "No se encuentra el anfitrión en " + host + ". Reintentando…"; notifyUi(); }
                }
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            }
        }, "join");
        t.setDaemon(true);
        t.start();
    }

    // ---------- Eventos del servidor ----------
    @Override
    public void onClients(int count) {
        clients = count;
        if (server != null) server.broadcastText("{\"t\":\"talk\",\"on\":" + talking + "}", null);
        if (count == 0) remoteTalking = false;
        main.post(this::routeAudio);
        notifyUi();
    }

    @Override
    public void onAudio(MiniServer.Client from, byte[] pcm) {
        playQ.offer(pcm);
        if (server != null) server.broadcastAudio(pcm, from); // si hay varios invitados, se escuchan entre ellos
    }

    @Override
    public void onText(MiniServer.Client from, String msg) {
        if (msg.contains("\"talk\"")) {
            remoteTalking = msg.contains("\"on\":true");
            if (server != null) server.broadcastText(msg, from);
            notifyUi();
        }
    }

    // ---------- Controles desde la pantalla ----------
    void setTalking(boolean on) {
        talking = on;
        sendText("{\"t\":\"talk\",\"on\":" + on + "}");
        notifyUi();
    }

    void setHandsFree(boolean on) {
        handsFree = on;
        setTalking(on);
    }

    private void notifyUi() {
        main.post(() -> { UiListener l = ui; if (l != null) l.onState(); });
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (server != null) server.stop();
        wsc.close();
        joinConnected = false;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) { }
        try {
            if (am != null) {
                if (deviceCallback != null) am.unregisterAudioDeviceCallback(deviceCallback);
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                else if (am.isBluetoothScoOn()) { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        notifyUi();
        super.onDestroy();
    }
}
