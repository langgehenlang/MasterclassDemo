package com.lang.chapter04;

import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.tools.perflib.captures.DataBuffer;
import com.android.tools.perflib.captures.MemoryMappedFileBuffer;
import com.lang.chapter04.tool.AnalyzerResult;
import com.lang.chapter04.tool.Tools;
import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Heap;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.Snapshot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private ImageView imageView1;
    private ImageView imageView2;
    private Button hprofBtn;

    private File heapDumpFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        init();
    }


    void init() {
        if (Environment.isExternalStorageEmulated()) {
            heapDumpFile = new File(Environment.getExternalStorageDirectory(), "heapDump.pro");
        } else {
            heapDumpFile = new File(getExternalCacheDir().getAbsolutePath(), "heapDump.pro");
        }

        Bitmap bitmap1 = BitmapFactory.decodeResource(getResources(), R.mipmap.test);
        Bitmap bitmap2 = BitmapFactory.decodeResource(getResources(), R.mipmap.test);

        imageView1 = findViewById(R.id.id_imv1);
        imageView2 = findViewById(R.id.id_imv2);

        imageView1.setImageBitmap(bitmap1);
        imageView2.setImageBitmap(bitmap2);

        hprofBtn = findViewById(R.id.id_hprof);
        hprofBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ???????????? GC
                Runtime.getRuntime().gc();
                System.runFinalization();
                try {
                    String path = heapDumpFile.getAbsolutePath();
                    Tools.print("init save path: " + path);
                    //??????????????????
                    Debug.dumpHprofData(path);
                    //??????hprof??????, ??????????????????????????????????????????????????????
                    DataBuffer buffer = new MemoryMappedFileBuffer(heapDumpFile);
                    //??????snapshot, ?????????????????????????????????????????????
                    Snapshot snapshot = Snapshot.createSnapshot(buffer);
                    // ??????Bitmap Class
                    Collection<ClassObj> bitmapClasses = snapshot.findClasses("android.graphics.Bitmap");
                    // ??????heap, ???????????????app???default heap??????.
                    // ???????????????,??????????????????app????????????default heap??????????????????????????????
                    Collection<Heap> heaps = snapshot.getHeaps();
                    Tools.print("bitmapClasses size: " + bitmapClasses.size() + ", heaps size: " + heaps.size());
                    Tools.print("-------------------- BEGIND " + System.currentTimeMillis() + " ----------------------");
                    for (Heap heap : heaps) {
                        // ???????????????app???default heap??????
                        Tools.print(heap.getName() + "," + heap.getId());
                        if (!heap.getName().equals("app") && !heap.getName().equals("default")) {
                            continue;
                        }

                        Map<Integer, List<AnalyzerResult>> map = new HashMap<>();
                        for (ClassObj clazz : bitmapClasses) {
                            //???heap??????????????????Bitmap??????
                            List<Instance> bitmapInstances = clazz.getHeapInstances(heap.getId());

                            for (int i = 0; i < bitmapInstances.size(); i++) {
                                //???GcRoot?????????????????????Integer.MAX_VALUE??????????????????????????????????????????????????????????????????
                                if (bitmapInstances.get(i).getDistanceToGcRoot() == Integer.MAX_VALUE) {
                                    continue;
                                }
                                List<AnalyzerResult> analyzerResults;
                                int curHashCode = Tools.getHashCodeByInstance(bitmapInstances.get(i));
                                AnalyzerResult result = Tools.getAnalyzerResult(bitmapInstances.get(i));
                                result.setInstance(bitmapInstances.get(i));
                                if (map.get(curHashCode) == null) {
                                    analyzerResults = new ArrayList<>();
                                }else {
                                    analyzerResults = map.get(curHashCode);
                                }

                                analyzerResults.add(result);
                                map.put(curHashCode, analyzerResults);
                            }
                        }

                        if (map.isEmpty()) {
                            Tools.print("??????head??????bitmap??????");
                        }

                        for (Map.Entry<Integer, List<AnalyzerResult>>  entry : map.entrySet()) {
                            List<AnalyzerResult> analyzerResults = entry.getValue();
                            //size??????2??????????????????????????????
                            if (analyzerResults.size() < 2) {
                                continue;
                            }
                            Tools.print("============================================================");
                            Tools.print("duplcateCount:" + analyzerResults.size());
                            Tools.print("stacks:[");
                            for (AnalyzerResult result : analyzerResults){
                                Tools.print("   [");
                                Tools.getStackInfo(result.getInstance());
                                Tools.print("   ]");
                            }
                            Tools.print("]");
                            Tools.print(analyzerResults.get(0).toString());
                            Tools.print("============================================================");
                        }
                    }
                    Tools.print("-------------------- END " + System.currentTimeMillis() + " ----------------------");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

    }
}
