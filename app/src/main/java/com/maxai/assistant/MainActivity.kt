package com.maxai.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as Sys
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Theme { var dark by mutableStateOf(true) }
private val BG get() = if (Theme.dark) Color(0xFF0B1024) else Color(0xFFD9E4F5)
private val INK get() = if (Theme.dark) Color(0xFFEAF0FF) else Color(0xFF14214A)
private val MUTE get() = if (Theme.dark) Color(0xFF9AA8D6) else Color(0xFF4A5A8A)
private val CARD get() = if (Theme.dark) listOf(Color(0xFF1A2145), Color(0xFF121833)) else listOf(Color.White, Color(0xFFEAF0FC))
private val BLUE = Color(0xFF3B8BFF)
private val CY = Color(0xFF00F0FF)
private val PK = Color(0xFFFF2BD6)

class Perm(val title: String, val desc: String, val must: Boolean, val ok: () -> Boolean, val ask: () -> Unit)

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val askPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    private val askMany = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun a11yOn(): Boolean {
        val s = Sys.Secure.getString(contentResolver, Sys.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains("$packageName/") && s.contains("MaxAccessibilityService")
    }

    /** Real Android permission requests. "must" ones gate the Ho gaya button. */
    private fun perms() = listOf(
        Perm("Microphone", "Taaki MAX tumhari awaaz sun sake aur \"Hello MAX\" pehchan sake.", true,
            { granted(Manifest.permission.RECORD_AUDIO) }, { askPerm.launch(Manifest.permission.RECORD_AUDIO) }),
        Perm("Dusre apps ke upar dikhna", "Taaki neon orb tum jo bhi kar rahe ho uske upar tair sake.", true,
            { Sys.canDrawOverlays(this) },
            { startActivity(Intent(Sys.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),
        Perm("Accessibility service", "Taaki MAX apps khol sake, tap kar sake aur WhatsApp message bhej sake. List me \"MAX\" on karo.", true,
            { a11yOn() }, { startActivity(Intent(Sys.ACTION_ACCESSIBILITY_SETTINGS)) }),
        Perm("Contacts", "Phone ke saved naam se call aur message ke liye.", false,
            { granted(Manifest.permission.READ_CONTACTS) }, { askPerm.launch(Manifest.permission.READ_CONTACTS) }),
        Perm("Phone call", "Seedha call lagane ke liye.", false,
            { granted(Manifest.permission.CALL_PHONE) }, { askPerm.launch(Manifest.permission.CALL_PHONE) }),
        Perm("Notifications", "Ye dikhane ke liye ki MAX jaag rahi hai.", false,
            { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) },
            { askPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }),
        Perm("Battery optimisation band", "Isse screen off hone par bhi MAX chup nahi hoti.", false,
            { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
            { startActivity(Intent(Sys.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }),
        Perm("Camera", "Photo khinchne ke liye, jab tum kahoge.", false,
            { granted(Manifest.permission.CAMERA) },
            { askMany.launch(arrayOf(Manifest.permission.CAMERA)) }),
        Perm("Call log aur call status", "Aane wali call ka naam batane ke liye.", false,
            { granted(Manifest.permission.READ_CALL_LOG) && granted(Manifest.permission.READ_PHONE_STATE) },
            { askMany.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)) }),
        Perm("Call uthana aur kaatna", "Tumhare kehne par call uthana ya kaatna.", false,
            { granted(Manifest.permission.ANSWER_PHONE_CALLS) },
            { askMany.launch(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS)) })
    )

    override fun onResume() { super.onResume(); tick++ }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        Theme.dark = Store.dark(this)
        setContent {
            MaterialTheme(colorScheme = if (Theme.dark) darkColorScheme(primary = BLUE) else lightColorScheme(primary = BLUE)) {
                var done by remember { mutableStateOf(Store.done(this@MainActivity)) }
                if (!done) Setup(tick, perms()) { Store.setDone(this@MainActivity, true); done = true }
                else Main()
            }
        }
    }
}

// ---------- building blocks ----------

@Composable
private fun Soft(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val sh = RoundedCornerShape(26.dp)
    Column(
        modifier.shadow(10.dp, sh, ambientColor = Color(0xFF9DB4E0), spotColor = Color(0xFF9DB4E0))
            .clip(sh).background(Brush.verticalGradient(CARD)).padding(16.dp),
        content = content
    )
}

@Composable
private fun Pill(t: String, on: Boolean, click: () -> Unit) {
    Text(
        t, color = if (on) Color.White else INK, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) BLUE else CARD[0])
            .clickable(onClick = click).padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

@Composable
private fun RowScope.Info(label: String, value: String) {
    Soft(Modifier.weight(1f)) {
        Text(label, color = MUTE, fontSize = 13.sp)
        Text(value, color = INK, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- setup ----------

@Composable
private fun Setup(tick: Int, perms: List<Perm>, onDone: () -> Unit) {
    val c = LocalContext.current
    var key by remember { mutableStateOf(Store.key(c)) }
    val ready = key.isNotBlank() && perms.filter { it.must }.all { it.ok() }
    val total = perms.size + 1
    val done = perms.count { it.ok() } + if (key.isNotBlank()) 1 else 0
    Column(
        Modifier.fillMaxSize().background(BG).systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("MAX set up karo", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = INK)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i -> Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < done) BLUE else Color.White)) }
        }
        Text("$done / $total ho gaya. Sab real Android permissions hain, koi fake nahi.", color = MUTE)
        Soft(Modifier.fillMaxWidth()) {
            Text("Gemini API key", fontWeight = FontWeight.Bold, color = INK)
            Text("aistudio.google.com/apikey se free key lo. Ye sirf Google ko bheji jaati hai.", color = MUTE, fontSize = 14.sp)
            OutlinedTextField(
                key, { key = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), placeholder = { Text("AIza…") }
            )
        }
        perms.forEach { p ->
            Soft(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title + if (p.must) "" else " (optional)", fontWeight = FontWeight.Bold, color = INK)
                        Text(p.desc, color = MUTE, fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (p.ok()) Text("Ho gaya ✓", color = Color(0xFF138A4B), fontWeight = FontWeight.SemiBold)
                    else Button(onClick = p.ask) { Text("Allow") }
                }
            }
        }
        Button(
            onClick = { Store.setKey(c, key); onDone() }, enabled = ready,
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)
        ) { Text("Ho gaya", fontSize = 18.sp) }
        Text("Optional wale baad me Settings se bhi on ho sakte hain.", color = MUTE, fontSize = 13.sp)
    }
}

