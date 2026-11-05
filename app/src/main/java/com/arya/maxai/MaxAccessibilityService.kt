package com.arya.maxai
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
/** User-enabled screen assistance base. Do not read passwords or silently send content. */
class MaxAccessibilityService:AccessibilityService(){
 override fun onAccessibilityEvent(event:AccessibilityEvent?){}
 override fun onInterrupt(){}
 fun readVisibleTextForUserRequestedAction():String{
  val root=rootInActiveWindow?:return ""
  val out=StringBuilder();collect(root,out);return out.toString()
 }
 private fun collect(n:AccessibilityNodeInfo,out:StringBuilder){
  n.text?.toString()?.takeIf{it.isNotBlank()}?.let{out.append(it).append('\n')}
  for(i in 0 until n.childCount)n.getChild(i)?.let{child->collect(child,out);child.recycle()}
 }
}
