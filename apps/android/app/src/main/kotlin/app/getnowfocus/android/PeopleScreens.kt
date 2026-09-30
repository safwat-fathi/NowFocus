package app.getnowfocus.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** (label, how many days ago that stands for). Null days = "can't remember". */
private val LAST_TALKED_CHOICES: List<Pair<String, Int?>> = listOf(
    "This week" to 3, "About 2 weeks" to 14, "About a month" to 30, "Longer" to 90, "Can't remember" to null,
)

/** The choice whose day count is nearest, so a stored timestamp still highlights a chip as it ages. */
private fun choiceFor(person: Person, now: Long): Int? {
    val days = (now - (person.lastTalkedAt ?: return null)) / DAY_MS
    return when {
        days < 9 -> 3
        days < 22 -> 14
        days < 60 -> 30
        else -> 90
    }
}

/**
 * Pick people from the system contact picker and say roughly when you last
 * talked to each. Stateless; used by the onboarding step and [PeopleScreen].
 *
 * ACTION_PICK on Phone.CONTENT_URI hands back a one-row URI we may read with
 * no READ_CONTACTS permission - the picker grants that single row temporarily.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeopleEditor(
    people: List<Person>,
    onAdd: (name: String, phone: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onSetLastTalked: (id: String, lastTalkedAt: Long?) -> Unit,
) {
    val context = LocalContext.current
    var pickFailed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult // backed out of the picker
        val picked = readPickedPhone(context, uri)
        pickFailed = picked == null
        if (picked != null) onAdd(picked.first, picked.second)
    }
    val now = System.currentTimeMillis()

    people.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(top = NowFocusSpace.s3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = TextStyle(fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                Text(p.phone, style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700))
            }
            GhostButton("Remove") { onRemove(p.id) }
        }
        Text("LAST TALKED", style = kickerStyle(NowFocusColors.neutral700), modifier = Modifier.padding(vertical = NowFocusSpace.s1))
        val selected = choiceFor(p, now)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NowFocusSpace.s1), verticalArrangement = Arrangement.spacedBy(NowFocusSpace.s1)) {
            LAST_TALKED_CHOICES.forEach { (label, daysAgo) ->
                Chip(label, selected = daysAgo == selected) { onSetLastTalked(p.id, daysAgo?.let { now - it * DAY_MS }) }
            }
        }
        Spacer(Modifier.height(NowFocusSpace.s3))
        SectionRule()
    }

    if (people.size < Person.MAX) {
        Spacer(Modifier.height(NowFocusSpace.s3))
        SecondaryButton("+ Add someone") {
            try {
                picker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI))
            } catch (_: android.content.ActivityNotFoundException) {
                pickFailed = true // no contacts app to pick from
            }
        }
    }
    if (pickFailed) {
        Text(
            "Couldn't read that contact. Pick one that has a phone number.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 12.sp, color = NowFocusColors.neutral700),
            modifier = Modifier.padding(top = NowFocusSpace.s2),
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) NowFocusColors.text else Color.Transparent)
            .border(1.dp, NowFocusColors.divider)
            .clickable(onClick = onClick)
            .padding(horizontal = NowFocusSpace.s2, vertical = NowFocusSpace.s1),
    ) {
        Text(
            text,
            style = TextStyle(
                fontFamily = ArchivoSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                color = if (selected) NowFocusColors.bg else NowFocusColors.text,
            ),
        )
    }
}

/** Name and number of the row the picker returned, or null if it can't be read or has no number. */
private fun readPickedPhone(context: Context, uri: Uri): Pair<String, String>? = try {
    context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val name = c.getString(0)?.trim().orEmpty()
        val number = c.getString(1)?.trim().orEmpty()
        if (name.isEmpty() || number.isEmpty()) null else name to number
    }
} catch (_: SecurityException) {
    null
}

@Composable
fun PeopleScreen(
    people: List<Person>,
    onAdd: (name: String, phone: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onSetLastTalked: (id: String, lastTalkedAt: Long?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NowFocusSpace.s4)) {
        Spacer(Modifier.height(NowFocusSpace.s2))
        GhostButton("‹ Rules", onClick = onBack)
        Text("People who matter", style = headingStyle(22.sp))
        Spacer(Modifier.height(NowFocusSpace.s2))
        Text(
            "When a blocked app opens, we'll point you to whoever you've talked to least recently. Nothing leaves this phone.",
            style = TextStyle(fontFamily = ArchivoRegular, fontSize = 14.sp, color = NowFocusColors.neutral800),
        )
        Spacer(Modifier.height(NowFocusSpace.s3))
        SectionRule(thick = true)
        PeopleEditor(people, onAdd, onRemove, onSetLastTalked)
        Spacer(Modifier.height(NowFocusSpace.s6))
    }
}
