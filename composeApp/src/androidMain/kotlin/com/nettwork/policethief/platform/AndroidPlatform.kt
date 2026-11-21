package com.nettwork.policethief.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.nettwork.policethief.di.REAL_LOCATION_PROVIDER
import com.nettwork.policethief.platform.background.AndroidBackgroundLocationController
import com.nettwork.policethief.platform.location.RealLocationProvider
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android 플랫폼 구현을 Koin 에 등록한다. androidApp 의 Application.onCreate 에서 사용. */
fun androidPlatformModule(context: Context) = module {
    val appContext = context.applicationContext
    single<HttpClientEngine> { OkHttp.create() }
    single<Settings> { SharedPreferencesSettings(appContext.getSharedPreferences("pnt_settings", Context.MODE_PRIVATE)) }
    single<SecureTokenStorage> { KeystoreTokenStorage(appContext) }
    single<PermissionController> { AndroidPermissionController(appContext) }
    single<LocationProvider>(REAL_LOCATION_PROVIDER) { RealLocationProvider(appContext) }
    single<BackgroundLocationController> { AndroidBackgroundLocationController(appContext) }
    single<GameAlerts> { AndroidGameAlerts(appContext) }
}

/** 권한 이름 매핑. */
internal fun AppPermission.androidPermissions(): List<String> = when (this) {
    AppPermission.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    AppPermission.CAMERA -> listOf(Manifest.permission.CAMERA)
    AppPermission.NOTIFICATION ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
}

class AndroidPermissionController(private val context: Context) : PermissionController {
    /**
     * Android 는 Activity 없이 "아직 묻지 않음"과 "거부"를 구분할 수 없으므로 GRANTED / DENIED 만 돌려준다.
     * 위치는 대략적 위치만 허용해도 GRANTED 로 본다(정확도는 위치 값의 accuracy 로 드러남).
     */
    override fun status(permission: AppPermission): PermissionState {
        val names = permission.androidPermissions()
        if (names.isEmpty()) return PermissionState.GRANTED
        val granted = names.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        return if (granted) PermissionState.GRANTED else PermissionState.DENIED
    }

    override fun openAppSettings() {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * Android Keystore 의 AES-256-GCM 키로 토큰을 암호화해 SharedPreferences 에 저장한다.
 * 키는 하드웨어 보안 영역(지원 기기)에 있고 앱 밖으로 꺼낼 수 없다.
 * (androidx.security-crypto 의 EncryptedSharedPreferences 는 지원 중단(deprecated)되어 직접 구현했다.)
 */
class KeystoreTokenStorage(context: Context) : SecureTokenStorage {
    private val prefs = context.getSharedPreferences("pnt_secure", Context.MODE_PRIVATE)

    override fun readToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            // 키가 무효화된 경우(기기 잠금 변경, 백업 복원 등) 복호화할 수 없으므로 토큰을 지우고 재로그인하게 한다.
            Log.w(TAG, "저장된 토큰을 복호화하지 못해 삭제합니다: ${e.message}")
            clear()
            null
        }
    }

    override fun writeToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreTokenStorage"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pnt_auth_token_key"
        const val KEY_TOKEN = "auth_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
