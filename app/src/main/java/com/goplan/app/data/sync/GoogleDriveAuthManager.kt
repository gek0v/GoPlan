package com.goplan.app.data.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

@Suppress("DEPRECATION")
class GoogleDriveAuthManager(
    private val context: Context? = null,
    private val clientProvider: ((Context) -> GoogleSignInClient)? = null,
    private val signInResultExtractor: ((Intent?) -> Task<GoogleSignInAccount>)? = null,
    private val existingAccountProvider: ((Context?) -> GoogleSignInAccount?)? = null
) {
    private val _signedInAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val signedInAccount: StateFlow<GoogleSignInAccount?> = _signedInAccount.asStateFlow()

    init {
        checkExistingSignIn()
    }

    private fun checkExistingSignIn() {
        _signedInAccount.value = try {
            if (existingAccountProvider != null) {
                existingAccountProvider.invoke(context)
            } else {
                context?.let { ctx -> GoogleSignIn.getLastSignedInAccount(ctx) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getSignInIntent(): Intent {
        val ctx = context ?: throw IllegalStateException("Context is required for getSignInIntent")
        val client = clientProvider?.invoke(ctx) ?: run {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                .build()
            GoogleSignIn.getClient(ctx, gso)
        }
        return client.signInIntent
    }

    fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount> {
        if (data == null) {
            return Result.failure(IllegalArgumentException("Intent data is null"))
        }
        return try {
            val task = signInResultExtractor?.invoke(data) ?: GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                _signedInAccount.value = account
                Result.success(account)
            } else {
                Result.failure(IllegalStateException("No se pudo obtener la cuenta de Google"))
            }
        } catch (e: Throwable) {
            Result.failure(formatSignInException(e))
        }
    }

    fun getDriveService(account: GoogleSignInAccount): Drive {
        val ctx = context ?: throw IllegalStateException("Context is required to create Drive service")
        val credential = GoogleAccountCredential.usingOAuth2(
            ctx, setOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account.account
        }
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("GoPlan").build()
    }

    fun signOut(onComplete: () -> Unit = {}) {
        _signedInAccount.value = null
        context?.let { ctx ->
            try {
                val client = clientProvider?.invoke(ctx)
                    ?: GoogleSignIn.getClient(ctx, GoogleSignInOptions.DEFAULT_SIGN_IN)
                client.signOut().addOnCompleteListener {
                    onComplete()
                }
            } catch (_: Throwable) {
                onComplete()
            }
        } ?: onComplete()
    }

    fun isConfigured(): Boolean {
        return try {
            val ctx = context ?: return false
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                .build()
            clientProvider?.invoke(ctx) ?: GoogleSignIn.getClient(ctx, gso)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getSha1Fingerprint(): String = getAppSha1Fingerprint(context)

    companion object {
        fun formatSignInException(e: Throwable): Throwable {
            val apiException = findApiException(e)
            if (apiException != null) {
                val statusCode = apiException.statusCode
                val statusMessage = CommonStatusCodes.getStatusCodeString(statusCode)
                if (statusCode == 10 || statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
                    val msg = "Error 10 (DEVELOPER_ERROR): Falta autorizar la app en Google Cloud Console. Revisa:\n" +
                            "1. Añade tu correo a 'Usuarios de prueba' (Test Users) en la Pantalla de consentimiento OAuth.\n" +
                            "2. Verifica que el ID de cliente Android tenga el paquete 'com.goplan.app' y la huella SHA-1.\n" +
                            "3. Asegúrate de que 'Google Drive API' esté habilitada y añade el permiso '.../auth/drive.file'."
                    return IllegalStateException(msg, e)
                }
                return IllegalStateException("Google Sign-In falló [$statusCode: $statusMessage]: ${apiException.message ?: ""}", e)
            }
            val msg = e.message ?: ""
            if (msg.contains("10:") || msg.contains("10 :") || msg.contains("DEVELOPER_ERROR")) {
                val formatted = "Error 10 (DEVELOPER_ERROR): Falta autorizar la app en Google Cloud Console. Revisa:\n" +
                        "1. Añade tu correo a 'Usuarios de prueba' (Test Users) en la Pantalla de consentimiento OAuth.\n" +
                        "2. Verifica que el ID de cliente Android tenga el paquete 'com.goplan.app' y la huella SHA-1.\n" +
                        "3. Asegúrate de que 'Google Drive API' esté habilitada y añade el permiso '.../auth/drive.file'."
                return IllegalStateException(formatted, e)
            }
            return e
        }

        private fun findApiException(e: Throwable?): ApiException? {
            var current = e
            while (current != null) {
                if (current is ApiException) return current
                current = current.cause
            }
            return null
        }

        fun getAppSha1Fingerprint(context: Context?): String {
            if (context == null) return "No disponible (sin Context)"
            return try {
                val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val packageInfo = context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    packageInfo.signingInfo?.apkContentsSigners
                        ?: packageInfo.signingInfo?.signingCertificateHistory
                } else {
                    @Suppress("DEPRECATION")
                    val packageInfo = context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNATURES
                    )
                    @Suppress("DEPRECATION")
                    packageInfo.signatures
                }

                val cert = signatures?.firstOrNull()?.toByteArray()
                    ?: return "No se encontró certificado de firma"

                val md = MessageDigest.getInstance("SHA-1")
                val digest = md.digest(cert)
                digest.joinToString(":") { String.format("%02X", it) }
            } catch (ex: Throwable) {
                "Error al obtener SHA-1: ${ex.message}"
            }
        }
    }
}
