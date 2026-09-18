package com.mohan7byte.justassistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class LiveAssistantService extends Service {
    private static final int NOTIF_ID = 7;
    private static final String CHANNEL = "live_assistant";
    private static final String WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean sessionReady = new AtomicBoolean(false);
    private final BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(64);

    private OkHttpClient client;
    private WebSocket socket;
    private AudioRecord recorder;
    private AudioTrack player;
    private android.media.audiofx.AcousticEchoCanceler echoCanceler;
    private android.media.audiofx.NoiseSuppressor noiseSuppressor;

    private ExecutorService captureExecutor;
    private ExecutorService playbackExecutor;
    private ScheduledExecutorService scheduler;

    private volatile boolean userStopped = true;
    private volatile boolean reconnectPending;
    private volatile String apiKey;
    private volatile String model;
    private volatile String persona;
    private volatile String resumeHandle;

    private WindowManager windowManager;
    private OrbView orbView;

    @Override public void onCreate() {
        super.onCreate();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        playbackExecutor = Executors.newSingleThreadExecutor();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();

        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSession();
            return START_NOT_STICKY;
        }

        apiKey = SecurePrefs.getApiKey(this);
        model = SecurePrefs.getModel(this);
        persona = SecurePrefs.getPersona(this);

        if (apiKey.isEmpty()) {
            notifyState("Add a Gemini API key in Settings");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (running.compareAndSet(false, true)) {
            userStopped = false;
            SecurePrefs.saveRunning(this, true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
            showOverlay();
            initPlayback();
            connect(false);
        }

        return START_NOT_STICKY;
    }

    private void connect(boolean reconnect) {
        if (!running.get()) return;
        sessionReady.set(false);

        if (socket != null) {
            try { socket.close(1000, "reconnect"); } catch (Exception ignored) {}
            socket = null;
        }

        HttpUrl url = HttpUrl.get(WS_ENDPOINT).newBuilder()
                .addQueryParameter("key", apiKey)
                .build();

        Request request = new Request.Builder().url(url).build();
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .build();
        }

        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                sendSetup(ws);
            }

            @Override public void onMessage(WebSocket ws, String text) {
                handleServerMessage(ws, text);
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                sessionReady.set(false);
                stopCapture();
                if (!userStopped) scheduleReconnect(700);
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                sessionReady.set(false);
                stopCapture();
                if (!userStopped) scheduleReconnect(500);
            }
        });
    }

    private void sendSetup(WebSocket ws) {
        try {
            JSONObject setup = new JSONObject();
            setup.put("model", model.startsWith("models/") ? model : "models/" + model);

            JSONObject generation = new JSONObject();
            generation.put("responseModalities", new JSONArray().put("AUDIO"));
            setup.put("generationConfig", generation);

            JSONObject system = new JSONObject();
            system.put("parts", new JSONArray().put(new JSONObject().put("text", persona)));
            setup.put("systemInstruction", system);

            setup.put("contextWindowCompression",
                    new JSONObject().put("slidingWindow", new JSONObject()));
            setup.put("inputAudioTranscription", new JSONObject());
            setup.put("outputAudioTranscription", new JSONObject());

            JSONObject resume = new JSONObject();
            if (resumeHandle != null && !resumeHandle.isEmpty()) {
                resume.put("handle", resumeHandle);
            }
            setup.put("sessionResumption", resume);

            ws.send(new JSONObject().put("setup", setup).toString());
        } catch (Exception e) {
            stopSession();
        }
    }

    private void handleServerMessage(WebSocket ws, String text) {
        try {
            JSONObject root = new JSONObject(text);

            if (root.has("sessionResumptionUpdate")) {
                JSONObject sr = root.getJSONObject("sessionResumptionUpdate");
                if (sr.optBoolean("resumable", false)) {
                    String handle = sr.optString("newHandle", "");
                    if (!handle.isEmpty()) resumeHandle = handle;
                }
                return;
            }

            if (root.has("goAway")) {
                long delay = parseDurationMillis(root.getJSONObject("goAway").optJSONObject("timeLeft"));
                delay = Math.max(250L, delay - 250L);
                scheduler.schedule(() -> {
                    if (running.get() && socket != null) {
                        try { socket.close(1000, "server reconnect"); } catch (Exception ignored) {}
                    }
                }, delay, TimeUnit.MILLISECONDS);
                return;
            }

            if (root.has("setupComplete")) {
                sessionReady.set(true);
                startCapture();
                updateNotification("Gemini Live active");
                return;
            }

            if (!root.has("serverContent")) return;
            JSONObject content = root.getJSONObject("serverContent");

            if (content.optBoolean("interrupted", false)) {
                flushPlayback();
                return;
            }

            if (content.optBoolean("turnComplete", false)) {
                setOrbSpeaking(false);
            }

            JSONObject modelTurn = content.optJSONObject("modelTurn");
            if (modelTurn == null) return;

            JSONArray parts = modelTurn.optJSONArray("parts");
            if (parts == null) return;

            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;

                JSONObject inline = part.optJSONObject("inlineData");
                if (inline == null) inline = part.optJSONObject("inline_data");
                if (inline == null) continue;

                String data = inline.optString("data", "");
                if (data.isEmpty()) continue;

                byte[] pcm = Base64.decode(data, Base64.DEFAULT);
                offerPlayback(pcm);
                setOrbSpeaking(true);
            }
        } catch (Exception ignored) {
            // Ignore malformed/unrecognized events without killing the live session.
        }
    }

    private long parseDurationMillis(JSONObject duration) {
        if (duration == null) return 1500L;
        long seconds = duration.optLong("seconds", 0L);
        long nanos = duration.optLong("nanos", 0L);
        return seconds * 1000L + nanos / 1_000_000L;
    }

    private synchronized void startCapture() {
        if (!running.get() || !sessionReady.get()) return;
        if (captureExecutor != null && !captureExecutor.isShutdown()) return;

        captureExecutor = Executors.newSingleThreadExecutor();
        captureExecutor.execute(() -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                stopSession();
                return;
            }

            final int sampleRate = 16000;
            int min = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(min * 2, 4096);

            try {
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(recorder.getAudioSessionId());
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(recorder.getAudioSessionId());
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                }

                recorder.startRecording();
                byte[] pcm = new byte[2048]; // 64 ms at 16 kHz mono, 16-bit

                while (running.get() && sessionReady.get()) {
                    int n = recorder.read(pcm, 0, pcm.length);
                    if (n <= 0 || socket == null) continue;

                    byte[] exact = new byte[n];
                    System.arraycopy(pcm, 0, exact, 0, n);
                    String b64 = Base64.encodeToString(exact, Base64.NO_WRAP);

                    JSONObject audio = new JSONObject();
                    audio.put("data", b64);
                    audio.put("mimeType", "audio/pcm;rate=16000");

                    JSONObject realtime = new JSONObject();
                    realtime.put("audio", audio);
                    socket.send(new JSONObject().put("realtimeInput", realtime).toString());
                }
            } catch (Exception ignored) {
            } finally {
                releaseRecorder();
            }
        });
    }

    private synchronized void stopCapture() {
        if (captureExecutor != null) {
            captureExecutor.shutdownNow();
            captureExecutor = null;
        }
        releaseRecorder();
    }

    private synchronized void releaseRecorder() {
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        try { if (echoCanceler != null) echoCanceler.release(); } catch (Exception ignored) {}
        try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        echoCanceler = null;
        noiseSuppressor = null;
    }

    private void initPlayback() {
        if (player != null) return;
        int min = AudioTrack.getMinBufferSize(
                24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(min * 2, 8192);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        player = new AudioTrack(attrs, format, buffer,
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        player.play();

        playbackExecutor.execute(() -> {
            while (running.get()) {
                try {
                    byte[] chunk = audioQueue.take();
                    if (player != null) player.write(chunk, 0, chunk.length);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {}
            }
        });
    }

    private void offerPlayback(byte[] pcm) {
        if (!audioQueue.offer(pcm)) {
            audioQueue.poll();
            audioQueue.offer(pcm);
        }
    }

    private void flushPlayback() {
        audioQueue.clear();
        try {
            if (player != null) {
                player.pause();
                player.flush();
                player.play();
            }
        } catch (Exception ignored) {}
        setOrbSpeaking(false);
    }

    private void scheduleReconnect(long delayMs) {
        if (!running.get() || userStopped || reconnectPending) return;
        reconnectPending = true;
        scheduler.schedule(() -> {
            reconnectPending = false;
            if (running.get() && !userStopped) connect(true);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void stopSession() {
        userStopped = true;
        reconnectPending = false;
        running.set(false);
        sessionReady.set(false);
        SecurePrefs.saveRunning(this, false);

        stopCapture();
        flushPlayback();

        if (socket != null) {
            try { socket.cancel(); } catch (Exception ignored) {}
            socket = null;
        }
        if (client != null) {
            try { client.dispatcher().cancelAll(); } catch (Exception ignored) {}
            client = null;
        }

        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || orbView != null) return;
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            orbView = new OrbView(this);
            orbView.setOnClickListener(v -> stopSession());

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    96, 96,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    android.graphics.PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = dp(110);
            windowManager.addView(orbView, p);
        } catch (Exception ignored) {
            orbView = null;
        }
    }

    private void removeOverlay() {
        if (windowManager != null && orbView != null) {
            try { windowManager.removeViewImmediate(orbView); } catch (Exception ignored) {}
        }
        orbView = null;
        windowManager = null;
    }

    private void setOrbSpeaking(boolean speaking) {
        if (orbView != null) {
            orbView.post(() -> orbView.setSpeaking(speaking));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, StopReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Just Assistant")
                .setContentText("Gemini Live active")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Stop", pi)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Gemini Live", NotificationManager.IMPORTANCE_LOW));
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Just Assistant")
                .setContentText(text)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop",
                        PendingIntent.getBroadcast(this, 1,
                                new Intent(this, StopReceiver.class),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build());
    }

    private void notifyState(String text) {
        createChannel();
        updateNotification(text);
    }

    @Override public void onDestroy() {
        if (running.get()) stopSession();
        else {
            SecurePrefs.saveRunning(this, false);
            removeOverlay();
            stopCapture();
            if (socket != null) {
                try { socket.cancel(); } catch (Exception ignored) {}
                socket = null;
            }
        }

        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (playbackExecutor != null) playbackExecutor.shutdownNow();
        if (scheduler != null) scheduler.shutdownNow();
        if (client != null) {
            try { client.dispatcher().cancelAll(); } catch (Exception ignored) {}
            client = null;
        }
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
