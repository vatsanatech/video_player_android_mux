// ... existing imports ...
import java.util.Objects;
// Add Mux imports here (Example: import com.mux.stats.sdk.muxstats.MuxStatsExoPlayer;)
package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

// Mux SDK Imports
import com.mux.stats.sdk.muxstats.MuxStatsExoPlayer;
import com.mux.stats.sdk.muxstats.CustomerData;
import com.mux.stats.sdk.muxstats.CustomerVideoData;
import com.mux.stats.sdk.muxstats.CustomerViewData;
import com.mux.stats.sdk.muxstats.CustomData;

// Flutter Imports
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;

import java.util.Map;
import java.util.Objects;

final class VideoPlayer {
    private static final String FORMAT_SS = "ss";
    private static final String FORMAT_DASH = "dash";
    private static final String FORMAT_HLS = "hls";
    private static final String FORMAT_OTHER = "other";

    private ExoPlayer exoPlayer;
    private Surface surface;
    private final TextureRegistry.SurfaceTextureEntry textureEntry;
    private QueuingEventSink eventSink;
    private final EventChannel eventChannel;
    private static final String USER_AGENT = "User-Agent";

    @VisibleForTesting boolean isInitialized = false;
    private final VideoPlayerOptions options;
    private DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();

    // MUX Fields
    private MuxStatsExoPlayer muxStatsExoPlayer = null;
    private CustomerData customerData = new CustomerData();

    VideoPlayer(
        Context context,
        EventChannel eventChannel,
        TextureRegistry.SurfaceTextureEntry textureEntry,
        String dataSource,
        String formatHint,
        @NonNull Map<String, String> httpHeaders,
        VideoPlayerOptions options) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;

        this.exoPlayer = new ExoPlayer.Builder(context).build();
        Uri uri = Uri.parse(dataSource);

        buildHttpDataSourceFactory(httpHeaders);
        DataSource.Factory dataSourceFactory =
            new DefaultDataSource.Factory(context, httpDataSourceFactory);

        MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint);

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();

        setUpVideoPlayer(exoPlayer, new QueuingEventSink());

        // Initialize MUX if enabled in headers
        if (Objects.equals(httpHeaders.get("enableMuxAnalytics"), "true")) {
            initializeMUXDataAnalytics(context, uri.toString(), httpHeaders);
        }
    }

    // ... buildHttpDataSourceFactory and buildMediaSource methods ...

    private void initializeMUXDataAnalytics(Context context, String videoURL, Map<String, String> headers) {
    // 1. Get and Validate Mux Environment Key
    String muxEnvKey = headers.get("muxEnvKey");
    if (muxEnvKey == null || muxEnvKey.isEmpty()) {
        android.util.Log.e("VideoPlayer", "Mux SDK: ❌ Environment key not found in headers");
        android.util.Log.e("VideoPlayer", "Mux SDK: Available headers: " + headers.keySet());
        return;
    }

    android.util.Log.d("VideoPlayer", "Mux SDK: Initializing with env key: " +
        muxEnvKey.substring(0, Math.min(10, muxEnvKey.length())) + "...");

    // 2. Initialize Video Data (Check Short Codes from Flutter)
    CustomerVideoData videoData = new CustomerVideoData();

    // Video Title: Check "vtt" (short code) -> "videoTitle" -> "cvd_video_title"
    String videoTitle = headers.get("vtt");
    if (videoTitle == null || videoTitle.isEmpty()) videoTitle = headers.get("videoTitle");
    if (videoTitle == null || videoTitle.isEmpty()) videoTitle = headers.get("cvd_video_title");
    videoData.setVideoTitle(videoTitle != null ? videoTitle : "STAGE-ANDROID");

    videoData.setVideoSourceUrl(videoURL);

    // Video ID: Check "vid" (short code) -> "videoId" -> "cvd_video_id"
    String videoId = headers.get("vid");
    if (videoId == null || videoId.isEmpty()) videoId = headers.get("videoId");
    if (videoId == null || videoId.isEmpty()) videoId = headers.get("cvd_video_id");
    if (videoId != null && !videoId.isEmpty()) {
        videoData.setVideoId(videoId);
    }

    // Video Duration: Check "vdu" (short code)
    String videoDuration = headers.get("vdu");
    if (videoDuration != null && !videoDuration.isEmpty()) {
        try {
            videoData.setVideoDuration(Long.parseLong(videoDuration));
        } catch (NumberFormatException e) {
            android.util.Log.w("VideoPlayer", "Mux SDK: Invalid video duration: " + videoDuration);
        }
    }

    // 3. Set View Data (Session Tracking)
    CustomerViewData viewData = new CustomerViewData();
    String sessionId = headers.get("xseid");
    viewData.setViewSessionId(sessionId != null ? sessionId : "STAGE-ANDROID");

    // 4. Update the main customerData object
    customerData.setCustomerVideoData(videoData);
    customerData.setCustomerViewData(viewData);

    // 5. Initialize Mux Player Monitor
    muxStatsExoPlayer = new MuxStatsExoPlayer(
        context, 
        muxEnvKey, 
        exoPlayer, 
        customerData
    );
}

    private void setUpVideoPlayer(ExoPlayer exoPlayer, QueuingEventSink eventSink) {
        this.exoPlayer = exoPlayer;
        this.eventSink = eventSink;

        eventChannel.setStreamHandler(
            new EventChannel.StreamHandler() {
                @Override
                public void onListen(Object o, EventChannel.EventSink sink) {
                    eventSink.setDelegate(sink);
                }

                @Override
                public void onCancel(Object o) {
                    eventSink.setDelegate(null);
                }
            });

        surface = new Surface(textureEntry.surfaceTexture());
        exoPlayer.setVideoSurface(surface);
        setAudioAttributes(exoPlayer, options.mixWithOthers);

        exoPlayer.addListener(new Listener() {
            // ... existing listener implementation ...
        });
    }

    // ... existing play, pause, seekTo, etc. methods ...

    void dispose() {
        if (muxStatsExoPlayer != null) {
            muxStatsExoPlayer.release();
        }
        if (isInitialized) {
            exoPlayer.stop();
        }
        textureEntry.release();
        eventChannel.setStreamHandler(null);
        if (surface != null) {
            surface.release();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }
}
