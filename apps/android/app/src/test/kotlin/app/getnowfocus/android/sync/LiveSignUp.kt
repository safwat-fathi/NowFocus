package app.getnowfocus.android.sync

import org.junit.Assume.assumeTrue

/**
 * Sign-up is confirmed by an emailed link a test can't open, so live tests promote the pending sign-up straight in the
 * database (SYNC_IT_DATABASE_URL, needs `psql`), then log in. Same row the server's verify step uses.
 */
fun confirmSignUp(email: String) {
    val db = System.getenv("SYNC_IT_DATABASE_URL")
    assumeTrue("set SYNC_IT_DATABASE_URL to confirm sign-ups in live tests", db != null)
    val sql = "insert into users (id, email, password_hash) select gen_random_uuid(), email, password_hash from pending_signups where email = '${email.lowercase()}'; " +
        "delete from pending_signups where email = '${email.lowercase()}'"
    val p = ProcessBuilder("psql", db, "-v", "ON_ERROR_STOP=1", "-c", sql).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0 && out.contains("INSERT 0 1")) { "could not confirm the sign-up: $out" }
}

suspend fun SyncApi.registerConfirmed(email: String, password: String, deviceName: String): AccountSession {
    register(email, password, deviceName)
    confirmSignUp(email)
    return login(email, password, deviceName)
}
