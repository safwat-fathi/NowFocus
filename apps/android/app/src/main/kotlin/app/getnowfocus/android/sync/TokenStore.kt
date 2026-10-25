package app.getnowfocus.android.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.syncAuthStore by preferencesDataStore(name = "sync_auth")

private val REFRESH_KEY = stringPreferencesKey("refresh")
private val PRO_KEY = booleanPreferencesKey("pro")

/**
 * What the server last said about the plan: true Pro, false Free, null when signed out or not asked yet. Read here
 * rather than from the app's main DataStore so a Google backup restore can never bring Pro back without a sign-in
 * (this file is excluded from backup, and [DataStoreAuthStore.clear] wipes it).
 */
fun Context.storedProFlow(): Flow<Boolean?> =
    applicationContext.syncAuthStore.data.map { p -> if (p[REFRESH_KEY] == null) null else p[PRO_KEY] }

/** Encrypts the refresh token with a key that never leaves the Android Keystore. */
interface SecretBox {
    fun encrypt(plain: String): String
    /** Null when it can't be read (key lost after a restore or a lock-screen reset): the user simply signs in again. */
    fun decrypt(sealed: String): String?
}

class KeystoreSecretBox(private val alias: String = "nowfocus_sync_token") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    override fun decrypt(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        String(c.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()
}

/**
 * Who this device is signed in as. Lives in its own DataStore file (sync_auth), which the backup rules exclude:
 * a restored phone must sign in again rather than inherit tokens.
 */
class DataStoreAuthStore(context: Context, private val box: SecretBox = KeystoreSecretBox()) : AuthStore {
    private val store = context.applicationContext.syncAuthStore
    private val userId = stringPreferencesKey("userId")
    private val email = stringPreferencesKey("email")
    private val deviceId = stringPreferencesKey("deviceId")
    private val refresh = REFRESH_KEY

    override suspend fun load(): StoredAuth? {
        val p = store.data.first()
        val token = p[refresh]?.let(box::decrypt) ?: return null
        return StoredAuth(p[userId] ?: return null, p[email] ?: return null, p[deviceId] ?: return null, token)
    }

    override suspend fun save(auth: StoredAuth) {
        store.edit { p ->
            if (p[userId] != auth.userId) p.remove(PRO_KEY)   // another account: its plan isn't known yet (a refresh saves the same one)
            p[userId] = auth.userId; p[email] = auth.email; p[deviceId] = auth.deviceId; p[refresh] = box.encrypt(auth.refreshToken)
        }
    }

    override suspend fun clear() { store.edit { it.clear() } }

    // Only while signed in: a pull that finishes after sign-out must not leave a Pro flag behind with no account.
    override suspend fun setPro(pro: Boolean) { store.edit { p -> if (p[refresh] != null) p[PRO_KEY] = pro } }
}
