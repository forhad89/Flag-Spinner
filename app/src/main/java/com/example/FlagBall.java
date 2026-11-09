package com.example;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Represents a single Country Flag physics ball inside the circular battle arena.
 * Supports elastic collision shrink/squash animation, rotational spin, and
 * procedural circular flag avatars for all 195 countries.
 */
public class FlagBall {

    public static final int TOTAL_COUNTRIES = 195;

    public enum PatternType {
        HORIZONTAL_3,
        VERTICAL_3,
        HORIZONTAL_2,
        VERTICAL_2,
        CENTER_CIRCLE,
        NORDIC_CROSS,
        DIAGONAL,
        CANTON
    }

    public static class CountrySpec {
        public final String code;
        public final String name;
        public final int color1;
        public final int color2;
        public final int color3;
        public final PatternType pattern;

        public CountrySpec(String code, String name, String hex1, String hex2, String hex3, PatternType pattern) {
            this.code = code;
            this.name = name;
            this.color1 = Color.parseColor(hex1);
            this.color2 = Color.parseColor(hex2);
            this.color3 = Color.parseColor(hex3);
            this.pattern = pattern;
        }
    }

    private final String countryCode;
    private final String countryName;
    private final int color1;
    private final int color2;
    private final int color3;
    private final PatternType pattern;

    public float x;
    public float y;
    public float vx;
    public float vy;
    public float radius;
    public final float baseRadius;

    // Visual shrink/squash & rotation state on collision
    public float impactScale = 1.0f;
    public float rotationDeg = 0f;
    public float angularVelDegPerSec = 0f;

    private boolean escaped = false;
    private boolean activeInVoid = true;
    private long escapedTimestampMs = 0L;

    private Bitmap cachedAvatar;
    private int cachedRadiusPx = -1;

