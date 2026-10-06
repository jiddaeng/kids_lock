package kr.kidslock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kr.kidslock.core.*
import kr.kidslock.data.*
import org.junit.Test
import java.io.File

class AllScreensTest {
    private fun flatten(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { flatten(v.getChildAt(it)) } else emptyList()
    private fun invoke(a: MainActivity, name: String, vararg args: Any?) {
        a.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }.apply { isAccessible=true }.invoke(a,*args)
    }
    private fun save(v:View, file:File) {
        if(v.width<=0 || v.height<=0) return
        val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b)); file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
    }
    @Test fun exportAllScreens() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val store=LocalStore.get(context)
        val oldPrefs=store.prefs.all.toMap()
        val dir=File(context.filesDir,"all-screens").apply { mkdirs() }
        store.paused=true; store.cloudConsent=false
        store.selectedApps=setOf("kr.kidslock.fixture")
        val at=System.currentTimeMillis()
        store.insert(Observation(at=at,app="gallery.demo",source="화면 구성 예시",analysis=Analysis(Purpose.STUDY,setOf("수학"),emptyList(),"화면 구성 예시 · 실제 분석 아님",Risk.NONE)))
        val row=store.observations(at).first()
        val rule=Rule("__all__","선택한 앱 전체",3600,300,true,false,1140,1260,true)
        fun screen(name:String,tab:Int=0,action:(MainActivity)->Unit={}) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    a.javaClass.getDeclaredField("tab").apply { isAccessible=true }.setInt(a,tab)
                    invoke(a,"render"); action(a)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(350)
                scenario.onActivity { a ->
                    val roots=WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width>0 }
                    val root=roots.lastOrNull { it !== a.window.decorView } ?: a.window.decorView
                    save(root,File(dir,"$name.png"))
                    flatten(root).filterIsInstance<ScrollView>().forEachIndexed { i,s ->
                        if(s.childCount>0 && s.getChildAt(0).height>s.height+20) save(s.getChildAt(0),File(dir,"$name-full${i+1}.png"))
                    }
                }
            }
        }
        try {
            screen("01-today-week",0)
            screen("02-today-day",0) { a -> a.javaClass.getDeclaredField("periodDays").apply { isAccessible=true }.setInt(a,1); invoke(a,"render") }
            screen("03-today-month",0) { a -> a.javaClass.getDeclaredField("periodDays").apply { isAccessible=true }.setInt(a,30); invoke(a,"render") }
            screen("04-records",1)
            screen("05-rules-empty",2)
            store.saveRule(rule)
            screen("06-rules-proposed",2)
            screen("07-rule-child-consent",2) { a -> flatten(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="자녀 · 이 약속에 동의" }.performClick() }
            store.saveRule(rule.copy(childApproved=true))
            screen("08-rules-agreed",2)
            screen("09-report",3)
            screen("10-settings",4)
            screen("11-app-selection",4) { invoke(it,"chooseApps") }
            store.consent=false
            screen("12-screen-consent",4) { invoke(it,"startAnalysis") }
            screen("13-pin-setup",4) { invoke(it,"configurePin", {}) }
            store.setParentPin("739152")
            screen("14-parent-verification",4) { invoke(it,"parentGate", {}) }
            screen("15-relay-connection",4) { invoke(it,"configureRelay") }
            RelayCredential(context).save("gallery-only-not-a-real-token-0000000000")
            screen("16-local-ai-consent",4) { invoke(it,"consentCloud") }
            screen("17-parent-rule-proposal",2) { invoke(it,"editRule",null,true) }
            screen("18-child-rule-change",2) { invoke(it,"editRule",rule,false) }
            screen("19-record-correction",1) { invoke(it,"explain",row) }
            screen("20-sharing-preview",3) { invoke(it,"showSharedReport",false) }
            screen("21-parent-report",3) { invoke(it,"showSharedReport",true) }
            screen("22-delete-confirmation",4) { a -> flatten(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="분석 기록 모두 삭제" }.performClick() }
        } finally {
            context.getSharedPreferences("relay",Context.MODE_PRIVATE).edit().clear().commit()
            store.writableDatabase.delete("observations","app=?",arrayOf("gallery.demo"))
            val edit=store.prefs.edit().clear()
            oldPrefs.forEach { (k,v) -> when(v) { is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v); is Int -> edit.putInt(k,v); is Long -> edit.putLong(k,v); is Float -> edit.putFloat(k,v); is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>) } }
            edit.commit()
        }
    }
}
