# SQLCipher is called from native code via JNI.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# PDFBox resolves filters, fonts and security handlers reflectively.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.**
