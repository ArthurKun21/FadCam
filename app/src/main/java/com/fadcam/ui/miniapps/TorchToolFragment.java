package com.fadcam.ui.miniapps;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.AvatarToggleView;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;

/**
 * TorchToolFragment - Modern industry-standard torch screen.
 * Hero tap-to-toggle button, animated glow, Material 3 pattern chips,
 * and fullscreen screen-light overlay with brightness slider.
 */
public class TorchToolFragment extends BaseFragment {

    private TorchManager torchManager;

    // Views
    private AvatarToggleView torchToggle;
    private View torchGlow;
    private TextView torchStatusLabel;
    private LinearLayout patternChipsContainer;
    private TextView patternInfoBtn;
    private LinearLayout screenLightRow;
    private TextView screenLightStatus;

    // Screen-light dialog
    private Dialog screenLightDialog;
    private boolean isScreenLightOn = false;

    // Animation guard: skip wake/sleep animation on the very first draw
    private boolean isFirstStateUpdate = true;

    public static TorchToolFragment newInstance() {
        return new TorchToolFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_torch_tool, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Init TorchManager
        torchManager = new TorchManager(requireContext());
        torchManager.setStateListener(new TorchManager.TorchStateListener() {
            @Override
            public void onTorchStateChanged(boolean isOn) {
                updateTorchState();
            }

            @Override
            public void onBrightnessChanged(float brightness) { /* not used */ }

            @Override
            public void onPatternChanged(TorchManager.FlashPattern pattern) {
                updatePatternChips();
            }

            @Override
            public void onError(String message) {
                FLog.e("TorchToolFragment", "Torch error: " + message);
            }
        });

        // Bind views
        FrameLayout torchButtonArea = view.findViewById(R.id.torch_button_area);
        torchToggle = view.findViewById(R.id.torch_toggle);
        torchGlow = view.findViewById(R.id.torch_glow);
        torchStatusLabel = view.findViewById(R.id.torch_status_label);
        patternChipsContainer = view.findViewById(R.id.pattern_chips_container);
        patternInfoBtn = view.findViewById(R.id.pattern_info_btn);
        screenLightRow = view.findViewById(R.id.screen_light_row);
        screenLightStatus = view.findViewById(R.id.screen_light_status);
        ImageView closeBtn = view.findViewById(R.id.torch_close_btn);

        // Close
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> {
                dismissScreenLight();
                torchManager.release();
                OverlayNavUtil.dismiss(requireActivity());
            });
        }

        // Hero tap area
        if (torchButtonArea != null) {
            torchButtonArea.setOnClickListener(v -> toggleTorch());
        }

        // Toggle is display-only
        if (torchToggle != null) {
            torchToggle.setClickable(false);
            torchToggle.setFocusable(false);
        }

        // Pattern chips
        setupPatternChips();

        // Pattern info
        if (patternInfoBtn != null) {
            patternInfoBtn.setOnClickListener(v -> showPatternInfoSheet());
        }

        // Screen light row
        if (screenLightRow != null) {
            screenLightRow.setOnClickListener(v -> toggleScreenLight());
        }

        // Initial draw (no animation on first render)
        updateTorchState();
    }

    // ── Torch toggle ─────────────────────────────────────────────────────────

    private void toggleTorch() {
        torchManager.setTorchEnabled(!torchManager.isTorchOn());
    }

    // ── State update ──────────────────────────────────────────────────────────

    private void updateTorchState() {
        boolean isOn = torchManager.isTorchOn();

        // AvatarToggle with animation (skip on first draw)
        if (torchToggle != null) {
            torchToggle.setChecked(isOn, !isFirstStateUpdate);
        }
        isFirstStateUpdate = false;

        // Glow ring
        if (torchGlow != null) {
            if (isOn) {
                torchGlow.setVisibility(View.VISIBLE);
                torchGlow.setScaleX(0.8f);
                torchGlow.setScaleY(0.8f);
                torchGlow.setAlpha(0f);
                torchGlow.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(300)
                        .start();
            } else {
                torchGlow.animate()
                        .scaleX(0.85f).scaleY(0.85f).alpha(0f)
                        .setDuration(250)
                        .withEndAction(() -> torchGlow.setVisibility(View.GONE))
                        .start();
            }
        }

        // Status label
        if (torchStatusLabel != null) {
            if (isOn) {
                torchStatusLabel.setText(getString(R.string.torch_status_on));
                torchStatusLabel.setTextColor(0xFFFFA726);
            } else {
                torchStatusLabel.setText(getString(R.string.torch_tap_to_enable));
                torchStatusLabel.setTextColor(0x80FFFFFF);
            }
        }

        updatePatternChips();

        // Auto-dismiss screen light when torch turns off
        if (!isOn && isScreenLightOn) {
            dismissScreenLight();
        }
    }

    // ── Pattern chips ─────────────────────────────────────────────────────────

    private void setupPatternChips() {
        if (patternChipsContainer == null) return;
        patternChipsContainer.removeAllViews();

        for (TorchManager.FlashPattern pattern : TorchManager.FlashPattern.values()) {
            View chip = createPatternChip(pattern);
            patternChipsContainer.addView(chip);
        }
    }

    private View createPatternChip(TorchManager.FlashPattern pattern) {
        LinearLayout chip = new LinearLayout(requireContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER);

        int chipH = dpToPx(48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, chipH, 1f);
        lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        chip.setLayoutParams(lp);

        // Icon
        TextView icon = new TextView(requireContext());
        icon.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.materialicons));
        icon.setText(getPatternIcon(pattern));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        icon.setGravity(android.view.Gravity.CENTER);
        chip.addView(icon);

        // Label
        TextView label = new TextView(requireContext());
        label.setText(getPatternName(pattern));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.setMarginStart(dpToPx(6));
        label.setLayoutParams(labelLp);
        chip.addView(label);

        chip.setTag(pattern);
        chip.setOnClickListener(v -> torchManager.setFlashPattern(pattern));

        stylePatternChip(chip, pattern == torchManager.getCurrentPattern());
        return chip;
    }

    private void stylePatternChip(LinearLayout chip, boolean isActive) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(16));
        if (isActive) {
            bg.setColor(0xFFFFA726);
        } else {
            bg.setColor(0xFF1E1E1E);
            bg.setStroke(dpToPx(1), 0xFF444444);
        }
        chip.setBackground(bg);
        chip.setAlpha(isActive ? 1.0f : 0.65f);

        int textColor = isActive ? 0xFF1A1A1A : 0xFFCCCCCC;
        for (int i = 0; i < chip.getChildCount(); i++) {
            if (chip.getChildAt(i) instanceof TextView) {
                ((TextView) chip.getChildAt(i)).setTextColor(textColor);
            }
        }
    }

    private void updatePatternChips() {
        if (patternChipsContainer == null) return;
        TorchManager.FlashPattern current = torchManager.getCurrentPattern();
        for (int i = 0; i < patternChipsContainer.getChildCount(); i++) {
            View child = patternChipsContainer.getChildAt(i);
            if (child instanceof LinearLayout && child.getTag() instanceof TorchManager.FlashPattern) {
                TorchManager.FlashPattern p = (TorchManager.FlashPattern) child.getTag();
                stylePatternChip((LinearLayout) child, p == current);
            }
        }
    }

    private String getPatternIcon(TorchManager.FlashPattern pattern) {
        switch (pattern) {
            case STEADY:  return "flashlight_on";
            case STROBE:  return "bolt";
            case SOS:     return "sos";
            default:      return "flashlight_on";
        }
    }

    private String getPatternName(TorchManager.FlashPattern pattern) {
        switch (pattern) {
            case STEADY:  return getString(R.string.torch_pattern_steady);
            case STROBE:  return getString(R.string.torch_pattern_strobe);
            case SOS:     return getString(R.string.torch_pattern_sos);
            default:      return pattern.name();
        }
    }

    // ── Screen light ──────────────────────────────────────────────────────────

    private void toggleScreenLight() {
        if (isScreenLightOn) {
            dismissScreenLight();
        } else {
            showScreenLight();
        }
    }

    private void showScreenLight() {
        screenLightDialog = new Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        screenLightDialog.setContentView(R.layout.overlay_torch_screen_light);

        WindowManager.LayoutParams lp = screenLightDialog.getWindow().getAttributes();
        screenLightDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        screenLightDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        screenLightDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        screenLightDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // Set max brightness immediately
        lp.screenBrightness = 1.0f;
        screenLightDialog.getWindow().setAttributes(lp);

        Slider slider = screenLightDialog.findViewById(R.id.screen_brightness_slider);
        if (slider != null) {
            slider.setValueFrom(0.05f);
            slider.setValueTo(1.0f);
            slider.setValue(1.0f);
            slider.addOnChangeListener((s, value, fromUser) -> {
                if (fromUser && screenLightDialog != null && screenLightDialog.getWindow() != null) {
                    WindowManager.LayoutParams attrs = screenLightDialog.getWindow().getAttributes();
                    attrs.screenBrightness = value;
                    screenLightDialog.getWindow().setAttributes(attrs);
                }
            });
        }

        View closeLightBtn = screenLightDialog.findViewById(R.id.screen_light_close_btn);
        if (closeLightBtn != null) {
            closeLightBtn.setOnClickListener(v -> dismissScreenLight());
        }

        screenLightDialog.setCancelable(false);
        screenLightDialog.show();

        isScreenLightOn = true;
        updateScreenLightStatus();
    }

    private void dismissScreenLight() {
        if (screenLightDialog != null) {
            try {
                if (screenLightDialog.getWindow() != null) {
                    screenLightDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    WindowManager.LayoutParams lp = screenLightDialog.getWindow().getAttributes();
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    screenLightDialog.getWindow().setAttributes(lp);
                }
                if (screenLightDialog.isShowing()) {
                    screenLightDialog.dismiss();
                }
            } catch (Exception e) {
                FLog.e("TorchToolFragment", "Error dismissing screen light", e);
            }
            screenLightDialog = null;
        }
        isScreenLightOn = false;
        updateScreenLightStatus();
    }

    private void updateScreenLightStatus() {
        if (screenLightStatus != null) {
            if (isScreenLightOn) {
                screenLightStatus.setText("ON");
                screenLightStatus.setTextColor(0xFFFFA726);
            } else {
                screenLightStatus.setText("OFF");
                screenLightStatus.setTextColor(0x66FFFFFF);
            }
        }
    }

    // ── Pattern info sheet ────────────────────────────────────────────────────

    private void showPatternInfoSheet() {
        ArrayList<OptionItem> items = new ArrayList<>();
        for (TorchManager.FlashPattern pattern : TorchManager.FlashPattern.values()) {
            items.add(new OptionItem(
                    pattern.name(),
                    getPatternName(pattern),
                    getPatternDescription(pattern),
                    null, null, null, null, null,
                    getPatternIcon(pattern)
            ));
        }

        String currentId = torchManager.getCurrentPattern().name();
        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.mini_app_torch_patterns_title),
                items,
                currentId,
                "torch_pattern_info"
        );
        sheet.show(getChildFragmentManager(), "torch_pattern_info");
    }

    private String getPatternDescription(TorchManager.FlashPattern pattern) {
        switch (pattern) {
            case STEADY: return getString(R.string.pattern_steady_desc);
            case STROBE: return getString(R.string.pattern_strobe_desc);
            case SOS:    return getString(R.string.pattern_sos_desc);
            default:     return "";
        }
    }

    // ── Back press ────────────────────────────────────────────────────────────

    @Override
    protected boolean onBackPressed() {
        dismissScreenLight();
        torchManager.release();
        OverlayNavUtil.dismiss(requireActivity());
        return true;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissScreenLight();
        if (torchManager != null) {
            torchManager.release();
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private int dpToPx(int dp) {
        return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
    }
}
