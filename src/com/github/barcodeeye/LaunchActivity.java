package com.github.barcodeeye;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import com.example.qrApp.R;
import com.github.barcodeeye.scan.CaptureActivity;

public class LaunchActivity extends Activity {

    private static final String TAG = LaunchActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        // delayed camera activity
        // see: https://code.google.com/p/google-glass-api/issues/detail?id=259
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
//                try {
//                    ActivityInfo activityInfo = getPackageManager().getActivityInfo(getComponentName(), 0);
//                    processVoiceAction(activityInfo.loadLabel(getPackageManager()).toString());
//                } catch (NameNotFoundException e) {
//                    e.printStackTrace();
                    processVoiceAction(null);
//                }
            }
        }, 100);

    }

    private void processVoiceAction(String command) {
        Log.v(TAG, "Voice command: " + command);
//        if (command == null) {
//            startActivity(CaptureActivity.newIntent(this, ScanAction.UNDEFINED));
//        } else if (command.toLowerCase().contains("product")) {



//        String name = "Andrey";
//        String QR = "QR";
//        String Query = "where a>b";
//
//        String url = "http://192.168.1.71:8081/hello/get_string?input_1="+name+"&input_2="+QR+"&input_3="+Query;
//
//        Intent intent = new Intent(Intent.ACTION_VIEW);
//        intent.setData(Uri.parse(url));
//        startActivity(intent);

        startActivity(CaptureActivity.newIntent(this)); //запускаем CaptureActivity
//
// } else {
//            startActivity(CaptureActivity.newIntent(this, ScanAction.UNDEFINED));
//        }

        finish();
    }
}
