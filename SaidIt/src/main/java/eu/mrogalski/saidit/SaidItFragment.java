package eu.mrogalski.saidit;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import eu.mrogalski.android.TimeFormat;
import eu.mrogalski.android.Views;

public class SaidItFragment extends Fragment {

    private static final String TAG = SaidItFragment.class.getSimpleName();
    private static final String YOUR_NOTIFICATION_CHANNEL_ID = "SaidItServiceChannel";
    private Button record_pause_button;
    private Button listenButton;

    ListenButtonClickListener listenButtonClickListener = new ListenButtonClickListener();

    private boolean isListening = true;
    private boolean isRecording = false;

    private LinearLayout ready_section;
    private RecyclerView timeWheel;
    private Button saveButton;
    private int maxMemorizedSeconds;
    private TextView history_limit;
    private PickerAdapter pickerAdapter;
    private int currentSelectedPosition = 0;

    private static class PickerEntry {
        final int secondsAgo;
        final String label;
        PickerEntry(int secondsAgo, String label) {
            this.secondsAgo = secondsAgo;
            this.label = label;
        }
    }
    private ArrayList<PickerEntry> pickerEntries = new ArrayList<>();
    private TextView history_size;
    private TextView history_size_title;

    private LinearLayout rec_section;
    private TextView rec_indicator;
    private TextView rec_time;

    private ImageButton rate_on_google_play;
    private ImageView heart;

    @Override
    public void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        final Activity activity = getActivity();
        assert activity != null;
        activity.bindService(new Intent(activity, SaidItService.class), echoConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
        final Activity activity = getActivity();
        assert activity != null;
        activity.unbindService(echoConnection);
        echo = null;
    }

    class ActivityResult {
        final int requestCode;
        final int resultCode;
        final Intent data;

        ActivityResult(int requestCode, int resultCode, Intent data) {
            this.requestCode = requestCode;
            this.resultCode = resultCode;
            this.data = data;
        }
    }

    private Runnable updater = new Runnable() {
        @Override
        public void run() {
            final View view = getView();
            if (view == null) return;
            if (echo == null) return;
            echo.getState(serviceStateCallback);
        }
    };

