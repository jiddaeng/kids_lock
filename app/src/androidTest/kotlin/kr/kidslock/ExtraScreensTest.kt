package kr.kidslock
import android.content.*
import android.graphics.*
import android.view.*
import android.view.inspector.WindowInspector
import android.widget.*
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.core.app.*
import androidx.test.platform.app.InstrumentationRegistry
import kr.kidslock.core.*
import kr.kidslock.data.LocalStore
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ExtraScreensTest {
 private fun flat(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{flat(v.getChildAt(it))}else emptyList()
 private fun save(v:View,f:File){val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun captureOcrAndRestOverlay(){
  val i=InstrumentationRegistry.getInstrumentation()
  val c=ApplicationProvider.getApplicationContext<Context>()
  val dir=File(c.getExternalFilesDir(null),"all-screens").apply{mkdirs()}
  val input=File(c.cacheDir,"gallery-ocr.png")
  val b=Bitmap.createBitmap(1100,400,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(b);canvas.drawColor(Color.WHITE)
  val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=60f}
  canvas.drawText("수학 강의 방정식 문제풀이",30f,120f,paint)
  canvas.drawText("화면 출력용 예시 이미지",30f,240f,paint)
  input.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
  ActivityScenario.launch(MainActivity::class.java).use{ scenario->
   scenario.onActivity{it.javaClass.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}.invoke(it,41,-1,Intent().setData(android.net.Uri.fromFile(input)))}
   var found=false
   val deadline=System.currentTimeMillis()+40000
   while(!found && System.currentTimeMillis()<deadline){
    Thread.sleep(500)
    scenario.onActivity{a-> val root=WindowInspector.getGlobalWindowViews().lastOrNull{v->v.isShown && flat(v).filterIsInstance<TextView>().any{t->t.text.toString()=="실제 OCR 결과"}};if(root!=null){save(root,File(dir,"23-ocr-result.png"));found=true}}
   }
   assertTrue("OCR dialog missing",found)
  }
  input.delete()
  val s=LocalStore.get(c);val old=s.prefs.all.toMap()
  val auto=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
  fun shell(cmd:String){ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(cmd)).use{it.readBytes()}}
  val oldService=android.provider.Settings.Secure.getString(c.contentResolver,"enabled_accessibility_services")
  try{
   s.prefs.edit().putString("rules","[]").commit()
   s.cloudConsent=false;s.consent=true;s.paused=false;s.selectedApps=setOf("kr.kidslock.fixture")
   s.saveRule(Rule("__all__","선택한 앱 전체",3600,300,true,true))
   s.saveProgress("__all__",RuleProgress(day=java.time.LocalDate.now().toString(),blockedUntil=System.currentTimeMillis()+600000))
   shell("settings delete secure enabled_accessibility_services")
   shell("settings put secure enabled_accessibility_services kr.kidslock/kr.kidslock.capture.ScreenObserverService")
   shell("settings put secure accessibility_enabled 1")
   c.startActivity(Intent().setComponent(ComponentName("kr.kidslock.fixture","kr.kidslock.fixture.FixtureActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   var found=false;val deadline=System.currentTimeMillis()+30000
   while(!found && System.currentTimeMillis()<deadline){
    Thread.sleep(500)
    i.runOnMainSync{val root=WindowInspector.getGlobalWindowViews().lastOrNull{v->v.isShown && flat(v).filterIsInstance<TextView>().any{it.text.toString().startsWith("잠깐, 함께 정한 휴식")}};if(root!=null){save(root,File(dir,"24-rest-overlay.png"));found=true}}
   }
   assertTrue("Rest overlay missing",found)
  }finally{
   s.paused=true;s.consent=false
   val edit=s.prefs.edit().clear();old.forEach{(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}};edit.commit()
   if(oldService.isNullOrBlank())shell("settings delete secure enabled_accessibility_services") else shell("settings put secure enabled_accessibility_services $oldService")
  }
 }
}
