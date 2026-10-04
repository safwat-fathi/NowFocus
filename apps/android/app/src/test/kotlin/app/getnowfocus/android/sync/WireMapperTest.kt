package app.getnowfocus.android.sync

import app.getnowfocus.android.AppRule
import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy
import app.getnowfocus.android.PartialRule
import app.getnowfocus.android.PolicyMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireMapperTest {
    // A policy as it could come from the server after macOS and a newer build touched it.
    private val serverPolicy = """
        {"id":"11111111-1111-4111-8111-111111111111","name":"Deep Work","mode":"blocklist",
         "categories":["social"],"notificationPolicy":"quiet","futureField":{"a":1},
         "domainRules":[
           {"id":"d1","domain":"youtube.com","includeSubdomains":true,"enabled":true},
           {"id":"d2","domain":"reddit.com","includeSubdomains":false,"enabled":true},
           {"id":"d3","domain":"twitter.com","includeSubdomains":true,"enabled":false}],
         "applicationRules":[
           {"id":"a1","platform":"android","nativeIdentifier":"com.google.android.youtube","displayName":"YouTube","enabled":true},
           {"id":"a2","platform":"macos","nativeIdentifier":"com.apple.Music","displayName":"Music","enabled":true},
           {"id":"a3","platform":"android","nativeIdentifier":"com.old.app","displayName":"Old","enabled":false}],
         "feedRules":[{"k":"v"}],
         "partial":["YT_SHORTS","SOME_FUTURE_RULE",{"obj":true}]}
    """.trimIndent()
    private val raw get() = JSONObject(serverPolicy)

    @Test fun `toLocal keeps only what Android can enforce`() {
        val p = PolicyWire.toLocal(raw)
        assertEquals("11111111-1111-4111-8111-111111111111", p.id)
        assertEquals(listOf("youtube.com", "reddit.com"), p.domains)                 // the disabled twitter rule is not enforced
        assertEquals(listOf(AppRule("com.google.android.youtube", "YouTube")), p.apps) // other platform and disabled apps are ignored
        assertEquals(setOf(PartialRule.YT_SHORTS), p.partial)
    }

    @Test fun `an untouched round trip changes nothing the user did not touch`() {
        val merged = PolicyWire.merge(PolicyWire.toLocal(raw), raw)
        assertEquals(raw.toString().length, merged.toString().length)
        assertEquals("quiet", merged.getString("notificationPolicy"))
        assertEquals(1, merged.getJSONObject("futureField").getInt("a"))
        assertEquals("""[{"k":"v"}]""", merged.getJSONArray("feedRules").toString())
        assertEquals("blocklist", merged.getString("mode"))
        assertEquals(3, merged.getJSONArray("domainRules").length())      // including the disabled one
        assertEquals(3, merged.getJSONArray("applicationRules").length()) // including macOS's and the disabled one
        assertEquals(3, merged.getJSONArray("partial").length())          // unknown name and the object survive
        assertFalse(merged.getJSONArray("domainRules").getJSONObject(1).getBoolean("includeSubdomains")) // still false
    }

    @Test fun `editing on Android merges into the server copy instead of rebuilding it`() {
        val edited = PolicyWire.toLocal(raw).copy(
            name = "Focus",
            domains = listOf("youtube.com", "news.ycombinator.com"),           // reddit removed, hn added
            apps = listOf(AppRule("com.google.android.youtube", "YouTube TV")), // renamed
            partial = setOf(PartialRule.FB_REELS),                              // YT_SHORTS swapped for FB_REELS
        )
        val m = PolicyWire.merge(edited, raw)
        assertEquals("Focus", m.getString("name"))
        val domains = m.getJSONArray("domainRules").objects().map { it.getString("domain") }
        assertEquals(listOf("youtube.com", "twitter.com", "news.ycombinator.com"), domains) // reddit gone, disabled twitter kept, new one appended
        assertTrue(m.getJSONArray("domainRules").objects().last().getBoolean("includeSubdomains"))
        val apps = m.getJSONArray("applicationRules").objects()
        assertEquals("YouTube TV", apps.first { it.getString("nativeIdentifier") == "com.google.android.youtube" }.getString("displayName"))
        assertTrue(apps.any { it.getString("platform") == "macos" })        // macOS's rule is untouched
        val partial = m.getJSONArray("partial").items()
        assertTrue("SOME_FUTURE_RULE" in partial && "FB_REELS" in partial && "YT_SHORTS" !in partial)
        assertEquals(1, m.getJSONObject("futureField").getInt("a"))
    }

    @Test fun `re-adding a domain that is disabled on the server enables it instead of duplicating it`() {
        val edited = PolicyWire.toLocal(raw).copy(domains = listOf("youtube.com", "reddit.com", "twitter.com"))
        val rules = PolicyWire.merge(edited, raw).getJSONArray("domainRules").objects()
        assertEquals(3, rules.size)
        assertTrue(rules.first { it.getString("domain") == "twitter.com" }.getBoolean("enabled"))
    }

    @Test fun `merge never mutates the server record it started from`() {
        val before = raw.toString()
        val r = raw
        PolicyWire.merge(PolicyWire.toLocal(r).copy(domains = listOf("a.com"), name = "x"), r)
        assertEquals(before, r.toString())
    }

    @Test fun `a brand new profile is a blocklist with fresh rule ids`() {
        val m = PolicyWire.merge(BlockPolicy(id = "ABC", name = "New", domains = listOf("WWW.Example.com/x")), null)
        assertEquals("blocklist", m.getString("mode"))
        assertEquals("abc", m.getString("id"))
        val rule = m.getJSONArray("domainRules").getJSONObject(0)
        assertEquals("example.com", rule.getString("domain"))                  // normalized like the server does
        assertTrue(rule.getString("id").isNotEmpty())
        assertTrue(m.isNull("partial"))                                        // nothing to say, nothing sent
    }

    @Test fun `unusable domains are left out of what is sent`() {
        val m = PolicyWire.merge(BlockPolicy(id = "a", name = "n", domains = listOf("localhost", "evil.com\n1.2.3.4 bank.com", "ok.com")), null)
        assertEquals(listOf("ok.com"), m.getJSONArray("domainRules").objects().map { it.getString("domain") })
    }

    @Test fun `a mode this build does not know is unsupported, a missing one is a blocklist`() {
        assertFalse(PolicyWire.supported(JSONObject("""{"id":"a","name":"n","mode":"quarantine"}""")))
        assertTrue(PolicyWire.supported(JSONObject("""{"id":"a","name":"n","mode":"allowlist"}""")))
        assertTrue(PolicyWire.supported(raw))
        assertTrue(PolicyWire.supported(JSONObject("""{"id":"a","name":"n"}""")))
        assertEquals(PolicyMode.BLOCKLIST, PolicyWire.toLocal(JSONObject("""{"id":"a","name":"n"}""")).mode)
    }

    @Test fun `an allowlist keeps its mode and its other platforms' apps through an edit`() {
        val serverRaw = JSONObject("""{"id":"a","name":"Only these","mode":"allowlist","domainRules":[],
            "applicationRules":[{"id":"r1","platform":"windows","nativeIdentifier":"C:\\\\Code.exe","enabled":true}]}""")
        val local = PolicyWire.toLocal(serverRaw)
        assertEquals(PolicyMode.ALLOWLIST, local.mode)
        assertTrue("only this platform's apps are local", local.apps.isEmpty())
        val merged = PolicyWire.merge(local.copy(apps = listOf(AppRule("com.code", "Code"))), serverRaw)
        assertEquals("allowlist", merged.getString("mode"))
        assertEquals(setOf("windows", "android"), merged.getJSONArray("applicationRules").objects().map { it.getString("platform") }.toSet())
    }

    @Test fun `a new allowlist is sent as one, and the mode counts as a change`() {
        val p = BlockPolicy(id = "a", name = "n", mode = PolicyMode.ALLOWLIST)
        assertEquals("allowlist", PolicyWire.merge(p, null).getString("mode"))
        assertFalse(PolicyWire.same(p, p.copy(mode = PolicyMode.BLOCKLIST)))
        assertNotEquals(PolicyWire.canonical(p), PolicyWire.canonical(p.copy(mode = PolicyMode.BLOCKLIST)))
    }

    @Test fun `same ignores order, case and the spelling of a domain`() {
        val a = BlockPolicy("id", "n", listOf("youtube.com", "x.com"))
        assertTrue(PolicyWire.same(a, BlockPolicy("ID", "n", listOf("WWW.X.COM", "https://youtube.com/"))))
        assertFalse(PolicyWire.same(a, a.copy(name = "other")))
        assertFalse(PolicyWire.same(a, a.copy(domains = listOf("youtube.com"))))
        assertFalse(PolicyWire.same(a, a.copy(apps = listOf(AppRule("p", "l")))))
    }

    @Test fun `JSON nulls and missing fields do not break reading`() {
        val p = PolicyWire.toLocal(JSONObject("""{"id":"A","name":null,"domainRules":[{"domain":"a.com","enabled":null}],
            "applicationRules":[{"platform":"android","nativeIdentifier":"p","displayName":null}],"partial":null}"""))
        assertEquals("a", p.id); assertEquals("", p.name)
        assertEquals(listOf("a.com"), p.domains)
        assertEquals(listOf(AppRule("p", "p")), p.apps)                        // label falls back to the package name
        assertTrue(p.partial.isEmpty())
    }

    // ---- bedtime

    @Test fun `bedtime round trips and keeps fields from other platforms`() {
        val raw = JSONObject("""{"enabled":true,"windDownMinute":1290,"sleepMinute":1350,"wakeMinute":420,"lockAtSleep":false,
            "policyId":"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA","somethingNew":7}""")
        val local = BedtimeWire.toLocal(raw)
        assertEquals(BedtimeSettings(windDownMinute = 1290, sleepMinute = 1350, wakeMinute = 420, enabled = true, quietNotifications = true, lockAtSleep = false, policyId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), local)
        val merged = BedtimeWire.merge(local.copy(sleepMinute = 1380), raw)
        assertEquals(1380, merged.getInt("sleepMinute"))
        assertEquals(7, merged.getInt("somethingNew"))
        assertEquals(raw.getInt("sleepMinute"), 1350) // untouched
        assertTrue(merged.getBoolean("quietNotifications"))
    }

    @Test fun `a bedtime without a profile sends an explicit null and reads back as null`() {
        val m = BedtimeWire.merge(BedtimeSettings(policyId = null), null)
        assertTrue(m.has("policyId") && m.isNull("policyId"))
        assertNull(BedtimeWire.toLocal(m).policyId)
    }
}
