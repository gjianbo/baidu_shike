package com.hnwd.bdplayer.extension.api;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.hnwd.bdplayer.extension.api.IDownloadCallback;

interface IDownloadService {
    Bundle capabilities();
    boolean start(String taskId, String attemptId, String url, String cookies,
        in ParcelFileDescriptor output, IDownloadCallback callback);
    void cancel(String taskId, String attemptId);
}
