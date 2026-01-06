// ... existing imports ...
import java.util.Objects;
// Add Mux imports here (Example: import com.mux.stats.sdk.muxstats.MuxStatsExoPlayer;)

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
        if (Objects.equals(httpHeaders.get("vtt"), "true")) {
            initializeMUXDataAnalytics(context, uri.toString(), httpHeaders);
        }
    }

    // ... buildHttpDataSourceFactory and buildMediaSource methods ...

    private void initializeMUXDataAnalytics(Context context, String videoURL, Map<String, String> data) {
        // Set Video Data
        customerData.getCustomerVideoData().setVideoTitle(
            data.get("videoTitle") == null ? "STAGE-ANDROID" : data.get("videoTitle")
        );
        customerData.getCustomerVideoData().setVideoSourceUrl(videoURL);

        // Set View Data
        customerData.setCustomerViewData(new CustomerViewData());
        customerData.getCustomerViewData().setViewSessionId(
            data.get("sessionID") == null ? "STAGE-ANDROID" : data.get("sessionID")
        );

        // Set Custom Data (MAX 5)
        customerData.setCustomData(new CustomData());
        customerData.getCustomData().setCustomData1(data.getOrDefault("customData1", ""));
        customerData.getCustomData().setCustomData2(data.getOrDefault("customData2", ""));
        customerData.getCustomData().setCustomData3(data.getOrDefault("customData3", ""));
        customerData.getCustomData().setCustomData4(data.getOrDefault("customData4", ""));
        customerData.getCustomData().setCustomData5(data.getOrDefault("customData5", ""));

        // Initialize Mux Player Monitor
        muxStatsExoPlayer = new MuxStatsExoPlayer(
            context, 
            Objects.requireNonNull(data.get("muxEnvKey")), 
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
