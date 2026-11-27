package com.hnwd.bdplayer.extension.api;

import android.os.Bundle;

oneway interface IDownloadCallback {
    void onState(String taskId, String attemptId, String state, in Bundle details);
}
