package eu.mrogalski.saidit;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import static eu.mrogalski.saidit.SaidIt.*;

public class SaidItService extends Service {
    static final String TAG = SaidItService.class.getSimpleName();
    private static final int FOREGROUND_NOTIFICATION_ID = 458;
    private static final String YOUR_NOTIFICATION_CHANNEL_ID = "SaidItServiceChannel";
    public static final long RESERVED_MEMORY = 50 * 1024 * 1024; // 50 MB for app overhead

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;


    File opusFile;
    AudioRecord audioRecord; // used only in the audio thread
    OpusFileWriter opusFileWriter; // used only in the audio thread
    final OpusRingBuffer opusRingBuffer = new OpusRingBuffer(); // used only in the audio thread
    OpusEncoder opusEncoder; // used only in the audio thread
    final byte[] pcmBuf = new byte[OpusRingBuffer.FRAME_SIZE * 2];
    int pcmBufBytes;

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread

    @Override
    public void onCreate() {

        SAMPLE_RATE = 48000;
        FILL_RATE = 2 * SAMPLE_RATE;

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }

    }

    @Override
    public void onDestroy() {
        stopRecording(null, "");
        innerStopListening();
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new BackgroundRecorderBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void enableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, true).commit();

        innerStartListening();
    }

    public void disableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).commit();

        innerStopListening();
    }

    int state;

    static final int STATE_READY = 0;
    static final int STATE_LISTENING = 1;
    static final int STATE_RECORDING = 2;

    private void innerStartListening() {
        switch(state) {
            case STATE_READY:
                break;
            case STATE_LISTENING:
            case STATE_RECORDING:
                return;
        }
        state = STATE_LISTENING;

        Log.d(TAG, "Queueing: START LISTENING");

        startService(new Intent(this, this.getClass()));

        long memorySize = getMemorySize();
        final long available = getAvailableMemory();
        if (memorySize > available) memorySize = available;
        final long finalMemorySize = memorySize;

        audioHandler.post(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                Log.d(TAG, "Executing: START LISTENING");
                Log.d(TAG, "Audio: INITIALIZING AUDIO_RECORD");

                try {
                    opusEncoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
                } catch (OpusException e) {
                    Log.e(TAG, "Audio: OPUS ENCODER INIT ERROR", e);
                    state = STATE_READY;
                    return;
                }

                audioRecord = new AudioRecord(
                       MediaRecorder.AudioSource.MIC,
                       SAMPLE_RATE,
                       AudioFormat.CHANNEL_IN_MONO,
                       AudioFormat.ENCODING_PCM_16BIT,
                       AudioMemory.CHUNK_SIZE);

                if(audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Audio: INITIALIZATION ERROR - releasing resources");
                    audioRecord.release();
                    audioRecord = null;
                    state = STATE_READY;
                    return;
                }

                Log.d(TAG, "Audio: STARTING AudioRecord");
                opusRingBuffer.allocate(finalMemorySize);
                pcmBufBytes = 0;

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioRecord.startRecording();
                audioHandler.post(audioReader);
            }
        });


    }

    private void innerStopListening() {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }
        state = STATE_READY;
        Log.d(TAG, "Queueing: STOP LISTENING");

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                if(audioRecord != null)
                    audioRecord.release();
                audioRecord = null;
                audioHandler.removeCallbacks(audioReader);
                opusRingBuffer.clear();
            }
        });

    }

    public void dumpRecording(final float memorySeconds, final RecordingReceiver recordingReceiver, String newFileName) {
        if(state != STATE_LISTENING) throw new IllegalStateException("Not listening!");

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependFrames = (int)(memorySeconds * SAMPLE_RATE / OpusRingBuffer.FRAME_SIZE);
                int framesAvailable = opusRingBuffer.getFrameCount();

                int skipFrames = Math.max(0, framesAvailable - prependFrames);

                int useFrames = framesAvailable - skipFrames;
                long millis  = System.currentTimeMillis() - 1000L * useFrames * OpusRingBuffer.FRAME_SIZE / SAMPLE_RATE;
                final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE;
                final String dateTime = DateUtils.formatDateTime(SaidItService.this, millis, flags);
                String filename = "Echo - " + dateTime + ".opus";
                if(!newFileName.equals("")){
                    filename = newFileName + ".opus";
                }

                File storageDir;
                if(isExternalStorageWritable()){
                    storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Echo");
                }else{
                    storageDir = new File(getFilesDir(), "Echo");
                }

                if(!storageDir.exists()){
                    storageDir.mkdir();
                }
                File file = new File(storageDir, filename);

                if (!file.exists()) {
                    try {
                        if (!file.createNewFile()) {
                            throw new IOException("Failed to create file");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        showToast(getString(R.string.cant_create_file) + file.getAbsolutePath());
                    }
                }
                try (OpusFileWriter writer = new OpusFileWriter(file, SAMPLE_RATE)) {
                    try {
                        opusRingBuffer.read(skipFrames, new OpusRingBuffer.FrameConsumer() {
                            @Override
                            public void consume(byte[] frame, int offset, int length) throws IOException {
                                writer.writeFrame(frame, offset, length);
                            }
                        });
                    } catch (IOException e) {
                        showToast(getString(R.string.error_during_writing_history_into) + file.getAbsolutePath());
                        Log.e(TAG, "Error during writing history into " + file.getAbsolutePath(), e);
                    }
                    if (recordingReceiver != null) {
                        recordingReceiver.fileReady(file, writer.getTotalPcmSamples() / (float) SAMPLE_RATE);
                    }
                } catch (IOException e) {
                    showToast(getString(R.string.cant_create_file) + file.getAbsolutePath());
                    Log.e(TAG, "Can't create file " + file.getAbsolutePath(), e);
                }
            }
        });

    }
    private static boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }
    private void showToast(String message) {
        Toast.makeText(SaidItService.this, message, Toast.LENGTH_LONG).show();
    }

    public void startRecording(final float prependedMemorySeconds) {
        switch(state) {
            case STATE_READY:
                innerStartListening();
                break;
            case STATE_LISTENING:
                break;
            case STATE_RECORDING:
                return;
        }
        state = STATE_RECORDING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependFrames = (int)(prependedMemorySeconds * SAMPLE_RATE / OpusRingBuffer.FRAME_SIZE);
                int framesAvailable = opusRingBuffer.getFrameCount();

                int skipFrames = Math.max(0, framesAvailable - prependFrames);

                int useFrames = framesAvailable - skipFrames;
                long millis  = System.currentTimeMillis() - 1000L * useFrames * OpusRingBuffer.FRAME_SIZE / SAMPLE_RATE;
                final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE;
                final String dateTime = DateUtils.formatDateTime(SaidItService.this, millis, flags);
                String filename = "Echo - " + dateTime + ".opus";

                File storageDir;
                if(isExternalStorageWritable()){
                    storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Echo");
                }else{
                    storageDir = new File(getFilesDir(), "Echo");
                }
                final String storagePath = storageDir.getAbsolutePath();

                String path = storagePath + "/" + filename;

                opusFile = new File(path);
                try {
                    opusFile.createNewFile();
                } catch (IOException e) {
                    filename = filename.replace(':', '.');
                    path = storagePath + "/" + filename;
                    opusFile = new File(path);
                }
                try {
                    opusFileWriter = new OpusFileWriter(opusFile, SAMPLE_RATE);
                } catch (IOException e) {
                    final String errorMessage = getString(R.string.cant_create_file) + path;
                    Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                    Log.e(TAG, errorMessage, e);
                    return;
                }

                if(skipFrames < framesAvailable) {
                    try {
                        opusRingBuffer.read(skipFrames, new OpusRingBuffer.FrameConsumer() {
                            @Override
                            public void consume(byte[] frame, int offset, int length) throws IOException {
                                opusFileWriter.writeFrame(frame, offset, length);
                            }
                        });
                    } catch (IOException e) {
                        Toast.makeText(SaidItService.this, getString(R.string.error_during_writing_history_into) + path, Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Error during writing history into " + path, e);
                        stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
                    }
                }
            }
        });

    }

    public long getMemorySize() {
        SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        long pref = preferences.getLong(AUDIO_MEMORY_SIZE_KEY, getAvailableMemory());
        long available = getAvailableMemory();
        if (pref > available) pref = available;
        return pref;
    }

    public long getAvailableMemory() {
        return Runtime.getRuntime().maxMemory() - RESERVED_MEMORY;
    }

    public void setMemorySize(final long memorySize) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        long clamped = memorySize;
        long available = getAvailableMemory();
        if (clamped > available) clamped = available;
        final long finalSize = clamped;
        preferences.edit().putLong(AUDIO_MEMORY_SIZE_KEY, finalSize).commit();

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            audioHandler.post(new Runnable() {
                @Override
                public void run() {
                    opusRingBuffer.allocate(finalSize);
                }
            });
        }
    }

    public interface RecordingReceiver {
        public void fileReady(File file, float runtime);
    }

    public void stopRecording(final RecordingReceiver recordingReceiver, String newFileName) {
        switch(state) {
            case STATE_READY:
            case STATE_LISTENING:
                return;
            case STATE_RECORDING:
                break;
        }
        state = STATE_LISTENING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                try {
                    opusFileWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "CLOSING ERROR", e);
                }
                if(recordingReceiver != null) {
                    recordingReceiver.fileReady(opusFile, opusFileWriter.getTotalPcmSamples() / (float) SAMPLE_RATE);
                }
                opusFileWriter = null;
            }
        });

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if(!preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStopListening();
        }

        stopForeground(true);
    }

    private void flushAudioRecord() {
        // Only allowed on the audio thread
        assert audioHandler.getLooper() == Looper.myLooper();
        audioHandler.removeCallbacks(audioReader); // remove any delayed callbacks
        audioReader.run();
    }

    final Runnable audioReader = new Runnable() {
        @Override
        public void run() {
            try {
                if (audioRecord == null) return;
                byte[] chunk = new byte[AudioMemory.CHUNK_SIZE];
                final int read = audioRecord.read(chunk, 0, chunk.length, AudioRecord.READ_NON_BLOCKING);
                if (read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AUDIO RECORD ERROR - BAD VALUE");
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AUDIO RECORD ERROR - INVALID OPERATION");
                } else if (read == AudioRecord.ERROR) {
                    Log.e(TAG, "AUDIO RECORD ERROR - UNKNOWN ERROR");
                } else if (read > 0) {
                    int off = 0;
                    int remaining = read;
                    while (remaining > 0) {
                        int copy = Math.min(remaining, pcmBuf.length - pcmBufBytes);
                        System.arraycopy(chunk, off, pcmBuf, pcmBufBytes, copy);
                        pcmBufBytes += copy;
                        off += copy;
                        remaining -= copy;

                        if (pcmBufBytes == pcmBuf.length) {
                            byte[] packet = new byte[4096];
                            int encoded = opusEncoder.encode(pcmBuf, 0, OpusRingBuffer.FRAME_SIZE, packet, 0, packet.length);
                            pcmBufBytes = 0;

                            byte[] frame = new byte[encoded];
                            System.arraycopy(packet, 0, frame, 0, encoded);
                            opusRingBuffer.store(frame);

                            if (opusFileWriter != null) {
                                opusFileWriter.writeFrame(frame, 0, encoded);
                            }
                        }
                    }
                }

                // Reschedule
                float bufferSizeInSeconds = audioRecord.getBufferSizeInFrames() / (float)SAMPLE_RATE;
                float delaySeconds = bufferSizeInSeconds - 1;
                delaySeconds = Math.max(delaySeconds, bufferSizeInSeconds * 0.5f);
                delaySeconds = Math.min(delaySeconds, bufferSizeInSeconds * 0.9f);
                audioHandler.postDelayed(audioReader, (long)(delaySeconds * 1000));
            } catch (IOException e) {
                final String errorMessage = getString(R.string.error_during_recording_into) + (opusFile != null ? opusFile.getName() : "?");
                Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                Log.e(TAG, errorMessage, e);
                stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
            } catch (OpusException e) {
                Log.e(TAG, "OPUS ENCODE ERROR", e);
            } catch (Exception e) {
                Log.e(TAG, "AUDIO READER ERROR", e);
            }
        }
    };

    public interface StateCallback {
        public void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded);
    }

    public void getState(final StateCallback stateCallback) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        final boolean listeningEnabled = preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        final boolean recording = (state == STATE_RECORDING);
        final Handler sourceHandler = new Handler();
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int frameCount = opusRingBuffer.getFrameCount();
                float frameSeconds = (float) frameCount * OpusRingBuffer.FRAME_SIZE / SAMPLE_RATE;

                int recordedSamples = 0;
                if(opusFileWriter != null) {
                    recordedSamples += opusFileWriter.getTotalPcmSamples();
                }
                float recordedSeconds = recordedSamples / (float) SAMPLE_RATE;

                final float finalFrameSeconds = frameSeconds;
                final float finalRecordedSeconds = recordedSeconds;
                sourceHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stateCallback.state(listeningEnabled, recording,
                                finalFrameSeconds,
                                finalFrameSeconds,
                                finalRecordedSeconds);
                    }
                });
            }
        });
    }

    public float getMemorizedSeconds() {
        long mem = getMemorySize();
        int maxFrames = (int) (mem / (OpusRingBuffer.AVG_FRAME_BUDGET));
        return maxFrames * (float) OpusRingBuffer.FRAME_SIZE / SAMPLE_RATE;
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        return START_STICKY;
    }

    // Workaround for bug where recent app removal caused service to stop
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT| PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmService = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, YOUR_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.recording))
                .setSmallIcon(R.drawable.ic_stat_notify_recording)
                .setTicker(getString(R.string.recording))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true); // Ensure notification is ongoing

        // Create the notification channel
        NotificationChannel channel = new NotificationChannel(
                YOUR_NOTIFICATION_CHANNEL_ID,
                "Recording Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);

        return notificationBuilder.build();
    }

}