    SaidItService echo;
    private ServiceConnection echoConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder binder) {
            Log.d(TAG, "onServiceConnected");
            SaidItService.BackgroundRecorderBinder typedBinder = (SaidItService.BackgroundRecorderBinder) binder;
            if (echo != null && echo == typedBinder.getService()) {
                Log.d(TAG, "update loop already running, skipping");
                return;
            }
            echo = typedBinder.getService();
            getView().postOnAnimation(updater);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d(TAG, "onServiceDisconnected");
            echo = null;
        }
    };

    public int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View rootView = inflater.inflate(R.layout.fragment_background_recorder, container, false);

        if (rootView == null) return null;

        final Activity activity = getActivity();
        final AssetManager assets = activity.getAssets();
        final Typeface robotoCondensedBold = Typeface.createFromAsset(assets, "RobotoCondensedBold.ttf");
        final Typeface robotoCondensedRegular = Typeface.createFromAsset(assets, "RobotoCondensed-Regular.ttf");
        final float density = activity.getResources().getDisplayMetrics().density;

        Views.search((ViewGroup) rootView, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {

                if (view instanceof Button) {
                    final Button button = (Button) view;
                    button.setTypeface(robotoCondensedBold);
                    final int shadowColor = button.getShadowColor();
                    button.setShadowLayer(0.01f, 0, density * 2, shadowColor);
                } else if (view instanceof TextView) {

                    final TextView textView = (TextView) view;
                    textView.setTypeface(robotoCondensedRegular);
                }
            }
        });

        history_limit = (TextView) rootView.findViewById(R.id.history_limit);
        history_size = (TextView) rootView.findViewById(R.id.history_size);
        history_size_title = (TextView) rootView.findViewById(R.id.history_size_title);

        history_limit.setTypeface(robotoCondensedBold);
        history_size.setTypeface(robotoCondensedBold);

        listenButton = (Button) rootView.findViewById(R.id.listen_button);
        if (listenButton != null) {
            listenButton.setOnClickListener(listenButtonClickListener);
        }

        final int statusBarHeight = getStatusBarHeight();
        listenButton.setPadding(listenButton.getPaddingLeft(), listenButton.getPaddingTop() + statusBarHeight, listenButton.getPaddingRight(), listenButton.getPaddingBottom());
        final ViewGroup.LayoutParams layoutParams = listenButton.getLayoutParams();
        layoutParams.height += statusBarHeight;
        listenButton.setLayoutParams(layoutParams);


        record_pause_button = (Button) rootView.findViewById(R.id.rec_stop_button);
        record_pause_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                echo.stopRecording(new PromptFileReceiver(getActivity()), "");
            }
        });

        timeWheel = (RecyclerView) rootView.findViewById(R.id.time_wheel);
        saveButton = (Button) rootView.findViewById(R.id.save_button);

        generateEntries(86400);

        pickerAdapter = new PickerAdapter(pickerEntries);
        timeWheel.setAdapter(pickerAdapter);

        LinearLayoutManager llm = new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, false);
        timeWheel.setLayoutManager(llm);

        LinearSnapHelper snapHelper = new LinearSnapHelper();
        snapHelper.attachToRecyclerView(timeWheel);

        timeWheel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrentPosition();
                    updatePickerLimit();
                }
            }
        });

        // Start at 0 (10s)
        llm.scrollToPosition(0);
        // Give layout a chance to settle, then update selection highlight
        timeWheel.post(new Runnable() {
            @Override
            public void run() {
                updateCurrentPosition();
                updatePickerLimit();
            }
        });

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSaveDialog(false);
            }
        });
        saveButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showSaveDialog(true);
                return true;
            }
        });

        ready_section = (LinearLayout) rootView.findViewById(R.id.ready_section);
        rec_section = (LinearLayout) rootView.findViewById(R.id.rec_section);
        rec_indicator = (TextView) rootView.findViewById(R.id.rec_indicator);
        rec_time = (TextView) rootView.findViewById(R.id.rec_time);

        rate_on_google_play = (ImageButton) rootView.findViewById(R.id.rate_on_google_play);

        final Animation pulse = AnimationUtils.loadAnimation(activity, R.anim.pulse);
        heart = (ImageView) rootView.findViewById(R.id.heart);
        heart.startAnimation(pulse);

        rate_on_google_play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mafik/echo")));
                } catch (android.content.ActivityNotFoundException anfe) {
                    // ignore
                }
            }
        });

        heart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                heart.animate().scaleX(10).scaleY(10).alpha(0).setDuration(2000).start();
                Handler handler = new Handler(activity.getMainLooper());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // star the app
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sponsors/mafik")));
                        } catch (android.content.ActivityNotFoundException anfe) {
                            // ignore
                        }
                    }
                }, 1000);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        heart.setAlpha(0f);
                        heart.setScaleX(1);
                        heart.setScaleY(1);
                        heart.animate().alpha(1).start();

                    }
                }, 3000);
            }
        });

        rootView.findViewById(R.id.settings_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(activity, SettingsActivity.class));
            }
        });
        serviceStateCallback.state(isListening, isRecording, 0, 0, 0);
        return rootView;
    }

    private SaidItService.StateCallback serviceStateCallback = new SaidItService.StateCallback() {
        @Override
        public void state(final boolean listeningEnabled, final boolean recording, final float memorized, final float totalMemory, final float recorded) {
            final Activity activity = getActivity();
            if (activity == null) return;
            final Resources resources = activity.getResources();
            if ((isRecording != recording) || (isListening != listeningEnabled)) {
                if (recording != isRecording) {
                    isRecording = recording;
                    if (recording) {
                        rec_section.setVisibility(View.VISIBLE);
                    } else {
                        rec_section.setVisibility(View.GONE);
                    }
                }

                if (listeningEnabled != isListening) {
                    isListening = listeningEnabled;
                    if (listeningEnabled) {
                        listenButton.setText(R.string.listening_enabled_disable);
                        listenButton.setBackgroundResource(R.drawable.top_green_button);
                        listenButton.setShadowLayer(0.01f, 0, resources.getDimensionPixelOffset(R.dimen.shadow_offset), resources.getColor(R.color.dark_green));
                    } else {
                        listenButton.setText(R.string.listening_disabled_enable);
                        listenButton.setBackgroundResource(R.drawable.top_gray_button);
                        listenButton.setShadowLayer(0.01f, 0, resources.getDimensionPixelOffset(R.dimen.shadow_offset), 0xff666666);
                    }
                }

                if (listeningEnabled && !recording) {
                    ready_section.setVisibility(View.VISIBLE);
                } else {
                    ready_section.setVisibility(View.GONE);
                }
            }

            TimeFormat.naturalLanguage(resources, totalMemory, timeFormatResult);

            if (!history_limit.getText().equals(timeFormatResult.text)) {
                history_limit.setText(timeFormatResult.text);
            }

            TimeFormat.naturalLanguage(resources, memorized, timeFormatResult);

            if (!history_size.getText().equals(timeFormatResult.text)) {
                history_size_title.setText(resources.getQuantityText(R.plurals.history_size_title, timeFormatResult.count));
                history_size.setText(timeFormatResult.text);
            }

            maxMemorizedSeconds = (int) memorized;
            saveButton.setEnabled(maxMemorizedSeconds > 0);
            updatePickerLimit();

            TimeFormat.naturalLanguage(resources, recorded, timeFormatResult);

            if (!rec_time.getText().equals(timeFormatResult.text)) {
                rec_indicator.setText(resources.getQuantityText(R.plurals.recorded, timeFormatResult.count));
                rec_time.setText(timeFormatResult.text);
            }

            history_size.postOnAnimationDelayed(updater, 100);
        }
    };

    final TimeFormat.Result timeFormatResult = new TimeFormat.Result();
    private final SimpleDateFormat timeFormatHHmm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private void updateCurrentPosition() {
        LinearLayoutManager llm = (LinearLayoutManager) timeWheel.getLayoutManager();
        if (llm == null) return;
        int first = llm.findFirstVisibleItemPosition();
        int last = llm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;
        int center = timeWheel.getHeight() / 2;
        int bestPos = currentSelectedPosition;
        int bestDist = Integer.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            View v = llm.findViewByPosition(i);
            if (v == null) continue;
            int dist = Math.abs((v.getTop() + v.getHeight() / 2) - center);
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = i;
            }
        }
        currentSelectedPosition = bestPos;
        pickerAdapter.setSelectedPosition(currentSelectedPosition);
    }

    private class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.ViewHolder> {
        private final ArrayList<PickerEntry> entries;
        private int selectedPosition = 0;

        PickerAdapter(ArrayList<PickerEntry> entries) {
            this.entries = entries;
        }

        void setSelectedPosition(int pos) {
            if (pos == selectedPosition) return;
            int old = selectedPosition;
            selectedPosition = pos;
            notifyItemChanged(old);
            notifyItemChanged(selectedPosition);
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            tv.setGravity(android.view.Gravity.CENTER);
            float density = parent.getResources().getDisplayMetrics().density;
            tv.setPadding(0, (int)(12 * density), 0, (int)(12 * density));
            return new ViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            holder.textView.setText(entries.get(position).label);
            if (position == selectedPosition) {
                holder.textView.setTextSize(28);
                holder.textView.setTextColor(0xFF222222);
            } else {
                holder.textView.setTextSize(20);
                holder.textView.setTextColor(0x88222222);
            }
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ViewHolder(TextView tv) {
                super(tv);
                textView = tv;
            }
        }
    }

    private class ListenButtonClickListener implements View.OnClickListener {

        @SuppressLint("ValidFragment")
        final WorkingDialog dialog = new WorkingDialog();

        public ListenButtonClickListener() {
            dialog.setDescriptionStringId(R.string.work_preparing_memory);
        }

        @Override
        public void onClick(View v) {
            echo.getState(new SaidItService.StateCallback() {
                @Override
                public void state(final boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded) {
                    if (listeningEnabled) {
                        echo.disableListening();
                    } else {
                        dialog.show(getFragmentManager(), "Preparing memory");

                        new Handler().post(new Runnable() {
                            @Override
                            public void run() {
                                echo.enableListening();
                                echo.getState(new SaidItService.StateCallback() {
                                    @Override
                                    public void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded) {
                                        dialog.dismiss();
                                    }
                                });
                            }
                        });
                    }
                }
            });
        }
    }

    private void showSaveDialog(final boolean keepRecording) {
        final int seconds = getSelectedSeconds();
        if (seconds <= 0) return;

        if (keepRecording) {
            echo.startRecording(seconds);
        } else {
            View dialogView = View.inflate(getActivity(), R.layout.dialog_save_recording, null);
            EditText fileName = dialogView.findViewById(R.id.recording_name);
            new AlertDialog.Builder(getActivity())
                .setView(dialogView)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (fileName.getText().toString().length() > 0) {
                            echo.dumpRecording(seconds, new PromptFileReceiver(getActivity()), fileName.getText().toString());
                        } else {
                            Toast.makeText(getActivity(), "Please enter a file name", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        }
    }

    private void updateLabels(int seconds) {
        // Info is shown on the NumberPicker itself via its formatter
    }

    private void updatePickerLimit() {
        saveButton.setEnabled(maxMemorizedSeconds > 0);
    }

    private int getSelectedSeconds() {
        if (pickerEntries.isEmpty()) return 0;
        if (currentSelectedPosition < 0 || currentSelectedPosition >= pickerEntries.size()) return 0;
        return Math.min(pickerEntries.get(currentSelectedPosition).secondsAgo, maxMemorizedSeconds);
    }

    private static String formatDuration(int secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        return (secs / 3600) + "h";
    }

    private void generateEntries(int maxSeconds) {
        pickerEntries.clear();
        if (maxSeconds <= 10) return;

        // 10-60s, step 10s
        for (int s = 10; s <= 60; s += 10) {
            pickerEntries.add(new PickerEntry(s, formatDuration(s)));
        }

        // 1-10min, step 1min (skip 60s which is already added)
        for (int s = 120; s <= 600; s += 60) {
            pickerEntries.add(new PickerEntry(s, formatDuration(s)));
        }

        // 10min-1h: 5min steps, clock-aligned
        addClockAligned(600, 3600, 300);
        // 1h-12h: 15min steps, clock-aligned
        addClockAligned(3600, 43200, 900);
        // 12h+: 1h steps, clock-aligned
        addClockAligned(43200, maxSeconds, 3600);

        updatePickerLimit();
    }

    private void addClockAligned(int startSec, int maxSeconds, int stepSec) {
        long nowMs = System.currentTimeMillis();
        long stepMs = stepSec * 1000L;
        long t = (nowMs / stepMs) * stepMs;
        while (true) {
            int secsAgo = (int) ((nowMs - t) / 1000);
            if (secsAgo < startSec) { t -= stepMs; continue; }
            if (secsAgo >= maxSeconds) break;
            int prev = pickerEntries.isEmpty() ? -1 : pickerEntries.get(pickerEntries.size() - 1).secondsAgo;
            if (secsAgo != prev) {
                String label = timeFormatHHmm.format(new Date(t));
                pickerEntries.add(new PickerEntry(secsAgo, label));
            }
            t -= stepMs;
        }
    }

    static Notification buildNotificationForFile(Context context, File outFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri fileUri = FileProvider.getUriForFile(context, context.getApplicationContext().getPackageName() + ".provider", outFile);
        intent.setDataAndType(fileUri, "audio/wav");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // Grant read permission to the receiving app

        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, YOUR_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.recording_saved))
                .setContentText(outFile.getName())
                .setSmallIcon(R.drawable.ic_stat_notify_recorded)
                .setTicker(outFile.getName())
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        notificationBuilder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        notificationBuilder.setCategory(NotificationCompat.CATEGORY_MESSAGE);
        return notificationBuilder.build();
    }

    static class NotifyFileReceiver implements SaidItService.WavFileReceiver {

        private Context context;

        public NotifyFileReceiver(Context context) {
            this.context = context;
        }

        @Override
        public void fileReady(final File file, float runtime) {
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            notificationManager.notify(43, buildNotificationForFile(context, file));
        }
    }

    static class PromptFileReceiver implements SaidItService.WavFileReceiver {

        private Activity activity;

        public PromptFileReceiver(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void fileReady(final File file, float runtime) {
            new RecordingDoneDialog()
                    .setFile(file)
                    .setRuntime(runtime)
                    .show(activity.getFragmentManager(), "Recording Done");
        }
    }
}
