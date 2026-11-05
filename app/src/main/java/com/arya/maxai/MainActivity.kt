package com.arya.maxai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
 private var tts: TextToSpeech?=null
 private val perms=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState); tts=TextToSpeech(this,this)
  setContent { MaxScreen(
   askPerms={perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE,Manifest.permission.POST_NOTIFICATIONS))},
   overlay={
    if(!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
    else startService(Intent(this,MaxOverlayService::class.java))
   },
   accessibility={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},
   biometric={verifyOwner()},
   launch={pkg->packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)},
   speak={tts?.speak(it,TextToSpeech.QUEUE_FLUSH,null,"MAX")}
  ) }
 }
 override fun onInit(status:Int){if(status==TextToSpeech.SUCCESS)tts?.language=Locale("hi","IN")}
 private fun verifyOwner(){
  val a=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
  if(BiometricManager.from(this).canAuthenticate(a)!=BiometricManager.BIOMETRIC_SUCCESS)return
  val p=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){})
  p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("MAX owner verification").setSubtitle("Device biometric or screen lock").setAllowedAuthenticators(a).build())
 }
 override fun onDestroy(){tts?.shutdown();super.onDestroy()}
}

@Composable fun MaxScreen(askPerms:()->Unit,overlay:()->Unit,accessibility:()->Unit,biometric:()->Unit,launch:(String)->Unit,speak:(String)->Unit){
 val client=remember{GeminiClient()}; val scope= rememberCoroutineScope()
 var key by remember{mutableStateOf("")}; var model by remember{mutableStateOf("gemini-2.5-flash")}
 var prompt by remember{mutableStateOf("")}; var answer by remember{mutableStateOf("Namaste! Main MAX hoon. Mere creator Arya Mahilange hain. ✨")}
 var error by remember{mutableStateOf("")}; var busy by remember{mutableStateOf(false)}
 val bg=Brush.linearGradient(listOf(Color(0xFF080C1A),Color(0xFF1B1640),Color(0xFF071D2A)))
 MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF82E9FF),secondary=Color(0xFFB8A0FF))){
  Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text("MAX",fontSize=42.sp,fontWeight=FontWeight.Black,color=Color.White)
   Text("AI VOICE ASSISTANT",color=Color(0xFF83E9FF),letterSpacing=3.sp,fontSize=11.sp)
   Card(colors=CardDefaults.cardColors(containerColor=Color(0xAA202746)),shape=RoundedCornerShape(26.dp)){
    Column(Modifier.fillMaxWidth().padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){
     Box(Modifier.size(100.dp).background(Brush.radialGradient(listOf(Color(0xFF9AF4FF),Color(0xFF7257D9),Color.Transparent)),CircleShape),contentAlignment=Alignment.Center){Text("M",fontSize=44.sp,color=Color.White,fontWeight=FontWeight.Bold)}
     Text("Hey! Kya karna hai? 💜",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
     Text(answer,color=Color(0xFFDDE3FF))
     Button(onClick={speak(answer)}){Text("MAX bole")}
     if(error.isNotBlank())Text(error,color=Color(0xFFFFB5A9),fontSize=12.sp)
    }
   }
   Text("GEMINI API KEY (BYOK)",color=Color.White,fontWeight=FontWeight.Bold)
   OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),label={Text("Paste your own Gemini API key")},singleLine=true)
   Text("Key memory-only hai; app process band hone par clear. APK mein shared key hardcode nahi hai.",color=Color(0xFFB8BED5),fontSize=11.sp)
   OutlinedTextField(model,{model=it},Modifier.fillMaxWidth(),label={Text("Text model ID")},singleLine=true)
   OutlinedTextField(prompt,{prompt=it},Modifier.fillMaxWidth(),label={Text("MAX ko message likho")},minLines=2)
   Button(enabled=!busy&&key.isNotBlank()&&prompt.isNotBlank(),modifier=Modifier.fillMaxWidth(),onClick={
    val q=prompt;busy=true;error=""
    scope.launch{try{answer=client.generate(key,model,"You are MAX, a friendly, playful, confident Hindi-English assistant. Keep replies age-appropriate. Creator: Arya Mahilange. User: $q");prompt=""}
     catch(e:Exception){error=e.message?:"Request failed"}finally{busy=false}}
   }){Text(if(busy)"Thinking..." else "Send to Gemini")}
   Text("ANDROID SETUP",color=Color.White,fontWeight=FontWeight.Bold)
   Setup("Microphone + notifications","Runtime permission request",askPerms)
   Setup("Floating overlay","Android overlay settings",overlay)
   Setup("Accessibility","Enable manually in Android Settings",accessibility)
   Setup("Owner verification","Android biometric / screen-lock prompt",biometric)
   Text("APP LAUNCHER",color=Color.White,fontWeight=FontWeight.Bold)
   Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
    listOf("WhatsApp" to "com.whatsapp","ChatGPT" to "com.openai.chatgpt","Termux" to "com.termux").forEach{(n,p)->OutlinedButton(onClick={launch(p)},modifier=Modifier.weight(1f)){Text(n,fontSize=10.sp)}}
   }
   Text("Voice in this version uses Android system speech/TTS. Gemini Live audio-to-audio and Gemini voice selection are not yet wired.",color=Color(0xFFFFD28A),fontSize=12.sp)
  }
 }
}
@Composable private fun Setup(title:String,detail:String,onClick:()->Unit){
 Card(colors=CardDefaults.cardColors(containerColor=Color(0xAA202746)),shape=RoundedCornerShape(16.dp)){
  Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){Text(title,color=Color.White,fontWeight=FontWeight.SemiBold);Text(detail,color=Color(0xFFB8BED5),fontSize=11.sp)}
   Button(onClick=onClick){Text("Setup")}
  }
 }
}
