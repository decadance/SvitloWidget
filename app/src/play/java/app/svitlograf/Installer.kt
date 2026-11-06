package app.svitlograf

import android.content.Context
import java.io.File

/** Google Play updates the app itself; this build never installs APKs. */
object Installer {
    fun canInstall(ctx: Context): Boolean = false
    fun openInstallPermission(ctx: Context) = Unit
    fun install(ctx: Context, apk: File) = Unit
}
