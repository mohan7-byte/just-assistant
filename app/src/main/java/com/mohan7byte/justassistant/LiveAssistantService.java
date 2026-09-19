package com.mohan7byte.justassistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private final BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(96);

    private OkHttpClient client;
    private WebSocket socket;
    private AudioRecord recorder;
    private AudioTrack player;

    private ExecutorService captureExecutor;
    private ExecutorService playbackExecutor;
    private ScheduledExecutorService scheduler;

    private volatile boolean userStopped = true;
    private volatile boolean reconnectPending;
    private volatile int consecutiveFailures;
    private volatile String apiKey;
    private volatile String model;
    private volatile String persona;
    private volatile String resumeHandle;

    @Override public void onCreate() {
        super.onCreate();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        playbackExecutor = Executors.newSingleThreadExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            if (running.get()) SecurePrefs.touchRunning(this);
        }, 0, 2, TimeUnit.SECONDS);
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
            failSession("Open Just Assistant and save your Gemini API key first.");
            return START_NOT_STICKY;
        }

        if (!hasMicPermission()) {
            failSession("Microphone permission is required.");
            return START_NOT_STICKY;
        }

        if (running.compareAndSet(false, true)) {
            userStopped = false;
            reconnectPending = false;
            consecutiveFailures = 0;
            SecurePrefs.saveRunning(this, true);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, buildNotification(),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(NOTIF_ID, buildNotification());
                }

                notifyState(LiveState.CONNECTING, "Connecting to Gemini Live…");
                initPlayback();
                connect(false);
            } catch (SecurityException e) {
                failSession("Android blocked microphone access. Open the app and tap Start Live once.");
            } catch (Exception e) {
                failSession("Could not start Live: " + safeError(e));
            }
        }

        return START_NOT_STICKY;
    }

    private void connect(boolean reconnect) {
        if (!running.get()) return;
        sessionReady.set(false);

        if (apiKey == null || apiKey.trim().isEmpty()) {
            failSession("Gemini API key is missing.");
            return;
        }

        if (socket != null) {
            try { socket.cancel(); } catch (Exception ignored) {}
            socket = null;
        }

        HttpUrl url = HttpUrl.get(WS_ENDPOINT).newBuilder()
                .addQueryParameter("key", apiKey)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        if (client == null) {
            client = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .pingInterval(15, TimeUnit.SECONDS)
                    .build();
        }

        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                consecutiveFailures = 0;
                sendSetup(ws);
            }

            @Override public void onMessage(WebSocket ws, String text) {
                handleServerMessage(ws, text);
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                sessionReady.set(false);
                stopCapture();

                if (userStopped) return;

                int http = response == null ? -1 : response.code();
                if (http == 400 || http == 401 || http == 403 || http == 404
                        || ++consecutiveFailures >= 5) {
                    failSession("Gemini Live connection failed"
                            + (http > 0 ? " (" + http + ")" : "")
                            + ". Check your API key and model name.");
                } else {
                    scheduleReconnect(Math.min(5000L, 750L * consecutiveFailures));
                }
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                sessionReady.set(false);
                stopCapture();

                if (!userStopped) {
                    if (++consecutiveFailures >= 5) {
                        failSession("Gemini Live connection closed repeatedly.");
                    } else {
                        scheduleReconnect(Math.min(5000L, 750L * consecutiveFailures));
                    }
                }
            }
        });
    }

    private void sendSetup(WebSocket ws) {
        try {
            JSONObject setup = new JSONObject();
            setup.put("model", model.startsWith("models/") ? model : "models/" + model);
            setup.put("responseModalities", new JSONArray().put("AUDIO"));

            JSONObject system = new JSONObject();
            system.put("parts", new JSONArray().put(new JSONObject().put("text", persona)));
            setup.put("systemInstruction", system);

            setup.put("contextWindowCompression",
                    new JSONObject().put("slidingWindow", new JSONObject()));

            JSONObject resume = new JSONObject();
            if (resumeHandle != null && !resumeHandle.isEmpty()) {
                resume.put("handle", resumeHandle);
            }
            setup.put("sessionResumption", resume);

            ws.send(new JSONObject().put("setup", setup).toString());
        } catch (Exception e) {
            failSession("Could not configure Gemini Live: " + safeError(e));
        }
    }

    private void handleServerMessage(WebSocket ws, String text) {
        try {
            JSONObject root = new JSONObject(text);

            if (root.has("error")) {
                JSONObject error = root.optJSONObject("error");
                failSession(error == null
                        ? "Gemini Live returned an error."
                        : error.optString("message", "Gemini Live returned an error."));
                return;
            }

            if (root.has("sessionResumptionUpdate")) {
                JSONObject sr = root.getJSONObject("sessionResumptionUpdate");
                if (sr.optBoolean("resumable", false)) {
                    String handle = sr.optString("newHandle", "");
                    if (handle.isEmpty()) {
                        handle = sr.optString("handle", "");
                    }
                    if (!handle.isEmpty()) resumeHandle = handle;
                }
                return;
            }

            if (root.has("goAway")) {
                long delay = parseDurationMillis(root.getJSONObject("goAway")
                        .optJSONObject("timeLeft"));
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
                consecutiveFailures = 0;
                startCapture();
                notifyState(LiveState.READY, "Gemini Live ready");
                updateNotification("Gemini Live active");
                return;
            }

            JSONObject content = root.optJSONObject("serverContent");
            if (content == null) return;

            if (content.optBoolean("interrupted", false)) {
                flushPlayback();
                notifyState(LiveState.IDLE, "Listening…");
                return;
            }

            if (content.optBoolean("turnComplete", false)) {
                setOrbSpeaking(false);
                notifyState(LiveState.IDLE, "Listening…");
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
                notifyState(LiveState.SPEAKING, "Speaking…");
            }
        } catch (Exception ignored) {
            // Ignore individual malformed messages; keep the realtime session alive.
        }
    }

    private long parseDurationMillis(JSONObject duration) {
        if (duration == null) return 1500L;

        long seconds = duration.optLong("seconds", 0L);
        long nanos = duration.optLong("nanos", 0L);
        String text = duration.optString("timeLeft", "");

        if (text.endsWith("s")) {
            try {
                double value = Double.parseDouble(text.substring(0, text.length() - 1));
                return Math.max(0L, Math.round(value * 1000.0));
            } catch (Exception ignored) {
            }
        }

        return seconds * 1000L + nanos / 1_000_000L;
    }

    private synchronized void startCapture() {
        if (!running.get() || !sessionReady.get()) return;
        if (captureExecutor != null && !captureExecutor.isShutdown()) return;

        captureExecutor = Executors.newSingleThreadExecutor();
        captureExecutor.execute(() -> {
            if (!hasMicPermission()) {
                failSession("Microphone permission was lost.");
                return;
            }

            final int sampleRate = 16000;
            int min = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (min <= 0) {
                failSession("This device does not expose a 16 kHz microphone input.");
                return;
            }

            int bufferSize = Math.max(min * 2, 4096);

            try {
                AudioRecord input = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                if (input.getState() != AudioRecord.STATE_INITIALIZED) {
                    input.release();
                    throw new IllegalStateException("Microphone initialization failed");
                }

                recorder = input;
                recorder.startRecording();

                // 1024 bytes = 32 ms of mono 16-bit PCM at 16 kHz.
                byte[] pcm = new byte[1024];

                while (running.get() && sessionReady.get()) {
                    int n = recorder.read(pcm, 0, pcm.length);

                    if (n <= 0 || socket == null) continue;

                    byte[] exact = new byte[n];
                    System.arraycopy(pcm, 0, exact, 0, n);

                    JSONObject audio = new JSONObject();
                    audio.put("data", Base64.encodeToString(exact, Base64.NO_WRAP));
                    audio.put("mimeType", "audio/pcm;rate=16000");

                    JSONObject realtime = new JSONObject();
                    realtime.put("audio", audio);

                    socket.send(new JSONObject()
                            .put("realtimeInput", realtime)
                            .toString());
                }
            } catch (Exception e) {
                if (!userStopped) {
                    failSession("Microphone capture failed: " + safeError(e));
                }
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
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
    }

    private void initPlayback() {
        if (player != null) return;

        int min = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (min <= 0) {
            throw new IllegalStateException("Audio output is unavailable");
        }

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        int bufferSize = Math.max(min * 2, 8192);

        AudioTrack track = new AudioTrack(
                attrs,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("Audio output initialization failed");
        }

        player = track;
        player.play();

        playbackExecutor.execute(() -> {
            while (running.get()) {
                try {
                    byte[] chunk = audioQueue.take();
                    AudioTrack current = player;
                    if (current != null) {
                        current.write(chunk, 0, chunk.length);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
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
        } catch (Exception ignored) {
        }
        setOrbSpeaking(false);
    }

    private void setOrbSpeaking(boolean speaking) {
        notifyState(speaking ? LiveState.SPEAKING : LiveState.IDLE,
                speaking ? "Speaking…" : "Listening…");
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

        notifyState(LiveState.STOPPED, "Stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void failSession(String message) {
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

        notifyState(LiveState.ERROR, message);
        updateNotification(message);
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, StopReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                this,
                1,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Just Assistant")
                .setContentText("Starting Gemini Live…")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Stop", pi)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL,
                "Gemini Live",
                NotificationManager.IMPORTANCE_LOW));
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(
                NOTIF_ID,
                new NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentTitle("Just Assistant")
                        .setContentText(text)
                        .setOngoing(true)
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .addAction(
                                android.R.drawable.ic_media_pause,
                                "Stop",
                                PendingIntent.getBroadcast(
                                        this,
                                        1,
                                        new Intent(this, StopReceiver.class),
                                        PendingIntent.FLAG_UPDATE_CURRENT
                                                | PendingIntent.FLAG_IMMUTABLE))
                        .build());
    }

    private void notifyState(String state, String message) {
        Intent intent = new Intent(LiveState.ACTION);
        intent.setPackage(getPackageName());
        intent.putExtra(LiveState.EXTRA_STATE, state);
        intent.putExtra(LiveState.EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }

    @Override public void onDestroy() {
        if (running.get()) {
            stopSession();
        } else {
            SecurePrefs.saveRunning(this, false);
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