// ---------- main ----------

@Composable
private fun Main() {
    val c = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val on by MaxService.running.collectAsState()
    LaunchedEffect(Unit) { if (!MaxService.running.value) MaxService.send(c, "START") }
    Column(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) { 0 -> Home(on); 1 -> ContactsTab(); 2 -> NotesTab(); else -> SettingsTab() }
        }
        Nav(tab, { tab = it }) { MaxService.send(c, "TALK") }
    }
}

@Composable
private fun Orb(onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "a")
    val p by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "p")
    Box(
        Modifier.size(200.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF1B1550), Color(0xFF07061F)))).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2
            fun arc(rad: Float, st: Float, col: Color, w: Float) {
                val tl = Offset(c.x - rad, c.y - rad)
                val sz = Size(rad * 2, rad * 2)
                drawArc(col.copy(alpha = 0.25f), st, 100f, false, tl, sz, style = Stroke(w * 3, cap = StrokeCap.Round))
                drawArc(col, st, 100f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            arc(r * 0.86f, a, CY, 5f); arc(r * 0.86f, a + 180, CY, 5f)
            arc(r * 0.68f, -a * 1.4f, PK, 4f); arc(r * 0.68f, -a * 1.4f + 180, PK, 4f)
            drawCircle(Brush.radialGradient(listOf(Color.White, PK, Color.Transparent), c, r * 0.4f * p), r * 0.4f * p, c)
        }
        Image(painterResource(R.drawable.max_orb), "MAX", Modifier.size(150.dp).clip(CircleShape))
    }
}

