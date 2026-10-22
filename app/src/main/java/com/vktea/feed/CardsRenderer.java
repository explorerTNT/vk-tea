package com.vktea.feed;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.AuthWebViewActivity;
import com.vktea.util.RuDate;
import com.vktea.util.VkTextUtils;

import org.json.JSONObject;

import java.util.List;

public class CardsRenderer {

    public static void render(LinearLayout container, Post post, int linkColor) {
        container.removeAllViews();
        Context ctx = container.getContext();
        for (Post.Card card : post.cards) {
            switch (card.type) {
                case "poll":
                    renderPoll(container, post, card, ctx);
                    break;
                case "audio":
                case "voice":
                    renderAudio(container, card, ctx);
                    break;
                case "link":
                case "article":
                case "note":
                case "market":
                    renderLinkCard(container, card, ctx, linkColor);
                    break;
                case "doc":
                    renderRow(container, card, R.string.att_doc, card.title, card.subtitle, ctx);
                    break;
                case "geo":
                    renderRow(container, card, R.string.att_geo, card.title, "", ctx);
                    break;
                case "sticker":
                    renderSticker(container, card, ctx);
                    break;
                case "album":
                    renderAlbum(container, card, ctx);
                    break;
                case "gift":
                case "graffiti":
                    renderSticker(container, card, ctx);
                    break;
            }
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout cardBg(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(ctx, 8);
        lp.setMargins(m, m, m, 0);
        box.setLayoutParams(lp);
        box.setPadding(m, m, m, m);
        box.setBackgroundColor(0xFFF0F2F5);
        return box;
    }

    private static void renderPoll(LinearLayout container, Post post, Post.Card card, Context ctx) {
        LinearLayout box = cardBg(ctx);
        TextView question = new TextView(ctx);
        question.setText(card.title);
        question.setTextColor(0xFF2E3033);
        question.setTextSize(16);
        question.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(question);

        for (Post.PollOption option : card.options) {
            if (card.pollVoted) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = dp(ctx, 8);
                row.setLayoutParams(rowLp);

                TextView label = new TextView(ctx);
                label.setText(option.text + "  " + (option.rate > 0 ? Math.round(option.rate * 100) + "%" : ""));
                label.setTextSize(15);
                label.setTextColor(option.id == card.pollAnswerId ? 0xFF4774A8 : 0xFF2E3033);
                row.addView(label);

                ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                bar.setProgress((int) Math.round(option.rate * 100));
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 6));
                bar.setLayoutParams(barLp);
                row.addView(bar);
                box.addView(row);
            } else {
                TextView optionView = new TextView(ctx);
                optionView.setText(option.text);
                optionView.setTextSize(15);
                optionView.setTextColor(0xFF2E3033);
                optionView.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
                optionView.setBackground(ctx.getDrawable(android.R.drawable.list_selector_background));
                optionView.setOnClickListener(v -> vote(post, card, option.id, container));
                box.addView(optionView);
            }
        }

