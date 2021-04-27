/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lang.chapter05;

import android.annotation.SuppressLint;
import android.os.StrictMode;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class ProcessCpuTracker {
    private static final String TAG = "ProcessCpuTracker";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG || false;

    static final int PROCESS_STAT_MINOR_FAULTS = 0;
    static final int PROCESS_STAT_MAJOR_FAULTS = 1;
    static final int PROCESS_STAT_UTIME = 2;
    static final int PROCESS_STAT_STIME = 3;

    /**
     * Stores user time and system time in jiffies.
     */
    private final long[] mProcessStatsData = new long[4];

    /**
     * Stores user time and system time in jiffies.  Used for
     * public API to retrieve CPU use for a process.  Must lock while in use.
     */
    private final long[] mSinglePidStatsData = new long[4];

    static final int PROCESS_FULL_STAT_MINOR_FAULTS = 1;
    static final int PROCESS_FULL_STAT_MAJOR_FAULTS = 2;
    static final int PROCESS_FULL_STAT_UTIME = 3;
    static final int PROCESS_FULL_STAT_STIME = 4;
    static final int PROCESS_FULL_STAT_VSIZE = 5;

    private final String[] mProcessFullStatsStringData = new String[6];
    private final long[] mProcessFullStatsData = new long[6];


    private final long[] mSystemCpuData = new long[7];

    private final float[] mLoadAverageData = new float[3];

    // How long a CPU jiffy is in milliseconds.
    private final long mJiffyMillis;

    private float mLoad1 = 0;
    private float mLoad5 = 0;
    private float mLoad15 = 0;

    // All times are in milliseconds. They are converted from jiffies to milliseconds
    // when extracted from the kernel.
    private long mCurrentSampleTime;
    private long mLastSampleTime;

    private long mCurrentSampleRealTime;
    private long mLastSampleRealTime;

    private long mCurrentSampleWallTime;
    private long mLastSampleWallTime;

    private long mBaseUserTime;
    private long mBaseSystemTime;
    private long mBaseIoWaitTime;
    private long mBaseIrqTime;
    private long mBaseSoftIrqTime;
    private long mBaseIdleTime;
    private int mRelUserTime;
    private int mRelSystemTime;
    private int mRelIoWaitTime;
    private int mRelIrqTime;
    private int mRelSoftIrqTime;
    private int mRelIdleTime;
    private boolean mRelStatsAreGood;

    private int[] mCurPids;
    private int[] mCurThreadPids;

    private final ArrayList<Stats> mProcStats = new ArrayList<Stats>();
    private final ArrayList<Stats> mWorkingProcs = new ArrayList<Stats>();
    private boolean mWorkingProcsSorted;

    private boolean mFirst = true;

    private byte[] mBuffer = new byte[4096];

    // /proc/self/stat
    private static final int PROCESS_STATS_STATUS = 2 - 2;
    private static final int PROCESS_STATS_MINOR_FAULTS = 9 - 2;
    private static final int PROCESS_STATS_MAJOR_FAULTS = 11 - 2;
    private static final int PROCESS_STATS_UTIME = 13 - 2;
    private static final int PROCESS_STATS_STIME = 14 - 2;

    // /proc/stat
    private static final int SYSTEM_STATS_USER_TIME = 2;
    private static final int SYSTEM_STATS_NICE_TIME = 3;
    private static final int SYSTEM_STATS_SYS_TIME = 4;
    private static final int SYSTEM_STATS_IDLE_TIME = 5;
    private static final int SYSTEM_STATS_IOWAIT_TIME = 6;
    private static final int SYSTEM_STATS_IRQ_TIME = 7;
    private static final int SYSTEM_STATS_SOFT_IRQ_TIME = 8;

    private Stats mCurrentProcStat;
    private int mCurrentProcID;

    public interface FilterStats {
        /**
         * Which stats to pick when filtering
         */
        boolean needed(Stats stats);
    }

    public static class Stats {
        public final int pid;
        final String statFile;
        final String cmdlineFile;
        final String threadsDir;
        final ArrayList<Stats> threadStats = null;
        final ArrayList<Stats> workingThreads;


        public boolean interesting;

        public String baseName;
        public String name;
        public int nameWidth;

        // vsize capture when process first detected; can be used to
        // filter out kernel processes.
        public long vsize;

        /**
         * Time in milliseconds.
         */
        public long base_uptime;

        /**
         * Time in milliseconds.
         */
        public long rel_uptime;

        /**
         * Time in milliseconds.
         */
        public long base_utime;

        /**
         * Time in milliseconds.
         */
        public long base_stime;

        /**
         * Time in milliseconds.
         */
        public int rel_utime;

        /**
         * Time in milliseconds.
         */
        public int rel_stime;

        public long base_minfaults;
        public long base_majfaults;
        public int rel_minfaults;
        public int rel_majfaults;

        public boolean active;
        public boolean working;
        public boolean added;
        public boolean removed;

        public String status;

        Stats(int _pid, boolean isThread) {
            pid = _pid;
            if (isThread) {
                final File procDir = new File("/proc/self/task", Integer.toString(pid));
                workingThreads = null;
                statFile = procDir + "/stat";
                cmdlineFile = new File(procDir, "comm").toString();
                threadsDir = null;
            } else {
                final File procDir = new File("/proc", Integer.toString(pid));
                statFile = new File(procDir, "stat").toString();
                cmdlineFile = new File(procDir, "cmdline").toString();
                threadsDir = (new File(procDir, "task")).toString();
                workingThreads = new ArrayList<>();
            }
        }
    }

    private final static Comparator<Stats> sLoadComparator = new Comparator<Stats>() {
        public final int
        compare(Stats sta, Stats stb) {
            int ta = sta.rel_utime + sta.rel_stime;
            int tb = stb.rel_utime + stb.rel_stime;
            if (ta != tb) {
                return ta > tb ? -1 : 1;
            }
            if (sta.added != stb.added) {
                return sta.added ? -1 : 1;
            }
            if (sta.removed != stb.removed) {
                return sta.added ? -1 : 1;
            }
            return 0;
        }
    };


    public ProcessCpuTracker(int pid) {
        long jiffyHz = Os.sysconf(OsConstants._SC_CLK_TCK);
        mJiffyMillis = 1000 / jiffyHz;
        mCurrentProcID = pid;
        mCurrentProcStat = new Stats(mCurrentProcID, false);
    }

    public void update() {
        if (DEBUG) Log.v(TAG, "Update: " + this);

        final long nowUptime = SystemClock.uptimeMillis();
        final long nowRealtime = SystemClock.elapsedRealtime();
        final long nowWallTime = System.currentTimeMillis();

        final String[] sysCpu = readProcFile("/proc/stat");
        if (sysCpu != null) {
            // Total user time is user + nice time.
            final long usertime = Long.parseLong(sysCpu[SYSTEM_STATS_SYS_TIME]) * mJiffyMillis;
            // Total system time is simply system time.
            final long systemtime = Long.parseLong(sysCpu[SYSTEM_STATS_SYS_TIME]) * mJiffyMillis;
            // Total idle time is simply idle time.
            final long idletime = Long.parseLong(sysCpu[SYSTEM_STATS_IDLE_TIME]) * mJiffyMillis;
            // Total irq time is iowait + irq + softirq time.
            final long iowaittime = Long.parseLong(sysCpu[SYSTEM_STATS_IOWAIT_TIME]) * mJiffyMillis;
            final long irqtime = Long.parseLong(sysCpu[SYSTEM_STATS_IRQ_TIME]) * mJiffyMillis;
            final long softirqtime = Long.parseLong(sysCpu[SYSTEM_STATS_SOFT_IRQ_TIME]) * mJiffyMillis;

            // This code is trying to avoid issues with idle time going backwards,
            // but currently it gets into situations where it triggers most of the time. :(
            mRelUserTime = (int) (usertime - mBaseUserTime);
            mRelSystemTime = (int) (systemtime - mBaseSystemTime);
            mRelIoWaitTime = (int) (iowaittime - mBaseIoWaitTime);
            mRelIrqTime = (int) (irqtime - mBaseIrqTime);
            mRelSoftIrqTime = (int) (softirqtime - mBaseSoftIrqTime);
            mRelIdleTime = (int) (idletime - mBaseIdleTime);
            mRelStatsAreGood = true;

            if (DEBUG) {
                Log.i("Load", "Total U:" + (usertime)
                        + " S:" + (systemtime) + " I:" + (idletime)
                        + " W:" + (iowaittime) + " Q:" + (irqtime)
                        + " O:" + (softirqtime));
                Log.i("Load", "Rel U:" + mRelUserTime + " S:" + mRelSystemTime
                        + " I:" + mRelIdleTime + " Q:" + mRelIrqTime);
            }

            mBaseUserTime = usertime;
            mBaseSystemTime = systemtime;
            mBaseIoWaitTime = iowaittime;
            mBaseIrqTime = irqtime;
            mBaseSoftIrqTime = softirqtime;
            mBaseIdleTime = idletime;
        }

        mLastSampleTime = mCurrentSampleTime;
        mCurrentSampleTime = nowUptime;
        mLastSampleRealTime = mCurrentSampleRealTime;
        mCurrentSampleRealTime = nowRealtime;
        mLastSampleWallTime = mCurrentSampleWallTime;
        mCurrentSampleWallTime = nowWallTime;

        collectProcsStats("/proc/self/stat", mCurrentProcStat);
        if (mCurrentProcStat.workingThreads != null) {
            File[] threadsProcFiles = new File(mCurrentProcStat.threadsDir).listFiles();
            for (File thread : threadsProcFiles) {
                int threadID = Integer.parseInt(thread.getName());
                Log.d("xxxxx", "threadId:" + threadID);
                Stats threadStat = findThreadStat(threadID, mCurrentProcStat.workingThreads);
                if (threadStat == null) {
                    threadStat = new Stats(threadID, true);

                    getName(threadStat, threadStat.cmdlineFile);
                    mCurrentProcStat.workingThreads.add(threadStat);
                }
                collectProcsStats(threadStat.statFile, threadStat);
            }
            Collections.sort(mCurrentProcStat.workingThreads, sLoadComparator);
        }

        final String[] loadAverages = readProcFile("/proc/loadavg");
        if (loadAverages != null) {
            float load1 = Float.parseFloat(loadAverages[0]);
            float load5 = Float.parseFloat(loadAverages[1]);
            float load15 = Float.parseFloat(loadAverages[2]);
            if (load1 != mLoad1 || load5 != mLoad5 || load15 != mLoad15) {
                mLoad1 = load1;
                mLoad5 = load5;
                mLoad15 = load15;
            }
        }

        if (DEBUG) Log.i(TAG, "*** TIME TO COLLECT STATS: "
                + (SystemClock.uptimeMillis() - mCurrentSampleTime));

        mWorkingProcsSorted = false;
        mFirst = false;
    }

    private void collectProcsStats(String procFile, Stats st) {

        String[] procStats = readProcFile(procFile);
        //for (int i = 0; i < procStats.length; i++) {
        //    android.util.Log.e(TAG,"i:" + i + ", sys:" + procStats[i]);
        //}
        if (procStats == null) {
            return;
        }
        final String status = procStats[PROCESS_STATS_STATUS];
        final long minfaults = Long.parseLong(procStats[PROCESS_STATS_MINOR_FAULTS]);
        final long majfaults = Long.parseLong(procStats[PROCESS_STATS_MAJOR_FAULTS]);
        final long utime = Long.parseLong(procStats[PROCESS_STATS_UTIME]) * mJiffyMillis;
        final long stime = Long.parseLong(procStats[PROCESS_STATS_STIME]) * mJiffyMillis;

        if (DEBUG) {
            android.util.Log.v(TAG, "Stats changed " + st.name + " status:" + status + " pid=" + st.pid
                    + " utime=" + utime + "-" + st.base_utime
                    + " stime=" + stime + "-" + st.base_stime
                    + " minfaults=" + minfaults + "-" + st.base_minfaults
                    + " majfaults=" + majfaults + "-" + st.base_majfaults);
        }
        final long uptime = SystemClock.uptimeMillis();

        st.rel_uptime = uptime - st.base_uptime;
        st.base_uptime = uptime;
        st.rel_utime = (int) (utime - st.base_utime);
        st.rel_stime = (int) (stime - st.base_stime);
        st.base_utime = utime;
        st.base_stime = stime;
        st.rel_minfaults = (int) (minfaults - st.base_minfaults);
        st.rel_majfaults = (int) (majfaults - st.base_majfaults);
        st.base_minfaults = minfaults;
        st.base_majfaults = majfaults;
        st.status = status;
    }

    final void buildWorkingProcs() {
        if (!mWorkingProcsSorted) {
            mWorkingProcs.clear();
            final int N = mProcStats.size();
            for (int i = 0; i < N; i++) {
                Stats stats = mProcStats.get(i);
                if (stats.working) {
                    mWorkingProcs.add(stats);
                    if (stats.threadStats != null && stats.threadStats.size() > 1) {
                        stats.workingThreads.clear();
                        final int M = stats.threadStats.size();
                        for (int j = 0; j < M; j++) {
                            Stats tstats = stats.threadStats.get(j);
                            if (tstats.working) {
                                stats.workingThreads.add(tstats);
                            }
                        }
                        Collections.sort(stats.workingThreads, sLoadComparator);
                    }
                }
            }
            Collections.sort(mWorkingProcs, sLoadComparator);
            mWorkingProcsSorted = true;
        }
    }

    @SuppressLint("SimpleDateFormat")
    final public String printCurrentState(long now) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, false);
        pw.println("");
        pw.print("CPU usage from ");
        if (now > mLastSampleTime) {
            pw.print(now - mLastSampleTime);
            pw.print("ms to ");
            pw.print(now - mCurrentSampleTime);
            pw.print("ms ago");
        } else {
            pw.print(mLastSampleTime - now);
            pw.print("ms to ");
            pw.print(mCurrentSampleTime - now);
            pw.print("ms later");
        }
        pw.print(" (");
        pw.print(sdf.format(new Date(mLastSampleWallTime)));
        pw.print(" to ");
        pw.print(sdf.format(new Date(mCurrentSampleWallTime)));
        pw.print(")");
        long sampleTime = mCurrentSampleTime - mLastSampleTime;
        long sampleRealTime = mCurrentSampleRealTime - mLastSampleRealTime;
        long percAwake = sampleRealTime > 0 ? ((sampleTime * 100) / sampleRealTime) : 0;
        if (percAwake != 100) {
            pw.print(" with ");
            pw.print(percAwake);
            pw.print("% awake");
        }
        pw.println(":");
        final int totalTime = mRelUserTime + mRelSystemTime + mRelIoWaitTime
                + mRelIrqTime + mRelSoftIrqTime + mRelIdleTime;

        Stats st = mCurrentProcStat;
        printProcessCPU(pw,
                st.pid, st.name, st.status, (int) st.rel_uptime,
                st.rel_utime, st.rel_stime, 0, 0, 0, 0, st.rel_minfaults, st.rel_majfaults);
        if (st.workingThreads != null) {
            pw.println("thread stats:");
            int M = st.workingThreads.size();
            for (int j = 0; j < M; j++) {
                Stats tst = st.workingThreads.get(j);
                printProcessCPU(pw,
                        tst.pid, tst.name, tst.status, (int) st.rel_uptime,
                        tst.rel_utime, tst.rel_stime, 0, 0, 0, 0, tst.rel_minfaults, tst.rel_majfaults);
            }
        }

        printProcessCPU(pw, -1, "TOTAL", "", totalTime, mRelUserTime, mRelSystemTime,
                mRelIoWaitTime, mRelIrqTime, mRelSoftIrqTime, mRelIdleTime, 0, 0);
        pw.println(printCurrentLoad());

        if (DEBUG) {
            android.util.Log.i(TAG, "totalTime " + totalTime + " over sample time "
                    + (mCurrentSampleTime - mLastSampleTime) + ", real uptime:" + st.rel_uptime);
        }
        pw.flush();
        return sw.toString();
    }

    final private String printCurrentLoad() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, false);
        pw.print("Load: ");
        pw.print(mLoad1);
        pw.print(" / ");
        pw.print(mLoad5);
        pw.print(" / ");
        pw.println(mLoad15);
        pw.flush();
        return sw.toString();
    }

    private void printRatio(PrintWriter pw, long numerator, long denominator) {
        long thousands = (numerator * 1000) / denominator;
        long hundreds = thousands / 10;
        pw.print(hundreds);
        if (hundreds < 10) {
            long remainder = thousands - (hundreds * 10);
            if (remainder != 0) {
                pw.print('.');
                pw.print(remainder);
            }
        }
    }

    private void printProcessCPU(PrintWriter pw, int pid, String label, String status,
                                 int totalTime, int user, int system, int iowait, int irq, int softIrq, int idle,
                                 int minFaults, int majFaults) {
        if (totalTime == 0) {
            totalTime = 1;
        }
        printRatio(pw, user + system + iowait + irq + softIrq + idle, totalTime);
        pw.print("% ");
        if (pid >= 0) {
            pw.print(pid);
            pw.print("/");
        }
        pw.print(label + "(" + status + ")");
        pw.print(": ");
        printRatio(pw, user, totalTime);
        pw.print("% user + ");
        printRatio(pw, system, totalTime);
        pw.print("% kernel");
        if (iowait > 0) {
            pw.print(" + ");
            printRatio(pw, iowait, totalTime);
            pw.print("% iowait");
        }
        if (irq > 0) {
            pw.print(" + ");
            printRatio(pw, irq, totalTime);
            pw.print("% irq");
        }
        if (softIrq > 0) {
            pw.print(" + ");
            printRatio(pw, softIrq, totalTime);
            pw.print("% softirq");
        }
        if (idle > 0) {
            pw.print(" + ");
            printRatio(pw, idle, totalTime);
            pw.print("% idle");
        }
        if (minFaults > 0 || majFaults > 0) {
            pw.print(" / faults:");
            if (minFaults > 0) {
                pw.print(" ");
                pw.print(minFaults);
                pw.print(" minor");
            }
            if (majFaults > 0) {
                pw.print(" ");
                pw.print(majFaults);
                pw.print(" major");
            }
        }
        pw.println();
    }

    private void getName(Stats st, String cmdlineFile) {
        String newName = st.name;
        if (st.name == null || st.name.equals("app_process")
                || st.name.equals("<pre-initialized>")) {
            String cmdName = readFile(cmdlineFile, '\0');
            if (cmdName != null && cmdName.length() > 1) {
                newName = cmdName;
                int i = newName.lastIndexOf("/");
                if (i > 0 && i < newName.length() - 1) {
                    newName = newName.substring(i + 1);
                }
            }
            if (newName == null) {
                newName = st.baseName;
            }
        }
        if (st.name == null || !newName.equals(st.name)) {
            st.name = newName;
        }
    }

    protected String[] readProcFile(String file) {
        RandomAccessFile procFile = null;
        String procFileContents;
        try {
            procFile = new RandomAccessFile(file, "r");
            procFileContents = procFile.readLine();
            Log.d("wanglang", "procFileContents = " + procFileContents);
            int rightIndex = procFileContents.indexOf(")");
            if (rightIndex > 0) {
                procFileContents = procFileContents.substring(rightIndex + 2);
            }

            return procFileContents.split(" ");
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        } finally {
            try {
                procFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String readFile(String file, char endChar) {
        // Permit disk reads here, as /proc/meminfo isn't really "on
        // disk" and should be fast.  TODO: make BlockGuard ignore
        // /proc/ and /sys/ files perhaps?
        FileInputStream is = null;

        try {
            is = new FileInputStream(file);
            int len = is.read(mBuffer);
            is.close();
            if (len > 0) {
                int i;
                for (i = 0; i < len; i++) {
                    if (mBuffer[i] == endChar || mBuffer[i] == 10) {
                        break;
                    }
                }
                return new String(mBuffer, 0, i);
            }
        } catch (java.io.FileNotFoundException e) {
            //
        } catch (IOException e) {
            //
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Nullable
    private Stats findThreadStat(int id, ArrayList<Stats> stats) {
        for (Stats stat : stats) {
            if (stat.pid == id) {
                return stat;
            }
        }
        return null;
    }
}
