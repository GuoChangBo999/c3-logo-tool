# keep sshj + bouncycastle
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn org.slf4j.**
-dontwarn javax.**
