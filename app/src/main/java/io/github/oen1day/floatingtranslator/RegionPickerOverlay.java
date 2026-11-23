package io.github.oen1day.floatingtranslator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class RegionPickerOverlay {
    public interface Listener {
        void onRegionSelected(RectF normalizedRegion);

        void onCancelled();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final View root;
    private final SelectionView selectionView;
    private final LinearLayout introCard;
    private final TextView selectingHint;
    private final LinearLayout actionTray;
    private final Listener listener;
    private final WindowManager.LayoutParams params;
    private Rect existingRect;
    private boolean attached;

    public RegionPickerOverlay(
            Context context,
            WindowManager windowManager,
            int windowType,
            RectF existingRegion,
            Listener listener
    ) {
        this.context = context;
        this.windowManager = windowManager;
        this.listener = listener;
        root = LayoutInflater.from(context).inflate(R.layout.region_picker, null);
        UiText.localizeTree(root);
        selectionView = root.findViewById(R.id.selection_view);
        introCard = root.findViewById(R.id.intro_card);
        selectingHint = root.findViewById(R.id.selecting_hint);
        actionTray = root.findViewById(R.id.action_tray);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        selectionView.setInteractive(false);
        selectionView.setSelectionChangedListener(selectionReady -> {
            if (selectionReady) {
                actionTray.setVisibility(View.VISIBLE);
                UiText.setText(selectingHint, "已选择区域；可拖动重选或双指捏合调整");
            } else {
                actionTray.setVisibility(View.GONE);
                UiText.setText(selectingHint, "按住屏幕并拖动框选；松开后确认");
            }
        });

        Button cancelIntro = root.findViewById(R.id.cancel_region_button);
        Button startButton = root.findViewById(R.id.start_region_button);
        Button reselectButton = root.findViewById(R.id.reselect_region_button);
        Button cancelAction = root.findViewById(R.id.cancel_region_action_button);
        Button confirmButton = root.findViewById(R.id.confirm_region_button);

        cancelIntro.setOnClickListener(v -> cancel());
        cancelAction.setOnClickListener(v -> cancel());
        startButton.setOnClickListener(v -> beginSelection());
        reselectButton.setOnClickListener(v -> {
            selectionView.clearSelection();
            actionTray.setVisibility(View.GONE);
            UiText.setText(selectingHint, "按住屏幕并拖动框选；松开后确认");
        });
        confirmButton.setOnClickListener(v -> confirmSelection());

        root.post(() -> {
            if (existingRegion != null && selectionView.getWidth() > 0) {
                existingRect = new Rect(
                        Math.round(existingRegion.left * selectionView.getWidth()),
                        Math.round(existingRegion.top * selectionView.getHeight()),
                        Math.round(existingRegion.right * selectionView.getWidth()),
                        Math.round(existingRegion.bottom * selectionView.getHeight())
                );
            }
        });
    }

    public void show() {
        if (attached) {
            return;
        }
        windowManager.addView(root, params);
        attached = true;
    }

    public void destroy() {
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(root);
        } catch (RuntimeException ignored) {
            // Already removed.
        }
        attached = false;
    }

    private void beginSelection() {
        introCard.setVisibility(View.GONE);
        selectingHint.setVisibility(View.VISIBLE);
        selectionView.setInteractive(true);
        if (existingRect != null && !existingRect.isEmpty()) {
            selectionView.setSelection(existingRect);
            actionTray.setVisibility(View.VISIBLE);
            UiText.setText(selectingHint, "已带入上次区域；可重选或双指捏合调整");
        } else {
            actionTray.setVisibility(View.GONE);
            UiText.setText(selectingHint, "按住屏幕并拖动框选；松开后确认");
        }
    }

    private void confirmSelection() {
        Rect selected = selectionView.getSelection();
        if (selected.width() < dp(12) || selected.height() < dp(12)) {
            UiText.toast(context, "选区太小，请重新框选", Toast.LENGTH_SHORT);
            return;
        }
        int width = Math.max(1, selectionView.getWidth());
        int height = Math.max(1, selectionView.getHeight());
        RectF normalized = new RectF(
                selected.left / (float) width,
                selected.top / (float) height,
                selected.right / (float) width,
                selected.bottom / (float) height
        );
        destroy();
        listener.onRegionSelected(normalized);
    }

    private void cancel() {
        destroy();
        listener.onCancelled();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static class SelectionView extends View {
        public interface SelectionChangedListener {
            void onSelectionChanged(boolean selectionReady);
        }

        private enum GestureMode {
            NONE,
            PENDING_DRAW,
            DRAW,
            PINCH,
            CONSUME_UNTIL_UP
        }

        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect selection = new Rect();
        private int touchSlop;
        private SelectionChangedListener selectionChangedListener;
        private GestureMode gestureMode = GestureMode.NONE;
        private boolean interactive;
        private float startX;
        private float startY;
        private float pinchStartDistance;
        private float pinchStartCenterX;
        private float pinchStartCenterY;
        private Rect pinchStartRect = new Rect();

        public SelectionView(Context context) {
            super(context);
            initialize();
        }

        public SelectionView(Context context, AttributeSet attrs) {
            super(context, attrs);
            initialize();
        }

        private void initialize() {
            touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            dimPaint.setColor(0xA6000000);
            fillPaint.setColor(0x1653D7D9);
            borderPaint.setColor(0xFF53D7D9);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(
                    3f * getContext().getResources().getDisplayMetrics().density
            );
        }

        public void setInteractive(boolean interactive) {
            this.interactive = interactive;
            gestureMode = GestureMode.NONE;
            invalidate();
        }

        public void setSelectionChangedListener(SelectionChangedListener listener) {
            selectionChangedListener = listener;
        }

        public void setSelection(Rect rect) {
            selection.set(clampRect(rect));
            invalidate();
        }

        public void clearSelection() {
            selection.setEmpty();
            invalidate();
        }

        public Rect getSelection() {
            return new Rect(selection);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (selection.isEmpty()) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
                return;
            }

            canvas.drawRect(0, 0, getWidth(), selection.top, dimPaint);
            canvas.drawRect(0, selection.bottom, getWidth(), getHeight(), dimPaint);
            canvas.drawRect(0, selection.top, selection.left, selection.bottom, dimPaint);
            canvas.drawRect(
                    selection.right,
                    selection.top,
                    getWidth(),
                    selection.bottom,
                    dimPaint
            );
            canvas.drawRect(selection, fillPaint);
            canvas.drawRect(selection, borderPaint);

            float radius = 7f * getResources().getDisplayMetrics().density;
            canvas.drawCircle(selection.left, selection.top, radius, borderPaint);
            canvas.drawCircle(selection.right, selection.top, radius, borderPaint);
            canvas.drawCircle(selection.left, selection.bottom, radius, borderPaint);
            canvas.drawCircle(selection.right, selection.bottom, radius, borderPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!interactive) {
                return false;
            }

            float x = Math.max(0, Math.min(getWidth(), event.getX()));
            float y = Math.max(0, Math.min(getHeight(), event.getY()));
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = x;
                    startY = y;
                    gestureMode = GestureMode.PENDING_DRAW;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2 && !selection.isEmpty()) {
                        beginPinch(event);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (gestureMode == GestureMode.PINCH) {
                        updatePinch(event);
                    } else if (gestureMode == GestureMode.PENDING_DRAW
                            || gestureMode == GestureMode.DRAW) {
                        if (gestureMode == GestureMode.PENDING_DRAW
                                && (Math.abs(x - startX) > touchSlop
                                || Math.abs(y - startY) > touchSlop)) {
                            gestureMode = GestureMode.DRAW;
                        }
                        if (gestureMode == GestureMode.DRAW) {
                            selection.set(
                                    Math.round(Math.min(startX, x)),
                                    Math.round(Math.min(startY, y)),
                                    Math.round(Math.max(startX, x)),
                                    Math.round(Math.max(startY, y))
                            );
                            invalidate();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (gestureMode == GestureMode.PINCH) {
                        gestureMode = GestureMode.CONSUME_UNTIL_UP;
                        notifySelectionReady();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (gestureMode == GestureMode.DRAW
                            || gestureMode == GestureMode.PINCH
                            || gestureMode == GestureMode.CONSUME_UNTIL_UP) {
                        notifySelectionReady();
                    }
                    gestureMode = GestureMode.NONE;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    gestureMode = GestureMode.NONE;
                    return true;
                default:
                    return false;
            }
        }

        private void beginPinch(MotionEvent event) {
            gestureMode = GestureMode.PINCH;
            pinchStartDistance = distance(event, 0, 1);
            pinchStartCenterX = (event.getX(0) + event.getX(1)) / 2f;
            pinchStartCenterY = (event.getY(0) + event.getY(1)) / 2f;
            pinchStartRect.set(selection);
        }

        private void updatePinch(MotionEvent event) {
            if (event.getPointerCount() < 2 || pinchStartDistance < 1f) {
                return;
            }
            float distance = distance(event, 0, 1);
            float scale = Math.max(0.25f, Math.min(4f, distance / pinchStartDistance));
            float centerX = (event.getX(0) + event.getX(1)) / 2f;
            float centerY = (event.getY(0) + event.getY(1)) / 2f;

            Rect resized = new Rect(
                    Math.round(centerX + (pinchStartRect.left - pinchStartCenterX) * scale),
                    Math.round(centerY + (pinchStartRect.top - pinchStartCenterY) * scale),
                    Math.round(centerX + (pinchStartRect.right - pinchStartCenterX) * scale),
                    Math.round(centerY + (pinchStartRect.bottom - pinchStartCenterY) * scale)
            );
            selection.set(clampRect(resized));
            invalidate();
        }

        private float distance(MotionEvent event, int firstPointer, int secondPointer) {
            float dx = event.getX(secondPointer) - event.getX(firstPointer);
            float dy = event.getY(secondPointer) - event.getY(firstPointer);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private void notifySelectionReady() {
            boolean ready = selection.width() >= dp(9) && selection.height() >= dp(9);
            if (selectionChangedListener != null) {
                selectionChangedListener.onSelectionChanged(ready);
            }
        }

        private Rect clampRect(Rect source) {
            int left = Math.max(0, Math.min(getWidth(), source.left));
            int top = Math.max(0, Math.min(getHeight(), source.top));
            int right = Math.max(0, Math.min(getWidth(), source.right));
            int bottom = Math.max(0, Math.min(getHeight(), source.bottom));
            if (right < left) {
                int temp = left;
                left = right;
                right = temp;
            }
            if (bottom < top) {
                int temp = top;
                top = bottom;
                bottom = temp;
            }
            return new Rect(left, top, right, bottom);
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }
}
