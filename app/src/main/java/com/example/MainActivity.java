package com.example;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.Locale;

/**
 * MainActivity managing user duration inputs (2, 5, 7, 15, 30, 60 mins),
 * live countdown timer (clickable to stop/pause the simulation),
 * speed multiplier button (boosts flag speed immediately),
 * remaining flag counter, and UI visibility transitions.
 */
public class MainActivity extends ComponentActivity implements FlagArenaView.ArenaEventListener {

    private static final int[] DURATION_OPTIONS_MINUTES = new int[]{2, 5, 7, 15, 30, 60};
    private static final float[] SPEED_MULTIPLIERS = new float[]{1.0f, 2.0f, 4.0f, 8.0f};

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
    private TextView[] durationChips;
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
        setupDurationChips();
        setupActions();

        updateRemainingCounterDisplay(FlagBall.TOTAL_COUNTRIES, FlagBall.TOTAL_COUNTRIES);
        remainingTimeMs = selectedDurationMinutes * 60L * 1000L;
        updateTimerDisplay(remainingTimeMs, false);
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
        btnStartSimulation = findViewById(R.id.btnStartSimulation);

        durationChips = new TextView[]{
                findViewById(R.id.chip2Min),
                findViewById(R.id.chip5Min),
                findViewById(R.id.chip7Min),
                findViewById(R.id.chip15Min),
                findViewById(R.id.chip30Min),
                findViewById(R.id.chip60Min)
        };

        flagArenaView.setArenaEventListener(this);
    }

    private void setupDurationChips() {
        for (int i = 0; i < durationChips.length; i++) {
            final int index = i;
            final int minutes = DURATION_OPTIONS_MINUTES[i];
            durationChips[i].setOnClickListener(v -> selectDurationChip(index, minutes));
        }
        selectDurationChip(0, DURATION_OPTIONS_MINUTES[0]);
    }

    private void selectDurationChip(int selectedIndex, int minutes) {
        selectedDurationMinutes = minutes;
        for (int i = 0; i < durationChips.length; i++) {
            TextView chip = durationChips[i];
            if (i == selectedIndex) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected);
                chip.setTextColor(Color.WHITE);
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip_unselected);
                chip.setTextColor(Color.parseColor("#94A3B8"));
            }
        }

        if (flagArenaView.getSimulationState() != FlagArenaView.SimulationState.RUNNING
                || flagArenaView.isSimulationPaused()) {
            timerRunning = false;
            timerHandler.removeCallbacks(timerTickRunnable);
            remainingTimeMs = selectedDurationMinutes * 60L * 1000L;
            updateTimerDisplay(remainingTimeMs, false);
            flagArenaView.resetToIdle(selectedDurationMinutes);
        }
    }

    private void setupActions() {
        btnStartSimulation.setOnClickListener(v -> startBattleSimulation());

        // Speed Button: immediately increases flag movement & collision speed
        btnSpeedMultiplier.setOnClickListener(v -> {
            speedMultiplierIndex = (speedMultiplierIndex + 1) % SPEED_MULTIPLIERS.length;
            float mult = SPEED_MULTIPLIERS[speedMultiplierIndex];
            flagArenaView.setSpeedMultiplier(mult);
            int multInt = Math.round(mult);
            btnSpeedMultiplier.setText(String.format(Locale.US, "Speed: %dx", multInt));
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
            // Stop the timer and show bottom control panel so user can reconfigure or restart
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
            // Resume movement & countdown if battle was running
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
