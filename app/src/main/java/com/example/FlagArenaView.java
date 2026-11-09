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
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Custom 60 FPS SurfaceView physics engine and renderer for the
 * "Last Flag in the Circle Wins" simulation battle.
 */
public class FlagArenaView extends SurfaceView implements SurfaceHolder.Callback {

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
    private Paint arenaOuterTrackPaint;
    private Paint gapBeaconPaint;
    private Paint sparkPaint;
    private Paint winnerHaloPaint;

    private final RectF arenaArcRect = new RectF();
    private final RectF outerTrackRect = new RectF();

    private float arenaCenterX = 0f;
    private float arenaCenterY = 0f;
    private float arenaRadius = 0f;
    private float ballRadius = 20f; // Diameter ~40px (within 36-44px spec)

    private float gapAngle = 290f; // Starts near top-right
    private float gapSweep = GAP_SWEEP_DEGREES;
    private float gapRotationSpeedDegPerSec = 42f;

    private float targetBallSpeed = 240f;
    private float speedMultiplier = 1.0f;
    private long selectedDurationMs = 2 * 60 * 1000L;
    private long elapsedSimulationMs = 0L;
    private long lastExitTimestampMs = 0L;

    private SimulationState simulationState = SimulationState.IDLE;
    private FlagBall winnerBall = null;
    private int remainingCount = FlagBall.TOTAL_COUNTRIES;

    private ArenaEventListener eventListener;
    private PhysicsThread physicsThread;
    private boolean surfaceReady = false;

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
        arenaRingPaint.setStrokeWidth(5.5f * density);
        arenaRingPaint.setStrokeCap(Paint.Cap.ROUND);
        arenaRingPaint.setShadowLayer(8f * density, 0f, 0f, Color.argb(140, 59, 130, 246));

        arenaOuterTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arenaOuterTrackPaint.setColor(Color.argb(50, 148, 163, 184));
        arenaOuterTrackPaint.setStyle(Paint.Style.STROKE);
        arenaOuterTrackPaint.setStrokeWidth(1.5f * density);
        arenaOuterTrackPaint.setPathEffect(new DashPathEffect(new float[]{8f * density, 8f * density}, 0f));

        gapBeaconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gapBeaconPaint.setColor(Color.parseColor("#3B82F6"));
        gapBeaconPaint.setStyle(Paint.Style.FILL);

        sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sparkPaint.setStyle(Paint.Style.FILL);

        winnerHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        winnerHaloPaint.setColor(Color.parseColor("#FACC15"));
        winnerHaloPaint.setStyle(Paint.Style.STROKE);
        winnerHaloPaint.setStrokeWidth(3.5f * density);
    }

    public void setArenaEventListener(ArenaEventListener listener) {
        this.eventListener = listener;
    }

    public void setSpeedMultiplier(float multiplier) {
        synchronized (stateLock) {
            this.speedMultiplier = Math.max(1.0f, Math.min(20.0f, multiplier));
        }
    }

    public float getSpeedMultiplier() {
        synchronized (stateLock) {
            return speedMultiplier;
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

    /**
     * Configures pacing parameters (ball velocity, rotation speed, exit cadence)
     * proportionally to the user's selected duration in minutes.
     */
    private void configurePacingForDuration(int durationMinutes) {
        this.selectedDurationMs = Math.max(1, durationMinutes) * 60L * 1000L;
        this.gapSweep = GAP_SWEEP_DEGREES;

        float refRadius = Math.max(220f, arenaRadius);
        switch (durationMinutes) {
            case 2:
                targetBallSpeed = refRadius * 0.92f;
                gapRotationSpeedDegPerSec = 52f;
                break;
            case 5:
                targetBallSpeed = refRadius * 0.80f;
                gapRotationSpeedDegPerSec = 44f;
                break;
            case 7:
                targetBallSpeed = refRadius * 0.74f;
                gapRotationSpeedDegPerSec = 40f;
                break;
            case 15:
                targetBallSpeed = refRadius * 0.66f;
                gapRotationSpeedDegPerSec = 34f;
                break;
            case 30:
                targetBallSpeed = refRadius * 0.58f;
                gapRotationSpeedDegPerSec = 28f;
                break;
            case 60:
            default:
                targetBallSpeed = refRadius * 0.52f;
                gapRotationSpeedDegPerSec = 24f;
                break;
        }
    }

    /**
     * Resets and starts a new 195-flag simulation battle for the given duration in minutes.
     */
    public void startSimulation(int durationMinutes) {
        synchronized (stateLock) {
            configurePacingForDuration(durationMinutes);
            elapsedSimulationMs = 0L;
            lastExitTimestampMs = 0L;
            winnerBall = null;
            sparks.clear();
            populateArenaBallsLocked();
            simulationState = SimulationState.RUNNING;
        }
        invalidate();
    }

    /**
     * Resets the arena back to the pre-battle IDLE preview state with all 195 flags inside.
     */
    public void resetToIdle(int durationMinutes) {
        synchronized (stateLock) {
            configurePacingForDuration(durationMinutes);
            elapsedSimulationMs = 0L;
            lastExitTimestampMs = 0L;
            winnerBall = null;
            sparks.clear();
            populateArenaBallsLocked();
            simulationState = SimulationState.IDLE;
        }
        invalidate();
    }

    /**
     * Called when the user-configured countdown timer reaches 00:00.
     * Crowns the surviving flag closest to the arena center if more than 1 remains.
     */
    public void finishByTimer() {
        FlagBall crowned = null;
        synchronized (stateLock) {
            if (simulationState != SimulationState.RUNNING) {
                return;
            }
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
            winnerBall = crowned;
            simulationState = SimulationState.WON;
            if (crowned != null) {
                spawnVictorySparksLocked(crowned.x, crowned.y);
            }
        }
        if (crowned != null && eventListener != null) {
            final FlagBall finalWinner = crowned;
            mainHandler.post(() -> eventListener.onSimulationWon(finalWinner, false));
        }
        invalidate();
    }

    private void populateArenaBallsLocked() {
        if (arenaRadius <= 0f) {
            return;
        }
        // Keep ball diameter strictly in the ~36-44px range (radius 18-22px),
        // scaled safely so 195 balls fit with breathing room inside the circle.
        float maxSafeRadius = arenaRadius / 19.2f;
        ballRadius = Math.max(16f, Math.min(22f, maxSafeRadius));

        balls.clear();
        balls.addAll(FlagBall.createAll195Countries(
                arenaCenterX,
                arenaCenterY,
                arenaRadius,
                ballRadius,
                targetBallSpeed,
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
    }

    private void updateArenaGeometryLocked(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        arenaCenterX = width * 0.5f;
        // Slightly above true vertical center to leave generous space for bottom controls
        arenaCenterY = height * 0.48f;
        float minDim = Math.min(width, height);
        arenaRadius = minDim * 0.42f;

        arenaArcRect.set(
                arenaCenterX - arenaRadius,
                arenaCenterY - arenaRadius,
                arenaCenterX + arenaRadius,
                arenaCenterY + arenaRadius
        );
        float outerOffset = 14f;
        outerTrackRect.set(
                arenaCenterX - arenaRadius - outerOffset,
                arenaCenterY - arenaRadius - outerOffset,
                arenaCenterX + arenaRadius + outerOffset,
                arenaCenterY + arenaRadius + outerOffset
        );

        arenaGlowPaint.setShader(new RadialGradient(
                arenaCenterX,
                arenaCenterY,
                arenaRadius * 1.15f,
                new int[]{
                        Color.argb(42, 59, 130, 246),
                        Color.argb(18, 30, 41, 59),
                        Color.argb(0, 15, 23, 42)
                },
                new float[]{0f, 0.78f, 1f},
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (stateLock) {
            surfaceReady = true;
            if (arenaRadius <= 0f && getWidth() > 0 && getHeight() > 0) {
                updateArenaGeometryLocked(getWidth(), getHeight());
                if (balls.isEmpty()) {
                    configurePacingForDuration(2);
                    populateArenaBallsLocked();
                }
            }
        }
        startPhysicsThread();
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
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (stateLock) {
            surfaceReady = false;
        }
        stopPhysicsThread();
    }

    public void resumeSimulationThread() {
        if (surfaceReady) {
            startPhysicsThread();
        }
    }

    public void pauseSimulationThread() {
        stopPhysicsThread();
    }

    private void startPhysicsThread() {
        stopPhysicsThread();
        physicsThread = new PhysicsThread();
        physicsThread.setRunning(true);
        physicsThread.start();
    }

    private void stopPhysicsThread() {
        if (physicsThread != null) {
            physicsThread.setRunning(false);
            try {
                physicsThread.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            physicsThread = null;
        }
    }

    /**
     * Advances the 2D physics simulation by dtSeconds.
     */
    private void stepPhysicsLocked(float dtSeconds) {
        if (arenaRadius <= 0f || balls.isEmpty()) {
            return;
        }

        float effectiveDt = dtSeconds * speedMultiplier;
        // Rotate the 35-degree exit gap continuously around the perimeter
        gapAngle = (gapAngle + gapRotationSpeedDegPerSec * effectiveDt) % 360f;
        if (gapAngle < 0f) {
            gapAngle += 360f;
        }

        if (simulationState == SimulationState.RUNNING) {
            elapsedSimulationMs += (long) (effectiveDt * 1000f);
        }

        // Calculate pacing target so 194 flags exit steadily across selectedDurationMs
        float progress = Math.min(1.0f, (float) elapsedSimulationMs / (float) Math.max(1L, selectedDurationMs));
        int expectedRemaining = Math.max(1, Math.round(FlagBall.TOTAL_COUNTRIES - (FlagBall.TOTAL_COUNTRIES - 1) * progress));
        long minIntervalBetweenExitsMs = Math.max(80L, selectedDurationMs / (FlagBall.TOTAL_COUNTRIES * 2L));
        boolean allowExitNow = (simulationState == SimulationState.RUNNING)
                && (remainingCount > 1)
                && (remainingCount > expectedRemaining || (elapsedSimulationMs - lastExitTimestampMs) >= (selectedDurationMs / FlagBall.TOTAL_COUNTRIES));

        float gapMidRad = (float) Math.toRadians(gapAngle + gapSweep * 0.5f);
        float gapTargetX = arenaCenterX + (float) Math.cos(gapMidRad) * arenaRadius;
        float gapTargetY = arenaCenterY + (float) Math.sin(gapMidRad) * arenaRadius;

        // If exits are lagging behind the steady duration schedule, gently guide one perimeter ball toward the gap
        FlagBall guidedBall = null;
        if (simulationState == SimulationState.RUNNING && remainingCount > expectedRemaining + 1) {
            float bestDistSq = Float.MAX_VALUE;
            for (int i = 0; i < balls.size(); i++) {
                FlagBall b = balls.get(i);
                if (!b.isEscaped()) {
                    float dxCenter = b.x - arenaCenterX;
                    float dyCenter = b.y - arenaCenterY;
                    if (dxCenter * dxCenter + dyCenter * dyCenter > (arenaRadius * 0.55f) * (arenaRadius * 0.55f)) {
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

        float simSpeedFactor = (simulationState == SimulationState.IDLE) ? 0.35f : 1.0f;
        float stepDt = Math.min(0.032f, dtSeconds * speedMultiplier * simSpeedFactor);

        int viewW = Math.max(1080, getWidth());
        int viewH = Math.max(1920, getHeight());

        // 1. Update positions & check circular boundary / exit gap
        for (int i = 0; i < balls.size(); i++) {
            FlagBall b = balls.get(i);
            if (!b.isActiveInVoid()) {
                continue;
            }

            if (b == guidedBall && simulationState == SimulationState.RUNNING) {
                float toGapX = gapTargetX - b.x;
                float toGapY = gapTargetY - b.y;
                float len = (float) Math.hypot(toGapX, toGapY);
                if (len > 1f) {
                    float pull = targetBallSpeed * 1.15f;
                    b.vx = b.vx * 0.86f + (toGapX / len) * pull * 0.14f;
                    b.vy = b.vy * 0.86f + (toGapY / len) * pull * 0.14f;
                }
            }

            b.x += b.vx * stepDt;
            b.y += b.vy * stepDt;

            if (b.isEscaped()) {
                // Escaped ball shoots outward into the void until off-screen
                if (b.x < -b.radius * 4f || b.x > viewW + b.radius * 4f
                        || b.y < -b.radius * 4f || b.y > viewH + b.radius * 4f) {
                    b.setActiveInVoid(false);
                }
                continue;
            }

            // If WON, draw surviving winner smoothly toward arena center
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

                // Check if ball strikes within the rotating 35-degree exit gap
                float angularMargin = 3.0f;
                boolean insideGap = isAngleInArc(
                        contactAngle,
                        (gapAngle + angularMargin) % 360f,
                        Math.max(8f, gapSweep - angularMargin * 2f)
                );

                boolean timeReady = (elapsedSimulationMs - lastExitTimestampMs) >= minIntervalBetweenExitsMs
                        || (remainingCount > expectedRemaining + 2);

                if (insideGap && allowExitNow && timeReady && remainingCount > 1) {
                    // Ball escapes through the exit gap!
                    b.setEscaped(true, elapsedSimulationMs);
                    lastExitTimestampMs = elapsedSimulationMs;
                    remainingCount--;

                    float escapeSpeed = Math.max(targetBallSpeed * 1.65f, 380f);
                    b.vx = nx * escapeSpeed;
                    b.vy = ny * escapeSpeed;

                    spawnEscapeSparksLocked(b.x, b.y, b.getPrimaryColor(), b.getSecondaryColor());

                    final FlagBall eliminated = b;
                    final int remNow = remainingCount;
                    if (eventListener != null) {
                        mainHandler.post(() -> eventListener.onFlagEliminated(eliminated, remNow, FlagBall.TOTAL_COUNTRIES));
                    }

                    // Check if only 1 flag remains -> Victory!
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
                    // Solid white wall collision: bounce inward elastically with slight random impulse
                    b.x = arenaCenterX + nx * boundaryDist;
                    b.y = arenaCenterY + ny * boundaryDist;

                    float vn = b.vx * nx + b.vy * ny;
                    if (vn > 0f) {
                        b.vx -= 2.0f * vn * nx;
                        b.vy -= 2.0f * vn * ny;
                    }

                    // Add slight random angular impulse (-0.18 to +0.18 rad) so balls never loop identically
                    float randomJitterAngle = (random.nextFloat() - 0.5f) * 0.36f;
                    float cosA = (float) Math.cos(randomJitterAngle);
                    float sinA = (float) Math.sin(randomJitterAngle);
                    float rx = b.vx * cosA - b.vy * sinA;
                    float ry = b.vx * sinA + b.vy * cosA;

                    // Normalize velocity near targetBallSpeed
                    float speed = (float) Math.hypot(rx, ry);
                    float desiredSpeed = targetBallSpeed * (0.90f + random.nextFloat() * 0.22f);
                    if (speed > 0.001f) {
                        b.vx = (rx / speed) * desiredSpeed;
                        b.vy = (ry / speed) * desiredSpeed;
                    }
                }
            }
        }

        // 2. Ball-to-ball elastic collisions with 2-pass anti-clipping positional relaxation
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

                        // Positional separation to prevent clipping or cluster sticking
                        float overlap = (minDist - dist) * 0.52f;
                        a.x -= nx * overlap;
                        a.y -= ny * overlap;
                        b.x += nx * overlap;
                        b.y += ny * overlap;

                        // Keep both inside the circle after separation
                        clampInsideArenaLocked(a);
                        clampInsideArenaLocked(b);

                        // Apply elastic collision impulse on first pass
                        if (pass == 0) {
                            float rvx = b.vx - a.vx;
                            float rvy = b.vy - a.vy;
                            float velAlongNormal = rvx * nx + rvy * ny;

                            if (velAlongNormal < 0f) {
                                float impulse = -1.0f * velAlongNormal;
                                a.vx -= impulse * nx;
                                a.vy -= impulse * ny;
                                b.vx += impulse * nx;
                                b.vy += impulse * ny;

                                regulateBallSpeed(a);
                                regulateBallSpeed(b);
                            }
                        }
                    }
                }
            }
        }

        // 3. Update spark particles
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

    private void regulateBallSpeed(FlagBall b) {
        float speed = (float) Math.hypot(b.vx, b.vy);
        float minSpeed = targetBallSpeed * 0.72f;
        float maxSpeed = targetBallSpeed * 1.38f;
        if (speed < 0.001f) {
            double ang = random.nextDouble() * Math.PI * 2.0;
            b.vx = (float) Math.cos(ang) * targetBallSpeed;
            b.vy = (float) Math.sin(ang) * targetBallSpeed;
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
        if (sparks.size() > 120) {
            return;
        }
        for (int i = 0; i < 12; i++) {
            SparkParticle sp = new SparkParticle();
            sp.x = x;
            sp.y = y;
            double ang = random.nextDouble() * Math.PI * 2.0;
            float spd = 60f + random.nextFloat() * 180f;
            sp.vx = (float) Math.cos(ang) * spd;
            sp.vy = (float) Math.sin(ang) * spd;
            sp.alpha = 1.0f;
            sp.radius = 3f + random.nextFloat() * 4f;
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

        // 2. Subtle Radial Arena Glow & Dashed Outer Orbit Track
        canvas.drawCircle(arenaCenterX, arenaCenterY, arenaRadius * 1.12f, arenaGlowPaint);
        canvas.drawOval(outerTrackRect, arenaOuterTrackPaint);

        // 3. Solid White Circular Ring with Rotating 35-Degree Exit Cutout Gap
        float solidStartAngle = (gapAngle + gapSweep) % 360f;
        float solidSweepAngle = 360f - gapSweep;
        canvas.drawArc(arenaArcRect, solidStartAngle, solidSweepAngle, false, arenaRingPaint);

        // 4. Glowing Exit Gap Endpoint Beacons so the 35-degree opening is unmistakable
        double startRad = Math.toRadians(gapAngle);
        double endRad = Math.toRadians((gapAngle + gapSweep) % 360f);
        float sx = arenaCenterX + (float) Math.cos(startRad) * arenaRadius;
        float sy = arenaCenterY + (float) Math.sin(startRad) * arenaRadius;
        float ex = arenaCenterX + (float) Math.cos(endRad) * arenaRadius;
        float ey = arenaCenterY + (float) Math.sin(endRad) * arenaRadius;
        canvas.drawCircle(sx, sy, 6f, gapBeaconPaint);
        canvas.drawCircle(ex, ey, 6f, gapBeaconPaint);

        // 5. Render Spark Particles
        for (int i = 0; i < sparks.size(); i++) {
            SparkParticle sp = sparks.get(i);
            sparkPaint.setColor(sp.color);
            sparkPaint.setAlpha(Math.max(0, Math.min(255, (int) (sp.alpha * 255f))));
            canvas.drawCircle(sp.x, sp.y, sp.radius, sparkPaint);
        }

        // 6. Render All Active Flag Balls (inside circle + shooting outward into void)
        for (int i = 0; i < balls.size(); i++) {
            FlagBall b = balls.get(i);
            if (!b.isActiveInVoid()) {
                continue;
            }
            b.draw(canvas);
        }

        // 7. Highlight Winner Flag inside the circle when WON
        if (simulationState == SimulationState.WON && winnerBall != null) {
            canvas.drawCircle(winnerBall.x, winnerBall.y, winnerBall.radius + 8f, winnerHaloPaint);
        }
    }

    /**
     * Dedicated 60 FPS Game Loop Thread using SurfaceHolder.lockCanvas().
     */
    private class PhysicsThread extends Thread {
        private volatile boolean running = false;

        public PhysicsThread() {
            super("FlagArenaPhysicsThread");
        }

        public void setRunning(boolean running) {
            this.running = running;
        }

        @Override
        public void run() {
            long lastTimeNs = System.nanoTime();
            final long targetFrameNs = 16_666_667L; // ~60 FPS

            while (running) {
                long nowNs = System.nanoTime();
                float dtSeconds = (nowNs - lastTimeNs) / 1_000_000_000f;
                lastTimeNs = nowNs;
                if (dtSeconds <= 0f || dtSeconds > 0.1f) {
                    dtSeconds = 0.0166f;
                }

                Canvas canvas = null;
                SurfaceHolder holder = getHolder();
                try {
                    canvas = holder.lockCanvas();
                    synchronized (stateLock) {
                        if (surfaceReady && canvas != null) {
                            stepPhysicsLocked(dtSeconds);
                            drawSceneLocked(canvas);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas);
                        } catch (Exception ignored) {
                        }
                    }
                }

                long frameElapsedNs = System.nanoTime() - nowNs;
                long sleepMs = (targetFrameNs - frameElapsedNs) / 1_000_000L;
                if (sleepMs > 1L) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }
}
