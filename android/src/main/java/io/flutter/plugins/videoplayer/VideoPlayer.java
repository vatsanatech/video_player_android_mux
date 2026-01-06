// Fixed VideoPlayer.java for GitHub Repository
// Repository: https://github.com/vatsanatech/video_player_android_mux
// File Path: android/src/main/java/io/flutter/plugins/videoplayer/VideoPlayer.java
//
// FIXES APPLIED:
// 1. Added all missing imports (lines 1-30)
// 2. Fixed line 50: Changed "vtt" to "enableMuxAnalytics"
// 3. Fixed initializeMUXDataAnalytics method: Correct header reading, no hardcoded key
// 4. Added error handling and logging

package io.flutter.plugins.videoplayer;

// Android Imports
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

// AndroidX Imports
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

// ExoPlayer 2.x Imports (version 2.18.7)
import com.google.android.exoplayer2.ExoPlayer;
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

// Java Imports
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

        // ✅ FIX LINE 50: Check enableMuxAnalytics header, NOT "vtt" (vtt is video title!)
        if (Objects.equals(httpHeaders.get("enableMuxAnalytics"), "true")) {
            initializeMUXDataAnalytics(context, uri.toString(), httpHeaders);
        }
    }

    // ... buildHttpDataSourceFactory and buildMediaSource methods ...

    private void initializeMUXDataAnalytics(Context context, String videoURL, Map<String, String> headers) {
        // ✅ FIX 1: Get and Validate Mux Environment Key (NOT hardcoded)
        String muxEnvKey = headers.get("muxEnvKey");
        if (muxEnvKey == null || muxEnvKey.isEmpty()) {
            Log.e("VideoPlayer", "Mux SDK: ❌ Environment key not found in headers");
            Log.e("VideoPlayer", "Mux SDK: Available headers: " + headers.keySet());
            return;
        }

        Log.d("VideoPlayer", "Mux SDK: Initializing with env key: " +
            muxEnvKey.substring(0, Math.min(10, muxEnvKey.length())) + "...");

        // ✅ FIX 2: Initialize Video Data - Check SHORT CODES first (what Flutter sends)
        CustomerVideoData videoData = new CustomerVideoData();

        // Video Title: Flutter sends "vtt" (short code), NOT "videoTitle"
        String videoTitle = headers.get("vtt");  // ✅ Primary: short code
        if (videoTitle == null || videoTitle.isEmpty()) {
            videoTitle = headers.get("videoTitle");  // Fallback: readable name
        }
        if (videoTitle == null || videoTitle.isEmpty()) {
            videoTitle = headers.get("cvd_video_title");  // Alternative format
        }
        videoData.setVideoTitle(videoTitle != null ? videoTitle : "STAGE-ANDROID");

        // Video Source URL
        videoData.setVideoSourceUrl(videoURL);

        // ✅ FIX 3: Video ID - Flutter sends "vid" (short code)
        String videoId = headers.get("vid");  // ✅ Primary: short code
        if (videoId == null || videoId.isEmpty()) {
            videoId = headers.get("videoId");  // Fallback
        }
        if (videoId == null || videoId.isEmpty()) {
            videoId = headers.get("cvd_video_id");  // Alternative format
        }
        if (videoId != null && !videoId.isEmpty()) {
            videoData.setVideoId(videoId);
        }

        // ✅ FIX 4: Video Duration - Flutter sends "vdu" (short code)
        String videoDuration = headers.get("vdu");
        if (videoDuration != null && !videoDuration.isEmpty()) {
            try {
                videoData.setVideoDuration(Long.parseLong(videoDuration));
            } catch (NumberFormatException e) {
                Log.w("VideoPlayer", "Mux SDK: Invalid video duration: " + videoDuration);
            }
        }

        // ✅ FIX 5: Video Content Type - Flutter sends "vctty" (short code)
        String contentType = headers.get("vctty");
        if (contentType != null && !contentType.isEmpty()) {
            videoData.setVideoContentType(contentType);
        }

        // ✅ FIX 6: Video Stream Type - Flutter sends "vsmty" (short code)
        String streamType = headers.get("vsmty");
        if (streamType != null && !streamType.isEmpty()) {
            videoData.setVideoStreamType(streamType);
        }

        // ✅ FIX 7: Video Series - Flutter sends "vsr" (short code)
        String videoSeries = headers.get("vsr");
        if (videoSeries != null && !videoSeries.isEmpty()) {
            videoData.setVideoSeries(videoSeries);
        }

        // ✅ FIX 8: Set View Data - Flutter sends "xseid" (short code), NOT "sessionID"
        CustomerViewData viewData = new CustomerViewData();

        // View Session ID: Flutter sends "xseid" (short code)
        String viewSessionId = headers.get("xseid");  // ✅ Primary: short code
        if (viewSessionId == null || viewSessionId.isEmpty()) {
            viewSessionId = headers.get("viewSessionId");  // Fallback
        }
        if (viewSessionId == null || viewSessionId.isEmpty()) {
            viewSessionId = headers.get("sessionID");  // Legacy fallback
        }

        if (viewSessionId != null && !viewSessionId.isEmpty()) {
            viewData.setViewSessionId(viewSessionId);
        } else {
            // Generate session ID if not provided
            viewSessionId = String.valueOf(System.currentTimeMillis());
            viewData.setViewSessionId(viewSessionId);
            Log.w("VideoPlayer", "Mux SDK: Generated session ID: " + viewSessionId);
        }

        // ✅ FIX 9: View Client Application Version - Flutter sends "xcialve" (short code)
        String appVersion = headers.get("xcialve");
        if (appVersion != null && !appVersion.isEmpty()) {
            viewData.setViewClientApplicationVersion(appVersion);
        }

        // ✅ FIX 10: Set Custom Data - Flutter sends "c1", "c2", "c3", "c4", "c5" (short codes)
        CustomData customData = new CustomData();

        // Custom Data 1 (User ID): Flutter sends "c1" (short code)
        String c1 = headers.get("c1");  // ✅ Primary: short code
        if (c1 == null || c1.isEmpty()) {
            c1 = headers.get("customData1");  // Fallback: readable name
        }
        if (c1 == null || c1.isEmpty()) {
            c1 = headers.get("cd_1");  // Alternative format
        }
        if (c1 != null && !c1.isEmpty()) {
            customData.setCustomData1(c1);
        }

        // Custom Data 2 (Subscription Status): Flutter sends "c2" (short code)
        String c2 = headers.get("c2");  // ✅ Primary: short code
        if (c2 == null || c2.isEmpty()) {
            c2 = headers.get("customData2");  // Fallback
        }
        if (c2 == null || c2.isEmpty()) {
            c2 = headers.get("cd_2");  // Alternative format
        }
        if (c2 != null && !c2.isEmpty()) {
            customData.setCustomData2(c2);
        }

        // Custom Data 3 (Content ID): Flutter sends "c3" (short code)
        String c3 = headers.get("c3");  // ✅ Primary: short code
        if (c3 == null || c3.isEmpty()) {
            c3 = headers.get("customData3");  // Fallback
        }
        if (c3 == null || c3.isEmpty()) {
            c3 = headers.get("cd_3");  // Alternative format
        }
        if (c3 != null && !c3.isEmpty()) {
            customData.setCustomData3(c3);
        }

        // Custom Data 4 (Platform): Flutter sends "c4" (short code)
        String c4 = headers.get("c4");  // ✅ Primary: short code
        if (c4 == null || c4.isEmpty()) {
            c4 = headers.get("customData4");  // Fallback
        }
        if (c4 == null || c4.isEmpty()) {
            c4 = headers.get("cd_4");  // Alternative format
        }
        if (c4 != null && !c4.isEmpty()) {
            customData.setCustomData4(c4);
        }

        // Custom Data 5 (Decoder Name): Flutter sends "c5" (short code)
        String c5 = headers.get("c5");  // ✅ Primary: short code
        if (c5 == null || c5.isEmpty()) {
            c5 = headers.get("customData5");  // Fallback
        }
        if (c5 == null || c5.isEmpty()) {
            c5 = headers.get("cd_5");  // Alternative format
        }
        if (c5 != null && !c5.isEmpty()) {
            customData.setCustomData5(c5);
        }

        // Update the main customerData object
        customerData.setCustomerVideoData(videoData);
        customerData.setCustomerViewData(viewData);
        customerData.setCustomData(customData);

        // ✅ FIX 11: Initialize Mux Player Monitor with error handling
        try {
            muxStatsExoPlayer = new MuxStatsExoPlayer(
                context,
                muxEnvKey,  // ✅ Use muxEnvKey from headers, NOT hardcoded
                exoPlayer,
                customerData
            );

            Log.d("VideoPlayer", "Mux SDK: ✅ Successfully initialized");
            Log.d("VideoPlayer", "Mux SDK: Video Title: " + videoData.getVideoTitle());
            Log.d("VideoPlayer", "Mux SDK: View Session ID: " + viewData.getViewSessionId());
            Log.d("VideoPlayer", "Mux SDK: Video ID: " + (videoId != null ? videoId : "N/A"));
            Log.d("VideoPlayer", "Mux SDK: Custom Data 1 (User ID): " + (c1 != null ? c1 : "N/A"));
            Log.d("VideoPlayer", "Mux SDK: Custom Data 2 (Subscription): " + (c2 != null ? c2 : "N/A"));

        } catch (Exception e) {
            Log.e("VideoPlayer", "Mux SDK: ❌ Failed to initialize", e);
            Log.e("VideoPlayer", "Mux SDK: Error details: " + e.getMessage());
            e.printStackTrace();
            muxStatsExoPlayer = null;
        }
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

        exoPlayer.addListener(new Player.Listener() {
            // ... existing listener implementation ...
        });
    }

    // ... existing play, pause, seekTo, etc. methods ...

    // ✅ FIX 12: Improved dispose method with proper error handling
    void dispose() {
        if (muxStatsExoPlayer != null) {
            try {
                muxStatsExoPlayer.release();
                Log.d("VideoPlayer", "Mux SDK: ✅ Released successfully");
            } catch (Exception e) {
                Log.e("VideoPlayer", "Mux SDK: ❌ Error releasing", e);
            }
            muxStatsExoPlayer = null;
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
