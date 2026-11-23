package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class OverlayPanel {
    public interface Listener {
        void onTranslationChanged(boolean enabled);

        void onLanguagesChanged(String sourceMode, String targetLanguage);

        void onTranslationStrengthChanged(int strength);

        void onFullScreenRequested();

        void onRegionRequested();

        void onStopRequested();

        void onCollapsedChanged(boolean collapsed);
    }

    private static final String PREFS = "floating_translate_panel";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_OPACITY = "opacity";
    private static final String KEY_COLLAPSED = "collapsed";
    private static final int MIN_PANEL_WIDTH_DP = 150;
    private static final int MIN_PANEL_HEIGHT_DP = 120;
    private static final int EXPANDABLE_PANEL_WIDTH_DP = 280;
    private static final int EXPANDABLE_PANEL_HEIGHT_DP = 220;
    private static final int FULL_CONTROL_PANEL_WIDTH_DP = 300;
    private static final int FULL_CONTROL_PANEL_HEIGHT_DP = 340;

    private final Context context;
    private final WindowManager windowManager;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final View root;
    private final WindowManager.LayoutParams params;
    private final int windowType;
    private final TextView statusChip;
    private final TextView regionLabel;
    private final TextView resultText;
    private final TextView opacityValue;
    private final TextView resizeHandle;
    private final ImageView collapseButton;
    private final View panelContent;
    private final View titleBar;
    private final View controlsContainer;
    private final View resultFrame;
    private final Switch translationSwitch;
    private final Spinner sourceLanguageSpinner;
    private final Spinner targetLanguageSpinner;
    private final StrengthSelectorView translationStrengthSelector;

    private boolean attached;
    private boolean updatingSwitch;
    private boolean collapsed;
    private View collapsedTouchHandle;
    private WindowManager.LayoutParams collapsedTouchParams;
    private String sourceMode;
    private String targetLanguage;
    private int translationStrength;

    public OverlayPanel(
            Context context,
            WindowManager windowManager,
            int windowType,
            String initialSourceMode,
            String initialTargetLanguage,
            int initialTranslationStrength,
            Listener listener
    ) {
        this.context = context;
        this.windowManager = windowManager;
        this.windowType = windowType;
        this.listener = listener;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.root = LayoutInflater.from(context).inflate(R.layout.overlay_panel, null);
        UiText.localizeTree(root);

        statusChip = root.findViewById(R.id.status_chip);
        regionLabel = root.findViewById(R.id.region_label);
        resultText = root.findViewById(R.id.result_text);
        opacityValue = root.findViewById(R.id.opacity_value);
        resizeHandle = root.findViewById(R.id.resize_handle);
        collapseButton = root.findViewById(R.id.collapse_button);
        panelContent = root.findViewById(R.id.panel_content);
        titleBar = root.findViewById(R.id.title_bar);
        controlsContainer = root.findViewById(R.id.controls_container);
        resultFrame = root.findViewById(R.id.result_frame);
        translationSwitch = root.findViewById(R.id.translation_switch);
        sourceLanguageSpinner = root.findViewById(R.id.source_language_spinner);
        targetLanguageSpinner = root.findViewById(R.id.target_language_spinner);
        translationStrengthSelector = root.findViewById(
                R.id.translation_strength_spinner
        );

        sourceMode = validSource(initialSourceMode);
        targetLanguage = validTarget(initialTargetLanguage);
        translationStrength = TranslationStrength.clamp(initialTranslationStrength);

        Point screen = getScreenSize();
        int defaultWidth = Math.min(screen.x - dp(24), dp(430));
        int defaultHeight = Math.min((int) (screen.y * 0.58f), dp(620));
        int minWidth = minimumPanelWidth(screen);
        int minHeight = minimumPanelHeight(screen);

        int width = Math.max(minWidth, prefs.getInt(KEY_WIDTH, defaultWidth));
        int height = Math.max(minHeight, prefs.getInt(KEY_HEIGHT, defaultHeight));
        width = Math.min(width, screen.x - dp(12));
        height = Math.min(height, screen.y - dp(12));

        int x = prefs.getInt(KEY_X, Math.max(0, (screen.x - width) / 2));
        int y = prefs.getInt(KEY_Y, dp(72));
        x = clamp(x, 0, Math.max(0, screen.x - width));
        y = clamp(y, 0, Math.max(0, screen.y - height));

        int opacity = clamp(prefs.getInt(KEY_OPACITY, 92), 35, 100);

        params = new WindowManager.LayoutParams(
                width,
                height,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x;
        params.y = y;
        params.alpha = opacity / 100f;

        SeekBar opacitySlider = root.findViewById(R.id.opacity_slider);
        opacitySlider.setProgress(opacity - 35);
        opacityValue.setText(opacity + "%");
        opacitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int newOpacity = progress + 35;
                params.alpha = newOpacity / 100f;
                opacityValue.setText(newOpacity + "%");
                if (attached) {
                    windowManager.updateViewLayout(root, params);
                }
                prefs.edit().putInt(KEY_OPACITY, newOpacity).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        translationSwitch.setChecked(true);
        translationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingSwitch) {
                listener.onTranslationChanged(isChecked);
            }
        });

        setupLanguageSpinners();
        setupStrengthSelector();

        Button fullScreenButton = root.findViewById(R.id.full_screen_button);
        Button regionButton = root.findViewById(R.id.region_button);
        TextView closeButton = root.findViewById(R.id.close_button);
        fullScreenButton.setOnClickListener(v -> listener.onFullScreenRequested());
        regionButton.setOnClickListener(v -> listener.onRegionRequested());
        closeButton.setOnClickListener(v -> listener.onStopRequested());
        collapseButton.setOnClickListener(v -> toggleCollapsed());
        installPressFeedback(fullScreenButton);
        installPressFeedback(regionButton);
        installPressFeedback(closeButton);

        installDragHandler(titleBar);
        installCollapseButtonDrag();
        installResizeHandler();
        setCollapsed(prefs.getBoolean(KEY_COLLAPSED, false), false);
    }

    public void show() {
        if (attached) {
            return;
        }
        clampToScreen();
        windowManager.addView(root, params);
        attached = true;
        syncCollapsedHandle();
    }

    public void hide() {
        removeCollapsedTouchHandle();
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(root);
        } catch (RuntimeException ignored) {
            // The window may already have been removed by the system.
        }
        attached = false;
    }

    public void destroy() {
        hide();
    }

    public boolean isShowing() {
        return attached;
    }

    public boolean isActuallyAttached() {
        return attached
                && root.isAttachedToWindow()
                && root.getWindowVisibility() == View.VISIBLE
                && root.isShown();
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public boolean ensureAttached() {
        if (root.isAttachedToWindow()) {
            attached = true;
            return true;
        }
        Point screen = getScreenSize();
        int minWidth = minimumPanelWidth(screen);
        int minHeight = minimumPanelHeight(screen);
        params.width = clamp(params.width, minWidth, Math.max(minWidth, screen.x - dp(12)));
        params.height = clamp(params.height, minHeight, Math.max(minHeight, screen.y - dp(12)));
        params.x = clamp(params.x, 0, Math.max(0, screen.x - params.width));
        params.y = clamp(params.y, 0, Math.max(0, screen.y - params.height));
        try {
            windowManager.addView(root, params);
            attached = true;
            syncCollapsedHandle();
            return true;
        } catch (RuntimeException error) {
            attached = false;
            return false;
        }
    }

    public void setStatus(String text) {
        text = UiText.translate(context, text);
        String current = statusChip.getText() == null
                ? ""
                : statusChip.getText().toString();
        statusChip.setText(text);
        if (!text.equals(current) && statusChip.isAttachedToWindow()) {
            statusChip.animate().cancel();
            statusChip.setAlpha(0.58f);
            statusChip.setScaleX(0.96f);
            statusChip.setScaleY(0.96f);
            statusChip.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    public void setResult(String text) {
        String current = resultText.getText() == null
                ? ""
                : resultText.getText().toString();
        resultText.setText(text);
        if (!text.equals(current) && resultText.isAttachedToWindow()) {
            resultText.animate().cancel();
            resultText.setAlpha(0.66f);
            resultText.setTranslationY(dp(3));
            resultText.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(140L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    public void setRegionLabel(String text) {
        regionLabel.setText(UiText.translate(context, text));
    }

    public void setTranslationChecked(boolean checked) {
        updatingSwitch = true;
        translationSwitch.setChecked(checked);
        updatingSwitch = false;
    }

    public void setLanguages(String newSourceMode, String newTargetLanguage) {
        sourceMode = validSource(newSourceMode);
        targetLanguage = validTarget(newTargetLanguage);
        int sourceIndex = LanguageCatalog.indexOfSource(sourceMode);
        int targetIndex = LanguageCatalog.indexOfTarget(targetLanguage);
        sourceLanguageSpinner.setSelection(sourceIndex, false);
        targetLanguageSpinner.setSelection(targetIndex, false);
        refreshStrengthOptions();
    }

    public void setTranslationStrength(int strength) {
        translationStrength = normalizeStrengthForDirection(
                TranslationStrength.clamp(strength)
        );
        translationStrengthSelector.setSelection(translationStrength, false);
    }

    public void clampToScreen() {
        Point screen = getScreenSize();
        int minWidth = minimumPanelWidth(screen);
        int minHeight = minimumPanelHeight(screen);
        params.width = clamp(params.width, minWidth, Math.max(minWidth, screen.x - dp(12)));
        params.height = clamp(params.height, minHeight, Math.max(minHeight, screen.y - dp(12)));
        params.x = clamp(params.x, 0, Math.max(0, screen.x - params.width));
        params.y = clamp(params.y, 0, Math.max(0, screen.y - params.height));
        if (attached) {
            windowManager.updateViewLayout(root, params);
            if (collapsed) {
                syncCollapsedHandle();
            }
        }
    }

    public void refreshAfterDisplayChange() {
        Point screen = getScreenSize();
        int minWidth = minimumPanelWidth(screen);
        int minHeight = minimumPanelHeight(screen);
        params.width = clamp(params.width, minWidth, Math.max(minWidth, screen.x - dp(12)));
        params.height = clamp(params.height, minHeight, Math.max(minHeight, screen.y - dp(12)));
        params.x = clamp(params.x, 0, Math.max(0, screen.x - params.width));
        params.y = clamp(params.y, 0, Math.max(0, screen.y - params.height));

        if (attached) {
            removeCollapsedTouchHandle();
            try {
                windowManager.removeViewImmediate(root);
            } catch (RuntimeException ignored) {
                // The vendor WindowManager may already have removed the old window.
            }
            attached = false;
        }
        try {
            windowManager.addView(root, params);
            attached = true;
            syncCollapsedHandle();
        } catch (RuntimeException error) {
            attached = false;
            throw error;
        }
    }

    public Rect getWindowBounds() {
        return new Rect(params.x, params.y, params.x + params.width, params.y + params.height);
    }

    private void setupLanguageSpinners() {
        sourceLanguageSpinner.setAdapter(
                new LanguageAdapter(Arrays.asList(LanguageCatalog.SOURCE_OPTIONS))
        );
        targetLanguageSpinner.setAdapter(
                new LanguageAdapter(Arrays.asList(LanguageCatalog.TARGET_OPTIONS))
        );
        sourceLanguageSpinner.setSelection(
                LanguageCatalog.indexOfSource(sourceMode),
                false
        );
        targetLanguageSpinner.setSelection(
                LanguageCatalog.indexOfTarget(targetLanguage),
                false
        );

        sourceLanguageSpinner.setOnItemSelectedListener(
                new SimpleItemSelectedListener(position -> {
                    String selected = LanguageCatalog.SOURCE_OPTIONS[position].code;
                    if (!selected.equals(sourceMode)) {
                        sourceMode = selected;
                        refreshStrengthOptions();
                        listener.onLanguagesChanged(sourceMode, targetLanguage);
                    }
                })
        );
        targetLanguageSpinner.setOnItemSelectedListener(
                new SimpleItemSelectedListener(position -> {
                    String selected = LanguageCatalog.TARGET_OPTIONS[position].code;
                    if (!selected.equals(targetLanguage)) {
                        targetLanguage = selected;
                        refreshStrengthOptions();
                        listener.onLanguagesChanged(sourceMode, targetLanguage);
                    }
                })
        );
    }

    private void setupStrengthSelector() {
        translationStrength = normalizeStrengthForDirection(translationStrength);
        translationStrengthSelector.setLabels(strengthLabels());
        translationStrengthSelector.setSelection(translationStrength, false);
        translationStrengthSelector.setListener(position -> {
            int selected = TranslationStrength.clamp(position);
            if (selected != translationStrength) {
                translationStrength = selected;
                listener.onTranslationStrengthChanged(selected);
            }
        });
    }

    private void refreshStrengthOptions() {
        translationStrength = normalizeStrengthForDirection(translationStrength);
        translationStrengthSelector.setLabels(strengthLabels());
        translationStrengthSelector.setSelection(translationStrength, false);
    }

    private String[] strengthLabels() {
        String[] base = TranslationStrength.uiLabels(context);
        if (!ultraDirectionAvailable()) {
            return base;
        }
        String[] labels = Arrays.copyOf(
                base,
                base.length + 1
        );
        labels[labels.length - 1] = context.getString(R.string.strength_ja_zh);
        return labels;
    }

    private int normalizeStrengthForDirection(int strength) {
        if (strength == TranslationStrength.ULTRA
                && !ultraDirectionAvailable()) {
            return TranslationStrength.PRECISE;
        }
        return strength;
    }

    private boolean ultraDirectionAvailable() {
        if (!TranslationEngine.LANGUAGE_CHINESE.equals(targetLanguage)) {
            return false;
        }
        return TranslationEngine.MODE_AUTO.equals(sourceMode)
                || TranslationEngine.LANGUAGE_JAPANESE.equals(sourceMode);
    }

    private void setCollapsed(boolean newCollapsed, boolean persist) {
        if (!newCollapsed
                && (params.width < dp(FULL_CONTROL_PANEL_WIDTH_DP)
                || params.height < dp(FULL_CONTROL_PANEL_HEIGHT_DP))) {
            params.width = Math.max(params.width, dp(FULL_CONTROL_PANEL_WIDTH_DP));
            params.height = Math.max(params.height, dp(FULL_CONTROL_PANEL_HEIGHT_DP));
            Point screen = getScreenSize();
            params.width = Math.min(params.width, screen.x - dp(12));
            params.height = Math.min(params.height, screen.y - dp(12));
            params.x = clamp(params.x, 0, Math.max(0, screen.x - params.width));
            params.y = clamp(params.y, 0, Math.max(0, screen.y - params.height));
            if (attached) {
                windowManager.updateViewLayout(root, params);
            }
            persistBounds();
        }
        collapsed = newCollapsed;
        resizeHandle.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        applyCollapsedChrome(collapsed);
        syncCollapsedHandle();
        boolean animate = attached && persist;
        animateControlSection(titleBar, animate);
        animateControlSection(controlsContainer, animate);
        collapseButton.setImageResource(
                collapsed ? R.drawable.ic_panel_expand : R.drawable.ic_panel_collapse
        );
        collapseButton.setContentDescription(context.getString(
                collapsed ? R.string.expand_panel : R.string.collapse_panel
        ));
        if (animate) {
            collapseButton.animate().cancel();
            collapseButton.setScaleX(0.84f);
            collapseButton.setScaleY(0.84f);
            collapseButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(new android.view.animation.OvershootInterpolator())
                    .start();
        }
        resultText.setPadding(
                collapsed ? dp(8) : 0,
                collapsed ? dp(8) : 0,
                collapsed ? dp(54) : 0,
                0
        );
        if (persist) {
            listener.onCollapsedChanged(collapsed);
        }
        if (persist) {
            prefs.edit().putBoolean(KEY_COLLAPSED, collapsed).apply();
        }
    }

    private void applyCollapsedChrome(boolean isCollapsed) {
        if (isCollapsed) {
            root.setBackgroundResource(0);
            resultFrame.setBackgroundResource(0);
            collapseButton.setBackgroundResource(0);
            collapseButton.setPadding(dp(1), dp(1), dp(1), dp(1));
            collapseButton.setColorFilter(context.getColor(R.color.paper));
            panelContent.setPadding(0, 0, 0, 0);
            resultText.setShadowLayer(
                    dp(2),
                    0f,
                    dp(1),
                    Color.argb(180, 0, 0, 0)
            );
            return;
        }
        root.setBackgroundResource(R.drawable.panel_bg);
        resultFrame.setBackgroundResource(R.drawable.result_bg);
        collapseButton.setBackgroundResource(R.drawable.chip_bg);
        collapseButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        collapseButton.clearColorFilter();
        panelContent.setPadding(dp(7), dp(7), dp(7), dp(7));
        resultText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }

    private void syncCollapsedHandle() {
        if (!attached) {
            return;
        }
        if (!collapsed) {
            removeCollapsedTouchHandle();
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(root, params);
            } catch (RuntimeException ignored) {
                // The window may be between attachment states during rotation.
            }
            return;
        }
        root.post(this::addCollapsedTouchHandle);
    }

    private void addCollapsedTouchHandle() {
        removeCollapsedTouchHandle();
        if (!attached || !collapsed) {
            return;
        }
        int[] location = new int[2];
        collapseButton.getLocationOnScreen(location);
        int handleSize = dp(60);
        int inset = dp(8);
        collapsedTouchHandle = new View(context);
        collapsedTouchHandle.setContentDescription(
                context.getString(R.string.expand_panel)
        );
        collapsedTouchHandle.setOnClickListener(v -> setCollapsed(false, true));
        collapsedTouchParams = new WindowManager.LayoutParams(
                handleSize,
                handleSize,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        collapsedTouchParams.gravity = Gravity.TOP | Gravity.START;
        collapsedTouchParams.x = location[0] - inset;
        collapsedTouchParams.y = location[1] - inset;
        collapsedTouchParams.alpha = 0.01f;
        try {
            windowManager.addView(collapsedTouchHandle, collapsedTouchParams);
        } catch (RuntimeException error) {
            collapsedTouchHandle = null;
            collapsedTouchParams = null;
            return;
        }
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            windowManager.updateViewLayout(root, params);
        } catch (RuntimeException ignored) {
            // Keep the invisible handle usable even if this update races a rebind.
        }
    }

    private void removeCollapsedTouchHandle() {
        if (collapsedTouchHandle == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(collapsedTouchHandle);
        } catch (RuntimeException ignored) {
            // Already removed by the vendor window manager.
        }
        collapsedTouchHandle = null;
        collapsedTouchParams = null;
    }

    private void animateControlSection(View view, boolean animate) {
        view.animate().cancel();
        if (!animate) {
            view.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        if (collapsed) {
            view.animate()
                    .alpha(0f)
                    .translationY(-dp(6))
                    .setDuration(120L)
                    .withEndAction(() -> {
                        if (collapsed) {
                            view.setVisibility(View.GONE);
                            view.setAlpha(1f);
                            view.setTranslationY(0f);
                        }
                    })
                    .start();
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.setTranslationY(-dp(6));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void toggleCollapsed() {
        collapseButton.performHapticFeedback(
                android.view.HapticFeedbackConstants.CLOCK_TICK
        );
        setCollapsed(!collapsed, true);
    }

    private void installPressFeedback(View view) {
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    target.animate().cancel();
                    target.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(70L)
                            .start();
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    target.animate().cancel();
                    target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(130L)
                            .setInterpolator(
                                    new android.view.animation.DecelerateInterpolator()
                            )
                            .start();
                    return false;
                default:
                    return false;
            }
        });
    }

    private void installDragHandler(View dragTarget) {
        dragTarget.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int downX;
            private int downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = params.x;
                        downY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point screen = getScreenSize();
                        params.x = clamp(
                                downX + Math.round(event.getRawX() - downRawX),
                                0,
                                Math.max(0, screen.x - params.width)
                        );
                        params.y = clamp(
                                downY + Math.round(event.getRawY() - downRawY),
                                0,
                                Math.max(0, screen.y - params.height)
                        );
                        if (attached) {
                            windowManager.updateViewLayout(root, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        persistBounds();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void installCollapseButtonDrag() {
        collapseButton.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int downX;
            private int downY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = params.x;
                        downY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) + Math.abs(dy) > dp(6)) {
                            moved = true;
                        }
                        Point screen = getScreenSize();
                        params.x = clamp(downX + Math.round(dx), 0,
                                Math.max(0, screen.x - params.width));
                        params.y = clamp(downY + Math.round(dy), 0,
                                Math.max(0, screen.y - params.height));
                        if (attached) {
                            windowManager.updateViewLayout(root, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        persistBounds();
                        if (!moved) {
                            view.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        persistBounds();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void installResizeHandler() {
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int downWidth;
            private int downHeight;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downWidth = params.width;
                        downHeight = params.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Point screen = getScreenSize();
                        int minWidth = minimumPanelWidth(screen);
                        int minHeight = minimumPanelHeight(screen);
                        int maxWidth = Math.max(minWidth, screen.x - params.x - dp(6));
                        int maxHeight = Math.max(minHeight, screen.y - params.y - dp(6));
                        params.width = clamp(
                                downWidth + Math.round(event.getRawX() - downRawX),
                                minWidth,
                                maxWidth
                        );
                        params.height = clamp(
                                downHeight + Math.round(event.getRawY() - downRawY),
                                minHeight,
                                maxHeight
                        );
                        if (attached) {
                            windowManager.updateViewLayout(root, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        persistBounds();
                        if (params.width < dp(EXPANDABLE_PANEL_WIDTH_DP)
                                || params.height < dp(EXPANDABLE_PANEL_HEIGHT_DP)) {
                            setCollapsed(true, true);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void persistBounds() {
        prefs.edit()
                .putInt(KEY_X, params.x)
                .putInt(KEY_Y, params.y)
                .putInt(KEY_WIDTH, params.width)
                .putInt(KEY_HEIGHT, params.height)
                .apply();
    }

    private Point getScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        return size;
    }

    private String validSource(String source) {
        return LanguageCatalog.normalizeSource(source);
    }

    private String validTarget(String target) {
        return LanguageCatalog.normalizeTarget(target);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int minimumPanelWidth(Point screen) {
        return Math.min(dp(MIN_PANEL_WIDTH_DP), screen.x - dp(8));
    }

    private int minimumPanelHeight(Point screen) {
        return Math.min(dp(MIN_PANEL_HEIGHT_DP), screen.y - dp(8));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private class LanguageAdapter extends BaseAdapter {
        private final List<LanguageCatalog.Option> options;

        LanguageAdapter(List<LanguageCatalog.Option> options) {
            this.options = options;
        }

        @Override
        public int getCount() {
            return options.size();
        }

        @Override
        public LanguageCatalog.Option getItem(int position) {
            return options.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = optionView(convertView, parent, false);
            view.setText(LanguageCatalog.displayName(
                    context,
                    getItem(position).code
            ));
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = optionView(convertView, parent, true);
            view.setText(LanguageCatalog.displayName(
                    context,
                    getItem(position).code
            ));
            return view;
        }

        private TextView optionView(View convertView, ViewGroup parent, boolean dropdown) {
            TextView view = convertView instanceof TextView
                    ? (TextView) convertView
                    : new TextView(context);
            view.setTextColor(context.getColor(R.color.aqua));
            view.setTextSize(12f);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(8), 0, dp(8), 0);
            view.setBackgroundColor(dropdown ? context.getColor(R.color.ink_950) : 0);
            view.setMinHeight(dp(dropdown ? 42 : 34));
            return view;
        }
    }

    private interface ItemSelectedAction {
        void onSelected(int position);
    }

    private static class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final ItemSelectedAction action;

        SimpleItemSelectedListener(ItemSelectedAction action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(
                android.widget.AdapterView<?> parent,
                View view,
                int position,
                long id
        ) {
            action.onSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
