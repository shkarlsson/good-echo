package com.goodecho.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import eu.mrogalski.android.TimeFormat;
import eu.mrogalski.android.Views;

public class SettingsActivity extends Activity {
    static final String TAG = SettingsActivity.class.getSimpleName();
    final WorkingDialog dialog = new WorkingDialog();

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, SaidItService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService(connection);
    }

    SaidItService service;
    ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder binder) {
            SaidItService.BackgroundRecorderBinder typedBinder = (SaidItService.BackgroundRecorderBinder) binder;
            service = typedBinder.getService();
            syncUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            service = null;
        }
    };

    final TimeFormat.Result timeFormatResult = new TimeFormat.Result();

    private void syncUI() {
        long memoryBytes = service.getMemorySize();
        int mb = (int)(memoryBytes / 1024 / 1024);
        if (mb < 1) mb = 1;

        EditText input = (EditText) findViewById(R.id.memory_input);
        input.setText(String.valueOf(mb));

        updateMemoryValueLabel(mb);

        TimeFormat.naturalLanguage(getResources(), service.getMemorizedSeconds(), timeFormatResult);
        ((TextView)findViewById(R.id.history_limit)).setText(timeFormatResult.text);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final AssetManager assets = getAssets();
        final Resources resources = getResources();

        final float density = resources.getDisplayMetrics().density;

        final Typeface robotoCondensedBold = Typeface.createFromAsset(assets,"RobotoCondensedBold.ttf");
        final Typeface robotoCondensedRegular = Typeface.createFromAsset(assets, "RobotoCondensed-Regular.ttf");

        final ViewGroup root = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_settings, null);
        Views.search(root, new Views.SearchViewCallback() {
            @Override
            public void onView(View view, ViewGroup parent) {
                if(view instanceof Button) {
                    final Button button = (Button) view;
                    button.setTypeface(robotoCondensedBold);
                } else if(view instanceof TextView) {
                    final String tag = (String) view.getTag();
                    final TextView textView = (TextView) view;
                    if(tag != null) {
                        if(tag.equals("bold")) {
                            textView.setTypeface(robotoCondensedBold);
                        } else {
                            textView.setTypeface(robotoCondensedRegular);
                        }
                    } else {
                        textView.setTypeface(robotoCondensedRegular);
                    }
                }
            }
        });

        root.findViewById(R.id.settings_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        final LinearLayout settingsLayout = (LinearLayout) root.findViewById(R.id.settings_layout);

        final FrameLayout myFrameLayout = new FrameLayout(this) {
            @Override
            protected boolean fitSystemWindows(Rect insets) {
                settingsLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return true;
            }
        };

        myFrameLayout.addView(root);

        EditText memoryInput = (EditText) root.findViewById(R.id.memory_input);
        memoryInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyMemoryInput(v);
                    return true;
                }
                return false;
            }
        });
        memoryInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    applyMemoryInput((TextView) v);
                }
            }
        });
        memoryInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                int mb = 50;
                try {
                    mb = Integer.parseInt(s.toString());
                } catch (NumberFormatException ignored) {}
                updateMemoryValueLabel(mb);
            }
        });

        ImageButton githubButton = (ImageButton) root.findViewById(R.id.rate_on_google_play);
        githubButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shkarlsson/good-echo")));
                } catch (android.content.ActivityNotFoundException anfe) {
                    // ignore
                }
            }
        });

        final ImageView heart = (ImageView) root.findViewById(R.id.heart);
        final Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
        heart.startAnimation(pulse);
        heart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                heart.animate().scaleX(10).scaleY(10).alpha(0).setDuration(2000).start();
                Handler handler = new Handler(SettingsActivity.this.getMainLooper());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
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

        //debugPrintCodecs();

        dialog.setDescriptionStringId(R.string.work_preparing_memory);

        setContentView(myFrameLayout);
    }

    private void applyMemoryInput(TextView textView) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(textView.getWindowToken(), 0);
        int mb = 50;
        try {
            mb = Integer.parseInt(textView.getText().toString());
        } catch (NumberFormatException ignored) {}
        if (mb < 1) mb = 1;
        final long memoryBytes = mb * 1024L * 1024L;
        dialog.show(getFragmentManager(), "Preparing memory");
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                service.setMemorySize(memoryBytes);
                service.getState(new SaidItService.StateCallback() {
                    @Override
                    public void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded) {
                        syncUI();
                        if (dialog.isVisible()) dialog.dismiss();
                    }
                });
            }
        });
    }

    private void updateMemoryValueLabel(int mb) {
        long memoryBytes = mb * 1024L * 1024L;
        long available = service != null ? service.getAvailableMemory()
                : Runtime.getRuntime().maxMemory() - SaidItService.RESERVED_MEMORY;
        if (available < 1) available = 1;
        int pct = (int)(memoryBytes * 100 / available);
        if (pct > 100) pct = 100;
        ((TextView)findViewById(R.id.memory_value))
                .setText(mb + " MB (" + pct + "%)");
    }

    private void debugPrintCodecs() {
        final int codecCount = MediaCodecList.getCodecCount();
        for(int i = 0; i < codecCount; ++i) {
            final MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if(!info.isEncoder()) continue;
            boolean audioFound = false;
            String types = "";
            final String[] supportedTypes = info.getSupportedTypes();
            for(int j = 0; j < supportedTypes.length; ++j) {
                if(j > 0)
                    types += ", ";
                types += supportedTypes[j];
                if(supportedTypes[j].startsWith("audio")) audioFound = true;
            }
            if(!audioFound) continue;
            Log.d(TAG, "Codec " + i + ": " + info.getName() + " (" + types + ") encoder: " + info.isEncoder());
        }
    }

}
