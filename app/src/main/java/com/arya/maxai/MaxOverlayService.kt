package com.arya.maxai
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
class MaxOverlayService:Service(){
 private var wm:WindowManager?=null;private var view:TextView?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(view==null){wm=getSystemService(WINDOW_SERVICE) as WindowManager
   view=TextView(this).apply{text="MAX • tap to close";textSize=14f;setTextColor(-1);setBackgroundColor(0xEE5146B8.toInt());setPadding(22,16,22,16);setOnClickListener{stopSelf()}}
   val p=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.END;x=20;y=160}
   wm?.addView(view,p)
  };return START_NOT_STICKY
 }
 override fun onDestroy(){view?.let{wm?.removeView(it)};view=null;super.onDestroy()}
 override fun onBind(intent:Intent?):IBinder?=null
}
