package com.mauriziosavio.lettore;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LetturaServicePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
