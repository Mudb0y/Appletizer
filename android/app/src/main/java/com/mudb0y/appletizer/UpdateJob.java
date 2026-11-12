package com.mudb0y.appletizer;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** The daily check, while automatic checks are on. */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Updater.check(this, true, () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
