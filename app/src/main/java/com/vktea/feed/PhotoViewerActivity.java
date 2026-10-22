package com.vktea.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.vktea.R;

import java.util.ArrayList;
import java.util.List;

public class PhotoViewerActivity extends AppCompatActivity {

    private ViewPager2 pager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_viewer);

        List<String> photos = getIntent().getStringArrayListExtra("photos");
        int index = getIntent().getIntExtra("index", 0);
        if (photos == null || photos.isEmpty()) {
            finish();
            return;
        }

        pager = findViewById(R.id.photo_pager);
        pager.setAdapter(new PhotoAdapter(photos));
        pager.setCurrentItem(index, false);
    }

    static class PhotoAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<PhotoAdapter.Holder> {

        private final List<String> photos;

        PhotoAdapter(List<String> photos) {
            this.photos = photos;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_photo_viewer, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Glide.with(holder.photo)
                    .load(photos.get(position))
                    .override(holder.photo.getContext().getResources().getDisplayMetrics().widthPixels,
                            com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
                    .into(holder.photo);
            holder.photo.setOnClickListener(v -> ((AppCompatActivity) holder.photo.getContext()).finish());
        }

        @Override
        public int getItemCount() {
            return photos.size();
        }

        static class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final com.github.chrisbanes.photoview.PhotoView photo;

            Holder(@NonNull View itemView) {
                super(itemView);
                photo = itemView.findViewById(R.id.viewer_photo);
            }
        }
    }
}
