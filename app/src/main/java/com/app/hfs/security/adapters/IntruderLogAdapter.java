package com.hfs.security.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.hfs.security.databinding.ItemIntruderLogBinding;
import com.hfs.security.models.IntruderLog;

import java.util.List;

/**
 * Adapter for the Intruder Evidence list.
 * Responsible for displaying captured intruder photos and intrusion details.
 * Uses Glide for efficient image loading from the hidden internal storage.
 */
public class IntruderLogAdapter extends RecyclerView.Adapter<IntruderLogAdapter.LogViewHolder> {

    private final List<IntruderLog> logList;
    private final OnLogActionListener listener;

    /**
     * Interface for handling interactions with intrusion records.
     */
    public interface OnLogActionListener {
        void onLogClicked(IntruderLog log);
        void onDeleteClicked(IntruderLog log);
    }

    /**
     * Constructor for the adapter.
     * @param logList List of IntruderLog models.
     * @param listener Callback for click and delete events.
     */
    public IntruderLogAdapter(List<IntruderLog> logList, OnLogActionListener listener) {
        this.logList = logList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Initialize ViewBinding for the intruder item layout
        ItemIntruderLogBinding binding = ItemIntruderLogBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new LogViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        // Retrieve the intrusion record for the current position
        IntruderLog log = logList.get(position);
        holder.bind(log, listener);
    }

    @Override
    public int getItemCount() {
        return logList != null ? logList.size() : 0;
    }

    /**
     * ViewHolder class using ViewBinding for high-performance UI updates.
     */
    static class LogViewHolder extends RecyclerView.ViewHolder {
        private final ItemIntruderLogBinding binding;

        public LogViewHolder(ItemIntruderLogBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds intrusion data to the UI components.
         */
        public void bind(IntruderLog log, OnLogActionListener listener) {
            // 1. Display metadata
            binding.tvIntruderTime.setText(log.getFormattedDate());
            binding.tvTargetApp.setText("Target: " + log.getAppName());

            // 2. Load the intruder's face photo from internal path using Glide
            // Glide handles memory management and aspect ratio scaling automatically.
            Glide.with(itemView.getContext())
                    .load(log.getFilePath())
                    .centerCrop()
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .into(binding.ivIntruderPhoto);

            // 3. Handle Single Tap: View full-size photo
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onLogClicked(log);
                }
            });

            // 4. Handle Delete Icon: Remove evidence from logs
            binding.btnDeleteLog.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClicked(log);
                }
            });
        }
    }
}