# kotlinx.serialization keeps its generated serializers via the library's own consumer rules.
# Media3 FFmpeg extension is loaded by reflection from DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