        TextView meta = new TextView(ctx);
        meta.setText(card.pollTotal + " " + ctx.getString(R.string.poll_voted_suffix)
                + (card.pollAnonymous ? " · " + ctx.getString(R.string.poll_anonymous) : ""));
        meta.setTextSize(13);
        meta.setTextColor(0xFF909499);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(ctx, 8);
        meta.setLayoutParams(metaLp);
        box.addView(meta);
        container.addView(box);
    }

    private static void vote(Post post, Post.Card card, long answerId, LinearLayout container) {
        VKApi.get(container.getContext(), "polls.addVote",
                "owner_id=" + card.pollOwnerId + "&poll_id=" + card.pollId + "&answer_ids=" + answerId,
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        card.pollVoted = true;
                        card.pollAnswerId = answerId;
                        card.pollTotal++;
                        for (Post.PollOption o : card.options) {
                            if (o.id == answerId) {
                                o.votes++;
                            }
                        }
                        int total = Math.max(1, card.pollTotal);
                        for (Post.PollOption o : card.options) {
                            o.rate = o.votes / (double) total;
                        }
                        render(container, post, 0);
                    }

                    @Override
                    public void error(Exception e) {
                        Toast.makeText(container.getContext(), R.string.action_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private static void renderAudio(LinearLayout container, Post.Card card, Context ctx) {
        LinearLayout row = cardBg(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView play = new ImageView(ctx);
        play.setImageResource(R.drawable.exo_controls_play);
        play.setColorFilter(0xFF4774A8);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40));
        play.setLayoutParams(playLp);
        row.addView(play);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = dp(ctx, 10);
        texts.setLayoutParams(textsLp);

        TextView title = new TextView(ctx);
        title.setText(card.title);
        title.setTextColor(0xFF4774A8);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(card.subtitle + (card.duration > 0 ? " · " + card.duration / 60 + ":" + String.format("%02d", card.duration % 60) : ""));
        sub.setTextColor(0xFF909499);
        sub.setTextSize(14);
        texts.addView(sub);
        row.addView(texts);
        row.setOnClickListener(v -> {
            if (card.audioUrl != null && !card.audioUrl.isEmpty()) {
                Intent intent = new Intent(ctx, VideoPlayerActivity.class);
                intent.putExtra("direct_url", card.audioUrl);
                ctx.startActivity(intent);
            } else {
                Toast.makeText(ctx, R.string.audio_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        container.addView(row);
    }

    private static void renderLinkCard(LinearLayout container, Post.Card card, Context ctx, int linkColor) {
        LinearLayout box = cardBg(ctx);
        if (!card.imageUrl.isEmpty()) {
            ImageView image = new ImageView(ctx);
            image.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(ctx).load(card.imageUrl).into(image);
            box.addView(image);
        }
        TextView title = new TextView(ctx);
        title.setText(card.title);
        title.setTextColor(linkColor);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(ctx, card.imageUrl.isEmpty() ? 0 : 8);
        title.setLayoutParams(tl);
        box.addView(title);
        if (!card.subtitle.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(card.subtitle);
            sub.setTextColor(0xFF909499);
            sub.setTextSize(13);
            box.addView(sub);
        }
        box.setOnClickListener(v -> {
            Intent intent = new Intent(ctx, AuthWebViewActivity.class);
            intent.setData(Uri.parse(card.linkUrl));
            ctx.startActivity(intent);
        });
        container.addView(box);
    }

    private static void renderRow(LinearLayout container, Post.Card card, int iconTitle, String title, String sub, Context ctx) {
        LinearLayout row = cardBg(ctx);
        TextView t = new TextView(ctx);
        t.setText("📎 " + ctx.getString(iconTitle) + ": " + title + (sub.isEmpty() ? "" : " (" + sub + ")"));
        t.setTextColor(0xFF2E3033);
        t.setTextSize(15);
        row.addView(t);
        if (!card.linkUrl.isEmpty()) {
            row.setOnClickListener(v -> {
                Intent intent = new Intent(ctx, AuthWebViewActivity.class);
                intent.setData(Uri.parse(card.linkUrl));
                ctx.startActivity(intent);
            });
        }
        container.addView(row);
    }

    private static void renderSticker(LinearLayout container, Post.Card card, Context ctx) {
        ImageView image = new ImageView(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(ctx, 128), dp(ctx, 128));
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 160), dp(ctx, 160)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Glide.with(ctx).load(card.imageUrl).into(image);
        container.addView(image);
    }

    private static void renderAlbum(LinearLayout container, Post.Card card, Context ctx) {
        LinearLayout box = cardBg(ctx);
        TextView title = new TextView(ctx);
        title.setText("🖼 " + card.title);
        title.setTextColor(0xFF4774A8);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title);
        if (!card.photos.isEmpty()) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String url : card.photos) {
                ImageView iv = new ImageView(ctx);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, dp(ctx, 90), 1f);
                ilp.rightMargin = dp(ctx, 2);
                iv.setLayoutParams(ilp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Glide.with(ctx).load(url).into(iv);
                row.addView(iv);
            }
            box.addView(row);
        }
        box.setOnClickListener(v -> {
            if (!card.linkUrl.isEmpty()) {
                Intent intent = new Intent(ctx, AuthWebViewActivity.class);
                intent.setData(Uri.parse(card.linkUrl));
                ctx.startActivity(intent);
            }
        });
        container.addView(box);
    }
}
