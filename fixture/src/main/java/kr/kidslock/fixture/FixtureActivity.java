package kr.kidslock.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.WindowManager;

/** Separate, synthetic app for end-to-end screenshot OCR. Never contains real child data. */
public class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.WHITE);
                paint.setColor(Color.rgb(20, 60, 45));
                paint.setTextSize(getWidth() / 22f);
                canvas.drawText("수학 강의 방정식 문제풀이", 35, 200, paint);
                canvas.drawText("오늘의 학습 시간", 35, 330, paint);
                canvas.drawText("개인정보 없는 OCR 검증 화면", 35, 460, paint);
            }
        });
    }
}
