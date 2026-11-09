package com.example;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.Locale;

/**
 * MainActivity managing user duration inputs (1 to 180 mins via SeekBar slider and - / + buttons),
 * live countdown timer (clickable to stop/pause the simulation),
 * speed multiplier button (1x, 2x, 5x, 8x),
 * remaining flag counter, victory chime, and UI visibility transitions.
 */
public class MainActivity extends ComponentActivity implements FlagArenaView.ArenaEventListener {

    public static final int MIN_DURATION_MINUTES = 1;
    public static final int MAX_DURATION_MINUTES = 180;
    private static final float[] SPEED_MULTIPLIERS = new float[]{1.0f, 2.0f, 5.0f, 8.0f};

    private FlagArenaView flagArenaView;
    private TextView tvRemainingCounter;
    private TextView tvTimerCountdown;
    private TextView btnSpeedMultiplier;
    private TextView tvEliminatedFeed;

    private LinearLayout victoryOverlayCard;
    private ImageView ivWinnerFlag;
    private TextView tvWinnerTitle;
    private TextView tvWinnerSubtitle;

    private LinearLayout bottomControlPanel;
    private TextView tvDurationLabel;
    private TextView btnMinusDuration;
    private SeekBar seekBarDuration;
    private TextView btnPlusDuration;
    private Button btnStartSimulation;

    private int selectedDurationMinutes = 2;
    private int speedMultiplierIndex = 0;