    private static final Paint BITMAP_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public FlagBall(
            String countryCode,
            String countryName,
            int color1,
            int color2,
            int color3,
            PatternType pattern,
            float x,
            float y,
            float vx,
            float vy,
            float radius
    ) {
        this.countryCode = countryCode;
        this.countryName = countryName;
        this.color1 = color1;
        this.color2 = color2;
        this.color3 = color3;
        this.pattern = pattern;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.radius = radius;
        this.baseRadius = radius;
        ensureAvatarBitmap();
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getCountryName() {
        return countryName;
    }

    public int getPrimaryColor() {
        return color1;
    }

    public int getSecondaryColor() {
        return color2;
    }

    public boolean isEscaped() {
        return escaped;
    }

    public void setEscaped(boolean escaped, long timestampMs) {
        this.escaped = escaped;
        this.escapedTimestampMs = timestampMs;
    }

    public long getEscapedTimestampMs() {
        return escapedTimestampMs;
    }

    public boolean isActiveInVoid() {
        return activeInVoid;
    }

    public void setActiveInVoid(boolean activeInVoid) {
        this.activeInVoid = activeInVoid;
    }

    /**
     * Triggers a visible shrink/compress effect when this flag collides with another flag or wall.
     */
    public void triggerCollisionShrink(float intensity, float spinImpulse) {
        float targetShrink = Math.max(0.72f, 1.0f - 0.24f * Math.min(1.0f, intensity));
        if (targetShrink < this.impactScale) {
            this.impactScale = targetShrink;
        }
        this.angularVelDegPerSec = Math.max(-360f, Math.min(360f, this.angularVelDegPerSec + spinImpulse));
    }

    /**
     * Updates the smooth spring recovery of the shrink scale and ball rotation each frame.
     */
    public void updateVisualAnimation(float dtSeconds) {
        if (impactScale < 1.0f) {
            impactScale += (1.0f - impactScale) * Math.min(1.0f, dtSeconds * 14.0f);
            if (impactScale > 0.995f) {
                impactScale = 1.0f;
            }
        }
        rotationDeg = (rotationDeg + angularVelDegPerSec * dtSeconds) % 360f;
        angularVelDegPerSec *= Math.max(0.85f, 1.0f - dtSeconds * 2.5f);
    }

    public void setRadius(float newRadius) {
        if (Math.abs(this.radius - newRadius) > 0.5f) {
            this.radius = newRadius;
            ensureAvatarBitmap();
        }
    }

    private void ensureAvatarBitmap() {
        int rPx = Math.max(12, Math.round(radius));
        if (cachedAvatar != null && cachedRadiusPx == rPx) {
            return;
        }
        cachedRadiusPx = rPx;
        cachedAvatar = renderCircularFlagBitmap(rPx * 2);
    }

    public Bitmap createHighResBitmap(int diameterPx) {
        return renderCircularFlagBitmap(Math.max(48, diameterPx));
    }

    private Bitmap renderCircularFlagBitmap(int diameterPx) {
        int size = Math.max(24, diameterPx);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        float cx = size * 0.5f;
        float cy = size * 0.5f;
        float r = (size * 0.5f) - 1.5f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path circlePath = new Path();
        circlePath.addCircle(cx, cy, r, Path.Direction.CW);

        c.save();
        c.clipPath(circlePath);

        float left = cx - r;
        float top = cy - r;
        float right = cx + r;
        float bottom = cy + r;
        float d = r * 2f;

        switch (pattern) {
            case HORIZONTAL_3: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color1);
                c.drawRect(left, top, right, top + d / 3f + 1f, paint);
                paint.setColor(color2);
                c.drawRect(left, top + d / 3f, right, top + 2f * d / 3f + 1f, paint);
                paint.setColor(color3);
                c.drawRect(left, top + 2f * d / 3f, right, bottom, paint);
                break;
            }
            case VERTICAL_3: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color1);
                c.drawRect(left, top, left + d / 3f + 1f, bottom, paint);
                paint.setColor(color2);
                c.drawRect(left + d / 3f, top, left + 2f * d / 3f + 1f, bottom, paint);
                paint.setColor(color3);
                c.drawRect(left + 2f * d / 3f, top, right, bottom, paint);
                break;
            }
            case HORIZONTAL_2: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color1);
                c.drawRect(left, top, right, cy + 1f, paint);
                paint.setColor(color2);
                c.drawRect(left, cy, right, bottom, paint);
                break;
            }
            case VERTICAL_2: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color1);
                c.drawRect(left, top, cx + 1f, bottom, paint);
                paint.setColor(color2);
                c.drawRect(cx, top, right, bottom, paint);
                break;
            }
            case CENTER_CIRCLE: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color1);
                c.drawRect(left, top, right, bottom, paint);
                paint.setColor(color2);
                c.drawCircle(cx, cy, r * 0.48f, paint);
                break;
            }
            case NORDIC_CROSS: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color1);
                c.drawRect(left, top, right, bottom, paint);
                paint.setColor(color2);
                float bar = d * 0.22f;
                c.drawRect(left, cy - bar * 0.5f, right, cy + bar * 0.5f, paint);
                c.drawRect(left + d * 0.30f, top, left + d * 0.30f + bar, bottom, paint);
                break;
            }
            case DIAGONAL: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color1);
                c.drawRect(left, top, right, bottom, paint);
                Path tri = new Path();
                tri.moveTo(left, bottom);
                tri.lineTo(right, top);
                tri.lineTo(right, bottom);
                tri.close();
                paint.setColor(color3);
                c.drawPath(tri, paint);
                paint.setColor(color2);
                paint.setStrokeWidth(d * 0.18f);
                paint.setStyle(Paint.Style.STROKE);
                c.drawLine(left, bottom, right, top, paint);
                break;
            }
            case CANTON: {
                paint.setStyle(Paint.Style.FILL);
                int stripes = 5;
                float stripeH = d / stripes;
                for (int i = 0; i < stripes; i++) {
                    paint.setColor((i % 2 == 0) ? color1 : color2);
                    c.drawRect(left, top + i * stripeH, right, top + (i + 1) * stripeH + 1f, paint);
                }
                paint.setColor(color3);
                c.drawRect(left, top, cx + r * 0.1f, cy + r * 0.1f, paint);
                break;
            }
        }

        // Subtle dark pill behind the ISO code for high legibility on any flag color
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(165, 15, 23, 42));
        float pillW = r * 1.28f;
        float pillH = r * 0.76f;
        RectF pillRect = new RectF(cx - pillW * 0.5f, cy - pillH * 0.5f, cx + pillW * 0.5f, cy + pillH * 0.5f);
        c.drawRoundRect(pillRect, pillH * 0.45f, pillH * 0.45f, paint);

        // Crisp country code text (e.g., BD, US, AR, BR, IN)
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(r * 0.62f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textBaseline = cy - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(countryCode, cx, textBaseline, paint);

        c.restore();

        // Outer metallic/white rim around the circular flag ball
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, size * 0.045f));
        paint.setColor(Color.argb(240, 255, 255, 255));
        c.drawCircle(cx, cy, r, paint);

        return bmp;
    }

    public void draw(Canvas canvas) {
        ensureAvatarBitmap();
        if (cachedAvatar != null) {
            if (Math.abs(impactScale - 1.0f) > 0.01f || Math.abs(rotationDeg) > 0.5f) {
                canvas.save();
                canvas.translate(x, y);
                canvas.scale(impactScale, impactScale);
                canvas.rotate(rotationDeg * 0.25f);
                canvas.drawBitmap(
                        cachedAvatar,
                        -cachedAvatar.getWidth() * 0.5f,
                        -cachedAvatar.getHeight() * 0.5f,
                        BITMAP_PAINT
                );
                canvas.restore();
            } else {
                canvas.drawBitmap(
                        cachedAvatar,
                        x - cachedAvatar.getWidth() * 0.5f,
                        y - cachedAvatar.getHeight() * 0.5f,
                        BITMAP_PAINT
                );
            }
        }
    }

    private static final CountrySpec[] ALL_195_SPECS = new CountrySpec[]{
            new CountrySpec("BD", "Bangladesh", "#006A4E", "#F42A41", "#006A4E", PatternType.CENTER_CIRCLE),
            new CountrySpec("US", "United States", "#B22234", "#FFFFFF", "#3C3B6E", PatternType.CANTON),
            new CountrySpec("AR", "Argentina", "#74ACDF", "#FFFFFF", "#74ACDF", PatternType.HORIZONTAL_3),
            new CountrySpec("BR", "Brazil", "#009C3B", "#FFDF00", "#002776", PatternType.CENTER_CIRCLE),
            new CountrySpec("IN", "India", "#FF9933", "#FFFFFF", "#138808", PatternType.HORIZONTAL_3),
            new CountrySpec("GB", "United Kingdom", "#012169", "#FFFFFF", "#C8102E", PatternType.NORDIC_CROSS),
            new CountrySpec("CA", "Canada", "#FF0000", "#FFFFFF", "#FF0000", PatternType.VERTICAL_3),
            new CountrySpec("JP", "Japan", "#FFFFFF", "#BC002D", "#FFFFFF", PatternType.CENTER_CIRCLE),
            new CountrySpec("DE", "Germany", "#000000", "#DD0000", "#FFCE00", PatternType.HORIZONTAL_3),
            new CountrySpec("FR", "France", "#0055A4", "#FFFFFF", "#EF4135", PatternType.VERTICAL_3),
            new CountrySpec("IT", "Italy", "#009246", "#FFFFFF", "#CE2B37", PatternType.VERTICAL_3),
            new CountrySpec("ES", "Spain", "#AA151B", "#F1BF00", "#AA151B", PatternType.HORIZONTAL_3),
            new CountrySpec("PT", "Portugal", "#006600", "#FF0000", "#FFCC00", PatternType.VERTICAL_2),
            new CountrySpec("NL", "Netherlands", "#AE1C28", "#FFFFFF", "#21468B", PatternType.HORIZONTAL_3),
            new CountrySpec("BE", "Belgium", "#000000", "#FDDA24", "#EF3340", PatternType.VERTICAL_3),
            new CountrySpec("CH", "Switzerland", "#DA291C", "#FFFFFF", "#DA291C", PatternType.NORDIC_CROSS),
            new CountrySpec("AT", "Austria", "#ED2939", "#FFFFFF", "#ED2939", PatternType.HORIZONTAL_3),
            new CountrySpec("SE", "Sweden", "#006AA7", "#FECC02", "#006AA7", PatternType.NORDIC_CROSS),
            new CountrySpec("NO", "Norway", "#BA0C2F", "#FFFFFF", "#00205B", PatternType.NORDIC_CROSS),
            new CountrySpec("DK", "Denmark", "#C8102E", "#FFFFFF", "#C8102E", PatternType.NORDIC_CROSS),
            new CountrySpec("FI", "Finland", "#FFFFFF", "#002F6C", "#FFFFFF", PatternType.NORDIC_CROSS),
            new CountrySpec("IS", "Iceland", "#02529C", "#DC1E35", "#FFFFFF", PatternType.NORDIC_CROSS),
            new CountrySpec("IE", "Ireland", "#169B62", "#FFFFFF", "#FF883E", PatternType.VERTICAL_3),
            new CountrySpec("PL", "Poland", "#FFFFFF", "#DC143C", "#FFFFFF", PatternType.HORIZONTAL_2),
            new CountrySpec("UA", "Ukraine", "#0057B7", "#FFD700", "#0057B7", PatternType.HORIZONTAL_2),
            new CountrySpec("GR", "Greece", "#0D5EAF", "#FFFFFF", "#0D5EAF", PatternType.CANTON),
            new CountrySpec("TR", "Turkey", "#E30A17", "#FFFFFF", "#E30A17", PatternType.CENTER_CIRCLE),
            new CountrySpec("SA", "Saudi Arabia", "#006C35", "#FFFFFF", "#006C35", PatternType.CENTER_CIRCLE),
            new CountrySpec("AE", "United Arab Emirates", "#00732F", "#FFFFFF", "#000000", PatternType.HORIZONTAL_3),
            new CountrySpec("EG", "Egypt", "#CE1126", "#FFFFFF", "#000000", PatternType.HORIZONTAL_3),
            new CountrySpec("ZA", "South Africa", "#E03C31", "#007749", "#001489", PatternType.DIAGONAL),
            new CountrySpec("NG", "Nigeria", "#008751", "#FFFFFF", "#008751", PatternType.VERTICAL_3),
            new CountrySpec("KE", "Kenya", "#000000", "#BB0000", "#006600", PatternType.HORIZONTAL_3),
            new CountrySpec("GH", "Ghana", "#CE1126", "#FCD116", "#006B3F", PatternType.HORIZONTAL_3),
            new CountrySpec("MA", "Morocco", "#C1272D", "#006233", "#C1272D", PatternType.CENTER_CIRCLE),
            new CountrySpec("DZ", "Algeria", "#006233", "#FFFFFF", "#D21034", PatternType.VERTICAL_2),
            new CountrySpec("TN", "Tunisia", "#E70013", "#FFFFFF", "#E70013", PatternType.CENTER_CIRCLE),
            new CountrySpec("ET", "Ethiopia", "#078930", "#FCDD09", "#DA121A", PatternType.HORIZONTAL_3),
            new CountrySpec("SN", "Senegal", "#00853F", "#FDEF42", "#E31B23", PatternType.VERTICAL_3),
            new CountrySpec("CM", "Cameroon", "#007A5E", "#CE1126", "#FCD116", PatternType.VERTICAL_3),
            new CountrySpec("CI", "Ivory Coast", "#F77F00", "#FFFFFF", "#009E60", PatternType.VERTICAL_3),
            new CountrySpec("AU", "Australia", "#00008B", "#FFFFFF", "#FF0000", PatternType.CANTON),
            new CountrySpec("NZ", "New Zealand", "#00247D", "#CC142B", "#FFFFFF", PatternType.CANTON),
            new CountrySpec("CN", "China", "#EE1C25", "#FFFF00", "#EE1C25", PatternType.CANTON),
            new CountrySpec("KR", "South Korea", "#FFFFFF", "#CD2E3A", "#0047A0", PatternType.CENTER_CIRCLE),
            new CountrySpec("KP", "North Korea", "#024FA2", "#ED1C27", "#FFFFFF", PatternType.HORIZONTAL_3),
            new CountrySpec("PK", "Pakistan", "#01411C", "#FFFFFF", "#01411C", PatternType.VERTICAL_2),
            new CountrySpec("ID", "Indonesia", "#FF0000", "#FFFFFF", "#FF0000", PatternType.HORIZONTAL_2),
            new CountrySpec("MY", "Malaysia", "#CC0001", "#FFFFFF", "#010066", PatternType.CANTON),
            new CountrySpec("SG", "Singapore", "#EF3340", "#FFFFFF", "#EF3340", PatternType.HORIZONTAL_2),
            new CountrySpec("TH", "Thailand", "#A51931", "#2D2A4A", "#F4F5F8", PatternType.HORIZONTAL_3),
            new CountrySpec("VN", "Vietnam", "#DA251D", "#FFFF00", "#DA251D", PatternType.CENTER_CIRCLE),
            new CountrySpec("PH", "Philippines", "#0038A8", "#CE1126", "#FCD116", PatternType.HORIZONTAL_2),
            new CountrySpec("MM", "Myanmar", "#FECB00", "#34B233", "#EA2839", PatternType.HORIZONTAL_3),
            new CountrySpec("LK", "Sri Lanka", "#FFBE29", "#8D153A", "#00534E", PatternType.VERTICAL_2),
            new CountrySpec("NP", "Nepal", "#DC143C", "#003893", "#FFFFFF", PatternType.DIAGONAL),
            new CountrySpec("AF", "Afghanistan", "#000000", "#D32011", "#007A36", PatternType.VERTICAL_3),
            new CountrySpec("IR", "Iran", "#239F40", "#FFFFFF", "#DA0000", PatternType.HORIZONTAL_3),
            new CountrySpec("IQ", "Iraq", "#CE1126", "#FFFFFF", "#000000", PatternType.HORIZONTAL_3),
            new CountrySpec("SY", "Syria", "#CE1126", "#FFFFFF", "#000000", PatternType.HORIZONTAL_3),
            new CountrySpec("JO", "Jordan", "#000000", "#FFFFFF", "#007A3D", PatternType.HORIZONTAL_3),
            new CountrySpec("LB", "Lebanon", "#ED1C24", "#FFFFFF", "#00A651", PatternType.HORIZONTAL_3),
            new CountrySpec("IL", "Israel", "#FFFFFF", "#0038B8", "#FFFFFF", PatternType.HORIZONTAL_3),
            new CountrySpec("PS", "Palestine", "#000000", "#FFFFFF", "#009736", PatternType.HORIZONTAL_3),
            new CountrySpec("KW", "Kuwait", "#007A3D", "#FFFFFF", "#CE1126", PatternType.HORIZONTAL_3),
            new CountrySpec("QA", "Qatar", "#8A1538", "#FFFFFF", "#8A1538", PatternType.VERTICAL_2),
            new CountrySpec("BH", "Bahrain", "#CE1126", "#FFFFFF", "#CE1126", PatternType.VERTICAL_2),
            new CountrySpec("OM", "Oman", "#DB161B", "#FFFFFF", "#008000", PatternType.HORIZONTAL_3),
            new CountrySpec("YE", "Yemen", "#CE1126", "#FFFFFF", "#000000", PatternType.HORIZONTAL_3),
            new CountrySpec("MX", "Mexico", "#006847", "#FFFFFF", "#CE1126", PatternType.VERTICAL_3),
            new CountrySpec("CO", "Colombia", "#FCD116", "#003893", "#CE1126", PatternType.HORIZONTAL_3),
            new CountrySpec("VE", "Venezuela", "#FFCC00", "#00247D", "#CF142B", PatternType.HORIZONTAL_3),
            new CountrySpec("EC", "Ecuador", "#FFD100", "#0072CE", "#EF3340", PatternType.HORIZONTAL_3),
            new CountrySpec("PE", "Peru", "#D91023", "#FFFFFF", "#D91023", PatternType.VERTICAL_3),
            new CountrySpec("CL", "Chile", "#FFFFFF", "#D52B1E", "#0039A6", PatternType.CANTON),
            new CountrySpec("UY", "Uruguay", "#0038A8", "#FFFFFF", "#FCD116", PatternType.CANTON),
            new CountrySpec("PY", "Paraguay", "#D52B1E", "#FFFFFF", "#0038A8", PatternType.HORIZONTAL_3),
            new CountrySpec("BO", "Bolivia", "#D52B1E", "#F9E300", "#007934", PatternType.HORIZONTAL_3),
            new CountrySpec("CU", "Cuba", "#002A8F", "#FFFFFF", "#CB1515", PatternType.DIAGONAL),
            new CountrySpec("DO", "Dominican Republic", "#002D62", "#FFFFFF", "#CE1126", PatternType.NORDIC_CROSS),
            new CountrySpec("HT", "Haiti", "#00209F", "#D21034", "#00209F", PatternType.HORIZONTAL_2),
            new CountrySpec("JM", "Jamaica", "#009B3A", "#FED100", "#000000", PatternType.DIAGONAL),
            new CountrySpec("CR", "Costa Rica", "#002B7F", "#CE1126", "#002B7F", PatternType.HORIZONTAL_3),
            new CountrySpec("PA", "Panama", "#FFFFFF", "#DA121A", "#072357", PatternType.VERTICAL_2),
            new CountrySpec("GT", "Guatemala", "#4997D0", "#FFFFFF", "#4997D0", PatternType.VERTICAL_3),
            new CountrySpec("HN", "Honduras", "#0073CF", "#FFFFFF", "#0073CF", PatternType.HORIZONTAL_3),
            new CountrySpec("SV", "El Salvador", "#0F47AF", "#FFFFFF", "#0F47AF", PatternType.HORIZONTAL_3),
            new CountrySpec("NI", "Nicaragua", "#0067C6", "#FFFFFF", "#0067C6", PatternType.HORIZONTAL_3),
            new CountrySpec("RU", "Russia", "#FFFFFF", "#0039A6", "#D52B1E", PatternType.HORIZONTAL_3),
            new CountrySpec("KZ", "Kazakhstan", "#00AFCA", "#FEC50C", "#00AFCA", PatternType.CENTER_CIRCLE),
            new CountrySpec("UZ", "Uzbekistan", "#0099B5", "#FFFFFF", "#1EB53A", PatternType.HORIZONTAL_3),
            new CountrySpec("TM", "Turkmenistan", "#00843D", "#D22630", "#FFFFFF", PatternType.VERTICAL_2),
            new CountrySpec("KG", "Kyrgyzstan", "#E8112D", "#FFEF00", "#E8112D", PatternType.CENTER_CIRCLE),
            new CountrySpec("TJ", "Tajikistan", "#CC0000", "#FFFFFF", "#006600", PatternType.HORIZONTAL_3),
            new CountrySpec("AZ", "Azerbaijan", "#00B5E2", "#EF3340", "#509E2F", PatternType.HORIZONTAL_3),
            new CountrySpec("AM", "Armenia", "#D90012", "#0033A0", "#F2A800", PatternType.HORIZONTAL_3),
            new CountrySpec("GE", "Georgia", "#FFFFFF", "#FF0000", "#FFFFFF", PatternType.NORDIC_CROSS),
            new CountrySpec("BY", "Belarus", "#D22730", "#009739", "#FFFFFF", PatternType.HORIZONTAL_2),
            new CountrySpec("MD", "Moldova", "#0046AE", "#FFD200", "#CC092F", PatternType.VERTICAL_3),
            new CountrySpec("RO", "Romania", "#002B7F", "#FCD116", "#CE1126", PatternType.VERTICAL_3),
            new CountrySpec("BG", "Bulgaria", "#FFFFFF", "#00966E", "#D62612", PatternType.HORIZONTAL_3),
            new CountrySpec("HU", "Hungary", "#CE2939", "#FFFFFF", "#477050", PatternType.HORIZONTAL_3),
            new CountrySpec("CZ", "Czechia", "#FFFFFF", "#D7141A", "#11457E", PatternType.HORIZONTAL_2),
            new CountrySpec("SK", "Slovakia", "#FFFFFF", "#0B4EA2", "#EE1C25", PatternType.HORIZONTAL_3),
            new CountrySpec("SI", "Slovenia", "#FFFFFF", "#005CE6", "#ED1C24", PatternType.HORIZONTAL_3),
            new CountrySpec("HR", "Croatia", "#FF0000", "#FFFFFF", "#171796", PatternType.HORIZONTAL_3),
            new CountrySpec("BA", "Bosnia and Herzegovina", "#002395", "#FECB00", "#002395", PatternType.DIAGONAL),
            new CountrySpec("RS", "Serbia", "#C6363C", "#0C4076", "#FFFFFF", PatternType.HORIZONTAL_3),
            new CountrySpec("ME", "Montenegro", "#C40308", "#D4AF37", "#C40308", PatternType.CENTER_CIRCLE),
            new CountrySpec("MK", "North Macedonia", "#D20000", "#FFE600", "#D20000", PatternType.CENTER_CIRCLE),
            new CountrySpec("AL", "Albania", "#E41E20", "#000000", "#E41E20", PatternType.CENTER_CIRCLE),
            new CountrySpec("EE", "Estonia", "#0072CE", "#000000", "#FFFFFF", PatternType.HORIZONTAL_3),
            new CountrySpec("LV", "Latvia", "#9E3039", "#FFFFFF", "#9E3039", PatternType.HORIZONTAL_3),
            new CountrySpec("LT", "Lithuania", "#FDB913", "#006A44", "#C1272D", PatternType.HORIZONTAL_3),
            new CountrySpec("LU", "Luxembourg", "#EA141D", "#FFFFFF", "#00A3E0", PatternType.HORIZONTAL_3),
            new CountrySpec("MT", "Malta", "#FFFFFF", "#CF142B", "#FFFFFF", PatternType.VERTICAL_2),
            new CountrySpec("CY", "Cyprus", "#FFFFFF", "#D57800", "#4E5B31", PatternType.CENTER_CIRCLE),
            new CountrySpec("MC", "Monaco", "#CE1126", "#FFFFFF", "#CE1126", PatternType.HORIZONTAL_2),
            new CountrySpec("AD", "Andorra", "#10069F", "#FEDD00", "#D50032", PatternType.VERTICAL_3),
            new CountrySpec("SM", "San Marino", "#FFFFFF", "#5EB6E4", "#FFFFFF", PatternType.HORIZONTAL_2),
            new CountrySpec("LI", "Liechtenstein", "#002B7F", "#CE1126", "#FFD800", PatternType.HORIZONTAL_2),
            new CountrySpec("VA", "Vatican City", "#FFE000", "#FFFFFF", "#FFE000", PatternType.VERTICAL_2),
            new CountrySpec("MN", "Mongolia", "#C4272F", "#015197", "#C4272F", PatternType.VERTICAL_3),
            new CountrySpec("KH", "Cambodia", "#032EA1", "#E00025", "#032EA1", PatternType.HORIZONTAL_3),
            new CountrySpec("LA", "Laos", "#CE1126", "#002868", "#CE1126", PatternType.HORIZONTAL_3),
            new CountrySpec("BN", "Brunei", "#F7E017", "#FFFFFF", "#000000", PatternType.DIAGONAL),
            new CountrySpec("TL", "Timor-Leste", "#DA291C", "#FFC72C", "#000000", PatternType.DIAGONAL),
            new CountrySpec("MV", "Maldives", "#D21034", "#007E3A", "#FFFFFF", PatternType.CENTER_CIRCLE),
            new CountrySpec("BT", "Bhutan", "#FFCC33", "#FF4E12", "#FFFFFF", PatternType.DIAGONAL),
            new CountrySpec("SD", "Sudan", "#D21034", "#FFFFFF", "#000000", PatternType.HORIZONTAL_3),
            new CountrySpec("SS", "South Sudan", "#000000", "#DA121A", "#078930", PatternType.HORIZONTAL_3),
            new CountrySpec("LY", "Libya", "#E70013", "#000000", "#239E46", PatternType.HORIZONTAL_3),
            new CountrySpec("MR", "Mauritania", "#00A95C", "#FFD700", "#D01C1F", PatternType.HORIZONTAL_3),
            new CountrySpec("ML", "Mali", "#14B53A", "#FCD116", "#CE1126", PatternType.VERTICAL_3),
            new CountrySpec("NE", "Niger", "#E05206", "#FFFFFF", "#0DB02B", PatternType.HORIZONTAL_3),
            new CountrySpec("TD", "Chad", "#002664", "#FECB00", "#C60C30", PatternType.VERTICAL_3),
            new CountrySpec("BF", "Burkina Faso", "#EF2B2D", "#009E49", "#FCD116", PatternType.HORIZONTAL_2),
            new CountrySpec("GN", "Guinea", "#CE1126", "#FCD116", "#009460", PatternType.VERTICAL_3),
            new CountrySpec("SL", "Sierra Leone", "#1EB53A", "#FFFFFF", "#0072C6", PatternType.HORIZONTAL_3),
            new CountrySpec("LR", "Liberia", "#BF0A30", "#FFFFFF", "#002868", PatternType.CANTON),
            new CountrySpec("TG", "Togo", "#006A4E", "#FFCE00", "#D21034", PatternType.CANTON),
            new CountrySpec("BJ", "Benin", "#008751", "#FCD116", "#E8112D", PatternType.HORIZONTAL_2),
            new CountrySpec("GW", "Guinea-Bissau", "#CE1126", "#FCD116", "#009E49", PatternType.VERTICAL_2),
            new CountrySpec("CV", "Cabo Verde", "#003893", "#CF2027", "#F7D116", PatternType.HORIZONTAL_3),
            new CountrySpec("GM", "Gambia", "#CE1126", "#0C1C8C", "#3A7728", PatternType.HORIZONTAL_3),
            new CountrySpec("GA", "Gabon", "#009E60", "#FCD116", "#3A75C4", PatternType.HORIZONTAL_3),
            new CountrySpec("CG", "Republic of the Congo", "#009543", "#FBDE4A", "#DC241F", PatternType.DIAGONAL),
            new CountrySpec("CD", "DR Congo", "#007FFF", "#F7D618", "#CE1021", PatternType.DIAGONAL),
            new CountrySpec("CF", "Central African Republic", "#003082", "#FFFFFF", "#289728", PatternType.HORIZONTAL_3),
            new CountrySpec("GQ", "Equatorial Guinea", "#3E9A00", "#FFFFFF", "#E32118", PatternType.HORIZONTAL_3),
            new CountrySpec("ST", "Sao Tome and Principe", "#12AD2B", "#FFCE00", "#D21034", PatternType.HORIZONTAL_3),
            new CountrySpec("AO", "Angola", "#CC092F", "#000000", "#FFCB00", PatternType.HORIZONTAL_2),
            new CountrySpec("NA", "Namibia", "#003580", "#D21034", "#009543", PatternType.DIAGONAL),
            new CountrySpec("BW", "Botswana", "#6DA9D2", "#000000", "#6DA9D2", PatternType.HORIZONTAL_3),
            new CountrySpec("ZW", "Zimbabwe", "#006400", "#FFD200", "#D40000", PatternType.HORIZONTAL_3),
            new CountrySpec("ZM", "Zambia", "#198A00", "#DE2010", "#EF7D00", PatternType.VERTICAL_2),
            new CountrySpec("MW", "Malawi", "#000000", "#CE1126", "#339E35", PatternType.HORIZONTAL_3),
            new CountrySpec("MZ", "Mozambique", "#007168", "#000000", "#FCE100", PatternType.HORIZONTAL_3),
            new CountrySpec("MG", "Madagascar", "#FC3D32", "#007E3A", "#FFFFFF", PatternType.HORIZONTAL_2),
            new CountrySpec("MU", "Mauritius", "#EA2839", "#1A206D", "#FFD500", PatternType.HORIZONTAL_3),
            new CountrySpec("SC", "Seychelles", "#003F87", "#FCD856", "#D62828", PatternType.DIAGONAL),
            new CountrySpec("KM", "Comoros", "#FFD100", "#FFFFFF", "#CE1126", PatternType.HORIZONTAL_3),
            new CountrySpec("TZ", "Tanzania", "#1EB53A", "#FCD116", "#00A3DD", PatternType.DIAGONAL),
            new CountrySpec("UG", "Uganda", "#000000", "#FCDC04", "#D90000", PatternType.HORIZONTAL_3),
            new CountrySpec("RW", "Rwanda", "#00A1DE", "#FAD201", "#20603D", PatternType.HORIZONTAL_3),
            new CountrySpec("BI", "Burundi", "#CE1126", "#FFFFFF", "#1EB53A", PatternType.DIAGONAL),
            new CountrySpec("SO", "Somalia", "#4189DD", "#FFFFFF", "#4189DD", PatternType.CENTER_CIRCLE),
            new CountrySpec("DJ", "Djibouti", "#6AB2E7", "#12AD2B", "#FFFFFF", PatternType.HORIZONTAL_2),
            new CountrySpec("ER", "Eritrea", "#4189DD", "#EA0437", "#12AD2B", PatternType.DIAGONAL),
            new CountrySpec("SZ", "Eswatini", "#3E5EB9", "#B10C0C", "#FFD900", PatternType.HORIZONTAL_3),
            new CountrySpec("LS", "Lesotho", "#00209F", "#FFFFFF", "#009543", PatternType.HORIZONTAL_3),
            new CountrySpec("BS", "Bahamas", "#00778B", "#FFC72C", "#00778B", PatternType.HORIZONTAL_3),
            new CountrySpec("BB", "Barbados", "#00267F", "#FFC726", "#00267F", PatternType.VERTICAL_3),
            new CountrySpec("TT", "Trinidad and Tobago", "#CE1126", "#FFFFFF", "#000000", PatternType.DIAGONAL),
            new CountrySpec("GY", "Guyana", "#009E49", "#FCD116", "#CE1126", PatternType.DIAGONAL),
            new CountrySpec("SR", "Suriname", "#377E3F", "#B40A2D", "#ECC81D", PatternType.HORIZONTAL_3),
            new CountrySpec("BZ", "Belize", "#003F87", "#CE1126", "#FFFFFF", PatternType.CENTER_CIRCLE),
            new CountrySpec("AG", "Antigua and Barbuda", "#CE1126", "#0072C6", "#FCD116", PatternType.HORIZONTAL_3),
            new CountrySpec("DM", "Dominica", "#006B3F", "#FCD116", "#D41C30", PatternType.NORDIC_CROSS),
            new CountrySpec("GD", "Grenada", "#CE1126", "#FCD116", "#007A5E", PatternType.DIAGONAL),
            new CountrySpec("KN", "Saint Kitts and Nevis", "#009E49", "#000000", "#CE1126", PatternType.DIAGONAL),
            new CountrySpec("LC", "Saint Lucia", "#66CCFF", "#FCD116", "#000000", PatternType.CENTER_CIRCLE),
            new CountrySpec("VC", "Saint Vincent and the Grenadines", "#0072C6", "#FCD116", "#009E60", PatternType.VERTICAL_3),
            new CountrySpec("FJ", "Fiji", "#68BFE5", "#002868", "#CE1126", PatternType.CANTON),
            new CountrySpec("PG", "Papua New Guinea", "#CE1126", "#FCD116", "#000000", PatternType.DIAGONAL),
            new CountrySpec("SB", "Solomon Islands", "#0051BA", "#FCD116", "#215B33", PatternType.DIAGONAL),
            new CountrySpec("VU", "Vanuatu", "#D21034", "#FDCE12", "#009543", PatternType.HORIZONTAL_2),
            new CountrySpec("WS", "Samoa", "#CE1126", "#FFFFFF", "#002B7F", PatternType.CANTON),
            new CountrySpec("TO", "Tonga", "#C10000", "#FFFFFF", "#C10000", PatternType.CANTON),
            new CountrySpec("TV", "Tuvalu", "#009FCA", "#FFCE00", "#00247D", PatternType.CANTON),
            new CountrySpec("KI", "Kiribati", "#CE1126", "#FCD116", "#003F87", PatternType.HORIZONTAL_2),
            new CountrySpec("FM", "Micronesia", "#75B2DD", "#FFFFFF", "#75B2DD", PatternType.CENTER_CIRCLE),
            new CountrySpec("MH", "Marshall Islands", "#003893", "#DD7500", "#FFFFFF", PatternType.DIAGONAL),
            new CountrySpec("PW", "Palau", "#0099FF", "#FFFF00", "#0099FF", PatternType.CENTER_CIRCLE),
            new CountrySpec("NR", "Nauru", "#002B7F", "#FFC61E", "#002B7F", PatternType.HORIZONTAL_3)
    };

    public static List<FlagBall> createAll195Countries(
            float arenaCenterX,
            float arenaCenterY,
            float arenaRadius,
            float ballRadius,
            float baseSpeed,
            Random random
    ) {
        List<FlagBall> balls = new ArrayList<>(TOTAL_COUNTRIES);

        List<CountrySpec> specs = new ArrayList<>(TOTAL_COUNTRIES);
        for (int i = 0; i < TOTAL_COUNTRIES; i++) {
            specs.add(ALL_195_SPECS[i % ALL_195_SPECS.length]);
        }
        for (int i = specs.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            CountrySpec tmp = specs.get(i);
            specs.set(i, specs.get(j));
            specs.set(j, tmp);
        }

        int index = 0;
        float ringSpacing = ballRadius * 2.18f;
        int ring = 0;

        while (index < TOTAL_COUNTRIES) {
            if (ring == 0) {
                CountrySpec spec = specs.get(index++);
                double angle = random.nextDouble() * Math.PI * 2.0;
                float speed = baseSpeed * (0.90f + random.nextFloat() * 0.30f);
                balls.add(new FlagBall(
                        spec.code,
                        spec.name,
                        spec.color1,
                        spec.color2,
                        spec.color3,
                        spec.pattern,
                        arenaCenterX,
                        arenaCenterY,
                        (float) (Math.cos(angle) * speed),
                        (float) (Math.sin(angle) * speed),
                        ballRadius
                ));
            } else {
                float rDist = Math.min(ring * ringSpacing, arenaRadius - ballRadius * 2.1f);
                int capacity = Math.max(6, (int) Math.floor((2.0 * Math.PI * rDist) / (ballRadius * 2.12f)));
                double angleOffset = random.nextDouble() * Math.PI * 2.0;
                for (int k = 0; k < capacity && index < TOTAL_COUNTRIES; k++) {
                    CountrySpec spec = specs.get(index++);
                    double posAngle = angleOffset + (2.0 * Math.PI * k) / capacity;
                    float jitterR = (random.nextFloat() - 0.5f) * (ballRadius * 0.15f);
                    float bx = arenaCenterX + (float) (Math.cos(posAngle) * (rDist + jitterR));
                    float by = arenaCenterY + (float) (Math.sin(posAngle) * (rDist + jitterR));

                    double velAngle = random.nextDouble() * Math.PI * 2.0;
                    float speed = baseSpeed * (0.90f + random.nextFloat() * 0.30f);
                    balls.add(new FlagBall(
                            spec.code,
                            spec.name,
                            spec.color1,
                            spec.color2,
                            spec.color3,
                            spec.pattern,
                            bx,
                            by,
                            (float) (Math.cos(velAngle) * speed),
                            (float) (Math.sin(velAngle) * speed),
                            ballRadius
                    ));
                }
            }
            ring++;
        }

        return balls;
    }
}
