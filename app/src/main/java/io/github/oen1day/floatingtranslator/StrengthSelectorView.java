package io.github.oen1day.floatingtranslator;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class StrengthSelectorView extends View {
    public interface Listener {
        void onStrengthChanged(int position);
    }

    private static final long FRAME_DELAY_MS = 33L;
    private static final int MAX_PARTICLES = 96;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactiveTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator colorEvaluator = new ArgbEvaluator();
    private final RectF trackRect = new RectF();
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private final int touchSlop;
    private final int activeTextColor;
    private final int inactiveTextColor;
    private final int accentColor;
    private final int accentDarkColor;

    private String[] labels = TranslationStrength.BASE_LABELS;
    private Listener listener;
    private int selectedIndex;
    private float indicatorPosition;
    private float downX;
    private boolean dragging;
    private ValueAnimator indicatorAnimator;

    private float trackLeft;
    private float trackRight;
    private float trackCenterY;
    private float trackRadius;
    private float thumbRadius;
    private float tailLength;
    private long lastFrameNanos;
    private float spawnRemainder;

    public StrengthSelectorView(Context context) {
        this(context, null);
    }

    public StrengthSelectorView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StrengthSelectorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        activeTextColor = context.getColor(R.color.aqua);
        inactiveTextColor = context.getColor(R.color.paper_muted);
        accentColor = context.getColor(R.color.aqua);
        accentDarkColor = context.getColor(R.color.aqua_dark);

        trackPaint.setColor(context.getColor(R.color.ink_800));
        fillPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(Color.argb(54, 85, 224, 226));
        thumbPaint.setColor(Color.WHITE);
        thumbBorderPaint.setStyle(Paint.Style.STROKE);
        thumbBorderPaint.setStrokeWidth(dp(1.5f));
        thumbBorderPaint.setColor(Color.argb(210, 85, 224, 226));
        particlePaint.setStyle(Paint.Style.FILL);

        configureTextPaint(activeTextPaint);
        configureTextPaint(inactiveTextPaint);
        setClickable(true);
        setFocusable(true);
        setContentDescription("翻译强度，当前" + TranslationStrength.label(selectedIndex));
    }

    public void setLabels(String[] values) {
        labels = values == null || values.length == 0
                ? TranslationStrength.BASE_LABELS
                : values;
        selectedIndex = Math.min(selectedIndex, labels.length - 1);
        indicatorPosition = selectedIndex;
        invalidate();
    }

    public void setListener(Listener value) {
        listener = value;
    }

    public void setSelection(int position, boolean animate) {
        int target = Math.max(0, Math.min(labels.length - 1, position));
        selectedIndex = target;
        setContentDescription("翻译强度，当前" + labels[target]);
        if (indicatorAnimator != null) {
            indicatorAnimator.cancel();
        }
        if (!animate || !isAttachedToWindow()) {
            indicatorPosition = target;
            invalidate();
            return;
        }
        indicatorAnimator = ValueAnimator.ofFloat(indicatorPosition, target);
        indicatorAnimator.setDuration(190L);
        indicatorAnimator.setInterpolator(new DecelerateInterpolator());
        indicatorAnimator.addUpdateListener(animation -> {
            indicatorPosition = (float) animation.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        indicatorAnimator.start();
    }

    public int getSelection() {
        return selectedIndex;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(dp(240f)), widthMeasureSpec);
        int height = resolveSize(Math.round(dp(68f)), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        trackLeft = dp(22f);
        trackRight = Math.max(trackLeft + dp(1f), width - dp(22f));
        trackCenterY = height - dp(20f);
        trackRadius = dp(9f);
        thumbRadius = dp(14f);
        tailLength = dp(72f);
        fillPaint.setShader(new LinearGradient(
                trackLeft,
                0f,
                trackRight,
                0f,
                new int[]{accentDarkColor, accentColor},
                null,
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (trackRight <= trackLeft) {
            return;
        }

        updateParticles();
        float progress = progress();
        float thumbX = thumbXForProgress(progress);

        trackRect.set(
                trackLeft,
                trackCenterY - trackRadius,
                trackRight,
                trackCenterY + trackRadius
        );
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint);

        trackRect.right = Math.max(trackLeft, thumbX);
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, fillPaint);

        drawParticles(canvas, thumbX);
        drawThumb(canvas, thumbX);
        drawLabels(canvas);

        if (shouldAnimate()) {
            postInvalidateDelayed(FRAME_DELAY_MS);
        } else {
            lastFrameNanos = 0L;
        }
    }

    private void drawThumb(Canvas canvas, float thumbX) {
        glowPaint.setShader(new RadialGradient(
                thumbX,
                trackCenterY,
                thumbRadius * 2.8f,
                new int[]{Color.argb(70, 85, 224, 226), Color.TRANSPARENT},
                null,
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(thumbX, trackCenterY, thumbRadius * 2.8f, glowPaint);
        canvas.drawCircle(thumbX, trackCenterY, thumbRadius, thumbPaint);
        canvas.drawCircle(thumbX, trackCenterY, thumbRadius, thumbBorderPaint);
    }

    private void drawParticles(Canvas canvas, float thumbX) {
        int saved = canvas.save();
        canvas.clipRect(trackLeft, 0f, Math.max(trackLeft, thumbX), getHeight());
        for (Particle particle : particles) {
            int alpha = Math.max(
                    0,
                    Math.min(255, Math.round(particle.alpha * 255f))
            );
            int color = (int) colorEvaluator.evaluate(
                    particle.whiteMix,
                    accentColor,
                    Color.WHITE
            );
            particlePaint.setColor(Color.argb(
                    alpha,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
            ));
            canvas.drawCircle(
                    particle.x,
                    particle.y,
                    Math.max(dp(0.55f), particle.size),
                    particlePaint
            );
        }
        canvas.restoreToCount(saved);
    }

    private void drawLabels(Canvas canvas) {
        for (int i = 0; i < labels.length; i++) {
            float center = stationX(i);
            float distance = Math.min(1f, Math.abs(indicatorPosition - i));
            float focus = 1f - distance;
            int color = (int) colorEvaluator.evaluate(
                    focus,
                    inactiveTextColor,
                    activeTextColor
            );
            activeTextPaint.setColor(color);
            inactiveTextPaint.setColor(color);
            float textSize = sp(11f + focus * 1f);
            activeTextPaint.setTextSize(textSize);
            inactiveTextPaint.setTextSize(textSize);
            Typeface typeface = Typeface.create(
                    "sans-serif",
                    focus > 0.55f ? Typeface.BOLD : Typeface.NORMAL
            );
            activeTextPaint.setTypeface(typeface);
            inactiveTextPaint.setTypeface(typeface);
            float baseline = dp(16f) - focus * dp(1f);
            Paint paint = focus >= 0.5f ? activeTextPaint : inactiveTextPaint;
            canvas.drawText(labels[i], center, baseline, paint);
        }
    }

    private void updateParticles() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return;
        }
        float deltaSeconds = Math.min(
                0.05f,
                (now - lastFrameNanos) / 1_000_000_000f
        );
        lastFrameNanos = now;

        float progress = progress();
        float thumbX = thumbXForProgress(progress);
        float density = 12f + progress * 64f;
        spawnRemainder += density * deltaSeconds;
        while (spawnRemainder >= 1f && particles.size() < MAX_PARTICLES) {
            spawnRemainder -= 1f;
            spawnParticle(thumbX, progress);
        }
        if (particles.size() >= MAX_PARTICLES) {
            spawnRemainder = Math.min(spawnRemainder, 1f);
        }

        float minX = thumbX - tailLength;
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle particle = particles.get(i);
            particle.life -= deltaSeconds;
            particle.x += particle.velocityX * deltaSeconds;
            particle.y += particle.velocityY * deltaSeconds;
            particle.velocityY += dp(5f) * deltaSeconds;
            particle.alpha = Math.max(
                    0f,
                    particle.life / particle.maxLife * particle.baseAlpha
            );
            if (particle.life <= 0f
                    || particle.x < minX
                    || particle.x < trackLeft
                    || particle.x > thumbX) {
                particles.remove(i);
            }
        }
    }

    private void spawnParticle(float thumbX, float progress) {
        Particle particle = new Particle();
        particle.x = thumbX - dp(1f + random.nextFloat() * 4f);
        particle.y = trackCenterY + dp((random.nextFloat() - 0.5f) * 10f);
        particle.velocityX = -dp(56f + random.nextFloat() * (38f + progress * 18f));
        particle.velocityY = dp((random.nextFloat() - 0.5f) * 16f);
        particle.size = dp(0.7f + random.nextFloat() * 1.55f);
        particle.maxLife = 0.46f + random.nextFloat() * 0.38f;
        particle.life = particle.maxLife;
        particle.baseAlpha = 0.38f + random.nextFloat() * 0.5f;
        particle.alpha = particle.baseAlpha;
        particle.whiteMix = random.nextFloat() * 0.36f;
        particles.add(particle);
    }

    private boolean shouldAnimate() {
        return isAttachedToWindow()
                && isShown()
                && getVisibility() == View.VISIBLE
                && getAlpha() > 0.01f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                downX = event.getX();
                dragging = false;
                if (indicatorAnimator != null) {
                    indicatorAnimator.cancel();
                }
                indicatorPosition = positionForX(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop) {
                    dragging = true;
                }
                indicatorPosition = positionForX(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int target = Math.round(positionForX(event.getX()));
                target = Math.max(0, Math.min(labels.length - 1, target));
                boolean changed = target != selectedIndex;
                setSelection(target, true);
                if (changed && listener != null) {
                    listener.onStrengthChanged(target);
                    performHapticFeedback(
                            android.view.HapticFeedbackConstants.CLOCK_TICK
                    );
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setSelection(selectedIndex, true);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNanos = 0L;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        particles.clear();
        spawnRemainder = 0f;
        lastFrameNanos = 0L;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        lastFrameNanos = 0L;
        if (visibility == View.VISIBLE) {
            postInvalidateOnAnimation();
        }
    }

    private float progress() {
        return labels.length <= 1
                ? 0f
                : Math.max(
                        0f,
                        Math.min(
                                1f,
                                indicatorPosition / (labels.length - 1f)
                        )
                );
    }

    private float thumbXForProgress(float progress) {
        return trackLeft + (trackRight - trackLeft) * progress;
    }

    private float stationX(int index) {
        if (labels.length <= 1) {
            return (trackLeft + trackRight) / 2f;
        }
        return thumbXForProgress(index / (labels.length - 1f));
    }

    private float positionForX(float x) {
        float width = Math.max(1f, trackRight - trackLeft);
        float fraction = (x - trackLeft) / width;
        return Math.max(
                0f,
                Math.min(labels.length - 1f, fraction * (labels.length - 1f))
        );
    }

    private void configureTextPaint(Paint paint) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(sp(11f));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private static class Particle {
        float x;
        float y;
        float velocityX;
        float velocityY;
        float size;
        float life;
        float maxLife;
        float alpha;
        float baseAlpha;
        float whiteMix;
    }
}
