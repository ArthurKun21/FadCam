package com.fadcam.ui.bottomsheet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bottom sheet displaying detailed battery information.
 */
public class BatteryInfoBottomSheet extends BottomSheetDialogFragment {
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_battery_info, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        TextView currentBatteryLevel = view.findViewById(R.id.currentBatteryLevel);
        TextView consumedPercentage = view.findViewById(R.id.consumedPercentage);
        TextView consumptionRate = view.findViewById(R.id.consumptionRate);
        TextView timeRemaining = view.findViewById(R.id.timeRemaining);
        TextView chargingStatus = view.findViewById(R.id.chargingStatus);
        LinearLayout consumptionCard = view.findViewById(R.id.consumptionCard);
        LinearLayout timeRemainingCard = view.findViewById(R.id.timeRemainingCard);
        LinearLayout chargingStatusCard = view.findViewById(R.id.chargingStatusCard);
        LinearLayout lowBatteryWarningCard = view.findViewById(R.id.lowBatteryWarningCard);
        TextView lowBatteryWarningText = view.findViewById(R.id.lowBatteryWarningText);
        
        // Battery warning threshold UI
        EditText batteryWarningInput = view.findViewById(R.id.batteryWarningInput);
        Button setBatteryWarningBtn = view.findViewById(R.id.setBatteryWarningBtn);
        TextView warningThresholdStatus = view.findViewById(R.id.warningThresholdStatus);
        
        // Get battery info from RemoteStreamManager using new JSON format
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        String batteryDetailsJson = manager.getBatteryDetailsJson(requireContext());
        
        // Load current battery warning threshold and set it in the input
        int currentThreshold = manager.getBatteryWarningThreshold();
        batteryWarningInput.setText(String.valueOf(currentThreshold));
        warningThresholdStatus.setText("Current threshold: " + currentThreshold + "%");
        
        try {
            // Parse the battery details JSON
            JSONObject batteryData = new JSONObject(batteryDetailsJson);
            int batteryPercent = batteryData.getInt("percent");
            String status = batteryData.getString("status");
            int consumed = batteryData.getInt("consumed");
            double remainingHours = batteryData.getDouble("remaining_hours");
            String warning = batteryData.getString("warning");
            
            // Display current battery level
            currentBatteryLevel.setText(batteryPercent + "%");
            
            // Show charging status
            chargingStatus.setText(status.equals("Charging") ? "🔌 Charging" : "🔋 Discharging");
            chargingStatusCard.setVisibility(View.VISIBLE);
            
            // Show consumption data if available (streaming active and battery consumed)
            if (consumed > 0) {
                consumptionCard.setVisibility(View.VISIBLE);
                consumedPercentage.setText(consumed + "%");
                
                // Calculate consumption rate if possible
                java.util.Map<String, Object> uptimeDetails = manager.getUptimeDetails();
                long uptimeSeconds = (long) uptimeDetails.get("seconds");
                if (uptimeSeconds > 3600) { // At least 1 hour
                    double hours = uptimeSeconds / 3600.0;
                    double ratePerHour = consumed / hours;
                    consumptionRate.setText(String.format("Rate: ~%.1f%% per hour", ratePerHour));
                } else {
                    consumptionRate.setVisibility(View.GONE);
                }
            } else {
                // Not streaming or no consumption yet
                consumptionCard.setVisibility(View.GONE);
            }
            
            // Show time remaining - ALWAYS show now (based on tested formula)
            timeRemainingCard.setVisibility(View.VISIBLE);
            if (remainingHours > 0) {
                if (remainingHours < 500) {
                    timeRemaining.setText(String.format("~%.1f hours remaining", remainingHours));
                } else {
                    timeRemaining.setText("~" + (int)remainingHours + "h+ remaining");
                }
            } else {
                // Fallback if calculation failed
                timeRemaining.setText("Unable to calculate");
            }
            
            // Show low battery warning if needed
            if (!warning.isEmpty()) {
                lowBatteryWarningCard.setVisibility(View.VISIBLE);
                if (lowBatteryWarningText != null) {
                    lowBatteryWarningText.setText(warning);
                }
            } else {
                lowBatteryWarningCard.setVisibility(View.GONE);
            }
            
        } catch (JSONException e) {
            // Fallback to simple display if parsing fails
            currentBatteryLevel.setText("Unable to parse battery info");
            consumptionCard.setVisibility(View.GONE);
            timeRemainingCard.setVisibility(View.GONE);
            chargingStatusCard.setVisibility(View.GONE);
            lowBatteryWarningCard.setVisibility(View.GONE);
        }
        
        // Set battery warning threshold button click listener
        setBatteryWarningBtn.setOnClickListener(v -> {
            String thresholdStr = batteryWarningInput.getText().toString().trim();
            if (thresholdStr.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a percentage", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                int threshold = Integer.parseInt(thresholdStr);
                if (threshold < 5 || threshold > 100) {
                    Toast.makeText(requireContext(), "Percentage must be between 5 and 100", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Make API call to set battery warning threshold
                setBatteryWarningThreshold(threshold, warningThresholdStatus);
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Invalid number", Toast.LENGTH_SHORT).show();
            }
        });
        
        // Update status text with current threshold if available
        warningThresholdStatus.setText("Current threshold: " + currentThreshold + "%");
    }
    
    /**
     * Set battery warning threshold via HTTP POST request
     */
    private void setBatteryWarningThreshold(int threshold, TextView statusText) {
        Thread thread = new Thread(() -> {
            try {
                RemoteStreamManager manager = RemoteStreamManager.getInstance();
                manager.setBatteryWarningThreshold(threshold);
                
                // Update UI on main thread
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Battery warning threshold set to " + threshold + "%", Toast.LENGTH_SHORT).show();
                    statusText.setText("Current threshold: " + threshold + "%");
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Failed to set threshold: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
        thread.start();
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            dialog.getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dialog.getWindow().setNavigationBarContrastEnforced(false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int flags = dialog.getWindow().getDecorView().getSystemUiVisibility();
                dialog.getWindow().getDecorView().setSystemUiVisibility(
                    flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                );
            }
        }
        return dialog;
    }
}
