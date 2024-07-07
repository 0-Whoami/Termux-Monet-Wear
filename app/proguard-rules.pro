# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html
-optimizations aggressive
-optimizationpasses 20
-allowaccessmodification
-dontskipnonpubliclibraryclasses
-repackageclasses ''
-mergeinterfacesaggressively
-overloadaggressively
-dontpreverify
-verbose
-renamesourcefileattribute SourceFile
-keep class com.termux.terminal.JNI{*;}
#-keepattributes SourceFile,LineNumberTable
