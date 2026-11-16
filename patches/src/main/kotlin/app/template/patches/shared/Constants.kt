package app.template.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * YouTube Music。APKMirror の arm64-v8a / armeabi-v7a 単体 APK (APK 形式) で動作を見ている版。
     * 9.20.53 は利用者のスマホの版、9.30.53 は解析に使った版。
     */
    val COMPATIBILITY_YTM = Compatibility(
        name = "YouTube Music",
        packageName = "com.google.android.apps.youtube.music",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0000,
        targets = listOf(
            AppTarget(version = "9.20.53"),
            AppTarget(version = "9.30.53"),
        )
    )
}
