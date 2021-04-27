package com.lang.chapter05;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    public static Context sContext;
    public static ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(Process.myPid());
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sContext = getApplicationContext();

        final Button testGc = findViewById(R.id.test_gc);
        testGc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processCpuTracker.update();
                testGc();
                processCpuTracker.update();
                android.util.Log.e("ProcessCpuTracker",
                        processCpuTracker.printCurrentState(SystemClock.uptimeMillis()));
            }
        });

        final Button testIO = findViewById(R.id.test_io);
        testIO.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processCpuTracker.update();

                testIO();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        processCpuTracker.update();
                        android.util.Log.e("ProcessCpuTracker",
                                processCpuTracker.printCurrentState(SystemClock.uptimeMillis()));
                    }
                }, 5000);
            }
        });

        final Button processOut = findViewById(R.id.test_process);
        processOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processCpuTracker.update();
                android.util.Log.e("ProcessCpuTracker",
                        processCpuTracker.printCurrentState(SystemClock.uptimeMillis()));
            }
        });

    }

    private void testGc() {
        for (int i = 0; i < 10000; i++) {
            int[] test = new int[100000];
            System.gc();
        }
    }

    private void testIO() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                writeSth();
                try {
                    Thread.sleep(100000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, "SingleThread").start();
    }

    private void writeSth() {
        try {
            File f = new File(getFilesDir(), "aee.txt");

            if (f.exists()) {
                f.delete();
            }
            FileOutputStream fos = new FileOutputStream(f);

            byte[] data = new byte[1024 * 4 * 3000];

            for (int i = 0; i < 30; i++) {
                Arrays.fill(data, (byte) i);
                fos.write(data);
                fos.flush();
            }
            fos.close();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
