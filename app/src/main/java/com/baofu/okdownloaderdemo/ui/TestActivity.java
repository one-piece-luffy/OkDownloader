package com.baofu.okdownloaderdemo.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.baofu.okdownloaderdemo.R;

public class TestActivity extends AppCompatActivity {

    TextView tv;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        tv = findViewById(R.id.tv);
        new Thread(new Runnable() {
            @Override
            public void run() {
                tv.setText("Test");
            }
        }).start();

    }
}