@Composable
private fun Home(on: Boolean) {
    val c = LocalContext.current
    val last by MaxService.lastReply.collectAsState()
    var q by remember { mutableStateOf("") }
    val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greet = if (hr < 12) "Good morning" else if (hr < 17) "Good afternoon" else "Good evening"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.max_logo), "MAX", Modifier.size(46.dp).clip(CircleShape))
                Spacer(Modifier.width(10.dp))
                Text("MAX", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = INK)
            }
            Text(
                if (on) "Jaag rahi hai ●" else "Soyi hui hai", fontWeight = FontWeight.SemiBold,
                color = if (on) Color(0xFF138A4B) else MUTE,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                    .clickable { MaxService.send(c, if (on) "STOP" else "START") }.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Text("$greet, dost", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = INK)
        Text("Your personal AI assistant. \"Hello MAX\" bolo, kisi bhi app me se.", color = MUTE)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Orb { MaxService.send(c, "TALK") } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Khabar", false) { MaxService.send(c, "TEXT", "aaj ki badi khabrein batao") }
            Pill("Website banao", false) { MaxService.send(c, "TEXT", "ek neon cyberpunk portfolio website banao") }
            Pill("Torch", false) { MaxService.send(c, "TEXT", "torch on karo") }
        }
        if (last.isNotBlank()) Soft(Modifier.fillMaxWidth()) { Text(last, color = INK, fontSize = 16.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Info("Aaj", SimpleDateFormat("d MMM", Locale.getDefault()).format(Date()))
            Info("Yaadein", Store.contacts(c).size.toString())
            Info("Notes", Store.notes(c).size.toString())
        }
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), singleLine = true,
            placeholder = { Text("MAX se kuch bhi poochho…") },
            trailingIcon = {
                IconButton(onClick = { if (q.isNotBlank()) { MaxService.send(c, "TEXT", q); q = "" } }) {
                    Icon(Icons.Filled.Send, "Send", tint = BLUE)
                }
            }
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ContactsTab() {
    val c = LocalContext.current
    var name by remember { mutableStateOf("") }
    var num by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(Store.contacts(c).toList()) }
    var phone by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showPhone by remember { mutableStateOf(false) }
    val hasPerm = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Yaadein", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = INK) }
        item { Text("Yahan save number par MAX call aur WhatsApp karega. Phone ke contacts bhi dikh sakte hain.", color = MUTE) }
        item {
            Soft(Modifier.fillMaxWidth()) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Naam") })
                OutlinedTextField(num, { num = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Number (+91...)") })
                Button(
                    onClick = {
                        if (name.isNotBlank() && num.isNotBlank()) {
                            Store.saveContact(c, name, num); list = Store.contacts(c).toList(); name = ""; num = ""
                        }
                    },
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text("Save karo") }
            }
        }
        items(list) { (n, v) ->
            Soft(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n, fontWeight = FontWeight.Bold, color = INK)
                        Text(v, color = MUTE)
                    }
                    IconButton(onClick = { Store.deleteContact(c, n); list = Store.contacts(c).toList() }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MUTE)
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    if (!showPhone) phone = Actions(c).allContacts()
                    showPhone = !showPhone
                },
                enabled = hasPerm
            ) { Text(if (showPhone) "Phone contacts chhupao" else "Phone ke saare contacts dikhao") }
            if (!hasPerm) Text("Pehle Setup mein Contacts ki permission do.", color = MUTE)
        }
        if (showPhone) {
            items(phone) { (n, v) ->
                Soft(Modifier.fillMaxWidth()) {
                    Text(n, fontWeight = FontWeight.SemiBold, color = INK)
                    Text(v, color = MUTE)
                }
            }
        }
    }
}

