package com.microsoft.azure.mobile.sasquatch.activities;

import android.annotation.SuppressLint;
import android.support.v7.app.AppCompatActivity;

import java.util.Random;

public class CrashSubActivity extends AppCompatActivity {

    static final String INTENT_EXTRA_CRASH_TYPE = "INTENT_EXTRA_CRASH_TYPE";

    @Override
    @SuppressLint("MissingSuperCall")
    public void onResume() {
        switch (getIntent().getIntExtra(INTENT_EXTRA_CRASH_TYPE, 0)) {

            case 1:
                new StringBuilder(Integer.MAX_VALUE);
                break;

            case 2:
                getResources().openRawResource(~new Random().nextInt(10));
                break;

            case 0:
            default:
                /* super not called crash */
        }
    }
}