    private long remainingTimeMs = 2 * 60 * 1000L;
    private long lastTimerTickRealtimeMs = 0L;
    private boolean timerRunning = false;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!timerRunning || flagArenaView.isSimulationPaused()) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            long deltaMs = Math.max(0L, nowMs - lastTimerTickRealtimeMs);
            lastTimerTickRealtimeMs = nowMs;

            float mult = SPEED_MULTIPLIERS[speedMultiplierIndex];
            remainingTimeMs -= (long) (deltaMs * mult);

            if (remainingTimeMs <= 0L) {
                remainingTimeMs = 0L;
                updateTimerDisplay(0L, false);
                timerRunning = false;
                flagArenaView.finishByTimer();
                return;
            }

            updateTimerDisplay(remainingTimeMs, false);
            timerHandler.postDelayed(this, 100L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            int navy = Color.parseColor("#0F172A");
            window.setStatusBarColor(navy);
            window.setNavigationBarColor(navy);
        }

        setContentView(R.layout.activity_main);

        bindViews();
        setupDurationSliderAndButtons();
        setupActions();

        updateRemainingCounterDisplay(FlagBall.TOTAL_COUNTRIES, FlagBall.TOTAL_COUNTRIES);
        applySelectedDuration(selectedDurationMinutes, true);
    }

    private void bindViews() {
        flagArenaView = findViewById(R.id.flagArenaView);
        tvRemainingCounter = findViewById(R.id.tvRemainingCounter);
        tvTimerCountdown = findViewById(R.id.tvTimerCountdown);
        btnSpeedMultiplier = findViewById(R.id.btnSpeedMultiplier);
        tvEliminatedFeed = findViewById(R.id.tvEliminatedFeed);

        victoryOverlayCard = findViewById(R.id.victoryOverlayCard);
        ivWinnerFlag = findViewById(R.id.ivWinnerFlag);
        tvWinnerTitle = findViewById(R.id.tvWinnerTitle);
        tvWinnerSubtitle = findViewById(R.id.tvWinnerSubtitle);

        bottomControlPanel = findViewById(R.id.bottomControlPanel);
        tvDurationLabel = findViewById(R.id.tvDurationLabel);
        btnMinusDuration = findViewById(R.id.btnMinusDuration);
        seekBarDuration = findViewById(R.id.seekBarDuration);
        btnPlusDuration = findViewById(R.id.btnPlusDuration);
        btnStartSimulation = findViewById(R.id.btnStartSimulation);

        flagArenaView.setArenaEventListener(this);
    }

    private void setupDurationSliderAndButtons() {
        // SeekBar progress 0..179 maps to 1..180 minutes
        seekBarDuration.setMax(MAX_DURATION_MINUTES - MIN_DURATION_MINUTES);
        seekBarDuration.setProgress(selectedDurationMinutes - MIN_DURATION_MINUTES);

        seekBarDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int minutes = MIN_DURATION_MINUTES + progress;
                if (fromUser) {
                    applySelectedDuration(minutes, false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        btnMinusDuration.setOnClickListener(v -> {
            int newMins = Math.max(MIN_DURATION_MINUTES, selectedDurationMinutes - 1);
            applySelectedDuration(newMins, true);
        });

        btnPlusDuration.setOnClickListener(v -> {
            int newMins = Math.min(MAX_DURATION_MINUTES, selectedDurationMinutes + 1);
            applySelectedDuration(newMins, true);
        });
    }

    private void applySelectedDuration(int minutes, boolean syncSeekBar) {
        selectedDurationMinutes = Math.max(MIN_DURATION_MINUTES, Math.min(MAX_DURATION_MINUTES, minutes));
        if (syncSeekBar && seekBarDuration != null) {
            seekBarDuration.setProgress(selectedDurationMinutes - MIN_DURATION_MINUTES);
        }

        String unit = (selectedDurationMinutes == 1) ? "min" : "mins";
        tvDurationLabel.setText(String.format(Locale.US, "Duration: %d %s", selectedDurationMinutes, unit));

        if (flagArenaView.getSimulationState() != FlagArenaView.SimulationState.RUNNING
                || flagArenaView.isSimulationPaused()) {
            timerRunning = false;
            timerHandler.removeCallbacks(timerTickRunnable);
            remainingTimeMs = selectedDurationMinutes * 60L * 1000L;
            updateTimerDisplay(remainingTimeMs, flagArenaView.isSimulationPaused());
            flagArenaView.resetToIdle(selectedDurationMinutes);
        }
    }

    private void setupActions() {
        btnStartSimulation.setOnClickListener(v -> startBattleSimulation());

        // Speed Button: cycles 1x, 2x, 5x, 8x
        btnSpeedMultiplier.setOnClickListener(v -> {
            speedMultiplierIndex = (speedMultiplierIndex + 1) % SPEED_MULTIPLIERS.length;
            float mult = SPEED_MULTIPLIERS[speedMultiplierIndex];
            flagArenaView.setSpeedMultiplier(mult);
            int multInt = Math.round(mult);
            btnSpeedMultiplier.setText(String.format(Locale.US, "%dx", multInt));
            if (multInt > 1) {
                btnSpeedMultiplier.setBackgroundResource(R.drawable.bg_chip_selected);
                btnSpeedMultiplier.setTextColor(Color.WHITE);
            } else {
                btnSpeedMultiplier.setBackgroundResource(R.drawable.bg_chip_unselected);
                btnSpeedMultiplier.setTextColor(Color.WHITE);
            }
        });

        // Clicking on the Timer stops/pauses the simulation and brings back controls
        tvTimerCountdown.setOnClickListener(v -> toggleTimerStopState());
    }

    private void toggleTimerStopState() {
        boolean nowPaused = !flagArenaView.isSimulationPaused();
        flagArenaView.setSimulationPaused(nowPaused);

        if (nowPaused) {
            timerHandler.removeCallbacks(timerTickRunnable);
            updateTimerDisplay(remainingTimeMs, true);
            tvEliminatedFeed.setText("Paused");

            if (bottomControlPanel.getVisibility() != View.VISIBLE) {
                bottomControlPanel.animate().cancel();
                bottomControlPanel.setAlpha(0f);
                bottomControlPanel.setVisibility(View.VISIBLE);
                bottomControlPanel.animate()
                        .alpha(1f)
                        .setDuration(220L)
                        .start();
            }
        } else {
            updateTimerDisplay(remainingTimeMs, false);
            if (flagArenaView.getSimulationState() == FlagArenaView.SimulationState.RUNNING) {
                lastTimerTickRealtimeMs = System.currentTimeMillis();
                timerRunning = true;
                timerHandler.removeCallbacks(timerTickRunnable);
                timerHandler.postDelayed(timerTickRunnable, 100L);
                tvEliminatedFeed.setText("Battle Resumed!");

                bottomControlPanel.animate().cancel();
                bottomControlPanel.animate()
                        .alpha(0f)
                        .setDuration(220L)
                        .withEndAction(() -> bottomControlPanel.setVisibility(View.GONE))
                        .start();
            } else {
                tvEliminatedFeed.setText(R.string.eliminated_feed_placeholder);
            }
        }
    }

    private void startBattleSimulation() {
        victoryOverlayCard.animate().cancel();
        victoryOverlayCard.setVisibility(View.GONE);

        flagArenaView.setSimulationPaused(false);
        updateRemainingCounterDisplay(FlagBall.TOTAL_COUNTRIES, FlagBall.TOTAL_COUNTRIES);
        remainingTimeMs = selectedDurationMinutes * 60L * 1000L;
        updateTimerDisplay(remainingTimeMs, false);
        tvEliminatedFeed.setText("Battle Started!");

        bottomControlPanel.animate().cancel();
        bottomControlPanel.animate()
                .alpha(0f)
                .setDuration(240L)
                .withEndAction(() -> bottomControlPanel.setVisibility(View.GONE))
                .start();

        flagArenaView.startSimulation(selectedDurationMinutes);
        lastTimerTickRealtimeMs = System.currentTimeMillis();
        timerRunning = true;
        timerHandler.removeCallbacks(timerTickRunnable);
        timerHandler.postDelayed(timerTickRunnable, 100L);
    }

    @Override
    public void onFlagEliminated(FlagBall eliminatedBall, int remainingCount, int totalCount) {
        updateRemainingCounterDisplay(remainingCount, totalCount);
        if (eliminatedBall != null) {
            tvEliminatedFeed.setText(getString(
                    R.string.eliminated_feed_format,
                    eliminatedBall.getCountryName(),
                    eliminatedBall.getCountryCode()
            ));
        }
    }

    @Override
    public void onSimulationWon(FlagBall winnerBall, boolean wonByLastStanding) {
        timerRunning = false;
        timerHandler.removeCallbacks(timerTickRunnable);

        playVictoryChime();

        if (winnerBall != null) {
            Bitmap winnerAvatar = winnerBall.createHighResBitmap(160);
            ivWinnerFlag.setImageBitmap(winnerAvatar);
            tvWinnerTitle.setText(getString(R.string.winner_format, winnerBall.getCountryName()));
            tvWinnerSubtitle.setText(wonByLastStanding
                    ? getString(R.string.winner_subtitle_last_standing)
                    : getString(R.string.winner_subtitle_time_up));
            tvEliminatedFeed.setText(String.format(
                    Locale.US,
                    "Champion: %s (%s)",
                    winnerBall.getCountryName(),
                    winnerBall.getCountryCode()
            ));
        }

        victoryOverlayCard.setAlpha(0f);
        victoryOverlayCard.setScaleX(0.88f);
        victoryOverlayCard.setScaleY(0.88f);
        victoryOverlayCard.setVisibility(View.VISIBLE);
        victoryOverlayCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .start();

        bottomControlPanel.animate().cancel();
        bottomControlPanel.setAlpha(0f);
        bottomControlPanel.setVisibility(View.VISIBLE);
        bottomControlPanel.animate()
                .alpha(1f)
                .setDuration(300L)
                .start();
    }

    /**
     * Synthesizes and plays a celebratory 4-note ascending bell chime (C5 - E5 - G5 - C6)
     * using Android's AudioTrack when only one flag remains in the arena.
     */
    private void playVictoryChime() {
        new Thread(() -> {
            AudioTrack track = null;
            try {
                final int sampleRate = 44100;
                final double totalDurationSec = 1.15;
                final int numSamples = (int) (sampleRate * totalDurationSec);
                short[] pcm = new short[numSamples];

                final double[] freqs = new double[]{523.25, 659.25, 783.99, 1046.50};
                final double[] starts = new double[]{0.00, 0.14, 0.28, 0.42};
                final double[] durations = new double[]{0.45, 0.45, 0.45, 0.72};

                for (int i = 0; i < numSamples; i++) {
                    double t = (double) i / sampleRate;
                    double sampleSum = 0.0;

                    for (int n = 0; n < freqs.length; n++) {
                        double dt = t - starts[n];
                        if (dt >= 0.0 && dt <= durations[n]) {
                            double attack = Math.min(1.0, dt / 0.012);
                            double decay = Math.exp(-3.8 * dt / durations[n]);
                            double env = attack * decay;
                            double fundamental = Math.sin(2.0 * Math.PI * freqs[n] * dt);
                            double overtone = 0.30 * Math.sin(2.0 * Math.PI * (freqs[n] * 2.0) * dt);
                            sampleSum += env * (fundamental + overtone) * 0.32;
                        }
                    }

                    double clamped = Math.max(-1.0, Math.min(1.0, sampleSum));
                    pcm[i] = (short) (clamped * 28000);
                }

                int bufferBytes = pcm.length * 2;
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferBytes)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep(1250L);
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (Exception ignored) {
                    }
                    try {
                        track.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "VictoryChimeThread").start();
    }

    private void updateRemainingCounterDisplay(int remaining, int total) {
        tvRemainingCounter.setText(getString(R.string.remaining_counter_format, remaining, total));
    }

    private void updateTimerDisplay(long timeMs, boolean stopped) {
        long totalSeconds = Math.max(0L, (timeMs + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        String prefix = stopped ? "⏹ " : "⏱ ";
        tvTimerCountdown.setText(String.format(Locale.US, "%s%02d:%02d", prefix, minutes, seconds));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (flagArenaView != null) {
            flagArenaView.resumeSimulationThread();
        }
        if (timerRunning && (flagArenaView == null || !flagArenaView.isSimulationPaused())) {
            lastTimerTickRealtimeMs = System.currentTimeMillis();
            timerHandler.removeCallbacks(timerTickRunnable);
            timerHandler.postDelayed(timerTickRunnable, 100L);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        timerHandler.removeCallbacks(timerTickRunnable);
        if (flagArenaView != null) {
            flagArenaView.pauseSimulationThread();
        }
    }

    @Override
    protected void onDestroy() {
        timerRunning = false;
        timerHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
