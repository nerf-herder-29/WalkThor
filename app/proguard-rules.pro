# PdfBox-Android (reads its own resources and uses some reflection)
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**
-dontwarn org.slf4j.**

# WalkThor's own classes are small; keep them as written so nothing can be stripped by mistake.
-keep class com.nerfherder.walkthor.** { *; }
