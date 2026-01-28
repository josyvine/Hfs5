package com.hfm.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileHiderAdapter extends RecyclerView.Adapter<FileHiderAdapter.FileViewHolder> implements Filterable {

    private static final String TAG = "FileHiderAdapter";
    private final Context context;
    private List<FileItem> fileList; // Master list
    private List<FileItem> fileListFiltered; // Display list
    private final OnItemClickListener itemClickListener;

    public interface OnItemClickListener {
        void onItemClick(int position);
        void onSelectionChanged();
    }

    public FileHiderAdapter(Context context, List<File> files, OnItemClickListener itemClickListener) {
        this.context = context;
        this.itemClickListener = itemClickListener;
        this.fileList = new ArrayList<>();
        for (File file : files) {
            this.fileList.add(new FileItem(file));
        }
        // Initialize filtered list as a copy of master list
        this.fileListFiltered = new ArrayList<>(this.fileList);
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.grid_item_file_delete, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final FileViewHolder holder, final int position) {
        if (position >= fileListFiltered.size()) return;

        final FileItem item = fileListFiltered.get(position);
        final File file = item.getFile();

        holder.fileName.setText(file.getName());
        holder.selectionOverlay.setVisibility(item.isSelected() ? View.VISIBLE : View.GONE);

        // Reset listener
        holder.selectionCheckbox.setOnCheckedChangeListener(null);
        holder.selectionCheckbox.setChecked(item.isSelected());

        holder.selectionCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                item.setSelected(isChecked);
                holder.selectionOverlay.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (itemClickListener != null) {
                    itemClickListener.onSelectionChanged();
                }
            }
        });

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (itemClickListener != null) {
                    // Find original index in master list
                    int originalPosition = fileList.indexOf(item);
                    if (originalPosition != -1) {
                        itemClickListener.onItemClick(originalPosition);
                    }
                }
            }
        });

        // GLIDE INTEGRATION
        int fallbackIcon = getIconForFileType(file.getName());
        
        Glide.with(context)
            .load(file)
            .apply(new RequestOptions()
                .placeholder(fallbackIcon)
                .error(fallbackIcon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop())
            .into(holder.thumbnailImage);
    }

    private int getIconForFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".pdf")) return android.R.drawable.ic_menu_save;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return android.R.drawable.ic_menu_agenda;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return android.R.drawable.ic_menu_slideshow;
        if (lower.endsWith(".txt") || lower.endsWith(".rtf") || lower.endsWith(".log")) return android.R.drawable.ic_menu_view;
        if (lower.endsWith(".html") || lower.endsWith(".xml") || lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".c") || lower.endsWith(".cpp")) return android.R.drawable.ic_menu_edit;
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) return android.R.drawable.ic_menu_set_as;
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")) return android.R.drawable.ic_media_play;
        return android.R.drawable.ic_menu_info_details;
    }

    @Override
    public int getItemCount() {
        return fileListFiltered.size();
    }

    public List<FileItem> getItems() {
        return fileListFiltered;
    }

    public List<FileItem> getOriginalItems() {
        return fileList;
    }

    public void selectAll(boolean select) {
        for (FileItem item : fileListFiltered) {
            item.setSelected(select);
        }
        notifyDataSetChanged();
        if (itemClickListener != null) {
            itemClickListener.onSelectionChanged();
        }
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String charString = constraint.toString().toLowerCase();
                List<FileItem> filteredList = new ArrayList<>();
                if (charString.isEmpty()) {
                    filteredList.addAll(fileList);
                } else {
                    for (FileItem item : fileList) {
                        if (item.getFile().getName().toLowerCase().contains(charString) || 
                            item.getFile().getParentFile().getName().toLowerCase().contains(charString)) {
                            filteredList.add(item);
                        }
                    }
                }

                FilterResults filterResults = new FilterResults();
                filterResults.values = filteredList;
                return filterResults;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                fileListFiltered = (ArrayList<FileItem>) results.values;
                notifyDataSetChanged();
                if (itemClickListener != null) {
                    itemClickListener.onSelectionChanged();
                }
            }
        };
    }

    public static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnailImage;
        TextView fileName;
        View selectionOverlay;
        CheckBox selectionCheckbox;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImage = itemView.findViewById(R.id.thumbnail_image_delete);
            fileName = itemView.findViewById(R.id.file_name_delete);
            selectionOverlay = itemView.findViewById(R.id.selection_overlay);
            selectionCheckbox = itemView.findViewById(R.id.selection_checkbox);
        }
    }

    public static class FileItem {
        private File file;
        private boolean isSelected;

        public FileItem(File file) {
            this.file = file;
            this.isSelected = false;
        }

        public File getFile() {
            return file;
        }

        public boolean isSelected() {
            return isSelected;
        }

        public void setSelected(boolean selected) {
            isSelected = selected;
        }
    }
}