package com.docdoc.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

// ============================================================================
// File này THAY THẾ cho file MainActivity.java mặc định mà Capacitor tự tạo
// ra tại: android/app/src/main/java/com/docdoc/app/MainActivity.java
//
// Điểm khác biệt duy nhất so với bản mặc định là dòng registerPlugin(...)
// bên dưới, để Capacitor biết nạp plugin TtsFileSaverPlugin mà ta viết tay.
// ============================================================================
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TtsFileSaverPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
