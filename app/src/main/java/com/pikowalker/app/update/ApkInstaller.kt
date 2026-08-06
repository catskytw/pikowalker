package com.pikowalker.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {

    fun canInstallUnknownApps(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun install(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)

        // 自我更新時系統不保證會強制關閉呼叫端的舊行程（尤其部分 OEM 的背景凍結／
        // 省電機制會讓被滑掉的行程留在記憶體），導致更新後重開卻接回舊行程、顯示舊版本。
        // 系統安裝畫面是獨立的行程，不依賴我們存活，延遲一下再自殺，確保下次開啟一定是全新行程。
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, 1500)
    }
}
