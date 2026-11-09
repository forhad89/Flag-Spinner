package com.example;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Custom 60 FPS Arena View for "Last Flag in the Circle Wins".
 * Features:
 * - Gentle, smooth 360-degree rotation of the circular ring and 35-degree exit gap.
 * - Flags inside the circle collide with each other and the wall with elastic shrink/bounce physics.
 * - As soon as a flag exits through the circle gap, it disappears immediately (no longer shown).
 * - Once all other flags exit the circle, the final remaining flag is crowned the Winner.
 */
public class FlagArenaView extends SurfaceView implements SurfaceHolder.Callback, Choreographer.FrameCallback {

    public static final float GAP_SWEEP_DEGREES = 35.0f;

    public enum SimulationState {
        IDLE,
        RUNNING,
        WON
    }

    public interface ArenaEventListener {
        void onFlagEliminated(FlagBall eliminatedBall, int remainingCount, int totalCount);
        void onSimulationWon(FlagBall winnerBall, boolean wonByLastStanding);
    }

    private static class SparkParticle {
        float x;
        float y;
        float vx;
        float vy;
        float alpha;
        float radius;
        int color;
    }

    private final Object stateLock = new Object();
    private final Random random = new Random();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<FlagBall> balls = new ArrayList<>(FlagBall.TOTAL_COUNTRIES);
    private final List<SparkParticle> sparks = new ArrayList<>();

    private Paint bgPaint;
    private Paint arenaGlowPaint;
    private Paint arenaRingPaint;
    private Paint arenaSegmentAccentPaint;
    private Paint arenaOuterTrackPaint;
    private Paint gapBeaconPaint;
    private Paint gapBeaconCorePaint;
    private Paint sparkPaint;
    private Paint winnerHaloPaint;

    private final RectF arenaArcRect = new RectF();
    private final RectF outerTrackRect = new RectF();

    private float arenaCenterX = 0f;
    private float arenaCenterY = 0f;
    private float maxArenaRadius = 0f;
    private float arenaRadius = 0f;
    private float ballRadius = 20f;

    private float gapAngle = 290f;
    private float outerTrackRotationDeg = 0f;
    private float gapSweep = GAP_SWEEP_DEGREES;
    // Gentle rotation speed ("circle ta halka halka gurbe")
    private float gapRotationSpeedDegPerSec = 24f;

    private float baseBallSpeed = 270f;
    private float gravityAccel = 240f;
    private float speedMultiplier = 1.0f;
    private boolean simulationPaused = false;

    private long selectedDurationMs = 2 * 60 * 1000L;
    private long elapsedSimulationMs = 0L;
    private long lastExitTimestampMs = 0L;

    private SimulationState simulationState = SimulationState.IDLE;
    private FlagBall winnerBall = null;
    private int remainingCount = FlagBall.TOTAL_COUNTRIES;

    private ArenaEventListener eventListener;
    private boolean loopActive = false;
    private long lastFrameTimeNs = 0L;

    public FlagArenaView(Context context) {
        super(context);
        init();
    }

    public FlagArenaView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FlagArenaView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        setWillNotDraw(false);

        float density = getResources().getDisplayMetrics().density;

        bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#0F172A"));
        bgPaint.setStyle(Paint.Style.FILL);

        arenaGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arenaGlowPaint.setStyle(Paint.Style.FILL);

        arenaRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arenaRingPaint.setColor(Color.WHITE);
        arenaRingPaint.setStyle(Paint.Style.STROKE);
        arenaRingPaint.setStrokeWidth(6.5f * density);
        arenaRingPaint.setStrokeCap(Paint.Cap.ROUND);
        arenaRingPaint.setShadowLayer(8f * density, 0f, 0f, Color.argb(160, 59, 130, 246));

        arenaSegmentAccentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arenaSegmentAccentPaint.setColor(Color.parseColor("#38BDF8"));
        arenaSegmentAccentPaint.setStyle(Paint.Style.STROKE);
        arenaSegmentAccentPaint.setStrokeWidth(3.2f * density);
        arenaSegmentAccentPaint.setStrokeCap(Paint.Cap.ROUND);

        arenaOuterTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arenaOuterTrackPaint.setColor(Color.argb(80, 148, 163, 184));
        arenaOuterTrackPaint.setStyle(Paint.Style.STROKE);
        arenaOuterTrackPaint.setStrokeWidth(2.0f * density);
        arenaOuterTrackPaint.setPathEffect(new DashPathEffect(new float[]{12f * density, 10f * density}, 0f));

        gapBeaconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gapBeaconPaint.setColor(Color.parseColor("#3B82F6"));
        gapBeaconPaint.setStyle(Paint.Style.FILL);

        gapBeaconCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gapBeaconCorePaint.setColor(Color.parseColor("#FACC15"));
        gapBeaconCorePaint.setStyle(Paint.Style.FILL);

        sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sparkPaint.setStyle(Paint.Style.FILL);

        winnerHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        winnerHaloPaint.setColor(Color.parseColor("#FACC15"));
        winnerHaloPaint.setStyle(Paint.Style.STROKE);
        winnerHaloPaint.setStrokeWidth(4.0f * density);
    }

    public void setArenaEventListener(ArenaEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Increases or sets the flag speed multiplier immediately so all flags move faster when tapped.
     */
    public void setSpeedMultiplier(float multiplier) {
        synchronized (stateLock) {
            float clamped = Math.max(1.0f, Math.min(20.0f, multiplier));
            float ratio = clamped / Math.max(0.1f, this.speedMultiplier);
            this.speedMultiplier = clamped;

            for (int i = 0; i < balls.size(); i++) {
                FlagBall b = balls.get(i);
                if (!b.isEscaped()) {
                    b.vx *= ratio;
                    b.vy *= ratio;
                    b.triggerCollisionShrink(0.55f, 140f);
                }
            }
        }
        invalidate();
    }

    public float getSpeedMultiplier() {
        synchronized (stateLock) {
            return speedMultiplier;
        }
    }

    public void setSimulationPaused(boolean paused) {
        synchronized (stateLock) {
            this.simulationPaused = paused;
        }
        invalidate();
    }

    public boolean isSimulationPaused() {
        synchronized (stateLock) {
            return simulationPaused;
        }
    }

    public int getRemainingCount() {
        synchronized (stateLock) {
            return remainingCount;
        }
    }

    public SimulationState getSimulationState() {
        synchronized (stateLock) {
            return simulationState;
        }
    }

    public FlagBall getWinnerBall() {
        synchronized (stateLock) {
            return winnerBall;
        }
    }

    private void configurePacingForDuration(int durationMinutes) {
        this.selectedDurationMs = Math.max(1, durationMinutes) * 60L * 1000L;
        this.gapSweep = GAP_SWEEP_DEGREES;

        float refRadius = Math.max(240f, maxArenaRadius);
        gravityAccel = refRadius * 0.68f;
        switch (durationMinutes) {
            case 2:
                baseBallSpeed = refRadius * 1.05f;
                gapRotationSpeedDegPerSec = 26f;
                break;
            case 5:
                baseBallSpeed = refRadius * 0.94f;
                gapRotationSpeedDegPerSec = 23f;
                break;
            case 7:
                baseBallSpeed = refRadius * 0.88f;
                gapRotationSpeedDegPerSec = 21f;
                break;
            case 15:
                baseBallSpeed = refRadius * 0.80f;
                gapRotationSpeedDegPerSec = 19f;
                break;
            case 30:
                baseBallSpeed = refRadius * 0.74f;
                gapRotationSpeedDegPerSec = 17f;
                break;
            case 60:
            default:
                baseBallSpeed = refRadius * 0.68f;
                gapRotationSpeedDegPerSec = 15f;
                break;
        }
    }

    public void startSimulation(int durationMinutes) {
        synchronized (stateLock) {
            simulationPaused = false;
            arenaRadius = maxArenaRadius;
            updateRingRectsLocked();
            configurePacingForDuration(durationMinutes);
            elapsedSimulationMs = 0L;
            lastExitTimestampMs = 0L;
            winnerBall = null;
            sparks.clear();
            populateArenaBallsLocked();
            simulationState = SimulationState.RUNNING;
        }
        startContinuousFrameLoop();
        invalidate();
    }

    public void resetToIdle(int durationMinutes) {
        synchronized (stateLock) {
            simulationPaused = false;
            arenaRadius = maxArenaRadius;
            updateRingRectsLocked();
            configurePacingForDuration(durationMinutes);
            elapsedSimulationMs = 0L;
            lastExitTimestampMs = 0L;
            winnerBall = null;
            sparks.clear();
            populateArenaBallsLocked();
            simulationState = SimulationState.IDLE;
        }
        startContinuousFrameLoop();
        invalidate();
    }

    public void finishByTimer() {
        FlagBall crowned = null;
        synchronized (stateLock) {
            if (simulationState != SimulationState.RUNNING) {
                return;
            }
            simulationPaused = false;
            float bestDistSq = Float.MAX_VALUE;
            for (int i = 0; i < balls.size(); i++) {
                FlagBall b = balls.get(i);
                if (!b.isEscaped()) {
                    float dx = b.x - arenaCenterX;
                    float dy = b.y - arenaCenterY;
                    float dSq = dx * dx + dy * dy;
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        crowned = b;
                    }
                }
            }
            if (crowned == null && !balls.isEmpty()) {
                crowned = balls.get(0);
            }
            // Hide any other remaining flags so only the winner is shown
            for (int i = 0; i < balls.size(); i++) {
                FlagBall b = balls.get(i);
                if (b != crowned) {
                    b.setEscaped(true, elapsedSimulationMs);
                    b.setActiveInVoid(false);
                }
            }
            remainingCount = 1;
            winnerBall = crowned;
            simulationState = SimulationState.WON;
            if (crowned != null) {
                spawnVictorySparksLocked(crowned.x, crowned.y);
            }
        }
        if (crowned != null && eventListener != null) {
            final FlagBall finalWinner = crowned;
            mainHandler.post(() -> {
                eventListener.onFlagEliminated(null, 1, FlagBall.TOTAL_COUNTRIES);
                eventListener.onSimulationWon(finalWinner, false);
            });
        }
        invalidate();
    }

    private void populateArenaBallsLocked() {
        if (maxArenaRadius <= 0f) {
            return;
        }
        arenaRadius = maxArenaRadius;
        updateRingRectsLocked();

        float maxSafeRadius = maxArenaRadius / 18.8f;
        ballRadius = Math.max(16f, Math.min(22f, maxSafeRadius));

        balls.clear();
        balls.addAll(FlagBall.createAll195Countries(
                arenaCenterX,
                arenaCenterY,
                arenaRadius,
                ballRadius,
                baseBallSpeed * speedMultiplier,
                random
        ));
        remainingCount = balls.size();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        synchronized (stateLock) {
            updateArenaGeometryLocked(w, h);
            if (balls.isEmpty()) {
                configurePacingForDuration(2);
                populateArenaBallsLocked();
            }
        }
        startContinuousFrameLoop();
    }

    private void updateArenaGeometryLocked(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        arenaCenterX = width * 0.5f;
        arenaCenterY = height * 0.50f;
        float minDim = Math.min(width, height);
        maxArenaRadius = minDim * 0.41f;
        if (arenaRadius <= 0f) {
            arenaRadius = maxArenaRadius;
        }
        updateRingRectsLocked();

        arenaGlowPaint.setShader(new RadialGradient(
                arenaCenterX,
                arenaCenterY,
                maxArenaRadius * 1.15f,
                new int[]{
                        Color.argb(46, 59, 130, 246),
                        Color.argb(20, 30, 41, 59),
                        Color.argb(0, 15, 23, 42)
                },
                new float[]{0f, 0.78f, 1f},
                Shader.TileMode.CLAMP
        ));
    }

    private void updateRingRectsLocked() {
        arenaArcRect.set(
                arenaCenterX - arenaRadius,
                arenaCenterY - arenaRadius,
                arenaCenterX + arenaRadius,
                arenaCenterY + arenaRadius
        );
        float outerOffset = 15f;
        outerTrackRect.set(
                arenaCenterX - arenaRadius - outerOffset,
                arenaCenterY - arenaRadius - outerOffset,
                arenaCenterX + arenaRadius + outerOffset,
                arenaCenterY + arenaRadius + outerOffset
        );
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startContinuousFrameLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopContinuousFrameLoop();
        super.onDetachedFromWindow();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (stateLock) {
            if (maxArenaRadius <= 0f && getWidth() > 0 && getHeight() > 0) {
                updateArenaGeometryLocked(getWidth(), getHeight());
                if (balls.isEmpty()) {
                    configurePacingForDuration(2);
                    populateArenaBallsLocked();
                }
            }
        }
        startContinuousFrameLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (stateLock) {
            updateArenaGeometryLocked(width, height);
            if (balls.isEmpty()) {
                configurePacingForDuration(2);
                populateArenaBallsLocked();
            }
        }
        startContinuousFrameLoop();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Managed by window lifecycle
    }

    public void resumeSimulationThread() {
        startContinuousFrameLoop();
    }

    public void pauseSimulationThread() {
        stopContinuousFrameLoop();
    }

    private void startContinuousFrameLoop() {
        if (!loopActive) {
            loopActive = true;
            lastFrameTimeNs = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopContinuousFrameLoop() {
        loopActive = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!loopActive) {
            return;
        }
        float dtSeconds = (frameTimeNanos - lastFrameTimeNs) / 1_000_000_000f;
        lastFrameTimeNs = frameTimeNanos;
        if (dtSeconds <= 0f || dtSeconds > 0.06f) {
            dtSeconds = 0.0166f;
        }

        synchronized (stateLock) {
            if (!simulationPaused) {
                stepPhysicsLocked(dtSeconds);
            }
        }

        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void stepPhysicsLocked(float dtSeconds) {
        if (maxArenaRadius <= 0f || balls.isEmpty()) {
            return;
        }

        // 1. Gentle continuous rotation of the circle ("circle ta halka halka gurbe")
        float circleRotDt = dtSeconds * (1.0f + (speedMultiplier - 1.0f) * 0.25f);
        gapAngle = (gapAngle + gapRotationSpeedDegPerSec * circleRotDt) % 360f;
        if (gapAngle < 0f) {
            gapAngle += 360f;
        }
        outerTrackRotationDeg = (outerTrackRotationDeg - gapRotationSpeedDegPerSec * 0.6f * circleRotDt) % 360f;

        // 2. Gradually shrink the circular arena during RUNNING battle as flags exit
        float flagEffectiveDt = dtSeconds * speedMultiplier;
        if (simulationState == SimulationState.RUNNING) {
            elapsedSimulationMs += (long) (flagEffectiveDt * 1000f);
            float eliminatedFraction = 1.0f - ((float) remainingCount / (float) FlagBall.TOTAL_COUNTRIES);
            float shrinkFactor = 1.0f - (0.35f * eliminatedFraction);
            float desiredArenaRadius = maxArenaRadius * Math.max(0.65f, shrinkFactor);
            arenaRadius += (desiredArenaRadius - arenaRadius) * Math.min(1.0f, dtSeconds * 3.0f);
            updateRingRectsLocked();
        } else {
            arenaRadius += (maxArenaRadius - arenaRadius) * Math.min(1.0f, dtSeconds * 5.0f);
            updateRingRectsLocked();
        }

        // 3. Pacing target for flag exits
        float progress = Math.min(1.0f, (float) elapsedSimulationMs / (float) Math.max(1L, selectedDurationMs));
        int expectedRemaining = Math.max(1, Math.round(FlagBall.TOTAL_COUNTRIES - (FlagBall.TOTAL_COUNTRIES - 1) * progress));
        long minIntervalBetweenExitsMs = Math.max(70L, selectedDurationMs / (FlagBall.TOTAL_COUNTRIES * 2L));
        boolean allowExitNow = (simulationState == SimulationState.RUNNING)
                && (remainingCount > 1)
                && (remainingCount > expectedRemaining || (elapsedSimulationMs - lastExitTimestampMs) >= (selectedDurationMs / FlagBall.TOTAL_COUNTRIES));

        float gapMidRad = (float) Math.toRadians(gapAngle + gapSweep * 0.5f);
        float gapTargetX = arenaCenterX + (float) Math.cos(gapMidRad) * arenaRadius;
        float gapTargetY = arenaCenterY + (float) Math.sin(gapMidRad) * arenaRadius;

        FlagBall guidedBall = null;
        if (simulationState == SimulationState.RUNNING && remainingCount > expectedRemaining + 1) {
            float bestDistSq = Float.MAX_VALUE;
            for (int i = 0; i < balls.size(); i++) {
                FlagBall b = balls.get(i);
                if (!b.isEscaped()) {
                    float dxCenter = b.x - arenaCenterX;
                    float dyCenter = b.y - arenaCenterY;
                    if (dxCenter * dxCenter + dyCenter * dyCenter > (arenaRadius * 0.48f) * (arenaRadius * 0.48f)) {
                        float dxGap = b.x - gapTargetX;
                        float dyGap = b.y - gapTargetY;
                        float dSq = dxGap * dxGap + dyGap * dyGap;
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            guidedBall = b;
                        }
                    }
                }
            }
        }

        float currentTargetSpeed = baseBallSpeed * (0.9f + 0.55f * speedMultiplier);
        float stepDt = Math.min(0.028f, dtSeconds * (0.85f + 0.45f * speedMultiplier));

        // 4. Update active flags (inside the circle + briefly shooting out of the gap before vanishing)
        for (int i = 0; i < balls.size(); i++) {
            FlagBall b = balls.get(i);
            if (!b.isActiveInVoid()) {
                continue;
            }

            b.updateVisualAnimation(dtSeconds);

            if (b.isEscaped()) {
                // Flag is visibly exiting the circle gap -> move outward, shrink & fade out, then vanish!
                b.x += b.vx * dtSeconds;
                b.y += b.vy * dtSeconds;
                b.escapeAlpha -= dtSeconds * 2.4f;
                b.impactScale = Math.max(0.25f, b.impactScale - dtSeconds * 1.6f);

                float dxOut = b.x - arenaCenterX;
                float dyOut = b.y - arenaCenterY;
                float distOut = (float) Math.hypot(dxOut, dyOut);

                if (b.escapeAlpha <= 0.02f || distOut > arenaRadius + b.radius * 4.5f) {
                    b.escapeAlpha = 0f;
                    b.setActiveInVoid(false);
                }
                continue;
            }

            if (simulationState != SimulationState.WON) {
                b.vy += gravityAccel * stepDt;
            }

            if (b == guidedBall && simulationState == SimulationState.RUNNING) {
                float toGapX = gapTargetX - b.x;
                float toGapY = gapTargetY - b.y;
                float len = (float) Math.hypot(toGapX, toGapY);
                if (len > 1f) {
                    float pull = currentTargetSpeed * 1.18f;
                    b.vx = b.vx * 0.84f + (toGapX / len) * pull * 0.16f;
                    b.vy = b.vy * 0.84f + (toGapY / len) * pull * 0.16f;
                }
            }

            b.x += b.vx * stepDt;
            b.y += b.vy * stepDt;

            if (simulationState == SimulationState.WON && b == winnerBall) {
                b.x += (arenaCenterX - b.x) * 0.08f;
                b.y += (arenaCenterY - b.y) * 0.08f;
                b.vx *= 0.90f;
                b.vy *= 0.90f;
                continue;
            }

            float dx = b.x - arenaCenterX;
            float dy = b.y - arenaCenterY;
            float dist = (float) Math.hypot(dx, dy);
            float boundaryDist = arenaRadius - b.radius;

            if (dist >= boundaryDist) {
                float nx = (dist > 0.0001f) ? (dx / dist) : 1f;
                float ny = (dist > 0.0001f) ? (dy / dist) : 0f;

                float contactAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (contactAngle < 0f) {
                    contactAngle += 360f;
                }

                float angularMargin = 2.5f;
                boolean insideGap = isAngleInArc(
                        contactAngle,
                        (gapAngle + angularMargin) % 360f,
                        Math.max(10f, gapSweep - angularMargin * 2f)
                );

                boolean timeReady = (elapsedSimulationMs - lastExitTimestampMs) >= minIntervalBetweenExitsMs
                        || (remainingCount > expectedRemaining + 2);

                if (insideGap && allowExitNow && timeReady && remainingCount > 1) {
                    // Flag shoots out visibly through the circle gap, then fades and vanishes right outside!
                    b.setEscaped(true, elapsedSimulationMs);
                    b.setActiveInVoid(true);
                    b.escapeAlpha = 1.0f;
                    b.impactScale = 1.0f;

                    float exitSpeed = Math.max(240f, baseBallSpeed * 0.95f);
                    b.vx = nx * exitSpeed;
                    b.vy = ny * exitSpeed;

                    lastExitTimestampMs = elapsedSimulationMs;
                    remainingCount--;

                    spawnEscapeSparksLocked(b.x, b.y, b.getPrimaryColor(), b.getSecondaryColor());

                    final FlagBall eliminated = b;
                    final int remNow = remainingCount;
                    if (eventListener != null) {
                        mainHandler.post(() -> eventListener.onFlagEliminated(eliminated, remNow, FlagBall.TOTAL_COUNTRIES));
                    }

                    // When all other 194 flags have exited, show the Winner!
                    if (remainingCount == 1) {
                        FlagBall lastSurvivor = findLastSurvivorLocked();
                        winnerBall = lastSurvivor;
                        simulationState = SimulationState.WON;
                        if (lastSurvivor != null) {
                            spawnVictorySparksLocked(lastSurvivor.x, lastSurvivor.y);
                            final FlagBall winRef = lastSurvivor;
                            if (eventListener != null) {
                                mainHandler.post(() -> eventListener.onSimulationWon(winRef, true));
                            }
                        }
                        allowExitNow = false;
                    } else if (remainingCount <= expectedRemaining) {
                        allowExitNow = false;
                    }
                } else {
                    // Solid white circular wall collision: bounce inward + shrink pulse
                    b.x = arenaCenterX + nx * boundaryDist;
                    b.y = arenaCenterY + ny * boundaryDist;

                    float vn = b.vx * nx + b.vy * ny;
                    if (vn > 0f) {
                        b.vx -= 2.0f * vn * nx;
                        b.vy -= 2.0f * vn * ny;
                    }

                    float tx = -ny;
                    float ty = nx;
                    float wallSpinKick = currentTargetSpeed * 0.14f;
                    b.vx += tx * wallSpinKick;
                    b.vy += ty * wallSpinKick;

                    float randomJitterAngle = (random.nextFloat() - 0.5f) * 0.40f;
                    float cosA = (float) Math.cos(randomJitterAngle);
                    float sinA = (float) Math.sin(randomJitterAngle);
                    float rx = b.vx * cosA - b.vy * sinA;
                    float ry = b.vx * sinA + b.vy * cosA;

                    float speed = (float) Math.hypot(rx, ry);
                    float desiredSpeed = currentTargetSpeed * (0.94f + random.nextFloat() * 0.24f);
                    if (speed > 0.001f) {
                        b.vx = (rx / speed) * desiredSpeed;
                        b.vy = (ry / speed) * desiredSpeed;
                    }

                    b.triggerCollisionShrink(0.85f, (random.nextFloat() - 0.5f) * 260f);
                }
            }
        }

        // 5. Ball-to-ball elastic collisions among remaining flags inside the circle
        int n = balls.size();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                FlagBall a = balls.get(i);
                if (a.isEscaped()) {
                    continue;
                }
                for (int j = i + 1; j < n; j++) {
                    FlagBall b = balls.get(j);
                    if (b.isEscaped()) {
                        continue;
                    }

                    float dx = b.x - a.x;
                    float dy = b.y - a.y;
                    float minDist = a.radius + b.radius;
                    float distSq = dx * dx + dy * dy;

                    if (distSq < minDist * minDist) {
                        float dist = (float) Math.sqrt(distSq);
                        float nx;
                        float ny;
                        if (dist > 0.0001f) {
                            nx = dx / dist;
                            ny = dy / dist;
                        } else {
                            double randAng = random.nextDouble() * Math.PI * 2.0;
                            nx = (float) Math.cos(randAng);
                            ny = (float) Math.sin(randAng);
                            dist = minDist * 0.5f;
                        }

                        float overlap = (minDist - dist) * 0.53f;
                        a.x -= nx * overlap;
                        a.y -= ny * overlap;
                        b.x += nx * overlap;
                        b.y += ny * overlap;

                        clampInsideArenaLocked(a);
                        clampInsideArenaLocked(b);

                        if (pass == 0) {
                            float rvx = b.vx - a.vx;
                            float rvy = b.vy - a.vy;
                            float velAlongNormal = rvx * nx + rvy * ny;

                            if (velAlongNormal < 0f) {
                                float impulse = -1.04f * velAlongNormal;
                                a.vx -= impulse * nx;
                                a.vy -= impulse * ny;
                                b.vx += impulse * nx;
                                b.vy += impulse * ny;

                                regulateBallSpeed(a, currentTargetSpeed);
                                regulateBallSpeed(b, currentTargetSpeed);

                                float impactStrength = Math.min(1.0f, Math.abs(velAlongNormal) / (currentTargetSpeed * 0.8f));
                                if (impactStrength > 0.14f) {
                                    float spin = (random.nextFloat() - 0.5f) * 220f;
                                    a.triggerCollisionShrink(impactStrength, spin);
                                    b.triggerCollisionShrink(impactStrength, -spin);
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Update spark particles
        for (int i = sparks.size() - 1; i >= 0; i--) {
            SparkParticle sp = sparks.get(i);
            sp.x += sp.vx * dtSeconds;
            sp.y += sp.vy * dtSeconds;
            sp.alpha -= dtSeconds * 1.8f;
            if (sp.alpha <= 0f) {
                sparks.remove(i);
            }
        }
    }

    private void clampInsideArenaLocked(FlagBall b) {
        float dx = b.x - arenaCenterX;
        float dy = b.y - arenaCenterY;
        float distSq = dx * dx + dy * dy;
        float maxR = arenaRadius - b.radius;
        if (distSq > maxR * maxR && distSq > 0.0001f) {
            float dist = (float) Math.sqrt(distSq);
            b.x = arenaCenterX + (dx / dist) * maxR;
            b.y = arenaCenterY + (dy / dist) * maxR;
        }
    }

    private void regulateBallSpeed(FlagBall b, float refSpeed) {
        float speed = (float) Math.hypot(b.vx, b.vy);
        float minSpeed = refSpeed * 0.75f;
        float maxSpeed = refSpeed * 1.42f;
        if (speed < 0.001f) {
            double ang = random.nextDouble() * Math.PI * 2.0;
            b.vx = (float) Math.cos(ang) * refSpeed;
            b.vy = (float) Math.sin(ang) * refSpeed;
        } else if (speed < minSpeed) {
            b.vx = (b.vx / speed) * minSpeed;
            b.vy = (b.vy / speed) * minSpeed;
        } else if (speed > maxSpeed) {
            b.vx = (b.vx / speed) * maxSpeed;
            b.vy = (b.vy / speed) * maxSpeed;
        }
    }

    private FlagBall findLastSurvivorLocked() {
        for (int i = 0; i < balls.size(); i++) {
            FlagBall b = balls.get(i);
            if (!b.isEscaped()) {
                return b;
            }
        }
        return balls.isEmpty() ? null : balls.get(0);
    }

    private boolean isAngleInArc(float angleDeg, float arcStartDeg, float arcSweepDeg) {
        float diff = (angleDeg - arcStartDeg + 360f) % 360f;
        return diff >= 0f && diff <= arcSweepDeg;
    }

    private void spawnEscapeSparksLocked(float x, float y, int c1, int c2) {
        if (sparks.size() > 140) {
            return;
        }
        for (int i = 0; i < 14; i++) {
            SparkParticle sp = new SparkParticle();
            sp.x = x;
            sp.y = y;
            double ang = random.nextDouble() * Math.PI * 2.0;
            float spd = 70f + random.nextFloat() * 190f;
            sp.vx = (float) Math.cos(ang) * spd;
            sp.vy = (float) Math.sin(ang) * spd;
            sp.alpha = 1.0f;
            sp.radius = 3.5f + random.nextFloat() * 4f;
            sp.color = (i % 2 == 0) ? c1 : c2;
            sparks.add(sp);
        }
    }

    private void spawnVictorySparksLocked(float x, float y) {
        for (int i = 0; i < 36; i++) {
            SparkParticle sp = new SparkParticle();
            sp.x = x;
            sp.y = y;
            double ang = (Math.PI * 2.0 * i) / 36.0;
            float spd = 90f + random.nextFloat() * 240f;
            sp.vx = (float) Math.cos(ang) * spd;
            sp.vy = (float) Math.sin(ang) * spd;
            sp.alpha = 1.0f;
            sp.radius = 4f + random.nextFloat() * 5f;
            sp.color = (i % 2 == 0) ? Color.parseColor("#FACC15") : Color.parseColor("#3B82F6");
            sparks.add(sp);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        synchronized (stateLock) {
            drawSceneLocked(canvas);
        }
    }

    private void drawSceneLocked(Canvas canvas) {
        if (canvas == null) {
            return;
        }
        // 1. Dark Navy Background (#0F172A)
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), bgPaint);

        if (arenaRadius <= 0f) {
            return;
        }

        // 2. Subtle Radial Arena Glow & Counter-Rotating Dashed Outer Track
        canvas.drawCircle(arenaCenterX, arenaCenterY, arenaRadius * 1.14f, arenaGlowPaint);
        canvas.save();
        canvas.rotate(outerTrackRotationDeg, arenaCenterX, arenaCenterY);
        canvas.drawOval(outerTrackRect, arenaOuterTrackPaint);
        canvas.restore();

        // 3. Solid White Circular Ring with Rotating 35-Degree Exit Cutout Gap
        float solidStartAngle = (gapAngle + gapSweep) % 360f;
        float solidSweepAngle = 360f - gapSweep;
        canvas.drawArc(arenaArcRect, solidStartAngle, solidSweepAngle, false, arenaRingPaint);

        // 4. Rotating Cyan Accent Segments along the White Ring
        int segmentCount = 8;
        float usableSweep = solidSweepAngle - 16f;
        float stepAngle = usableSweep / segmentCount;
        for (int i = 0; i < segmentCount; i++) {
            float segStart = (solidStartAngle + 8f + i * stepAngle) % 360f;
            canvas.drawArc(arenaArcRect, segStart, 12f, false, arenaSegmentAccentPaint);
        }

        // 5. Glowing Exit Gap Endpoint Beacons
        double startRad = Math.toRadians(gapAngle);
        double endRad = Math.toRadians((gapAngle + gapSweep) % 360f);
        float sx = arenaCenterX + (float) Math.cos(startRad) * arenaRadius;
        float sy = arenaCenterY + (float) Math.sin(startRad) * arenaRadius;
        float ex = arenaCenterX + (float) Math.cos(endRad) * arenaRadius;
        float ey = arenaCenterY + (float) Math.sin(endRad) * arenaRadius;
        canvas.drawCircle(sx, sy, 9f, gapBeaconPaint);
        canvas.drawCircle(ex, ey, 9f, gapBeaconPaint);
        canvas.drawCircle(sx, sy, 4.5f, gapBeaconCorePaint);
        canvas.drawCircle(ex, ey, 4.5f, gapBeaconCorePaint);

        // 6. Render Spark Particles
        for (int i = 0; i < sparks.size(); i++) {
            SparkParticle sp = sparks.get(i);
            sparkPaint.setColor(sp.color);
            sparkPaint.setAlpha(Math.max(0, Math.min(255, (int) (sp.alpha * 255f))));
            canvas.drawCircle(sp.x, sp.y, sp.radius, sparkPaint);
        }

        // 7. Render active flags (inside the circle + flags visibly exiting the gap before vanishing)
        for (int i = 0; i < balls.size(); i++) {
            FlagBall b = balls.get(i);
            if (b.isActiveInVoid()) {
                b.draw(canvas);
            }
        }

        // 8. Highlight Winner Flag inside the circle when WON
        if (simulationState == SimulationState.WON && winnerBall != null) {
            canvas.drawCircle(winnerBall.x, winnerBall.y, winnerBall.radius + 9f, winnerHaloPaint);
        }
    }
}
