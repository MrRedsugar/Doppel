# Adapted from centic9/poi-on-android a29c7a4cdd94175c2be43678c82afc818b60ff3f.
# Copyright 2015-2024 Dominik Stadler, Apache-2.0.
# Doppel: omit app-wide optimizations; preserve reflectively selected parser factories/providers.
# Apache POI
-dontwarn org.apache.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn org.etsi.**
-dontwarn org.w3.**
-dontwarn com.microsoft.schemas.**
-dontwarn com.graphbuilder.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**
-dontwarn java.awt.**
-dontwarn net.sf.saxon.**
-dontwarn org.apache.batik.**
-dontwarn org.apache.logging.log4j.**

-dontnote org.apache.**
-dontnote org.openxmlformats.schemas.**
-dontnote org.etsi.**
-dontnote org.w3.**
-dontnote com.microsoft.schemas.**
-dontnote com.graphbuilder.**
-dontnote javax.naming.**
-dontnote java.lang.management.**
-dontnote org.slf4j.impl.**

-keeppackagenames org.apache.poi.ss.formula.function

-keep,allowoptimization,allowobfuscation class org.apache.logging.log4j.** { *; }
-keep,allowoptimization class org.apache.commons.compress.archivers.zip.** { *; }
-keep,allowoptimization class org.apache.poi.schemas.** { *; }
-keep,allowoptimization class org.apache.xmlbeans.** { *; }
-keep,allowoptimization class org.openxmlformats.schemas.** { *; }
-keep,allowoptimization class com.microsoft.schemas.** { *; }

-keep class com.fasterxml.aalto.stax.InputFactoryImpl { public <init>(); }
-keep class com.fasterxml.aalto.stax.OutputFactoryImpl { public <init>(); }
-keep class com.fasterxml.aalto.stax.EventFactoryImpl { public <init>(); }
-keep class org.apache.logging.log4j.simple.SimpleLoggerContextFactory { public <init>(); }
-keep class * implements org.apache.poi.extractor.ExtractorProvider { public <init>(); }
-keep class * implements org.apache.poi.sl.usermodel.SlideShowProvider { public <init>(); }
-keep class * implements org.apache.poi.ss.usermodel.WorkbookProvider { public <init>(); }
