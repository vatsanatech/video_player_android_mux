package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.mux.stats.sdk.muxstats.MuxStatsExoPlayer;
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

        exoPlayer = new ExoPlayer.Builder(context).build();
        Uri uri = Uri.parse(dataSource);

        buildHttpDataSourceFactory(httpHeaders);
        DataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(context, httpDataSourceFactory);

        // ✅ FIXED: Restored buildMediaSource call so video actually loads
        MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint);

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();

        setUpVideoPlayer(exoPlayer, new QueuingEventSink());

        // ✅ MUX Initialization logic
        if (Objects.equals(httpHeaders.get("enableMuxAnalytics"), "true")) {
            initializeMUXDataAnalytics(context, uri.toString(), httpHeaders);
        }
    }

    @VisibleForTesting
    public void buildHttpDataSourceFactory(@NonNull Map<String, String> httpHeaders) {
        final boolean httpHeadersNotEmpty = !httpHeaders.isEmpty();
        final String userAgent =
                httpHeadersNotEmpty && httpHeaders.containsKey(USER_AGENT)
                        ? httpHeaders.get(USER_AGENT)
                        : "ExoPlayer";

        httpDataSourceFactory.setUserAgent(userAgent).setAllowCrossProtocolRedirects(true);

        if (httpHeadersNotEmpty) {
            httpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
        }
    }

    private MediaSource buildMediaSource(
            Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint) {
        int type;
        if (formatHint == null) {
            type = Util.inferContentType(uri);
        } else {
            switch (formatHint) {
                case FORMAT_SS: type = C.CONTENT_TYPE_SS; break;
                case FORMAT_DASH: type = C.CONTENT_TYPE_DASH; break;
                case FORMAT_HLS: type = C.CONTENT_TYPE_HLS; break;
                case FORMAT_OTHER: type = C.CONTENT_TYPE_OTHER; break;
                default: type = -1; break;
            }
        }
        switch (type) {
            case C.CONTENT_TYPE_SS:
                return new SsMediaSource.Factory(new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_DASH:
                return new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.CONTENT_TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            default: throw new IllegalStateException("Unsupported type: " + type);
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

        exoPlayer.addListener(
                new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(final int playbackState) {
                        if (playbackState == Player.STATE_BUFFERING) {
                            sendBufferingUpdate();
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "bufferingStart");
                            eventSink.success(event);
                        } else if (playbackState == Player.STATE_READY) {
                            if (!isInitialized) {
                                isInitialized = true;
                                sendInitialized();
                            }
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "bufferingEnd");
                            eventSink.success(event);
                        } else if (playbackState == Player.STATE_ENDED) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "completed");
                            eventSink.success(event);
                        }
                    }

                    @Override
                    public void onPlayerError(@NonNull final PlaybackException error) {
                        if (eventSink != null) {
                            eventSink.error("VideoError", "Video player had error: " + error.getLocalizedMessage(), null);
                        }
                    }
                });
    }

    private void initializeMUXDataAnalytics(Context context, String videoURL, Map<String, String> headers) {
        String muxEnvKey = headers.get("muxEnvKey");
        if (muxEnvKey == null || muxEnvKey.isEmpty()) {
            Log.e("VideoPlayer", "Mux SDK: ❌ Environment key not found in headers");
            return;
        }

        CustomerVideoData videoData = new CustomerVideoData();
        String videoTitle = headers.get("vtt");
        if (videoTitle == null || videoTitle.isEmpty()) videoTitle = headers.get("videoTitle");
        videoData.setVideoTitle(videoTitle != null ? videoTitle : "STAGE-ANDROID");
        videoData.setVideoSourceUrl(videoURL);

        CustomerViewData viewData = new CustomerViewData();
        String viewSessionId = headers.get("xseid");
        if (viewSessionId != null) viewData.setViewSessionId(viewSessionId);

        CustomData customData = new CustomData();
        customData.setCustomData1(headers.get("c1"));
        customData.setCustomData2(headers.get("c2"));
        customData.setCustomData3(headers.get("c3"));

        customerData.setCustomerVideoData(videoData);
        customerData.setCustomerViewData(viewData);
        customerData.setCustomData(customData);

        try {
            muxStatsExoPlayer = new MuxStatsExoPlayer(context, muxEnvKey, exoPlayer, customerData);
            Log.d("VideoPlayer", "Mux SDK: ✅ Successfully initialized");
        } catch (Exception e) {
            Log.e("VideoPlayer", "Mux SDK: ❌ Failed to initialize", e);
        }
    }

    void sendBufferingUpdate() {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "bufferingUpdate");
        List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
        event.put("values", Collections.singletonList(range));
        eventSink.success(event);
    }

    private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
        exoPlayer.setAudioAttributes(
                new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                !isMixMode);
    }

    void play() { exoPlayer.setPlayWhenReady(true); }
    void pause() { exoPlayer.setPlayWhenReady(false); }
    void setLooping(boolean value) { exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF); }
    void setVolume(double value) { exoPlayer.setVolume((float) Math.max(0.0, Math.min(1.0, value))); }
    void setPlaybackSpeed(double value) { exoPlayer.setPlaybackParameters(new PlaybackParameters(((float) value))); }
    void seekTo(int location) { exoPlayer.seekTo(location); }
    long getPosition() { return exoPlayer.getCurrentPosition(); }

    void sendInitialized() {
        if (isInitialized) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "initialized");
            event.put("duration", exoPlayer.getDuration());
            if (exoPlayer.getVideoFormat() != null) {
                Format videoFormat = exoPlayer.getVideoFormat();
                int width = videoFormat.width;
                int height = videoFormat.height;
                int rotationDegrees = videoFormat.rotationDegrees;
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = videoFormat.height;
                    height = videoFormat.width;
                }
                event.put("width", width);
                event.put("height", height);
                if (rotationDegrees == 180) event.put("rotationCorrection", 180);
            }
            eventSink.success(event);
        }
    }

    void dispose() {
        if (muxStatsExoPlayer != null) {
            muxStatsExoPlayer.release();
        }
        if (isInitialized) {
            exoPlayer.stop();
        }
        textureEntry.release();
        eventChannel.setStreamHandler(null);
        if (surface != null) surface.release();
        if (exoPlayer != null) exoPlayer.release();
    }
}
