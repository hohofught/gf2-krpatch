# Shizuku 가 특권 프로세스에서 리플렉션으로 로드하므로 이름이 바뀌면 안 된다
-keep class com.hoho.snqxkr.FileService { *; }
-keep class com.hoho.snqxkr.IFileService { *; }
-keep class com.hoho.snqxkr.IFileService$* { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
