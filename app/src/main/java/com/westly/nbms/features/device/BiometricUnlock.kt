package com.westly.nbms.features.device

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Name shown on the biometric prompt. The app is called "NBMS" everywhere the person can see it. */
internal const val BIOMETRIC_PROMPT_TITLE = "Unlock NBMS"

/**
 * The fingerprint option on the lock screen. It is offered only when the person turned it on, this phone has a
 * Device PIN for them, the phone has a strong biometric set up, and the server has not paused PIN tries.
 * The PIN keypad always stays available. Pure, so it can be tested.
 */
internal fun biometricOfferedOnLock(
    enabled: Boolean,
    hasPin: Boolean,
    available: Boolean,
    pausedByServer: Boolean
): Boolean = enabled && hasPin && available && !pausedByServer

/** How one biometric check ended. */
sealed interface BiometricResult {
    data object Success : BiometricResult

    /** The person closed the prompt or chose "Use PIN". Nothing went wrong. */
    data object Cancelled : BiometricResult

    /** The phone's fingerprints changed since biometric unlock was turned on, so the saved key no longer works. */
    data object KeyInvalidated : BiometricResult

    data class Failed(val message: String) : BiometricResult
}

/**
 * Biometric unlock for the Device Lock (the Device PIN on this phone). It adds a way in next to the PIN; it never
 * replaces it. Only Class 3 ("strong") biometrics count, and the check is tied to a key held in the Android
 * Keystore that can only be used right after a successful biometric check and is destroyed when new fingerprints
 * are enrolled. A "yes" that is not backed by that key is never accepted.
 */
@Singleton
class BiometricUnlock @Inject constructor(@ApplicationContext private val context: Context) {

    /** True when this phone has hardware and an enrolled strong biometric the app may use. */
    fun isAvailable(): Boolean =
        try {
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }

    /**
     * Shows the system biometric prompt for [uid]. Creates the person's key the first time.
     * Must be called on the main thread with a resumed [activity].
     */
    suspend fun authenticate(activity: FragmentActivity, uid: String, title: String, subtitle: String): BiometricResult {
        if (!isAvailable()) return BiometricResult.Failed(MSG_BIOMETRIC_UNAVAILABLE)

        val cipher = try {
            newCipher(uid)
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKey(uid)
            return BiometricResult.KeyInvalidated
        } catch (_: Exception) {
            return BiometricResult.Failed(MSG_BIOMETRIC_GENERIC)
        }

        return suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Using the key proves the phone's secure hardware really saw the biometric.
                    val proven = try {
                        result.cryptoObject?.cipher?.doFinal(ByteArray(1)) != null
                    } catch (_: Exception) {
                        false
                    }
                    if (cont.isActive) {
                        cont.resume(if (proven) BiometricResult.Success else BiometricResult.Failed(MSG_BIOMETRIC_GENERIC))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val outcome = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> BiometricResult.Cancelled
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricResult.Failed(MSG_BIOMETRIC_LOCKOUT)
                        else -> BiometricResult.Failed(errString.toString().ifBlank { MSG_BIOMETRIC_GENERIC })
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
                // onAuthenticationFailed (one wrong finger) keeps the prompt open; the system counts the tries.
            }

            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }

    /** Removes [uid]'s key from the Keystore (biometric unlock turned off, device removed). */
    fun deleteKey(uid: String) {
        try {
            keyStore().deleteEntry(alias(uid))
        } catch (_: Exception) {
            // Nothing to remove, or the Keystore is busy: an unused key does no harm.
        }
    }

    private fun alias(uid: String) = "nbms_biometric_$uid"

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun newCipher(uid: String): Cipher {
        val store = keyStore()
        val name = alias(uid)
        if (!store.containsAlias(name)) createKey(name)
        val key = store.getKey(name, null) as SecretKey
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    @Suppress("DEPRECATION")
    private fun createKey(name: String) {
        val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Every single use needs a fresh strong-biometric check.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal const val MSG_BIOMETRIC_UNAVAILABLE =
    "Fingerprint or face unlock is not set up on this phone. Add one in your phone's Settings, then try again."
internal const val MSG_BIOMETRIC_LOCKOUT = "Too many tries. Use your PIN, or try the fingerprint again later."
internal const val MSG_BIOMETRIC_GENERIC = "Biometric check didn't work. Use your PIN instead."
internal const val MSG_BIOMETRIC_KEY_CHANGED =
    "Your phone's fingerprints changed, so biometric unlock was turned off. Use your PIN, then turn it on again in Device Settings."

/** The [FragmentActivity] this composable lives in (the biometric prompt needs one), or null. */
internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
