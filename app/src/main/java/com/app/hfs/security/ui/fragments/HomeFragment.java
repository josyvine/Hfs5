package com.hfs.security.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.hfs.security.R;
import com.hfs.security.databinding.FragmentHomeBinding;
import com.hfs.security.services.HFSAccessibilityService;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * The Main Dashboard of the HFS App.
 * UPDATED: Now manages the HFSAccessibilityService (Zero-Flash Detection).
 * 
 * Logic Change:
 * Accessibility Services cannot be started/stopped programmatically for security reasons.
 * The toggle button now redirects the user to System Settings to Enable/Disable the guard.
 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private HFSDatabaseHelper db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the Home layout
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = HFSDatabaseHelper.getInstance(requireContext());

        setupClickListeners();
        refreshUI();
    }

    /**
     * Connects the UI buttons to their respective security functions.
     */
    private void setupClickListeners() {
        // Master Toggle: Redirects to Accessibility Settings to Enable/Disable
        binding.btnToggleSecurity.setOnClickListener(v -> {
            openAccessibilitySettings();
        });

        // Shortcut to view the Intruder Evidence Logs
        binding.btnViewLogs.setOnClickListener(v -> 
            Navigation.findNavController(v).navigate(R.id.nav_history)
        );

        // Shortcut to manage which apps are protected
        binding.cardProtectedApps.setOnClickListener(v -> 
            Navigation.findNavController(v).navigate(R.id.nav_protected_apps)
        );
    }

    /**
     * Updates the text and colors on the dashboard based on 
     * whether the Accessibility Service is currently active.
     */
    private void refreshUI() {
        boolean active = isAccessibilityServiceEnabled();

        if (active) {
            binding.tvSecurityStatus.setText("PROTECTION: ACTIVE");
            binding.tvSecurityStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hfs_active_green));
            binding.btnToggleSecurity.setText("DEACTIVATE SYSTEM");
            binding.ivStatusShield.setImageResource(R.drawable.ic_shield_active);
        } else {
            binding.tvSecurityStatus.setText("PROTECTION: INACTIVE");
            binding.tvSecurityStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hfs_inactive_red));
            binding.btnToggleSecurity.setText("ACTIVATE SILENT GUARD");
            binding.ivStatusShield.setImageResource(R.drawable.ic_shield_inactive);
        }

        // Display summary counts from the database
        int protectedCount = db.getProtectedAppsCount();
        binding.tvProtectedAppsSummary.setText(protectedCount + " Apps currently protected");
    }

    /**
     * Redirects the user to the specific Android Settings page.
     */
    private void openAccessibilitySettings() {
        Toast.makeText(requireContext(), "Find 'HFS Security' and toggle it.", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    /**
     * Utility method to check if HFSAccessibilityService is enabled in System Settings.
     */
    private boolean isAccessibilityServiceEnabled() {
        Context context = requireContext();
        String expectedComponentName = context.getPackageName() + "/" + HFSAccessibilityService.class.getName();
        
        String enabledServicesSetting = Settings.Secure.getString(
                context.getContentResolver(), 
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        
        if (enabledServicesSetting == null) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServicesSetting);

        while (splitter.hasNext()) {
            String componentName = splitter.next();
            if (componentName.equalsIgnoreCase(expectedComponentName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        // UI must refresh when user returns from Settings
        refreshUI(); 
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}