@Composable
private fun NotesTab() {
    val c = LocalContext.current
    var text by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(Store.notes(c)) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notes", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = INK)
        Text("Bolo \"note karo ...\" ya yahan likho.", color = MUTE)
        Soft(Modifier.fillMaxWidth()) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Naya note") })
            Button(
                onClick = { if (text.isNotBlank()) { Store.addNote(c, text); list = Store.notes(c); text = "" } },
                modifier = Modifier.padding(top = 10.dp)
            ) { Text("Save karo") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list) { i, n ->
                Soft(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n, color = INK, modifier = Modifier.weight(1f))
                        IconButton(onClick = { Store.deleteNote(c, i); list = Store.notes(c) }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = MUTE)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab() {
    val c = LocalContext.current
    var key by remember { mutableStateOf(Store.key(c)) }
    var voice by remember { mutableStateOf(Store.voice(c)) }
    var lang by remember { mutableStateOf(Store.lang(c)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = INK)
        Soft(Modifier.fillMaxWidth()) {
            Text("Gemini API key", fontWeight = FontWeight.Bold, color = INK)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(onClick = { Store.setKey(c, key) }, modifier = Modifier.padding(top = 10.dp)) { Text("Save karo") }
        }
        Soft(Modifier.fillMaxWidth()) {
            Text("MAX ki awaaz", fontWeight = FontWeight.Bold, color = INK, modifier = Modifier.padding(bottom = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Leda", "Aoede", "Kore", "Zephyr", "Sulafat", "Achernar", "Callirrhoe", "Pulcherrima", "Puck", "Charon", "Fenrir", "Orus").forEach { v ->
                    Pill(v, voice == v) { voice = v; Store.setVoice(c, v) }
                }
            }
        }
        Soft(Modifier.fillMaxWidth()) {
            Text("Main kis bhasha me sunu?", fontWeight = FontWeight.Bold, color = INK, modifier = Modifier.padding(bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Hindi / Hinglish", lang == "hi-IN") { lang = "hi-IN"; Store.setLang(c, lang) }
                Pill("English", lang == "en-IN") { lang = "en-IN"; Store.setLang(c, lang) }
            }
        }
        Soft(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var dark by remember { mutableStateOf(Theme.dark) }
                Text("Dark mode", fontWeight = FontWeight.Bold, color = INK, modifier = Modifier.weight(1f))
                Switch(checked = dark, onCheckedChange = { dark = it; Theme.dark = it; Store.setDark(c, it) })
            }
        }
        Soft(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var auto by remember { mutableStateOf(Store.autoSend(c)) }
                Column(Modifier.weight(1f)) {
                    Text("Message aur call bina pooche", fontWeight = FontWeight.Bold, color = INK)
                    Text("On karoge to MAX WhatsApp message aur call bina haan bole kar degi. Risky hai, isliye default band hai.", color = MUTE, fontSize = 13.sp)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; Store.setAutoSend(c, it) })
            }
        }
        Button(onClick = { Store.setDone(c, false); (c as? Activity)?.recreate() }) { Text("Permissions dobara dekho") }
    }
}

@Composable
private fun Nav(tab: Int, set: (Int) -> Unit, talk: () -> Unit) {
    val sh = RoundedCornerShape(32.dp)
    Row(
        Modifier.fillMaxWidth().padding(14.dp)
            .shadow(10.dp, sh, ambientColor = Color(0xFF9DB4E0), spotColor = Color(0xFF9DB4E0))
            .clip(sh).background(if (Theme.dark) Color(0xFF151C3A) else Color(0xFFF3F6FD)).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        NavIcon(Icons.Filled.Home, "Home", tab == 0) { set(0) }
        NavIcon(Icons.Filled.Contacts, "Yaadein", tab == 1) { set(1) }
        Box(
            Modifier.size(62.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF6CB4FF), BLUE))).clickable(onClick = talk),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Mic, "Talk", tint = Color.White, modifier = Modifier.size(30.dp)) }
        NavIcon(Icons.Filled.EditNote, "Notes", tab == 2) { set(2) }
        NavIcon(Icons.Filled.Settings, "Settings", tab == 3) { set(3) }
    }
}

@Composable
private fun NavIcon(i: ImageVector, label: String, on: Boolean, click: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) BLUE.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = click).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(i, label, tint = if (on) BLUE else MUTE)
        Text(label, fontSize = 11.sp, color = if (on) BLUE else MUTE)
    }
}
