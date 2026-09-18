package com.mohan7byte.justassistant;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import okhttp3.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class LiveAssistantService extends Service {
    private static final int NOTIF_ID = 7;
    private static final String CHANNEL = "live";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private OkHttpClient client;
    private WebSocket socket;
    private AudioRecord recorder;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        if (intent != null && running.compareAndSet(false, true)) {
            startSession(intent.getStringExtra("apiKey"),
                    intent.getStringExtra("model"),
                    intent.getStringExtra("persona"));
        }
        return START_NOT_STICKY;
    }

    private void startSession(String key, String model, String persona) {
        if (key == null || key.isEmpty() || model == null || model.isEmpty()) {
            stopSelf();
            return;
        }
        client = new OkHttpClient.Builder().build();

        Request request = new Request.Builder()
                // Placeholder endpoint until the exact current Live API transport contract is wired in.
                .url("https://generativelanguage.googleapis.com/ws/")
                .build();

        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                ws.send(buildSetup(model, persona));
                io.execute(() -> captureLoop(ws));
            }
            @Override public void onMessage(WebSocket ws, String text) {
                // Protocol handling is intentionally isolated for the Live API wire format.
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                stopSelf();
            }
        });
    }

    private String buildSetup(String model, String persona) {
        // Live API setup envelope; transport details are isolated from the UI/service lifecycle.
        String safePersona = persona == null ? "" :
                persona.replace("\\", "\\\\").replace(""", "\"");
        String safeModel = model.replace(""", "");
        return "{\"setup\":{" +
                "\"model\":\"models/" + safeModel + "\"," +
                "\"generation_config\":{\"response_modalities\":[\"AUDIO\"]}," +
                "\"system_instruction\":{\"parts\":[{\"text\":\"" + safePersona + "\"}]}" +
                "}}";
    }

    private void captureLoop(WebSocket ws) {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min * 2, 4096);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        byte[] pcm = new byte[4096];
        try {
            recorder.startRecording();
            while (running.get()) {
                int n = recorder.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                // Audio framing/JSON envelope will be implemented in the protocol layer.
            }
        } finally {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            recorder = null;
        }
    }

    @Override public void onDestroy() {
        running.set(false);
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        if (recorder != null) { recorder.release(); recorder = null; }
        if (socket != null) { socket.close(1000, "user stopped"); socket = null; }
        if (client != null) { client.dispatcher().executorService().shutdown(); client = null; }
        io.shutdownNow();
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, StopReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Just Assistant")
                .setContentText("Gemini Live active")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", pi)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Live Assistant", NotificationManager.IMPORTANCE_LOW));
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
