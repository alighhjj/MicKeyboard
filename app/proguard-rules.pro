# OkHttp / Okio 自带 consumer rules，无需额外配置。
# 保留辅助服务与磁贴服务（已通过 Manifest 引用保留，这里显式声明以防 R8 误伤）。
-keep class com.qwen.voiceinput.service.TextInsertionAccessibilityService { *; }
-keep class com.qwen.voiceinput.service.FloatingBubbleService { *; }
-keep class com.qwen.voiceinput.service.VoiceInputTileService { *; }
